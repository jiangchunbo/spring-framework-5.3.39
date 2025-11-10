/*
 * Copyright 2002-2023 the original author or authors.
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

package org.springframework.beans.factory.annotation;

import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.support.LookupOverride;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor BeanPostProcessor}
 * implementation that autowires annotated fields, setter methods, and arbitrary
 * config methods. Such members to be injected are detected through annotations:
 * by default, Spring's {@link Autowired @Autowired} and {@link Value @Value}
 * annotations.
 *
 * <p>Also supports JSR-330's {@link javax.inject.Inject @Inject} annotation,
 * if available, as a direct alternative to Spring's own {@code @Autowired}.
 *
 * <h3>Autowired Constructors</h3>
 * <p>Only one constructor of any given bean class may declare this annotation with
 * the 'required' attribute set to {@code true}, indicating <i>the</i> constructor
 * to autowire when used as a Spring bean. Furthermore, if the 'required' attribute
 * is set to {@code true}, only a single constructor may be annotated with
 * {@code @Autowired}. If multiple <i>non-required</i> constructors declare the
 * annotation, they will be considered as candidates for autowiring. The constructor
 * with the greatest number of dependencies that can be satisfied by matching beans
 * in the Spring container will be chosen. If none of the candidates can be satisfied,
 * then a primary/default constructor (if present) will be used. If a class only
 * declares a single constructor to begin with, it will always be used, even if not
 * annotated. An annotated constructor does not have to be public.
 *
 * <h3>Autowired Fields</h3>
 * <p>Fields are injected right after construction of a bean, before any
 * config methods are invoked. Such a config field does not have to be public.
 *
 * <h3>Autowired Methods</h3>
 * <p>Config methods may have an arbitrary name and any number of arguments; each of
 * those arguments will be autowired with a matching bean in the Spring container.
 * Bean property setter methods are effectively just a special case of such a
 * general config method. Config methods do not have to be public.
 *
 * <h3>Annotation Config vs. XML Config</h3>
 * <p>A default {@code AutowiredAnnotationBeanPostProcessor} will be registered
 * by the "context:annotation-config" and "context:component-scan" XML tags.
 * Remove or turn off the default annotation configuration there if you intend
 * to specify a custom {@code AutowiredAnnotationBeanPostProcessor} bean definition.
 *
 * <p><b>NOTE:</b> Annotation injection will be performed <i>before</i> XML injection;
 * thus the latter configuration will override the former for properties wired through
 * both approaches.
 *
 * <h3>{@literal @}Lookup Methods</h3>
 * <p>In addition to regular injection points as discussed above, this post-processor
 * also handles Spring's {@link Lookup @Lookup} annotation which identifies lookup
 * methods to be replaced by the container at runtime. This is essentially a type-safe
 * version of {@code getBean(Class, args)} and {@code getBean(String, args)}.
 * See {@link Lookup @Lookup's javadoc} for details.
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Stephane Nicoll
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @see #setAutowiredAnnotationType
 * @see Autowired
 * @see Value
 * @since 2.5
 */
