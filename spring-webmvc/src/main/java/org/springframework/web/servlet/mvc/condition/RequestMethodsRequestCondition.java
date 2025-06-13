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

package org.springframework.web.servlet.mvc.condition;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.cors.CorsUtils;

import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * A logical disjunction (' || ') request condition that matches a request
 * against a set of {@link RequestMethod RequestMethods}.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @since 3.1
 */
public final class RequestMethodsRequestCondition extends AbstractRequestCondition<RequestMethodsRequestCondition> {

	/**
	 * Per HTTP method cache to return ready instances from getMatchingCondition.
	 * 一些具体的请求方法的缓存，可以通过 RequestMethod 快速找到 RequestMethodsRequestCondition
	 */
	private static final Map<String, RequestMethodsRequestCondition> requestMethodConditionCache;

	static {
		// 构造一个 HTTP method name  -> RequestMethodsRequestCondition 的映射
		requestMethodConditionCache = CollectionUtils.newHashMap(RequestMethod.values().length);

		// 遍历 Request Method 枚举
		for (RequestMethod method : RequestMethod.values()) {
			requestMethodConditionCache.put(method.name(), new RequestMethodsRequestCondition(method));
		}
	}


	private final Set<RequestMethod> methods;


	/**
	 * Create a new instance with the given request methods.
	 *
	 * @param requestMethods 0 or more HTTP request methods;
	 *                       if, 0 the condition will match to every request
	 */
	public RequestMethodsRequestCondition(RequestMethod... requestMethods) {
		this.methods = (ObjectUtils.isEmpty(requestMethods)
				? Collections.emptySet()
				: new LinkedHashSet<>(Arrays.asList(requestMethods)));
	}

	/**
	 * Private constructor for internal use when combining conditions.
	 */
	private RequestMethodsRequestCondition(Set<RequestMethod> methods) {
		this.methods = methods;
	}


	/**
	 * Returns all {@link RequestMethod RequestMethods} contained in this condition.
	 */
	public Set<RequestMethod> getMethods() {
		return this.methods;
	}

	@Override
	protected Collection<RequestMethod> getContent() {
		return this.methods;
	}

	@Override
	protected String getToStringInfix() {
		return " || ";
	}

	/**
	 * Returns a new instance with a union of the HTTP request methods
	 * from "this" and the "other" instance.
	 */
	@Override
	public RequestMethodsRequestCondition combine(RequestMethodsRequestCondition other) {
		// 若都没有条件信息，不用处理了
		if (isEmpty() && other.isEmpty()) {
			return this;
		}

		// 若我有，别人没有，用我的
		else if (other.isEmpty()) {
			return this;
		}

		// 若我没有，别人有，用别人的
		else if (isEmpty()) {
			return other;
		}

		// 我和别人都有，合并
		Set<RequestMethod> set = new LinkedHashSet<>(this.methods);
		set.addAll(other.methods);
		return new RequestMethodsRequestCondition(set);
	}

	/**
	 * Check if any of the HTTP request methods match the given request and
	 * return an instance that contains the matching HTTP request method only.
	 *
	 * @param request the current request
	 * @return the same instance if the condition is empty (unless the request
	 * method is HTTP OPTIONS), a new condition with the matched request method,
	 * or {@code null} if there is no match or the condition is empty and the
	 * request method is OPTIONS.
	 */
	@Override
	@Nullable
	public RequestMethodsRequestCondition getMatchingCondition(HttpServletRequest request) {
		// request 是预飞请求
		if (CorsUtils.isPreFlightRequest(request)) {
			// 使用特定方法匹配
			return matchPreFlight(request);
		}

		// 如果当前没有设置 methods，表示支持一切吧
		if (getMethods().isEmpty()) {
			// 如果请求是 OPTIONS ，并且不能是 ERROR 的派发类型
			if (RequestMethod.OPTIONS.name().equals(request.getMethod()) &&
					!DispatcherType.ERROR.equals(request.getDispatcherType())) {
				// 这意味着，除非开发者显式声明 OPTIONS 方法的处理器，否则不会默认处理 OPTIONS
				return null; // We handle OPTIONS transparently, so don't match if no explicit declarations
			}
			return this;
		}

		// 匹配一个具体的
		return matchRequestMethod(request.getMethod());
	}

	/**
	 * On a pre-flight request match to the would-be, actual request.
	 * Hence empty conditions is a match, otherwise try to match to the HTTP
	 * method in the "Access-Control-Request-Method" header.
	 */
	@Nullable
	private RequestMethodsRequestCondition matchPreFlight(HttpServletRequest request) {
		// 如果没有写 method，那么匹配一切 method
		if (getMethods().isEmpty()) {
			// 返回 this
			return this;
		}

		// 拿到 Access-Control-Request-Method 拿到客户端实际想要执行的方法
		String expectedMethod = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
		// 尝试用这个 method 去获取唯一一个 Condition（仅包含 1 个method）
		return matchRequestMethod(expectedMethod);
	}

	/**
	 * 这个方法传入一个字符串 http method，得到一个具体的 RequestMethodsRequestCondition，这个条件里面必定只有 1 个支持的 method
	 * <p>得到一个仅包含 1 个 method 的 RequestMethodsRequestCondition
	 */
	@Nullable
	private RequestMethodsRequestCondition matchRequestMethod(String httpMethodValue) {
		RequestMethod requestMethod;
		try {
			// 解析成 HttpMethod
			requestMethod = RequestMethod.valueOf(httpMethodValue);

			// 如果当前支持的 methods 包含这个，那么就是匹配到了，从缓存里拿到一个特定请求的 RequestMethodsRequestCondition
			if (getMethods().contains(requestMethod)) {
				return requestMethodConditionCache.get(httpMethodValue);
			}

			// 如果请求方法是 HEAD，而且支持的 methods 包含 GET，那么就用 GET
			// 什么意思？不知道 TODO: HEAD 转换为 GET
			if (requestMethod.equals(RequestMethod.HEAD) && getMethods().contains(RequestMethod.GET)) {
				return requestMethodConditionCache.get(HttpMethod.GET.name());
			}
		} catch (IllegalArgumentException ex) {
			// Custom request method
		}
		return null;
	}

	/**
	 * Returns:
	 * <ul>
	 * <li>0 if the two conditions contain the same number of HTTP request methods
	 * <li>Less than 0 if "this" instance has an HTTP request method but "other" doesn't
	 * <li>Greater than 0 "other" has an HTTP request method but "this" doesn't
	 * </ul>
	 * <p>It is assumed that both instances have been obtained via
	 * {@link #getMatchingCondition(HttpServletRequest)} and therefore each instance
	 * contains the matching HTTP request method only or is otherwise empty.
	 */
	@Override
	public int compareTo(RequestMethodsRequestCondition other, HttpServletRequest request) {
		if (other.methods.size() != this.methods.size()) {
			return other.methods.size() - this.methods.size();
		} else if (this.methods.size() == 1) {
			if (this.methods.contains(RequestMethod.HEAD) && other.methods.contains(RequestMethod.GET)) {
				return -1;
			} else if (this.methods.contains(RequestMethod.GET) && other.methods.contains(RequestMethod.HEAD)) {
				return 1;
			}
		}
		return 0;
	}

}
