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

package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.core.MethodParameter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.ResolvableType;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.DelegatingServerHttpResponse;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Handler for return values of type {@link ResponseBodyEmitter} and subclasses
 * such as {@link SseEmitter} including the same types wrapped with
 * {@link ResponseEntity}.
 *
 * <p>As of 5.0 also supports reactive return value types for any reactive
 * library with registered adapters in {@link ReactiveAdapterRegistry}.
 *
 * @author Rossen Stoyanchev
 * @since 4.2
 */
public class ResponseBodyEmitterReturnValueHandler implements HandlerMethodReturnValueHandler {

	private final List<HttpMessageConverter<?>> sseMessageConverters;

	private final ReactiveTypeHandler reactiveHandler;


	/**
	 * Simple constructor with reactive type support based on a default instance of
	 * {@link ReactiveAdapterRegistry},
	 * {@link org.springframework.core.task.SyncTaskExecutor}, and
	 * {@link ContentNegotiationManager} with an Accept header strategy.
	 */
	public ResponseBodyEmitterReturnValueHandler(List<HttpMessageConverter<?>> messageConverters) {
		Assert.notEmpty(messageConverters, "HttpMessageConverter List must not be empty");
		this.sseMessageConverters = initSseConverters(messageConverters);
		this.reactiveHandler = new ReactiveTypeHandler();
	}

	/**
	 * Complete constructor with pluggable "reactive" type support.
	 *
	 * @param messageConverters converters to write emitted objects with
	 * @param registry          for reactive return value type support
	 * @param executor          for blocking I/O writes of items emitted from reactive types
	 * @param manager           for detecting streaming media types
	 * @since 5.0
	 */
	public ResponseBodyEmitterReturnValueHandler(List<HttpMessageConverter<?>> messageConverters,
												 ReactiveAdapterRegistry registry, TaskExecutor executor, ContentNegotiationManager manager) {

		Assert.notEmpty(messageConverters, "HttpMessageConverter List must not be empty");
		this.sseMessageConverters = initSseConverters(messageConverters);
		this.reactiveHandler = new ReactiveTypeHandler(registry, executor, manager);
	}

	private static List<HttpMessageConverter<?>> initSseConverters(List<HttpMessageConverter<?>> converters) {
		for (HttpMessageConverter<?> converter : converters) {
			if (converter.canWrite(String.class, MediaType.TEXT_PLAIN)) {
				return converters;
			}
		}
		List<HttpMessageConverter<?>> result = new ArrayList<>(converters.size() + 1);
		result.add(new StringHttpMessageConverter(StandardCharsets.UTF_8));
		result.addAll(converters);
		return result;
	}


	@Override
	public boolean supportsReturnType(MethodParameter returnType) {
		// 1. ResponseEntity<T> 返回类型使用 ResponseEntity 包装
		// 2. 直接返回类型是需要的类型
		Class<?> bodyType = ResponseEntity.class.isAssignableFrom(returnType.getParameterType()) ?
				ResolvableType.forMethodParameter(returnType).getGeneric().resolve() :
				returnType.getParameterType();

		// 支持 ResponseBodyEmitter 或者是这些 Reactive 库的返回值类型
		return (bodyType != null && (ResponseBodyEmitter.class.isAssignableFrom(bodyType) ||
				this.reactiveHandler.isReactiveType(bodyType)));
	}

