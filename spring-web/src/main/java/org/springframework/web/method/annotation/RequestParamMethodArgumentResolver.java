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

import java.beans.PropertyEditor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.UriComponentsContributor;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.support.MultipartResolutionDelegate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Resolves method arguments annotated with @{@link RequestParam}, arguments of
 * type {@link MultipartFile} in conjunction with Spring's {@link MultipartResolver}
 * abstraction, and arguments of type {@code javax.servlet.http.Part} in conjunction
 * with Servlet 3.0 multipart requests. This resolver can also be created in default
 * resolution mode in which simple types (int, long, etc.) not annotated with
 * {@link RequestParam @RequestParam} are also treated as request parameters with
 * the parameter name derived from the argument name.
 *
 * <p>If the method parameter type is {@link Map}, the name specified in the
 * annotation is used to resolve the request parameter String value. The value is
 * then converted to a {@link Map} via type conversion assuming a suitable
 * {@link Converter} or {@link PropertyEditor} has been registered.
 * Or if a request parameter name is not specified the
 * {@link RequestParamMapMethodArgumentResolver} is used instead to provide
 * access to all request parameters in the form of a map.
 *
 * <p>A {@link WebDataBinder} is invoked to apply type conversion to resolved request
 * header values that don't yet match the method parameter type.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Brian Clozel
 * @see RequestParamMapMethodArgumentResolver
 * @since 3.1
 */
