/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.web.method.annotation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.ExceptionDepthComparator;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ReflectionUtils.MethodFilter;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Discovers {@linkplain ExceptionHandler @ExceptionHandler} methods in a given class,
 * including all of its superclasses, and helps to resolve a given {@link Exception}
 * to the exception types supported by a given {@link Method}.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 */
public class ExceptionHandlerMethodResolver {

	/**
	 * A filter for selecting {@code @ExceptionHandler} methods.
	 */
	public static final MethodFilter EXCEPTION_HANDLER_METHODS = method ->
			AnnotatedElementUtils.hasAnnotation(method, ExceptionHandler.class);

	private static final Method NO_MATCHING_EXCEPTION_HANDLER_METHOD;

	static {
		try {
			NO_MATCHING_EXCEPTION_HANDLER_METHOD =
					ExceptionHandlerMethodResolver.class.getDeclaredMethod("noMatchingExceptionHandler");
		} catch (NoSuchMethodException ex) {
			throw new IllegalStateException("Expected method not found: " + ex);
		}
	}

	/**
	 * 异常映射到处理方法
	 */
	private final Map<Class<? extends Throwable>, Method> mappedMethods = new HashMap<>(16);

	private final Map<Class<? extends Throwable>, Method> exceptionLookupCache = new ConcurrentReferenceHashMap<>(16);

	/**
	 * A constructor that finds {@link ExceptionHandler} methods in the given type.
	 *
	 * @param handlerType the type to introspect
	 *                    <p>即将被内省的类型
	 */
	public ExceptionHandlerMethodResolver(Class<?> handlerType) {
		// 遍历每个拥有 @ExceptionHandler 注解的方法
		for (Method method : MethodIntrospector.selectMethods(handlerType, EXCEPTION_HANDLER_METHODS)) {

			// 读取 @ExceptionHandler 注解(或者读取方法签名)
			for (Class<? extends Throwable> exceptionType : detectExceptionMappings(method)) {
				// 所以，多个异常，可能映射到同一个方法
				addExceptionMapping(exceptionType, method);
			}
		}
	}

	/**
	 * Extract exception mappings from the {@code @ExceptionHandler} annotation first,
	 * and then as a fallback from the method signature itself.
	 * <p>
	 * 先读取注解 {@link ExceptionHandler}，如果没有读取到，才会走方法签名(二选一)
	 */
	@SuppressWarnings("unchecked")
	private List<Class<? extends Throwable>> detectExceptionMappings(Method method) {
		// 方法能够处理哪些异常，由 @ExceptionHandler 注解和方法参数决定

		List<Class<? extends Throwable>> result = new ArrayList<>();

		// 把方法上面 @ExceptionHandler 的异常类型，都收集到 result
		detectAnnotationExceptionMappings(method, result);

		// 如果注解没有声明，则读取方法签名
		if (result.isEmpty()) {
			for (Class<?> paramType : method.getParameterTypes()) {
				if (Throwable.class.isAssignableFrom(paramType)) {
					result.add((Class<? extends Throwable>) paramType);
				}
			}
		}

		// 如果还是没有，那么这个方法不合法
		if (result.isEmpty()) {
			throw new IllegalStateException("No exception types mapped to " + method);
		}
		return result;
	}

	/**
	 * 私有方法，收集方法可以处理的所有异常
	 *
	 * @param method 方法
	 * @param result 出参
	 */
	private void detectAnnotationExceptionMappings(Method method, List<Class<? extends Throwable>> result) {
		// 找到合并之后的注解 @ExceptionHandler
		ExceptionHandler ann = AnnotatedElementUtils.findMergedAnnotation(method, ExceptionHandler.class);

		Assert.state(ann != null, "No ExceptionHandler annotation");

		// 把注解里面的异常都放到 result
		result.addAll(Arrays.asList(ann.value()));
	}

