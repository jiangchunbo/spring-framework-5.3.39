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
	 *
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
		//
		this.contextPath = initContextPath(this.fullPath, contextPath);
		this.pathWithinApplication = extractPathWithinApplication(this.fullPath, this.contextPath);
	}

	private DefaultRequestPath(RequestPath requestPath, String contextPath) {
		this.fullPath = requestPath;
		this.contextPath = initContextPath(this.fullPath, contextPath);
		this.pathWithinApplication = extractPathWithinApplication(this.fullPath, this.contextPath);
	}

	private static PathContainer initContextPath(PathContainer path, @Nullable String contextPath) {
		// 应用上下文路径是 / 根路径，那么
		if (!StringUtils.hasText(contextPath) || StringUtils.matchesCharacter(contextPath, '/')) {
			return PathContainer.parsePath("");
		}

		validateContextPath(path.value(), contextPath);

		int length = contextPath.length();
		int counter = 0;

		for (int i = 0; i < path.elements().size(); i++) {
			PathContainer.Element element = path.elements().get(i);
			counter += element.value().length();
			if (length == counter) {
				return path.subPath(0, i + 1);
			}
		}

		// Should not happen..
		throw new IllegalStateException("Failed to initialize contextPath '" + contextPath + "'" +
				" for requestPath '" + path.value() + "'");
	}

	private static void validateContextPath(String fullPath, String contextPath) {
		int length = contextPath.length();
		if (contextPath.charAt(0) != '/' || contextPath.charAt(length - 1) == '/') {
			throw new IllegalArgumentException("Invalid contextPath: '" + contextPath + "': " +
					"must start with '/' and not end with '/'");
		}
		if (!fullPath.startsWith(contextPath)) {
			throw new IllegalArgumentException("Invalid contextPath '" + contextPath + "': " +
					"must match the start of requestPath: '" + fullPath + "'");
		}
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
