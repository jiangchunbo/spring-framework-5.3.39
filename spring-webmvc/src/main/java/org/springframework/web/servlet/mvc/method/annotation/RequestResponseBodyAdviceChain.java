/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.web.method.ControllerAdviceBean;

/**
 * Invokes {@link RequestBodyAdvice} and {@link ResponseBodyAdvice} where each
 * instance may be (and is most likely) wrapped with
 * {@link org.springframework.web.method.ControllerAdviceBean ControllerAdviceBean}.
 *
 * @author Rossen Stoyanchev
 * @since 4.2
 */
class RequestResponseBodyAdviceChain implements RequestBodyAdvice, ResponseBodyAdvice<Object> {

	private final List<Object> requestBodyAdvice = new ArrayList<>(4);

	private final List<Object> responseBodyAdvice = new ArrayList<>(4);


	/**
	 * Create an instance from a list of objects that are either of type
	 * {@code ControllerAdviceBean} or {@code RequestBodyAdvice}.
	 */
	public RequestResponseBodyAdviceChain(@Nullable List<Object> requestResponseBodyAdvice) {
		this.requestBodyAdvice.addAll(getAdviceByType(requestResponseBodyAdvice, RequestBodyAdvice.class));
		this.responseBodyAdvice.addAll(getAdviceByType(requestResponseBodyAdvice, ResponseBodyAdvice.class));
	}

	@SuppressWarnings("unchecked")
	static <T> List<T> getAdviceByType(@Nullable List<Object> requestResponseBodyAdvice, Class<T> adviceType) {
		if (requestResponseBodyAdvice != null) {
			List<T> result = new ArrayList<>();
			for (Object advice : requestResponseBodyAdvice) {
				Class<?> beanType = (advice instanceof ControllerAdviceBean ?
						((ControllerAdviceBean) advice).getBeanType() : advice.getClass());
				if (beanType != null && adviceType.isAssignableFrom(beanType)) {
					result.add((T) advice);
				}
			}
			return result;
		}
		return Collections.emptyList();
	}


	@Override
	public boolean supports(MethodParameter param, Type type, Class<? extends HttpMessageConverter<?>> converterType) {
		// 为什么没有实现，因为不期望被调用，这个类只是一个组合节点
		throw new UnsupportedOperationException("Not implemented");
	}

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		// 为什么没有实现，因为不期望被调用，这个类只是一个组合节点
		throw new UnsupportedOperationException("Not implemented");
	}

	@Override
	public HttpInputMessage beforeBodyRead(HttpInputMessage request, MethodParameter parameter,
			Type targetType, Class<? extends HttpMessageConverter<?>> converterType) throws IOException {

		// 找出所有组件 RequestBodyAdvice
		for (RequestBodyAdvice advice : getMatchingAdvice(parameter, RequestBodyAdvice.class)) {

			// 是否支持
			// parameter: 方法参数
			// targetType:
			if (advice.supports(parameter, targetType, converterType)) {

				// 执行
				request = advice.beforeBodyRead(request, parameter, targetType, converterType);
			}
		}
		return request;
	}

	@Override
	public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
			Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {

		for (RequestBodyAdvice advice : getMatchingAdvice(parameter, RequestBodyAdvice.class)) {
			if (advice.supports(parameter, targetType, converterType)) {
				body = advice.afterBodyRead(body, inputMessage, parameter, targetType, converterType);
			}
		}
		return body;
	}

	@Override
	@Nullable
	public Object beforeBodyWrite(@Nullable Object body, MethodParameter returnType, MediaType contentType,
			Class<? extends HttpMessageConverter<?>> converterType,
			ServerHttpRequest request, ServerHttpResponse response) {

		return processBody(body, returnType, contentType, converterType, request, response);
	}

	@Override
	@Nullable
	public Object handleEmptyBody(@Nullable Object body, HttpInputMessage inputMessage, MethodParameter parameter,
			Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {

		for (RequestBodyAdvice advice : getMatchingAdvice(parameter, RequestBodyAdvice.class)) {
			if (advice.supports(parameter, targetType, converterType)) {
				body = advice.handleEmptyBody(body, inputMessage, parameter, targetType, converterType);
			}
		}
		return body;
	}


	@SuppressWarnings("unchecked")
	@Nullable
	private <T> Object processBody(@Nullable Object body, MethodParameter returnType, MediaType contentType,
			Class<? extends HttpMessageConverter<?>> converterType,
			ServerHttpRequest request, ServerHttpResponse response) {

		for (ResponseBodyAdvice<?> advice : getMatchingAdvice(returnType, ResponseBodyAdvice.class)) {
			if (advice.supports(returnType, converterType)) {
				body = ((ResponseBodyAdvice<T>) advice).beforeBodyWrite((T) body, returnType,
						contentType, converterType, request, response);
			}
		}
		return body;
	}

	@SuppressWarnings("unchecked")
	private <A> List<A> getMatchingAdvice(MethodParameter parameter, Class<? extends A> adviceType) {
		// getAdvice 是一个非常简单的策略模式，if else 写法，只支持 2 种
		// 默认，这里应该有 1 个元素，用于支持 @JsonView
		List<Object> availableAdvice = getAdvice(adviceType);

		// 如果没有什么 advice，那么不用执行
		if (CollectionUtils.isEmpty(availableAdvice)) {
			return Collections.emptyList();
		}

		// 接下来，执行所有 advice
		List<A> result = new ArrayList<>(availableAdvice.size());
		for (Object advice : availableAdvice) {
			if (advice instanceof ControllerAdviceBean) {
				ControllerAdviceBean adviceBean = (ControllerAdviceBean) advice;
				if (!adviceBean.isApplicableToBeanType(parameter.getContainingClass())) {
					continue;
				}
				advice = adviceBean.resolveBean();
			}
			if (adviceType.isAssignableFrom(advice.getClass())) {
				result.add((A) advice);
			}
		}
		return result;
	}

	/**
	 * 只有两种情况，RequestBodyAdvice 或者 ResponseBodyAdvice，其他都报错。
	 */
	private List<Object> getAdvice(Class<?> adviceType) {
		if (RequestBodyAdvice.class == adviceType) {
			return this.requestBodyAdvice;
		}
		else if (ResponseBodyAdvice.class == adviceType) {
			return this.responseBodyAdvice;
		}
		else {
			throw new IllegalArgumentException("Unexpected adviceType: " + adviceType);
		}
	}

}