public class AutowiredAnnotationBeanPostProcessor implements SmartInstantiationAwareBeanPostProcessor,
		MergedBeanDefinitionPostProcessor, PriorityOrdered, BeanFactoryAware {

	protected final Log logger = LogFactory.getLog(getClass());

	/**
	 * 存放支持哪些注解，默认给了 4 个位置，说明最多 4 个
	 */
	private final Set<Class<? extends Annotation>> autowiredAnnotationTypes = new LinkedHashSet<>(4);

	private String requiredParameterName = "required";

	private boolean requiredParameterValue = true;

	private int order = Ordered.LOWEST_PRECEDENCE - 2;

	@Nullable
	private ConfigurableListableBeanFactory beanFactory;

	@Nullable
	private MetadataReaderFactory metadataReaderFactory;

	private final Set<String> lookupMethodsChecked = Collections.newSetFromMap(new ConcurrentHashMap<>(256));

	private final Map<Class<?>, Constructor<?>[]> candidateConstructorsCache = new ConcurrentHashMap<>(256);

	/**
	 * 这是一个缓存，通过 String 类型可以找到 InjectionMetadata 注入元数据
	 */
	private final Map<String, InjectionMetadata> injectionMetadataCache = new ConcurrentHashMap<>(256);

	/**
	 * Create a new {@code AutowiredAnnotationBeanPostProcessor} for Spring's
	 * standard {@link Autowired @Autowired} and {@link Value @Value} annotations.
	 * <p>Also supports JSR-330's {@link javax.inject.Inject @Inject} annotation,
	 * if available.
	 */
	@SuppressWarnings("unchecked")
	public AutowiredAnnotationBeanPostProcessor() {
		// @Autowired Spring 的依赖注入注解
		this.autowiredAnnotationTypes.add(Autowired.class);

		// @Value Spring 的值注解，可以绑定环境属性
		this.autowiredAnnotationTypes.add(Value.class);

		// @Inject JSR-330 标准注解
		try {
			this.autowiredAnnotationTypes.add((Class<? extends Annotation>)
					ClassUtils.forName("javax.inject.Inject", AutowiredAnnotationBeanPostProcessor.class.getClassLoader()));
			logger.trace("JSR-330 'javax.inject.Inject' annotation found and supported for autowiring");
		} catch (ClassNotFoundException ex) {
			// JSR-330 API not available - simply skip.
		}
	}

	/**
	 * Set the 'autowired' annotation type, to be used on constructors, fields,
	 * setter methods, and arbitrary config methods.
	 * <p>The default autowired annotation types are the Spring-provided
	 * {@link Autowired @Autowired} and {@link Value @Value} annotations as well
	 * as JSR-330's {@link javax.inject.Inject @Inject} annotation, if available.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation type to indicate that a member is supposed
	 * to be autowired.
	 */
	public void setAutowiredAnnotationType(Class<? extends Annotation> autowiredAnnotationType) {
		Assert.notNull(autowiredAnnotationType, "'autowiredAnnotationType' must not be null");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.add(autowiredAnnotationType);
	}

	/**
	 * Set the 'autowired' annotation types, to be used on constructors, fields,
	 * setter methods, and arbitrary config methods.
	 * <p>The default autowired annotation types are the Spring-provided
	 * {@link Autowired @Autowired} and {@link Value @Value} annotations as well
	 * as JSR-330's {@link javax.inject.Inject @Inject} annotation, if available.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation types to indicate that a member is supposed
	 * to be autowired.
	 */
	public void setAutowiredAnnotationTypes(Set<Class<? extends Annotation>> autowiredAnnotationTypes) {
		Assert.notEmpty(autowiredAnnotationTypes, "'autowiredAnnotationTypes' must not be empty");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.addAll(autowiredAnnotationTypes);
	}

	/**
	 * Set the name of an attribute of the annotation that specifies whether it is required.
	 *
	 * @see #setRequiredParameterValue(boolean)
	 */
	public void setRequiredParameterName(String requiredParameterName) {
		this.requiredParameterName = requiredParameterName;
	}

	/**
	 * Set the boolean value that marks a dependency as required.
	 * <p>For example if using 'required=true' (the default), this value should be
	 * {@code true}; but if using 'optional=false', this value should be {@code false}.
	 *
	 * @see #setRequiredParameterName(String)
	 */
	public void setRequiredParameterValue(boolean requiredParameterValue) {
		this.requiredParameterValue = requiredParameterValue;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		if (!(beanFactory instanceof ConfigurableListableBeanFactory)) {
			throw new IllegalArgumentException(
					"AutowiredAnnotationBeanPostProcessor requires a ConfigurableListableBeanFactory: " + beanFactory);
		}
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
		this.metadataReaderFactory = new SimpleMetadataReaderFactory(this.beanFactory.getBeanClassLoader());
	}

	/**
	 * Bean Definition 已经合并完毕，所以具备了顶级 BeanDefinition，这时候这个处理器就想在 Bean Definition 设置一些属性
	 */
	@Override
	public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
		// 寻找注入元数据
		InjectionMetadata metadata = findAutowiringMetadata(beanName, beanType, null);

		// 放到 BeanDefinition 里
		metadata.checkConfigMembers(beanDefinition);
	}

	@Override
	public void resetBeanDefinition(String beanName) {
		this.lookupMethodsChecked.remove(beanName);
		this.injectionMetadataCache.remove(beanName);
	}

	/**
	 * 推断构造器
	 */
	@Override
	@Nullable
	public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, final String beanName)
			throws BeanCreationException {

		// Let's check for lookup methods here...
		if (!this.lookupMethodsChecked.contains(beanName)) {
			if (AnnotationUtils.isCandidateClass(beanClass, Lookup.class)) {
				try {
					Class<?> targetClass = beanClass;
					do {
						ReflectionUtils.doWithLocalMethods(targetClass, method -> {
							Lookup lookup = method.getAnnotation(Lookup.class);
							if (lookup != null) {
								Assert.state(this.beanFactory != null, "No BeanFactory available");
								LookupOverride override = new LookupOverride(method, lookup.value());
								try {
									RootBeanDefinition mbd = (RootBeanDefinition)
											this.beanFactory.getMergedBeanDefinition(beanName);
									mbd.getMethodOverrides().addOverride(override);
								} catch (NoSuchBeanDefinitionException ex) {
									throw new BeanCreationException(beanName,
											"Cannot apply @Lookup to beans without corresponding bean definition");
								}
							}
						});
						targetClass = targetClass.getSuperclass();
					}
					while (targetClass != null && targetClass != Object.class);

				} catch (IllegalStateException ex) {
					throw new BeanCreationException(beanName, "Lookup method resolution failed", ex);
				}
			}
			this.lookupMethodsChecked.add(beanName);
		}

		// Quick check on the concurrent map first, with minimal locking.
		// 缓存，Spring 老套路
		Constructor<?>[] candidateConstructors = this.candidateConstructorsCache.get(beanClass);
		if (candidateConstructors == null) {
			// Fully synchronized resolution now...
			synchronized (this.candidateConstructorsCache) {
				candidateConstructors = this.candidateConstructorsCache.get(beanClass);
				if (candidateConstructors == null) {
					// 拿到所有构造器
					Constructor<?>[] rawCandidates;
					try {
						rawCandidates = beanClass.getDeclaredConstructors();
					} catch (Throwable ex) {
						throw new BeanCreationException(beanName,
								"Resolution of declared constructors on bean Class [" + beanClass.getName() +
										"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
					}
					List<Constructor<?>> candidates = new ArrayList<>(rawCandidates.length);
					Constructor<?> requiredConstructor = null;
					Constructor<?> defaultConstructor = null;
					Constructor<?> primaryConstructor = BeanUtils.findPrimaryConstructor(beanClass);
					int nonSyntheticConstructors = 0;
					for (Constructor<?> candidate : rawCandidates) {
						// 检查是否是编译器生成的构造器
						if (!candidate.isSynthetic()) {
							// 统计用户提供的构造器数量
							nonSyntheticConstructors++;
						} else if (primaryConstructor != null) {
							continue;
						}

						// 寻找 @Autowired
						MergedAnnotation<?> ann = findAutowiredAnnotation(candidate);

						// 1. 确定构造器到底有没有 @Autowired 注解
						// 没有 @Autowired
						// 不过也许这是一个代理类，我们试试寻找它的 UserClass 有没有加 @Autowired
						if (ann == null) {
							// 拿到具体的用户代理类， unwrap CGLIB
							Class<?> userClass = ClassUtils.getUserClass(beanClass);
							if (userClass != beanClass) {
								try {
									Constructor<?> superCtor =
											userClass.getDeclaredConstructor(candidate.getParameterTypes());
									// 看看有没有 @Autowired 构造器
									ann = findAutowiredAnnotation(superCtor);
								} catch (NoSuchMethodException ex) {
									// Simply proceed, no equivalent superclass constructor found...
								}
							}
						}

						// 2. 根据是否有 @Autowired 执行不同逻辑
						// 理解两个概念：最佳候选人、普通候选人

						// 下面的逻辑拆接下来：
						// - 存在 @Autowired
						//		- 已经找到 “最佳候选人”，不应该出现两个最佳候选人。
						//				"最佳候选人" 与 "最佳候选人" 不能共存。
						//		- 发现 "最佳候选人"，但是 candidates 已经有候选人(最佳or普通)，抛异常。
						//				"最佳候选人" 与 "候选人" 不能共存。
						//		- 添加到候选人列表
						// - 不存在 @Autowired
						// 		- 无参构造器，记为 defaultConstructor
						if (ann != null) {
							// 有 @Autowired 注解，那就添加到 candidates 里面（这一步后面 🥑🥑🥑🥑🥑🥑🥑🥑）

							// 发现已经找到了一个 “最好的候选人”，那么不应该这么定义
							if (requiredConstructor != null) {
								throw new BeanCreationException(beanName,
										"Invalid autowire-marked constructor: " + candidate +
												". Found constructor with 'required' Autowired annotation already: " +
												requiredConstructor);
							}

							// 是否是 required，如果是 true，意味着这是 “最好的候选人”
							boolean required = determineRequiredStatus(ann);
							if (required) {
								// 最好的候选人，但是还存在其他候选人，这也不对
								if (!candidates.isEmpty()) {
									throw new BeanCreationException(beanName,
											"Invalid autowire-marked constructors: " + candidates +
													". Found constructor with 'required' Autowired annotation: " +
													candidate);
								}
								requiredConstructor = candidate;
							}

							// 添加到 candidates 🥑🥑🥑🥑🥑🥑🥑🥑
							candidates.add(candidate);
						} else if (candidate.getParameterCount() == 0) {
							// 如果没有 @Autowired，但是无参构造器，赋值到 defaultConstructor
							defaultConstructor = candidate;
						}
					}

					// 如果发现候选人了
					if (!candidates.isEmpty()) {
						// Add default constructor to list of optional constructors, as fallback.
						// 如果没找到 “最佳候选人”，看看有没有无参构造器
						// 这是怎么回事，全都是 required == false ？？？怎么可能会这么设置
						if (requiredConstructor == null) {
							if (defaultConstructor != null) {
								candidates.add(defaultConstructor);
							} else if (candidates.size() == 1 && logger.isInfoEnabled()) {
								logger.info("Inconsistent constructor declaration on bean with name '" + beanName +
										"': single autowire-marked constructor flagged as optional - " +
										"this constructor is effectively required since there is no " +
										"default constructor to fall back to: " + candidates.get(0));
							}
						}
						candidateConstructors = candidates.toArray(new Constructor<?>[0]);
					} else if (rawCandidates.length == 1 && rawCandidates[0].getParameterCount() > 0) {
						candidateConstructors = new Constructor<?>[]{rawCandidates[0]};
					} else if (nonSyntheticConstructors == 2 && primaryConstructor != null &&
							defaultConstructor != null && !primaryConstructor.equals(defaultConstructor)) {
						candidateConstructors = new Constructor<?>[]{primaryConstructor, defaultConstructor};
					} else if (nonSyntheticConstructors == 1 && primaryConstructor != null) {
						candidateConstructors = new Constructor<?>[]{primaryConstructor};
					} else {
						candidateConstructors = new Constructor<?>[0];
					}
					this.candidateConstructorsCache.put(beanClass, candidateConstructors);
				}
			}
		}
		return (candidateConstructors.length > 0 ? candidateConstructors : null);
	}

	/**
	 * 关键的声明周期方法，处理属性
	 */
	@Override
	public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
		// 找到注解元数据，其实已经找到了，这里只是走缓存
		InjectionMetadata metadata = findAutowiringMetadata(beanName, bean.getClass(), pvs);

		try {
			// 执行注入
			metadata.inject(bean, beanName, pvs);
		} catch (BeanCreationException ex) {
			throw ex;
		} catch (Throwable ex) {
			throw new BeanCreationException(beanName, "Injection of autowired dependencies failed", ex);
		}
		return pvs;
	}

	@Deprecated
	@Override
	public PropertyValues postProcessPropertyValues(
			PropertyValues pvs, PropertyDescriptor[] pds, Object bean, String beanName) {

		return postProcessProperties(pvs, bean, beanName);
	}

	/**
	 * 'Native' processing method for direct calls with an arbitrary target instance,
	 * resolving all of its fields and methods which are annotated with one of the
	 * configured 'autowired' annotation types.
	 *
	 * @param bean the target instance to process
	 * @throws BeanCreationException if autowiring failed
	 * @see #setAutowiredAnnotationTypes(Set)
	 */
	public void processInjection(Object bean) throws BeanCreationException {
		Class<?> clazz = bean.getClass();
		InjectionMetadata metadata = findAutowiringMetadata(clazz.getName(), clazz, null);
		try {
			metadata.inject(bean, null, null);
		} catch (BeanCreationException ex) {
			throw ex;
		} catch (Throwable ex) {
			throw new BeanCreationException(
					"Injection of autowired dependencies failed for class [" + clazz + "]", ex);
		}
	}

	/**
	 * 寻找 autowireing 元数据
	 */
	private InjectionMetadata findAutowiringMetadata(String beanName, Class<?> clazz, @Nullable PropertyValues pvs) {
		// Fall back to class name as cache key, for backwards compatibility with custom callers.
		// 构建 cacheKey
		// 什么缓存，通过这个缓存 key，找到元数据；这个 cacheKey 肯定来自于 bean 的信息
		String cacheKey = (StringUtils.hasLength(beanName) ? beanName : clazz.getName());

		// Quick check on the concurrent map first, with minimal locking.
		// 通过 cacheKey 快速找到 metadata
		InjectionMetadata metadata = this.injectionMetadataCache.get(cacheKey);

		// metadata == null || metadata.targetClass 变了
		if (InjectionMetadata.needsRefresh(metadata, clazz)) {

			// 给整个 injectionMetadataCache 都加锁
			// 这是什么意思，要进行写入的时候，就整个加锁？
			synchronized (this.injectionMetadataCache) {
				// DCL 写法罢了
				metadata = this.injectionMetadataCache.get(cacheKey);
				if (InjectionMetadata.needsRefresh(metadata, clazz)) {

					// 如果之前是存在 metadata，那么清空
					if (metadata != null) {
						metadata.clear(pvs);
					}

					// 构建元数据
					metadata = buildAutowiringMetadata(clazz);
					this.injectionMetadataCache.put(cacheKey, metadata);
				}
			}
		}
		return metadata;
	}

	/**
	 * 私有方法。用于通过 clazz 构建 autowiring 元数据
	 */
	private InjectionMetadata buildAutowiringMetadata(Class<?> clazz) {
		// 快速判断 clazz 是否有机会带有 autowire 注解
		if (!AnnotationUtils.isCandidateClass(clazz, this.autowiredAnnotationTypes)) {
			return InjectionMetadata.EMPTY;
		}

		final List<InjectionMetadata.InjectedElement> elements = new ArrayList<>();
		Class<?> targetClass = clazz; // targetClass 按照类层次遍历

		do {
			// 收集字段
			final List<InjectionMetadata.InjectedElement> fieldElements = new ArrayList<>();

			// 遍历每个字段
			ReflectionUtils.doWithLocalFields(targetClass, field -> {
				MergedAnnotation<?> ann = findAutowiredAnnotation(field);
				if (ann != null) {
					// 如果是静态则不处理
					if (Modifier.isStatic(field.getModifiers())) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation is not supported on static fields: " + field);
						}
						return;
					}
					// 确实这个字段是否是必须的
					boolean required = determineRequiredStatus(ann);
					// 收集与字段有关的自动注入元素
					fieldElements.add(new AutowiredFieldElement(field, required));
				}
			});

			// 收集方法
			final List<InjectionMetadata.InjectedElement> methodElements = new ArrayList<>();

			// 遍历每个方法
			ReflectionUtils.doWithLocalMethods(targetClass, method -> {
				Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(method);
				if (!BridgeMethodResolver.isVisibilityBridgeMethodPair(method, bridgedMethod)) {
					return;
				}
				// 寻找注解
				MergedAnnotation<?> ann = findAutowiredAnnotation(bridgedMethod);
				if (ann != null && method.equals(ClassUtils.getMostSpecificMethod(method, clazz))) {
					// 静态方法处理
					if (Modifier.isStatic(method.getModifiers())) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation is not supported on static methods: " + method);
						}
						return;
					}

					// 无参方法不处理
					if (method.getParameterCount() == 0) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation should only be used on methods with parameters: " +
									method);
						}
					}
					boolean required = determineRequiredStatus(ann);
					PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, clazz);
					methodElements.add(new AutowiredMethodElement(method, required, pd));
				}
			});

			elements.addAll(0, sortMethodElements(methodElements, targetClass));
			elements.addAll(0, fieldElements);
			targetClass = targetClass.getSuperclass();
		}
		while (targetClass != null && targetClass != Object.class);

		// 找到了一些需要注入的元素，传入给 InjectionMetadata 构造一个对象
		return InjectionMetadata.forElements(elements, clazz);
	}

	@Nullable
	private MergedAnnotation<?> findAutowiredAnnotation(AccessibleObject ao) {
		// 获得合并的注解
		MergedAnnotations annotations = MergedAnnotations.from(ao);

		// 遍历每个支持的注解，看看 ao 上面有没有这个注解，如果有则就用这个注解
		for (Class<? extends Annotation> type : this.autowiredAnnotationTypes) {
			MergedAnnotation<?> annotation = annotations.get(type);
			if (annotation.isPresent()) {
				return annotation;
			}
		}
		return null;
	}

	/**
	 * Determine if the annotated field or method requires its dependency.
	 * <p>A 'required' dependency means that autowiring should fail when no beans
	 * are found. Otherwise, the autowiring process will simply bypass the field
	 * or method when no beans are found.
	 *
	 * @param ann the Autowired annotation
	 * @return whether the annotation indicates that a dependency is required
	 */
	@SuppressWarnings("deprecation")
	protected boolean determineRequiredStatus(MergedAnnotation<?> ann) {
		return determineRequiredStatus(ann.<AnnotationAttributes>asMap(
				mergedAnnotation -> new AnnotationAttributes(mergedAnnotation.getType())));
	}

	/**
	 * Determine if the annotated field or method requires its dependency.
	 * <p>A 'required' dependency means that autowiring should fail when no beans
	 * are found. Otherwise, the autowiring process will simply bypass the field
	 * or method when no beans are found.
	 *
	 * @param ann the Autowired annotation
	 * @return whether the annotation indicates that a dependency is required
	 * @deprecated since 5.2, in favor of {@link #determineRequiredStatus(MergedAnnotation)}
	 */
	@Deprecated
	protected boolean determineRequiredStatus(AnnotationAttributes ann) {
		// 默认是必须的 --> 这是因为如果注解没有 required 属性，就认为是 true
		// 如果注解有 required 属性，那么就看属性到底是什么
		return (!ann.containsKey(this.requiredParameterName) ||
				this.requiredParameterValue == ann.getBoolean(this.requiredParameterName));
	}

	/**
	 * Obtain all beans of the given type as autowire candidates.
	 *
	 * @param type the type of the bean
	 * @return the target beans, or an empty Collection if no bean of this type is found
	 * @throws BeansException if bean retrieval failed
	 * @deprecated since 5.3.24 since it is unused in the meantime
	 */
	@Deprecated
	protected <T> Map<String, T> findAutowireCandidates(Class<T> type) throws BeansException {
		if (this.beanFactory == null) {
			throw new IllegalStateException("No BeanFactory configured - " +
					"override the getBeanOfType method or specify the 'beanFactory' property");
		}
		return BeanFactoryUtils.beansOfTypeIncludingAncestors(this.beanFactory, type);
	}

	/**
	 * Sort the method elements via ASM for deterministic declaration order if possible.
	 */
	private List<InjectionMetadata.InjectedElement> sortMethodElements(
			List<InjectionMetadata.InjectedElement> methodElements, Class<?> targetClass) {

		if (this.metadataReaderFactory != null && methodElements.size() > 1) {
			// Try reading the class file via ASM for deterministic declaration order...
			// Unfortunately, the JVM's standard reflection returns methods in arbitrary
			// order, even between different runs of the same application on the same JVM.
			try {
				AnnotationMetadata asm =
						this.metadataReaderFactory.getMetadataReader(targetClass.getName()).getAnnotationMetadata();
				Set<MethodMetadata> asmMethods = asm.getAnnotatedMethods(Autowired.class.getName());
				if (asmMethods.size() >= methodElements.size()) {
					List<InjectionMetadata.InjectedElement> candidateMethods = new ArrayList<>(methodElements);
					List<InjectionMetadata.InjectedElement> selectedMethods = new ArrayList<>(asmMethods.size());
					for (MethodMetadata asmMethod : asmMethods) {
						for (Iterator<InjectionMetadata.InjectedElement> it = candidateMethods.iterator(); it.hasNext(); ) {
							InjectionMetadata.InjectedElement element = it.next();
							if (element.getMember().getName().equals(asmMethod.getMethodName())) {
								selectedMethods.add(element);
								it.remove();
								break;
							}
						}
					}
					if (selectedMethods.size() == methodElements.size()) {
						// All reflection-detected methods found in ASM method set -> proceed
						return selectedMethods;
					}
				}
			} catch (IOException ex) {
				logger.debug("Failed to read class file via ASM for determining @Autowired method order", ex);
				// No worries, let's continue with the reflection metadata we started with...
			}
		}
		return methodElements;
	}

	/**
	 * Register the specified bean as dependent on the autowired beans.
	 */
	private void registerDependentBeans(@Nullable String beanName, Set<String> autowiredBeanNames) {
		if (beanName != null) {
			for (String autowiredBeanName : autowiredBeanNames) {
				if (this.beanFactory != null && this.beanFactory.containsBean(autowiredBeanName)) {
					this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Autowiring by type from bean name '" + beanName +
							"' to bean named '" + autowiredBeanName + "'");
				}
			}
		}
	}

	/**
	 * Resolve the specified cached method argument or field value.
	 */
	@Nullable
	private Object resolveCachedArgument(@Nullable String beanName, @Nullable Object cachedArgument) {
		// cachedArgument 如果存在，类型一定是 ShortcutDependencyDescriptor
		if (cachedArgument instanceof DependencyDescriptor) {
			DependencyDescriptor descriptor = (DependencyDescriptor) cachedArgument;
			Assert.state(this.beanFactory != null, "No BeanFactory available");
			return this.beanFactory.resolveDependency(descriptor, beanName, null, null);
		} else {
			return cachedArgument;
		}
	}

	/**
	 * Class representing injection information about an annotated field.
	 */
	private class AutowiredFieldElement extends InjectionMetadata.InjectedElement {

		private final boolean required;

		private volatile boolean cached;

		@Nullable
		private volatile Object cachedFieldValue;

		public AutowiredFieldElement(Field field, boolean required) {
			super(field, null);
			this.required = required;
		}

		@Override
		protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
			// 注入的是一个字段
			Field field = (Field) this.member;
			Object value;

			// 尝试从缓存获取，使用 ShortcutDependencyDescriptor 快速解析
			if (this.cached) {
				try {
					value = resolveCachedArgument(beanName, this.cachedFieldValue);
				} catch (BeansException ex) {
					// Unexpected target bean mismatch for cached argument -> re-resolve
					this.cached = false;
					logger.debug("Failed to resolve cached argument", ex);
					value = resolveFieldValue(field, bean, beanName);
				}
			}
			// 解析字段
			else {
				value = resolveFieldValue(field, bean, beanName);
			}
			if (value != null) {
				ReflectionUtils.makeAccessible(field);
				field.set(bean, value);
			}
		}

		@Nullable
		private Object resolveFieldValue(Field field, Object bean, @Nullable String beanName) {
			// 构造了一个依赖描述器 (传入 Field 我只能说似乎是按照类型寻找)
			DependencyDescriptor desc = new DependencyDescriptor(field, this.required);
			// 设置包含这个依赖项的 bean.getClass()
			desc.setContainingClass(bean.getClass());

			// 这个容器是干嘛的呢，就是传给下面的依赖解析方法，这个方法会把解析到的依赖放进去，让你知道解析到了多少个
			Set<String> autowiredBeanNames = new LinkedHashSet<>(2);
			Assert.state(beanFactory != null, "No BeanFactory available");

			// 拿到一个类型转换器
			TypeConverter typeConverter = beanFactory.getTypeConverter();
			Object value;
			try {
				// 解析依赖
				value = beanFactory.resolveDependency(desc, beanName, autowiredBeanNames, typeConverter);
			} catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(field), ex);
			}

			// 以 AutowiredFieldElement 作为锁
			synchronized (this) {
				// 如果还是没有缓存
				if (!this.cached) {
					if (value != null || this.required) {
						Object cachedFieldValue = desc;
						// 注册依赖关系
						registerDependentBeans(beanName, autowiredBeanNames);

						// 如果 value（也就是解析出来的值）不为空，并且只匹配唯一的一个 beanName，
						// 那么创建了 ShortcutDependencyDescriptor 缓存下来，对于 prototype 可能很有用
						if (value != null && autowiredBeanNames.size() == 1) {
							String autowiredBeanName = autowiredBeanNames.iterator().next();
							if (beanFactory.containsBean(autowiredBeanName) &&
									beanFactory.isTypeMatch(autowiredBeanName, field.getType())) {
								cachedFieldValue = new ShortcutDependencyDescriptor(desc, autowiredBeanName);
							}
						}
						this.cachedFieldValue = cachedFieldValue;
						this.cached = true;
					} else {
						this.cachedFieldValue = null;
						// cached flag remains false
					}
				}
			}
			return value;
		}

	}

	/**
	 * Class representing injection information about an annotated method.
	 */
	private class AutowiredMethodElement extends InjectionMetadata.InjectedElement {

		private final boolean required;

		private volatile boolean cached;

		@Nullable
		private volatile Object[] cachedMethodArguments;

		public AutowiredMethodElement(Method method, boolean required, @Nullable PropertyDescriptor pd) {
			super(method, pd);
			this.required = required;
		}

		@Override
		protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
			// 是否应该跳过，没仔细看，应该是如果 PropertyValue 存在属性了，就不用调用方法赋值
			if (checkPropertySkipping(pvs)) {
				return;
			}
			Method method = (Method) this.member;
			Object[] arguments;
			if (this.cached) {
				try {
					arguments = resolveCachedArguments(beanName, this.cachedMethodArguments);
				} catch (BeansException ex) {
					// Unexpected target bean mismatch for cached argument -> re-resolve
					this.cached = false;
					logger.debug("Failed to resolve cached argument", ex);
					arguments = resolveMethodArguments(method, bean, beanName);
				}
			} else {
				// 解析方法参数
				arguments = resolveMethodArguments(method, bean, beanName);
			}
			if (arguments != null) {
				try {
					ReflectionUtils.makeAccessible(method);
					method.invoke(bean, arguments);
				} catch (InvocationTargetException ex) {
					throw ex.getTargetException();
				}
			}
		}

		@Nullable
		private Object[] resolveCachedArguments(@Nullable String beanName, @Nullable Object[] cachedMethodArguments) {
			if (cachedMethodArguments == null) {
				return null;
			}
			Object[] arguments = new Object[cachedMethodArguments.length];
			for (int i = 0; i < arguments.length; i++) {
				arguments[i] = resolveCachedArgument(beanName, cachedMethodArguments[i]);
			}
			return arguments;
		}

		@Nullable
		private Object[] resolveMethodArguments(Method method, Object bean, @Nullable String beanName) {
			// 方法多少个参数
			int argumentCount = method.getParameterCount();
			Object[] arguments = new Object[argumentCount];

			// 根据方法参数构建依赖描述器数字
			DependencyDescriptor[] descriptors = new DependencyDescriptor[argumentCount];
			Set<String> autowiredBeanNames = new LinkedHashSet<>(argumentCount * 2);
			Assert.state(beanFactory != null, "No BeanFactory available");

			// 类型转换器
			TypeConverter typeConverter = beanFactory.getTypeConverter();

			// 对于每个方法参数
			for (int i = 0; i < arguments.length; i++) {
				// 构造依赖描述器
				MethodParameter methodParam = new MethodParameter(method, i);
				DependencyDescriptor currDesc = new DependencyDescriptor(methodParam, this.required);
				currDesc.setContainingClass(bean.getClass());
				descriptors[i] = currDesc;
				try {
					Object arg = beanFactory.resolveDependency(currDesc, beanName, autowiredBeanNames, typeConverter);
					if (arg == null && !this.required) {
						arguments = null;
						break;
					}
					arguments[i] = arg;
				} catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(methodParam), ex);
				}
			}

			synchronized (this) {
				if (!this.cached) {
					if (arguments != null) {
						DependencyDescriptor[] cachedMethodArguments = Arrays.copyOf(descriptors, argumentCount);
						registerDependentBeans(beanName, autowiredBeanNames);
						if (autowiredBeanNames.size() == argumentCount) {
							Iterator<String> it = autowiredBeanNames.iterator();
							Class<?>[] paramTypes = method.getParameterTypes();
							for (int i = 0; i < paramTypes.length; i++) {
								String autowiredBeanName = it.next();
								if (arguments[i] != null && beanFactory.containsBean(autowiredBeanName) &&
										beanFactory.isTypeMatch(autowiredBeanName, paramTypes[i])) {
									cachedMethodArguments[i] = new ShortcutDependencyDescriptor(
											descriptors[i], autowiredBeanName);
								}
							}
						}
						this.cachedMethodArguments = cachedMethodArguments;
						this.cached = true;
					} else {
						this.cachedMethodArguments = null;
						// cached flag remains false
					}
				}
			}
			return arguments;
		}

	}

	/**
	 * DependencyDescriptor variant with a pre-resolved target bean name.
	 * <p>
	 * DependencyDescriptor 变体，其中包含了已经解析好的 target bean name
	 * <p>
	 * 为什么要缓存这个？因为按类型解析需要寻找
	 */
	@SuppressWarnings("serial")
	private static class ShortcutDependencyDescriptor extends DependencyDescriptor {

		/**
		 * beanName
		 */
		private final String shortcut;

		public ShortcutDependencyDescriptor(DependencyDescriptor original, String shortcut) {
			super(original);
			this.shortcut = shortcut;
		}

		@Override
		public Object resolveShortcut(BeanFactory beanFactory) {
			return beanFactory.getBean(this.shortcut, getDependencyType());
		}

	}

}
