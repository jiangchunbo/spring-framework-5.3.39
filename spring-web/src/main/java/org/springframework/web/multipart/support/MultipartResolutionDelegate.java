/*
 * Copyright 2002-2020 the original author or authors.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartRequest;
import org.springframework.web.util.WebUtils;

/**
 * A common delegate for {@code HandlerMethodArgumentResolver} implementations
 * which need to resolve {@link MultipartFile} and {@link Part} arguments.
 * <p>
 * 一种用于解析 MultipartFile 和 Part 参数的【方法参数解析器】实现的通用委托
 *
 * @author Juergen Hoeller
 * @since 4.3
 */
public final class MultipartResolutionDelegate {

	/**
	 * Indicates an unresolvable value.
	 */
	public static final Object UNRESOLVABLE = new Object();


	private MultipartResolutionDelegate() {
	}


	@Nullable
	public static MultipartRequest resolveMultipartRequest(NativeWebRequest webRequest) {
		MultipartRequest multipartRequest = webRequest.getNativeRequest(MultipartRequest.class);
		if (multipartRequest != null) {
			return multipartRequest;
		}
		HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
		if (servletRequest != null && isMultipartContent(servletRequest)) {
			return new StandardMultipartHttpServletRequest(servletRequest);
		}
		return null;
	}

	/**
	 * 从 request 类型以及，Content Type 判断
	 */
	public static boolean isMultipartRequest(HttpServletRequest request) {
		// 包装成 MultipartHttpServletRequest
		// 或者，请求 ContentType 是 multipart/form-data
		return (WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class) != null ||
				isMultipartContent(request));
	}

	/**
	 * 从 ContentType 判断是不是 multipart
	 */
	private static boolean isMultipartContent(HttpServletRequest request) {
		String contentType = request.getContentType();
		return (contentType != null && contentType.toLowerCase().startsWith("multipart/"));
	}

	/**
	 * 包装成 MultipartHttpServletRequest
	 */
	static MultipartHttpServletRequest asMultipartHttpServletRequest(HttpServletRequest request) {
		MultipartHttpServletRequest unwrapped = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
		if (unwrapped != null) {
			return unwrapped;
		}
		// 这里会立即触发解析
		return new StandardMultipartHttpServletRequest(request);
	}


	public static boolean isMultipartArgument(MethodParameter parameter) {
		Class<?> paramType = parameter.getNestedParameterType();
		return (MultipartFile.class == paramType ||
				isMultipartFileCollection(parameter) || isMultipartFileArray(parameter) ||
				(Part.class == paramType || isPartCollection(parameter) || isPartArray(parameter)));
	}

	@Nullable
	public static Object resolveMultipartArgument(String name, MethodParameter parameter, HttpServletRequest request)
			throws Exception {

		MultipartHttpServletRequest multipartRequest =
				WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
		boolean isMultipart = (multipartRequest != null || isMultipartContent(request));

		if (MultipartFile.class == parameter.getNestedParameterType()) {
			// 如果参数是 MultipartFile，但是却不是 multipart 请求
			if (!isMultipart) {
				return null;
			}

			// 应该不可能是 null 吧，除非前面 DispatcherServlet 没有解析？
			if (multipartRequest == null) {
				multipartRequest = new StandardMultipartHttpServletRequest(request);
			}

			// 总之拿到 MultipartFile
			return multipartRequest.getFile(name);
		} else if (isMultipartFileCollection(parameter)) {
			if (!isMultipart) {
				return null;
			}
			if (multipartRequest == null) {
				multipartRequest = new StandardMultipartHttpServletRequest(request);
			}
			// 与上面类似，上面是返回 1 个，这里是返回 List
			List<MultipartFile> files = multipartRequest.getFiles(name);
			return (!files.isEmpty() ? files : null);
		} else if (isMultipartFileArray(parameter)) {
			if (!isMultipart) {
				return null;
			}
			if (multipartRequest == null) {
				multipartRequest = new StandardMultipartHttpServletRequest(request);
			}
			List<MultipartFile> files = multipartRequest.getFiles(name);
			return (!files.isEmpty() ? files.toArray(new MultipartFile[0]) : null);
		} else if (Part.class == parameter.getNestedParameterType()) {
			if (!isMultipart) {
				return null;
			}
			return request.getPart(name);
		} else if (isPartCollection(parameter)) {
			if (!isMultipart) {
				return null;
			}
			List<Part> parts = resolvePartList(request, name);
			return (!parts.isEmpty() ? parts : null);
		} else if (isPartArray(parameter)) {
			if (!isMultipart) {
				return null;
			}
			List<Part> parts = resolvePartList(request, name);
			return (!parts.isEmpty() ? parts.toArray(new Part[0]) : null);
		} else {
			return UNRESOLVABLE;
		}
	}

	private static boolean isMultipartFileCollection(MethodParameter methodParam) {
		return (MultipartFile.class == getCollectionParameterType(methodParam));
	}

	private static boolean isMultipartFileArray(MethodParameter methodParam) {
		return (MultipartFile.class == methodParam.getNestedParameterType().getComponentType());
	}

	private static boolean isPartCollection(MethodParameter methodParam) {
		return (Part.class == getCollectionParameterType(methodParam));
	}

	private static boolean isPartArray(MethodParameter methodParam) {
		return (Part.class == methodParam.getNestedParameterType().getComponentType());
	}

	@Nullable
	private static Class<?> getCollectionParameterType(MethodParameter methodParam) {
		Class<?> paramType = methodParam.getNestedParameterType();
		if (Collection.class == paramType || List.class.isAssignableFrom(paramType)) {
			Class<?> valueType = ResolvableType.forMethodParameter(methodParam).asCollection().resolveGeneric();
			if (valueType != null) {
				return valueType;
			}
		}
		return null;
	}

	private static List<Part> resolvePartList(HttpServletRequest request, String name) throws Exception {
		Collection<Part> parts = request.getParts();
		List<Part> result = new ArrayList<>(parts.size());
		for (Part part : parts) {
			if (part.getName().equals(name)) {
				result.add(part);
			}
		}
		return result;
	}

}