	private void addExceptionMapping(Class<? extends Throwable> exceptionType, Method method) {
		Method oldMethod = this.mappedMethods.put(exceptionType, method);

		// 不能出现同一个异常，可以映射到多个 Method，有歧义
		// 我们这里是说，Class 完全相同，没有考虑类型兼容的问题
		if (oldMethod != null && !oldMethod.equals(method)) {
			throw new IllegalStateException("Ambiguous @ExceptionHandler method mapped for [" +
					exceptionType + "]: {" + oldMethod + ", " + method + "}");
		}
	}

	/**
	 * Whether the contained type has any exception mappings.
	 */
	public boolean hasExceptionMappings() {
		return !this.mappedMethods.isEmpty();
	}

	/**
	 * Find a {@link Method} to handle the given exception.
	 * <p>Uses {@link ExceptionDepthComparator} if more than one match is found.
	 *
	 * @param exception the exception
	 * @return a Method to handle the exception, or {@code null} if none found
	 */
	@Nullable
	public Method resolveMethod(Exception exception) {
		return resolveMethodByThrowable(exception);
	}

	/**
	 * Find a {@link Method} to handle the given Throwable.
	 * <p>Uses {@link ExceptionDepthComparator} if more than one match is found.
	 *
	 * @param exception the exception
	 * @return a Method to handle the exception, or {@code null} if none found
	 * @since 5.0
	 */
	@Nullable
	public Method resolveMethodByThrowable(Throwable exception) {
		Method method = resolveMethodByExceptionType(exception.getClass());
		if (method == null) {
			// 如果直接用异常对象找不到，那么尝试其 cause
			Throwable cause = exception.getCause();
			if (cause != null) {
				method = resolveMethodByThrowable(cause);
			}
		}
		return method;
	}

	/**
	 * Find a {@link Method} to handle the given exception type. This can be
	 * useful if an {@link Exception} instance is not available (e.g. for tools).
	 * <p>Uses {@link ExceptionDepthComparator} if more than one match is found.
	 *
	 * @param exceptionType the exception type
	 * @return a Method to handle the exception, or {@code null} if none found
	 */
	@Nullable
	public Method resolveMethodByExceptionType(Class<? extends Throwable> exceptionType) {
		// 每个 beanClass 独享自己的 resolver

		// 根据 exception 类型检查缓存
		Method method = this.exceptionLookupCache.get(exceptionType);
		if (method == null) {
			// 若无，则寻找对应的处理 method，找不到会返回一个标记对象
			method = getMappedMethod(exceptionType);
			this.exceptionLookupCache.put(exceptionType, method);
		}

		// 如果没有找到，返回 null
		return (method != NO_MATCHING_EXCEPTION_HANDLER_METHOD ? method : null);
	}

	/**
	 * Return the {@link Method} mapped to the given exception type, or
	 * {@link #NO_MATCHING_EXCEPTION_HANDLER_METHOD} if none.
	 */
	private Method getMappedMethod(Class<? extends Throwable> exceptionType) {
		List<Class<? extends Throwable>> matches = new ArrayList<>();

		// mappedMethods 可以根据异常找到处理的 Method
		for (Class<? extends Throwable> mappedException : this.mappedMethods.keySet()) {
			// 异常类型兼容，那么 Method 可以处理这种异常
			if (mappedException.isAssignableFrom(exceptionType)) {
				matches.add(mappedException);
			}
		}

		// 如果找到了多个可能因为定义了多个类型，都可以兼容这种异常
		// [相同异常类型映射到同一个方法，在侦测 ExceptionHandler 阶段就会即时发现并报错]
		if (!matches.isEmpty()) {
			if (matches.size() > 1) {
				// 排序，找出深度最浅的一个，作为处理异常的方法
				matches.sort(new ExceptionDepthComparator(exceptionType));
			}
			return this.mappedMethods.get(matches.get(0));
		} else {
			return NO_MATCHING_EXCEPTION_HANDLER_METHOD;
		}
	}

	/**
	 * For the {@link #NO_MATCHING_EXCEPTION_HANDLER_METHOD} constant.
	 */
	@SuppressWarnings("unused")
	private void noMatchingExceptionHandler() {
	}

}
