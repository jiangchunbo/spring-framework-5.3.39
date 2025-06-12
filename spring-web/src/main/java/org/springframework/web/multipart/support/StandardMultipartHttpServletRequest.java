/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.multipart.support;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.mail.internet.MimeUtility;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Spring MultipartHttpServletRequest adapter, wrapping a Servlet 3.0 HttpServletRequest
 * and its Part objects. Parameters get exposed through the native request's getParameter
 * methods - without any custom processing on our side.
 *
 * @author Juergen Hoeller
 * @author Rossen Stoyanchev
 * @see StandardServletMultipartResolver
 * @since 3.1
 */
public class StandardMultipartHttpServletRequest extends AbstractMultipartHttpServletRequest {

	@Nullable
	private Set<String> multipartParameterNames;


	/**
	 * Create a new StandardMultipartHttpServletRequest wrapper for the given request,
	 * immediately parsing the multipart content.
	 *
	 * @param request the servlet request to wrap
	 * @throws MultipartException if parsing failed
	 */
	public StandardMultipartHttpServletRequest(HttpServletRequest request) throws MultipartException {
		// 第 2 个参数是 false，说明默认会立即解析 request
		this(request, false);
	}

	/**
	 * Create a new StandardMultipartHttpServletRequest wrapper for the given request.
	 *
	 * @param request     the servlet request to wrap
	 * @param lazyParsing whether multipart parsing should be triggered lazily on
	 *                    first access of multipart files or parameters
	 * @throws MultipartException if an immediate parsing attempt failed
	 * @since 3.2.9
	 */
	public StandardMultipartHttpServletRequest(HttpServletRequest request, boolean lazyParsing)
			throws MultipartException {

		// super 其实这里没什么特别的逻辑，就是赋值 request
		super(request);

		if (!lazyParsing) {
			parseRequest(request);
		}
	}


	private void parseRequest(HttpServletRequest request) {
		try {
			// 获取 Servlet 定义的 Parts
			Collection<Part> parts = request.getParts();
			// 初始化用于存储 multipart 参数名的 set
			this.multipartParameterNames = new LinkedHashSet<>(parts.size());
			// 初始化用于存储 MultipartFile 的 map
			MultiValueMap<String, MultipartFile> files = new LinkedMultiValueMap<>(parts.size());

			for (Part part : parts) {
				// 每个部分都有 header
				String headerValue = part.getHeader(HttpHeaders.CONTENT_DISPOSITION);
				// 分析 content disposition，从中提取 filename 字段
				ContentDisposition disposition = ContentDisposition.parse(headerValue);
				String filename = disposition.getFilename();
				if (filename != null) {
					if (filename.startsWith("=?") && filename.endsWith("?=")) {
						filename = MimeDelegate.decode(filename);
					}
					files.add(part.getName(), new StandardMultipartFile(part, filename));
				} else {
					this.multipartParameterNames.add(part.getName());
				}
			}
			setMultipartFiles(files);
		} catch (Throwable ex) {
			handleParseFailure(ex);
		}
	}

	protected void handleParseFailure(Throwable ex) {
		String msg = ex.getMessage();
		if (msg != null) {
			msg = msg.toLowerCase();
			// 挺有意思的判断：直接检查字符串有没有 size exceed
			if (msg.contains("size") && msg.contains("exceed")) {
				throw new MaxUploadSizeExceededException(-1, ex);
			}
		}
		throw new MultipartException("Failed to parse multipart servlet request", ex);
	}

	@Override
	protected void initializeMultipart() {
		// 解析请求
		parseRequest(getRequest());
	}

	@Override
	public Enumeration<String> getParameterNames() {
		if (this.multipartParameterNames == null) {
			initializeMultipart();
		}
		if (this.multipartParameterNames.isEmpty()) {
			return super.getParameterNames();
		}

		// Servlet 3.0 getParameterNames() not guaranteed to include multipart form items
		// (e.g. on WebLogic 12) -> need to merge them here to be on the safe side
		Set<String> paramNames = new LinkedHashSet<>();
		Enumeration<String> paramEnum = super.getParameterNames();
		while (paramEnum.hasMoreElements()) {
			paramNames.add(paramEnum.nextElement());
		}
		paramNames.addAll(this.multipartParameterNames);
		return Collections.enumeration(paramNames);
	}

