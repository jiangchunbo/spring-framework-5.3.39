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

package org.springframework.web.servlet.mvc.method.annotation;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.servlet.http.HttpServletRequest;

import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringValueResolver;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.MatchableHandlerMapping;
import org.springframework.web.servlet.handler.RequestMatchResult;
import org.springframework.web.servlet.mvc.condition.AbstractRequestCondition;
import org.springframework.web.servlet.mvc.condition.CompositeRequestCondition;
import org.springframework.web.servlet.mvc.condition.ConsumesRequestCondition;
import org.springframework.web.servlet.mvc.condition.RequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Creates {@link RequestMappingInfo} instances from type and method-level
 * {@link RequestMapping @RequestMapping} annotations in
 * {@link Controller @Controller} classes.
 *
 * <p><strong>Deprecation Note:</strong></p> In 5.2.4,
 * {@link #setUseSuffixPatternMatch(boolean) useSuffixPatternMatch} and
 * {@link #setUseRegisteredSuffixPatternMatch(boolean) useRegisteredSuffixPatternMatch}
 * were deprecated in order to discourage use of path extensions for request
 * mapping and for content negotiation (with similar deprecations in
 * {@link org.springframework.web.accept.ContentNegotiationManagerFactoryBean
 * ContentNegotiationManagerFactoryBean}). For further context, please read issue
 * <a href="https://github.com/spring-projects/spring-framework/issues/24179">#24179</a>.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Sam Brannen
 * @since 3.1
 */
public class RequestMappingHandlerMapping extends RequestMappingInfoHandlerMapping
		implements MatchableHandlerMapping, EmbeddedValueResolverAware {

	private boolean useSuffixPatternMatch = false;

	private boolean useRegisteredSuffixPatternMatch = false;

	private boolean useTrailingSlashMatch = true;

	private Map<String, Predicate<Class<?>>> pathPrefixes = Collections.emptyMap();

	private ContentNegotiationManager contentNegotiationManager = new ContentNegotiationManager();

	@Nullable
	private StringValueResolver embeddedValueResolver;

	private RequestMappingInfo.BuilderConfiguration config = new RequestMappingInfo.BuilderConfiguration();


	/**
	 * Whether to use suffix pattern match (".*") when matching patterns to
	 * requests. If enabled a method mapped to "/users" also matches to "/users.*".
	 * <p>By default value this is set to {@code false}.
	 * <p>Also see {@link #setUseRegisteredSuffixPatternMatch(boolean)} for
	 * more fine-grained control over specific suffixes to allow.
	 * <p><strong>Note:</strong> This property is ignored when
	 * {@link #setPatternParser(PathPatternParser)} is configured.
	 *
	 * @deprecated as of 5.2.4. See class level note on the deprecation of
	 * path extension config options. As there is no replacement for this method,
	 * in 5.2.x it is necessary to set it to {@code false}. In 5.3 the default
	 * changes to {@code false} and use of this property becomes unnecessary.
	 */
	@Deprecated
	public void setUseSuffixPatternMatch(boolean useSuffixPatternMatch) {
		this.useSuffixPatternMatch = useSuffixPatternMatch;
	}

	/**
	 * Whether suffix pattern matching should work only against path extensions
	 * explicitly registered with the {@link ContentNegotiationManager}. This
	 * is generally recommended to reduce ambiguity and to avoid issues such as
	 * when a "." appears in the path for other reasons.
	 * <p>By default this is set to "false".
	 * <p><strong>Note:</strong> This property is ignored when
	 * {@link #setPatternParser(PathPatternParser)} is configured.
	 *
	 * @deprecated as of 5.2.4. See class level note on the deprecation of
	 * path extension config options.
	 */
	@Deprecated
	public void setUseRegisteredSuffixPatternMatch(boolean useRegisteredSuffixPatternMatch) {
		this.useRegisteredSuffixPatternMatch = useRegisteredSuffixPatternMatch;
		// 这个写法就是说，这两个变量都将会开启后缀模式匹配
		this.useSuffixPatternMatch = (useRegisteredSuffixPatternMatch || this.useSuffixPatternMatch);
	}

	/**
	 * Whether to match to URLs irrespective of the presence of a trailing slash.
	 * If enabled a method mapped to "/users" also matches to "/users/".
	 * <p>The default value is {@code true}.
	 */
	public void setUseTrailingSlashMatch(boolean useTrailingSlashMatch) {
		this.useTrailingSlashMatch = useTrailingSlashMatch;

		// 如果配置了 PatternParser，是否匹配末尾斜杠
		if (getPatternParser() != null) {
			getPatternParser().setMatchOptionalTrailingSeparator(useTrailingSlashMatch);
		}
	}

	/**
	 * Configure path prefixes to apply to controller methods.
	 * <p>Prefixes are used to enrich the mappings of every {@code @RequestMapping}
	 * method whose controller type is matched by the corresponding
	 * {@code Predicate}. The prefix for the first matching predicate is used.
	 * <p>Consider using {@link org.springframework.web.method.HandlerTypePredicate
	 * HandlerTypePredicate} to group controllers.
	 *
	 * @param prefixes a map with path prefixes as key
	 * @since 5.1
	 */
	public void setPathPrefixes(Map<String, Predicate<Class<?>>> prefixes) {
		this.pathPrefixes = (!prefixes.isEmpty() ?
				Collections.unmodifiableMap(new LinkedHashMap<>(prefixes)) :
				Collections.emptyMap());
	}

	/**
	 * The configured path prefixes as a read-only, possibly empty map.
	 *
	 * @since 5.1
	 */
	public Map<String, Predicate<Class<?>>> getPathPrefixes() {
		return this.pathPrefixes;
	}

	/**
	 * Set the {@link ContentNegotiationManager} to use to determine requested media types.
	 * If not set, the default constructor is used.
	 */
	public void setContentNegotiationManager(ContentNegotiationManager contentNegotiationManager) {
		Assert.notNull(contentNegotiationManager, "ContentNegotiationManager must not be null");
		this.contentNegotiationManager = contentNegotiationManager;
	}

	/**
	 * Return the configured {@link ContentNegotiationManager}.
	 */
	public ContentNegotiationManager getContentNegotiationManager() {
		return this.contentNegotiationManager;
	}

	@Override
	public void setEmbeddedValueResolver(StringValueResolver resolver) {
		this.embeddedValueResolver = resolver;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void afterPropertiesSet() {
		// 创建了一个 BuilderConfiguration
		this.config = new RequestMappingInfo.BuilderConfiguration();

		// 是否支持末尾斜杠匹配
		this.config.setTrailingSlashMatch(useTrailingSlashMatch());

		// 内容协商管理器
		this.config.setContentNegotiationManager(getContentNegotiationManager());

		// pattern
		if (getPatternParser() != null) {
			this.config.setPatternParser(getPatternParser());
			Assert.isTrue(!this.useSuffixPatternMatch && !this.useRegisteredSuffixPatternMatch,
					"Suffix pattern matching not supported with PathPatternParser.");
		} else {
			this.config.setSuffixPatternMatch(useSuffixPatternMatch());
			this.config.setRegisteredSuffixPatternMatch(useRegisteredSuffixPatternMatch());
			this.config.setPathMatcher(getPathMatcher());
		}

		super.afterPropertiesSet();
	}


	/**
	 * Whether to use registered suffixes for pattern matching.
	 *
	 * @deprecated as of 5.2.4. See deprecation notice on
	 * {@link #setUseSuffixPatternMatch(boolean)}.
	 */
	@Deprecated
	public boolean useSuffixPatternMatch() {
		return this.useSuffixPatternMatch;
	}

	/**
	 * Whether to use registered suffixes for pattern matching.
	 *
	 * @deprecated as of 5.2.4. See deprecation notice on
	 * {@link #setUseRegisteredSuffixPatternMatch(boolean)}.
	 */
	@Deprecated
	public boolean useRegisteredSuffixPatternMatch() {
		return this.useRegisteredSuffixPatternMatch;
	}

	/**
	 * Whether to match to URLs irrespective of the presence of a trailing slash.
	 */
	public boolean useTrailingSlashMatch() {
		return this.useTrailingSlashMatch;
	}

	/**
	 * Return the file extensions to use for suffix pattern matching.
	 *
	 * @deprecated as of 5.2.4. See class-level note on the deprecation of path
	 * extension config options.
	 */
	@Nullable
	@Deprecated
	@SuppressWarnings("deprecation")
	public List<String> getFileExtensions() {
		return this.config.getFileExtensions();
	}

	/**
	 * Obtain a {@link RequestMappingInfo.BuilderConfiguration} that can reflects
	 * the internal configuration of this {@code HandlerMapping} and can be used
	 * to set {@link RequestMappingInfo.Builder#options(RequestMappingInfo.BuilderConfiguration)}.
	 * <p>This is useful for programmatic registration of request mappings via
	 * {@link #registerHandlerMethod(Object, Method, RequestMappingInfo)}.
	 *
	 * @return the builder configuration that reflects the internal state
	 * @since 5.3.14
	 */
	public RequestMappingInfo.BuilderConfiguration getBuilderConfiguration() {
		return this.config;
	}


	/**
	 * {@inheritDoc}
	 * <p>Expects a handler to have either a type-level @{@link Controller}
	 * annotation or a type-level @{@link RequestMapping} annotation.
	 */
	@Override
	protected boolean isHandler(Class<?> beanType) {
		return (AnnotatedElementUtils.hasAnnotation(beanType, Controller.class) ||
				AnnotatedElementUtils.hasAnnotation(beanType, RequestMapping.class));
	}

	/**
	 * Uses method and type-level @{@link RequestMapping} annotations to create
	 * the RequestMappingInfo.
	 *
	 * @return the created RequestMappingInfo, or {@code null} if the method
	 * does not have a {@code @RequestMapping} annotation.
	 * @see #getCustomMethodCondition(Method)
	 * @see #getCustomTypeCondition(Class)
	 */
	@Override
	@Nullable
	protected RequestMappingInfo getMappingForMethod(Method method, Class<?> handlerType) {
		// 传入方法，创建一个请求映射的信息，其实就是解析方法上面的注解
		// >>>>>>>>>>>>>>> 如果方法上面没有注解 RequestMapping，就是返回 null，不用处理了
		RequestMappingInfo info = createRequestMappingInfo(method);
		if (info != null) {
			// 类上面也创建一个映射信息，可能需要一些前缀
			RequestMappingInfo typeInfo = createRequestMappingInfo(handlerType);

			// 把类级别的信息和方法级别的信息合并
			if (typeInfo != null) {
				info = typeInfo.combine(info);
			}

			// 传入你的 handler 类型，根据这个类型，你可以自定义前缀
			// 比如获取 Class 的全限定名，判断是否处于某个包下面
			String prefix = getPathPrefix(handlerType);
			if (prefix != null) {
				info = RequestMappingInfo.paths(prefix).options(this.config).build().combine(info);
			}
		}
		return info;
	}

	@Nullable
	String getPathPrefix(Class<?> handlerType) {
		// 这里可能一般不会有人去这么配
		// 需要提前注册 一个 String path -> Predicate 的映射关系
		for (Map.Entry<String, Predicate<Class<?>>> entry : this.pathPrefixes.entrySet()) {
			// Predicate 断言去测试这个 handlerType
			if (entry.getValue().test(handlerType)) {
				// 若通过，则获取 key，key 就是前缀，然后还进行了嵌入值解析，得到了 prefix 前缀
				String prefix = entry.getKey();
				if (this.embeddedValueResolver != null) {
					prefix = this.embeddedValueResolver.resolveStringValue(prefix);
				}
				return prefix;
			}
		}
		return null;
	}

	/**
	 * Delegates to {@link #createRequestMappingInfo(RequestMapping, RequestCondition)},
	 * supplying the appropriate custom {@link RequestCondition} depending on whether
	 * the supplied {@code annotatedElement} is a class or method.
	 *
	 * @see #getCustomTypeCondition(Class)
	 * @see #getCustomMethodCondition(Method)
	 */
	@Nullable
	private RequestMappingInfo createRequestMappingInfo(AnnotatedElement element) {
		// 获得注解信息
		RequestMapping requestMapping = AnnotatedElementUtils.findMergedAnnotation(element, RequestMapping.class);
		RequestCondition<?> condition = (element instanceof Class
				? getCustomTypeCondition((Class<?>) element)
				: getCustomMethodCondition((Method) element));

		// 创建一个 RequestMappingInfo 对象
		return (requestMapping != null ? createRequestMappingInfo(requestMapping, condition) : null);
	}

	/**
	 * Provide a custom type-level request condition.
	 * The custom {@link RequestCondition} can be of any type so long as the
	 * same condition type is returned from all calls to this method in order
	 * to ensure custom request conditions can be combined and compared.
	 * <p>Consider extending {@link AbstractRequestCondition} for custom
	 * condition types and using {@link CompositeRequestCondition} to provide
	 * multiple custom conditions.
	 *
	 * @param handlerType the handler type for which to create the condition
	 * @return the condition, or {@code null}
	 */
	@Nullable
	protected RequestCondition<?> getCustomTypeCondition(Class<?> handlerType) {
		// 自定义类型条件，似乎根本没用
		return null;
	}

	/**
	 * Provide a custom method-level request condition.
	 * The custom {@link RequestCondition} can be of any type so long as the
	 * same condition type is returned from all calls to this method in order
	 * to ensure custom request conditions can be combined and compared.
	 * <p>Consider extending {@link AbstractRequestCondition} for custom
	 * condition types and using {@link CompositeRequestCondition} to provide
	 * multiple custom conditions.
	 *
	 * @param method the handler method for which to create the condition
	 * @return the condition, or {@code null}
	 */
	@Nullable
	protected RequestCondition<?> getCustomMethodCondition(Method method) {
		// 自定义条件，似乎没有用
		return null;
	}

	/**
	 * Create a {@link RequestMappingInfo} from the supplied
	 * {@link RequestMapping @RequestMapping} annotation, which is either
	 * a directly declared annotation, a meta-annotation, or the synthesized
	 * result of merging annotation attributes within an annotation hierarchy.
	 */
	protected RequestMappingInfo createRequestMappingInfo(
			RequestMapping requestMapping, @Nullable RequestCondition<?> customCondition) {

		RequestMappingInfo.Builder builder = RequestMappingInfo
				// 注意一下，这里还会解析你路径中的嵌入值，也就是你甚至可以加入 ${} 这种标记
				.paths(resolveEmbeddedValuesInPatterns(requestMapping.path()))
				.methods(requestMapping.method())
				.params(requestMapping.params())
				.headers(requestMapping.headers())
				.consumes(requestMapping.consumes())
				.produces(requestMapping.produces())
				.mappingName(requestMapping.name());
		if (customCondition != null) {
			builder.customCondition(customCondition);
		}
		return builder.options(this.config).build();
	}

	/**
	 * Resolve placeholder values in the given array of patterns.
	 *
	 * @return a new array with updated patterns
	 */
	protected String[] resolveEmbeddedValuesInPatterns(String[] patterns) {
		if (this.embeddedValueResolver == null) {
			return patterns;
		} else {
			String[] resolvedPatterns = new String[patterns.length];
			for (int i = 0; i < patterns.length; i++) {
				resolvedPatterns[i] = this.embeddedValueResolver.resolveStringValue(patterns[i]);
			}
			return resolvedPatterns;
		}
	}

	@Override
	public void registerMapping(RequestMappingInfo mapping, Object handler, Method method) {
		super.registerMapping(mapping, handler, method);
		updateConsumesCondition(mapping, method);
	}

	/**
	 * {@inheritDoc}
	 * <p><strong>Note:</strong> To create the {@link RequestMappingInfo},
	 * please use {@link #getBuilderConfiguration()} and set the options on
	 * {@link RequestMappingInfo.Builder#options(RequestMappingInfo.BuilderConfiguration)}
	 * to match how this {@code HandlerMapping} is configured. This
	 * is important for example to ensure use of
	 * {@link org.springframework.web.util.pattern.PathPattern} or
	 * {@link org.springframework.util.PathMatcher} based matching.
	 *
	 * @param handler the bean name of the handler or the handler instance
	 * @param method  the method to register
	 * @param mapping the mapping conditions associated with the handler method
	 */
	@Override
	protected void registerHandlerMethod(Object handler, Method method, RequestMappingInfo mapping) {
		super.registerHandlerMethod(handler, method, mapping);
		updateConsumesCondition(mapping, method);
	}

	/**
	 * 这个方法是一个注册之后的后置操作
	 */
	private void updateConsumesCondition(RequestMappingInfo info, Method method) {
		// 拿到其中的 consumes 请求条件
		ConsumesRequestCondition condition = info.getConsumesCondition();
		if (!condition.isEmpty()) {
			// 遍历每个方法参数，找到其中带有 @RequestBody 的
			for (Parameter parameter : method.getParameters()) {
				MergedAnnotation<RequestBody> annot = MergedAnnotations.from(parameter).get(RequestBody.class);
				if (annot.isPresent()) {
					// 获取其中配置的 required 参数，表示是否必需请求体
					// 默认不是必需
					condition.setBodyRequired(annot.getBoolean("required"));
					break;
				}
			}
		}
	}

	@Override
	public RequestMatchResult match(HttpServletRequest request, String pattern) {
		Assert.isNull(getPatternParser(), "This HandlerMapping requires a PathPattern");
		RequestMappingInfo info = RequestMappingInfo.paths(pattern).options(this.config).build();
		RequestMappingInfo match = info.getMatchingCondition(request);
		return (match != null && match.getPatternsCondition() != null ?
				new RequestMatchResult(
						match.getPatternsCondition().getPatterns().iterator().next(),
						UrlPathHelper.getResolvedLookupPath(request),
						getPathMatcher()) : null);
	}

	@Override
	protected CorsConfiguration initCorsConfiguration(Object handler, Method method, RequestMappingInfo mappingInfo) {
		HandlerMethod handlerMethod = createHandlerMethod(handler, method);
		Class<?> beanType = handlerMethod.getBeanType();
		CrossOrigin typeAnnotation = AnnotatedElementUtils.findMergedAnnotation(beanType, CrossOrigin.class);
		CrossOrigin methodAnnotation = AnnotatedElementUtils.findMergedAnnotation(method, CrossOrigin.class);

		if (typeAnnotation == null && methodAnnotation == null) {
			return null;
		}

		CorsConfiguration config = new CorsConfiguration();
		updateCorsConfig(config, typeAnnotation);
		updateCorsConfig(config, methodAnnotation);

		if (CollectionUtils.isEmpty(config.getAllowedMethods())) {
			for (RequestMethod allowedMethod : mappingInfo.getMethodsCondition().getMethods()) {
				config.addAllowedMethod(allowedMethod.name());
			}
		}
		return config.applyPermitDefaultValues();
	}

	private void updateCorsConfig(CorsConfiguration config, @Nullable CrossOrigin annotation) {
		if (annotation == null) {
			return;
		}
		for (String origin : annotation.origins()) {
			config.addAllowedOrigin(resolveCorsAnnotationValue(origin));
		}
		for (String patterns : annotation.originPatterns()) {
			config.addAllowedOriginPattern(resolveCorsAnnotationValue(patterns));
		}
		for (RequestMethod method : annotation.methods()) {
			config.addAllowedMethod(method.name());
		}
		for (String header : annotation.allowedHeaders()) {
			config.addAllowedHeader(resolveCorsAnnotationValue(header));
		}
		for (String header : annotation.exposedHeaders()) {
			config.addExposedHeader(resolveCorsAnnotationValue(header));
		}

		String allowCredentials = resolveCorsAnnotationValue(annotation.allowCredentials());
		if ("true".equalsIgnoreCase(allowCredentials)) {
			config.setAllowCredentials(true);
		} else if ("false".equalsIgnoreCase(allowCredentials)) {
			config.setAllowCredentials(false);
		} else if (!allowCredentials.isEmpty()) {
			throw new IllegalStateException("@CrossOrigin's allowCredentials value must be \"true\", \"false\", " +
					"or an empty string (\"\"): current value is [" + allowCredentials + "]");
		}

		String allowPrivateNetwork = resolveCorsAnnotationValue(annotation.allowPrivateNetwork());
		if ("true".equalsIgnoreCase(allowPrivateNetwork)) {
			config.setAllowPrivateNetwork(true);
		} else if ("false".equalsIgnoreCase(allowPrivateNetwork)) {
			config.setAllowPrivateNetwork(false);
		} else if (!allowPrivateNetwork.isEmpty()) {
			throw new IllegalStateException("@CrossOrigin's allowPrivateNetwork value must be \"true\", \"false\", " +
					"or an empty string (\"\"): current value is [" + allowPrivateNetwork + "]");
		}

		if (annotation.maxAge() >= 0) {
			config.setMaxAge(annotation.maxAge());
		}
	}

	private String resolveCorsAnnotationValue(String value) {
		if (this.embeddedValueResolver != null) {
			String resolved = this.embeddedValueResolver.resolveStringValue(value);
			return (resolved != null ? resolved : "");
		} else {
			return value;
		}
	}

}
