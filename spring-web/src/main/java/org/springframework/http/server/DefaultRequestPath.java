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

package org.springframework.http.server;

import java.util.List;

import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Default implementation of {@link RequestPath}.
 *
 * @author Rossen Stoyanchev
 * @since 5.0
 */
class DefaultRequestPath implements RequestPath {

	/**
	 * 请求的全路径，包括 Context Path
	 * <p>
	 * 完全路径，包括 Servlet 前缀 (contextPath) 以及 pathWithinApplication
	 */
	private final PathContainer fullPath;

	/**
	 * Servlet 上下文路径，不是应用上下文，可能是 Servlet 的固定前缀
	 * <p>
	 * 例如，Servlet 使用 /api 作为匹配 path，这里就是 path
	 */
	private final PathContainer contextPath;

	/**
	 * 在应用中的路径，不包含 Servlet 的前缀
	 * <p>
	 * 例如，全路径是 /api/users，Servlet 的前缀是 /api，这里就是 /users
	 */
	private final PathContainer pathWithinApplication;

	DefaultRequestPath(String rawPath, @Nullable String contextPath) {
		// rawPath 就是 request.getRequestUri() -> 解析得到 fullPath
		this.fullPath = PathContainer.parsePath(rawPath);
		// 从 fullPath 寻找 contextPath 前缀，并返回 contextPath
		this.contextPath = initContextPath(this.fullPath, contextPath);
		// 从 contextPath 往后全都是 pathWithinApplication
		this.pathWithinApplication = extractPathWithinApplication(this.fullPath, this.contextPath);
	}

	private DefaultRequestPath(RequestPath requestPath, String contextPath) {
		this.fullPath = requestPath;
		this.contextPath = initContextPath(this.fullPath, contextPath);
		this.pathWithinApplication = extractPathWithinApplication(this.fullPath, this.contextPath);
	}

	/**
	 * 初始化
	 *
	 * @param path        请求完整路径
	 * @param contextPath 应用上下文路径
	 * @return 路径
	 */
	private static PathContainer initContextPath(PathContainer path, @Nullable String contextPath) {
		// 应用上下文路径是根路径，那么返回 ""
		if (!StringUtils.hasText(contextPath) || StringUtils.matchesCharacter(contextPath, '/')) {
			return PathContainer.parsePath("");
		}

		validateContextPath(path.value(), contextPath);

		int length = contextPath.length();
		int counter = 0;

		// 遍历每个元素 (包括分隔符和路径片段)，一直 substring 直到匹配完 contextPath
		for (int i = 0; i < path.elements().size(); i++) {
			PathContainer.Element element = path.elements().get(i);
			counter += element.value().length();
			if (length == counter) {
				return path.subPath(0, i + 1);
			}
		}

		// Should not happen..
		// 不应该发生
		throw new IllegalStateException("Failed to initialize contextPath '" + contextPath + "'" +
				" for requestPath '" + path.value() + "'");
	}

	/**
	 * 该方法是私有方法，传入的 contextPath 永远不可能是 "" 或者是 "/"
	 *
	 * @param fullPath    完整请求路径
	 * @param contextPath 应用上下文
	 */
	private static void validateContextPath(String fullPath, String contextPath) {
		int length = contextPath.length();

		// context path 开头如果不是 '/' 或者结尾是 '/' 都不是合法的
		// [必须以 '/' 开头，但是不能以 '/' 结束，例如 /api]
		if (contextPath.charAt(0) != '/' || contextPath.charAt(length - 1) == '/') {
			throw new IllegalArgumentException("Invalid contextPath: '" + contextPath + "': " +
					"must start with '/' and not end with '/'");
		}

		// 如果请求路径不属于这个应用上下文就抛异常
		if (!fullPath.startsWith(contextPath)) {
			throw new IllegalArgumentException("Invalid contextPath '" + contextPath + "': " +
					"must match the start of requestPath: '" + fullPath + "'");
		}

		// 即使请求路径开头是应用上下文，也不一定就是这个应用上下文
		// 例如 /api 和 /api2

		// 如果请求路径比应用上下文长，那么接下来必须是 / 另起一个路径，否则就不合法
		if (fullPath.length() > length && fullPath.charAt(length) != '/') {
			throw new IllegalArgumentException("Invalid contextPath '" + contextPath + "': " +
					"must match to full path segments for requestPath: '" + fullPath + "'");
		}
	}

	private static PathContainer extractPathWithinApplication(PathContainer fullPath, PathContainer contextPath) {
		return fullPath.subPath(contextPath.elements().size());
	}

	// PathContainer methods..

	@Override
	public String value() {
		return this.fullPath.value();
	}

	@Override
	public List<Element> elements() {
		return this.fullPath.elements();
	}

	// RequestPath methods..

	@Override
	public PathContainer contextPath() {
		return this.contextPath;
	}

	@Override
	public PathContainer pathWithinApplication() {
		return this.pathWithinApplication;
	}

	@Override
	public RequestPath modifyContextPath(String contextPath) {
		return new DefaultRequestPath(this, contextPath);
	}

	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (other == null || getClass() != other.getClass()) {
			return false;
		}
		DefaultRequestPath otherPath = (DefaultRequestPath) other;
		return (this.fullPath.equals(otherPath.fullPath) &&
				this.contextPath.equals(otherPath.contextPath) &&
				this.pathWithinApplication.equals(otherPath.pathWithinApplication));
	}

	@Override
	public int hashCode() {
		int result = this.fullPath.hashCode();
		result = 31 * result + this.contextPath.hashCode();
		result = 31 * result + this.pathWithinApplication.hashCode();
		return result;
	}

	@Override
	public String toString() {
		return this.fullPath.toString();
	}

}