	@Override
	public Map<String, String[]> getParameterMap() {
		if (this.multipartParameterNames == null) {
			initializeMultipart();
		}
		if (this.multipartParameterNames.isEmpty()) {
			return super.getParameterMap();
		}

		// Servlet 3.0 getParameterMap() not guaranteed to include multipart form items
		// (e.g. on WebLogic 12) -> need to merge them here to be on the safe side
		Map<String, String[]> paramMap = new LinkedHashMap<>(super.getParameterMap());
		for (String paramName : this.multipartParameterNames) {
			if (!paramMap.containsKey(paramName)) {
				paramMap.put(paramName, getParameterValues(paramName));
			}
		}
		return paramMap;
	}

	@Override
	public String getMultipartContentType(String paramOrFileName) {
		try {
			Part part = getPart(paramOrFileName);
			return (part != null ? part.getContentType() : null);
		} catch (Throwable ex) {
			throw new MultipartException("Could not access multipart servlet request", ex);
		}
	}

	@Override
	public HttpHeaders getMultipartHeaders(String paramOrFileName) {
		try {
			Part part = getPart(paramOrFileName);
			if (part != null) {
				HttpHeaders headers = new HttpHeaders();
				for (String headerName : part.getHeaderNames()) {
					headers.put(headerName, new ArrayList<>(part.getHeaders(headerName)));
				}
				return headers;
			} else {
				return null;
			}
		} catch (Throwable ex) {
			throw new MultipartException("Could not access multipart servlet request", ex);
		}
	}


	/**
	 * Spring MultipartFile adapter, wrapping a Servlet 3.0 Part object.
	 */
	@SuppressWarnings("serial")
	private static class StandardMultipartFile implements MultipartFile, Serializable {

		private final Part part;

		private final String filename;

		public StandardMultipartFile(Part part, String filename) {
			this.part = part;
			this.filename = filename;
		}

		@Override
		public String getName() {
			return this.part.getName();
		}

		@Override
		public String getOriginalFilename() {
			return this.filename;
		}

		@Override
		public String getContentType() {
			return this.part.getContentType();
		}

		@Override
		public boolean isEmpty() {
			return (this.part.getSize() == 0);
		}

		@Override
		public long getSize() {
			return this.part.getSize();
		}

		@Override
		public byte[] getBytes() throws IOException {
			return FileCopyUtils.copyToByteArray(this.part.getInputStream());
		}

		@Override
		public InputStream getInputStream() throws IOException {
			return this.part.getInputStream();
		}

		@Override
		public void transferTo(File dest) throws IOException, IllegalStateException {
			// 对于 tomcat 来说，传入了一个路径 Path，构造得到一个 File 对象
			// 然后使用 FileItem 写入这个 File
			this.part.write(dest.getPath());

			// 算是一个补偿措施
			// 如果参数 File dest 传入的是一个绝对路径，对于 Servlet 3.0 的底层实现未必能够如愿拷贝到那个位置
			// 正如下面的注释所说，某些 Servlet 容器（例如 Jetty）可能会将给定的绝对路径转换为临时目录中的相对路径

			// part 即使没有按照预期将文件写入指定的绝对路径，文件内容至少已经从内存转移到了某个临时存储位置（这个临时文件最终会被删除）
			// 为了确保文件最终能保存到用户请求的绝对路径位置，采用了一种 fallback（或者叫补偿）
			// 如果发现 dest 是绝对路径，且依然不存在，就使用 FileCopyUtils 进行拷贝
			if (dest.isAbsolute() && !dest.exists()) {
				// Servlet 3.0 Part.write is not guaranteed to support absolute file paths:
				// may translate the given path to a relative location within a temp dir
				// (e.g. on Jetty whereas Tomcat and Undertow detect absolute paths).
				// At least we offloaded the file from memory storage; it'll get deleted
				// from the temp dir eventually in any case. And for our user's purposes,
				// we can manually copy it to the requested location as a fallback.
				FileCopyUtils.copy(this.part.getInputStream(), Files.newOutputStream(dest.toPath()));
			}
		}

		@Override
		public void transferTo(Path dest) throws IOException, IllegalStateException {
			// in 流 → out 流
			FileCopyUtils.copy(this.part.getInputStream(), Files.newOutputStream(dest));
		}
	}


	/**
	 * Inner class to avoid a hard dependency on the JavaMail API.
	 */
	private static class MimeDelegate {

		public static String decode(String value) {
			try {
				return MimeUtility.decodeText(value);
			} catch (UnsupportedEncodingException ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

}