public class RequestParamMethodArgumentResolver extends AbstractNamedValueMethodArgumentResolver
		implements UriComponentsContributor {

	// 简单理解就是用于解析 @RequestParam 这一类的方法参数的

	/**
	 * 表示字符串类型的类型描述器
	 */
	private static final TypeDescriptor STRING_TYPE_DESCRIPTOR = TypeDescriptor.valueOf(String.class);

	/**
	 * 是否启用默认解析模式
	 */
	private final boolean useDefaultResolution;

	/**
	 * Create a new {@link RequestParamMethodArgumentResolver} instance.
	 *
	 * @param useDefaultResolution in default resolution mode a method argument
	 *                             that is a simple type, as defined in {@link BeanUtils#isSimpleProperty},
	 *                             is treated as a request parameter even if it isn't annotated, the
	 *                             request parameter name is derived from the method parameter name.
	 */
	public RequestParamMethodArgumentResolver(boolean useDefaultResolution) {
		this.useDefaultResolution = useDefaultResolution;
	}

	/**
	 * Create a new {@link RequestParamMethodArgumentResolver} instance.
	 *
	 * @param beanFactory          a bean factory used for resolving  ${...} placeholder
	 *                             and #{...} SpEL expressions in default values, or {@code null} if default
	 *                             values are not expected to contain expressions
	 * @param useDefaultResolution in default resolution mode a method argument
	 *                             that is a simple type, as defined in {@link BeanUtils#isSimpleProperty},
	 *                             is treated as a request parameter even if it isn't annotated, the
	 *                             request parameter name is derived from the method parameter name.
	 */
	public RequestParamMethodArgumentResolver(@Nullable ConfigurableBeanFactory beanFactory,
											  boolean useDefaultResolution) {

		super(beanFactory);
		this.useDefaultResolution = useDefaultResolution;
	}

	/**
	 * Supports the following:
	 * <ul>
	 * <li>@RequestParam-annotated method arguments.
	 * This excludes {@link Map} params where the annotation does not specify a name.
	 * See {@link RequestParamMapMethodArgumentResolver} instead for such params.
	 * <li>Arguments of type {@link MultipartFile} unless annotated with @{@link RequestPart}.
	 * <li>Arguments of type {@code Part} unless annotated with @{@link RequestPart}.
	 * <li>In default resolution mode, simple type arguments even if not with @{@link RequestParam}.
	 * </ul>
	 */
	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		// 是否能够解析这种方法参数

		// 一般我们认为有 @RequestParam 这个解析器就能处理
		// 还有一种情况是如果你给一个 Map 加注解。 @Request Map
		if (parameter.hasParameterAnnotation(RequestParam.class)) {
			if (Map.class.isAssignableFrom(parameter.nestedIfOptional().getNestedParameterType())) {
				RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
				return (requestParam != null && StringUtils.hasText(requestParam.name()));
			}
			return true;
		}

		// 不关心 @RequestPart 注解，因为这不是我的领域！！！
		if (parameter.hasParameterAnnotation(RequestPart.class)) {
			return false;
		}

		// 不带注解，但是类型很特殊
		parameter = parameter.nestedIfOptional();

		// 检查参数的类型，是否是 MultipartFile 或者 Part (支持聚合体)
		if (MultipartResolutionDelegate.isMultipartArgument(parameter)) {
			return true;
		}

		// 开启默认解析
		else if (this.useDefaultResolution) {
			// 如果使用默认解析，那么简单类型也可以处理
			return BeanUtils.isSimpleProperty(parameter.getNestedParameterType());
		} else {
			return false;
		}
	}

	@Override
	protected NamedValueInfo createNamedValueInfo(MethodParameter parameter) {
		RequestParam ann = parameter.getParameterAnnotation(RequestParam.class);
		return (ann != null ? new RequestParamNamedValueInfo(ann) : new RequestParamNamedValueInfo());
	}

	/**
	 * 这个方法的返回值可能是 Collection、数组、单个值、也可能是 String
	 */
	@Override
	@Nullable
	protected Object resolveName(String name, MethodParameter parameter, NativeWebRequest request) throws Exception {
		HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);

		// 解析 multipart 参数
		if (servletRequest != null) {
			Object mpArg = MultipartResolutionDelegate.resolveMultipartArgument(name, parameter, servletRequest);
			if (mpArg != MultipartResolutionDelegate.UNRESOLVABLE) {
				return mpArg;
			}
		}

		// 如果是 MultipartRequest 类型
		Object arg = null;
		MultipartRequest multipartRequest = request.getNativeRequest(MultipartRequest.class);
		if (multipartRequest != null) {
			List<MultipartFile> files = multipartRequest.getFiles(name);
			if (!files.isEmpty()) {
				arg = (files.size() == 1 ? files.get(0) : files);
			}
		}

		// 依然无法解析，就走 request.getParameterValues
		if (arg == null) {
			String[] paramValues = request.getParameterValues(name);
			if (paramValues != null) {
				arg = (paramValues.length == 1 ? paramValues[0] : paramValues);
			}
		}
		return arg;
	}

	@Override
	protected void handleMissingValue(String name, MethodParameter parameter, NativeWebRequest request)
			throws Exception {

		// 没有沿用父类的处理方式，因为父类的异常太不明显
		// 这里选择自己抛出特定的异常
		handleMissingValueInternal(name, parameter, request, false);
	}

	@Override
	protected void handleMissingValueAfterConversion(
			String name, MethodParameter parameter, NativeWebRequest request) throws Exception {
		handleMissingValueInternal(name, parameter, request, true);
	}

	/**
	 * 处理找不到值，其实也是抛出异常
	 */
	protected void handleMissingValueInternal(
			String name, MethodParameter parameter, NativeWebRequest request, boolean missingAfterConversion)
			throws Exception {

		HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);

		// 看看参数是不是 Multipart 相关的
		if (MultipartResolutionDelegate.isMultipartArgument(parameter)) {
			if (servletRequest == null || !MultipartResolutionDelegate.isMultipartRequest(servletRequest)) {
				throw new MultipartException("Current request is not a multipart request");
			} else {
				throw new MissingServletRequestPartException(name);
			}
		} else {
			throw new MissingServletRequestParameterException(name,
					parameter.getNestedParameterType().getSimpleName(), missingAfterConversion);
		}
	}

	@Override
	public void contributeMethodArgument(MethodParameter parameter, @Nullable Object value,
										 UriComponentsBuilder builder, Map<String, Object> uriVariables, ConversionService conversionService) {

		Class<?> paramType = parameter.getNestedParameterType();
		if (Map.class.isAssignableFrom(paramType) || MultipartFile.class == paramType || Part.class == paramType) {
			return;
		}

		RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
		String name = (requestParam != null && StringUtils.hasLength(requestParam.name()) ?
				requestParam.name() : parameter.getParameterName());
		Assert.state(name != null, "Unresolvable parameter name");

		parameter = parameter.nestedIfOptional();
		if (value instanceof Optional) {
			value = ((Optional<?>) value).orElse(null);
		}

		if (value == null) {
			if (requestParam != null &&
					(!requestParam.required() || !requestParam.defaultValue().equals(ValueConstants.DEFAULT_NONE))) {
				return;
			}
			builder.queryParam(name);
		} else if (value instanceof Collection) {
			for (Object element : (Collection<?>) value) {
				element = formatUriValue(conversionService, TypeDescriptor.nested(parameter, 1), element);
				builder.queryParam(name, element);
			}
		} else {
			builder.queryParam(name, formatUriValue(conversionService, new TypeDescriptor(parameter), value));
		}
	}

	@Nullable
	protected String formatUriValue(
			@Nullable ConversionService cs, @Nullable TypeDescriptor sourceType, @Nullable Object value) {

		if (value == null) {
			return null;
		} else if (value instanceof String) {
			return (String) value;
		} else if (cs != null) {
			return (String) cs.convert(value, sourceType, STRING_TYPE_DESCRIPTOR);
		} else {
			return value.toString();
		}
	}

	private static class RequestParamNamedValueInfo extends NamedValueInfo {

		public RequestParamNamedValueInfo() {
			super("", false, ValueConstants.DEFAULT_NONE);
		}

		public RequestParamNamedValueInfo(RequestParam annotation) {
			super(annotation.name(), annotation.required(), annotation.defaultValue());
		}

	}

}