	@Override
	@SuppressWarnings("resource")
	public void handleReturnValue(@Nullable Object returnValue, MethodParameter returnType,
								  ModelAndViewContainer mavContainer, NativeWebRequest webRequest) throws Exception {

		if (returnValue == null) {
			mavContainer.setRequestHandled(true);
			return;
		}

		HttpServletResponse response = webRequest.getNativeResponse(HttpServletResponse.class);
		Assert.state(response != null, "No HttpServletResponse");
		ServerHttpResponse outputMessage = new ServletServerHttpResponse(response);

		if (returnValue instanceof ResponseEntity) {
			ResponseEntity<?> responseEntity = (ResponseEntity<?>) returnValue;
			response.setStatus(responseEntity.getStatusCodeValue());
			outputMessage.getHeaders().putAll(responseEntity.getHeaders());
			returnValue = responseEntity.getBody();
			returnType = returnType.nested();
			if (returnValue == null) {
				mavContainer.setRequestHandled(true);
				outputMessage.flush();
				return;
			}
		}

		ServletRequest request = webRequest.getNativeRequest(ServletRequest.class);
		Assert.state(request != null, "No ServletRequest");

		// 两种处理方式：
		// 转换为 ResponseBodyEmitter
		ResponseBodyEmitter emitter;
		if (returnValue instanceof ResponseBodyEmitter) {
			emitter = (ResponseBodyEmitter) returnValue;
		} else {
			// 交给 reactive handler 处理
			emitter = this.reactiveHandler.handleValue(returnValue, returnType, mavContainer, webRequest);
			if (emitter == null) {
				// Not streaming: write headers without committing response..
				outputMessage.getHeaders().forEach((headerName, headerValues) -> {
					for (String headerValue : headerValues) {
						response.addHeader(headerName, headerValue);
					}
				});
				return;
			}
		}

		// 扩展响应头
		emitter.extendResponse(outputMessage);

		// At this point we know we're streaming..
		// 禁用缓存
		ShallowEtagHeaderFilter.disableContentCaching(request);

		// Wrap the response to ignore further header changes
		// Headers will be flushed at the first write
		outputMessage = new StreamingServletServerHttpResponse(outputMessage);

		HttpMessageConvertingHandler handler;
		try {
			// 构造一个 DeferredResult
			DeferredResult<?> deferredResult = new DeferredResult<>(emitter.getTimeout());
			WebAsyncUtils.getAsyncManager(webRequest).startDeferredResultProcessing(deferredResult, mavContainer);

			// handle 包装了 response 和 deferredResult
			handler = new HttpMessageConvertingHandler(outputMessage, deferredResult);
		} catch (Throwable ex) {
			emitter.initializeWithError(ex);
			throw ex;
		}

		emitter.initialize(handler);
	}


	/**
	 * ResponseBodyEmitter.Handler that writes with HttpMessageConverter's.
	 */
	private class HttpMessageConvertingHandler implements ResponseBodyEmitter.Handler {

		private final ServerHttpResponse outputMessage;

		private final DeferredResult<?> deferredResult;

		public HttpMessageConvertingHandler(ServerHttpResponse outputMessage, DeferredResult<?> deferredResult) {
			this.outputMessage = outputMessage;
			this.deferredResult = deferredResult;
		}

		@Override
		public void send(Object data, @Nullable MediaType mediaType) throws IOException {
			sendInternal(data, mediaType);
		}

		/**
		 * 当外部调用 send 时，就会将数据 flush 出去
		 */
		@SuppressWarnings("unchecked")
		private <T> void sendInternal(T data, @Nullable MediaType mediaType) throws IOException {
			for (HttpMessageConverter<?> converter : ResponseBodyEmitterReturnValueHandler.this.sseMessageConverters) {
				if (converter.canWrite(data.getClass(), mediaType)) {
					((HttpMessageConverter<T>) converter).write(data, mediaType, this.outputMessage);
					this.outputMessage.flush();
					return;
				}
			}
			throw new IllegalArgumentException("No suitable converter for " + data.getClass());
		}

		@Override
		public void complete() {
			try {
				// 数据刷出去
				this.outputMessage.flush();

				// 设置结果是 null
				this.deferredResult.setResult(null);
			} catch (IOException ex) {
				this.deferredResult.setErrorResult(ex);
			}
		}

		@Override
		public void completeWithError(Throwable failure) {
			this.deferredResult.setErrorResult(failure);
		}

		@Override
		public void onTimeout(Runnable callback) {
			this.deferredResult.onTimeout(callback);
		}

		@Override
		public void onError(Consumer<Throwable> callback) {
			this.deferredResult.onError(callback);
		}

		@Override
		public void onCompletion(Runnable callback) {
			this.deferredResult.onCompletion(callback);
		}
	}


	/**
	 * Wrap to silently ignore header changes HttpMessageConverter's that would
	 * otherwise cause HttpHeaders to raise exceptions.
	 */
	private static class StreamingServletServerHttpResponse extends DelegatingServerHttpResponse {

		private final HttpHeaders mutableHeaders = new HttpHeaders();

		public StreamingServletServerHttpResponse(ServerHttpResponse delegate) {
			super(delegate);
			this.mutableHeaders.putAll(delegate.getHeaders());
		}

		@Override
		public HttpHeaders getHeaders() {
			return this.mutableHeaders;
		}

	}

}
