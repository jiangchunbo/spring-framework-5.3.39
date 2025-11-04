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

package org.springframework.core.annotation;

import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.lang.Nullable;
import org.springframework.util.ConcurrentReferenceHashMap;

/**
 * Provides {@link AnnotationTypeMapping} information for a single source
 * annotation type. Performs a recursive breadth first crawl of all
 * meta-annotations to ultimately provide a quick way to map the attributes of
 * a root {@link Annotation}.
 *
 * <p>Supports convention based merging of meta-annotations as well as implicit
 * and explicit {@link AliasFor @AliasFor} aliases. Also provides information
 * about mirrored attributes.
 *
 * <p>This class is designed to be cached so that meta-annotations only need to
 * be searched once, regardless of how many times they are actually used.
 *
 * @author Phillip Webb
 * @author Sam Brannen
 * @see AnnotationTypeMapping
 * @since 5.2
 */
final class AnnotationTypeMappings {

	private static final IntrospectionFailureLogger failureLogger = IntrospectionFailureLogger.DEBUG;

	/**
	 * Key 是注解过滤器，其实目前来看就 3 种类型
	 * <p>
	 * 注意 {@link AnnotationFilter#PLAIN} 这个对象，它是 package 的实现类，用于过滤 java.lang 和 org.spring.lang
	 *
	 * @see AnnotationFilter#PLAIN
	 */
	private static final Map<AnnotationFilter, Cache> standardRepeatablesCache = new ConcurrentReferenceHashMap<>();

	private static final Map<AnnotationFilter, Cache> noRepeatablesCache = new ConcurrentReferenceHashMap<>();

	private final RepeatableContainers repeatableContainers;

	private final AnnotationFilter filter;

	private final List<AnnotationTypeMapping> mappings;

	private AnnotationTypeMappings(RepeatableContainers repeatableContainers,
								   AnnotationFilter filter, Class<? extends Annotation> annotationType,
								   Set<Class<? extends Annotation>> visitedAnnotationTypes) {

		this.repeatableContainers = repeatableContainers;
		this.filter = filter;
		this.mappings = new ArrayList<>();

		// 这里面对 mapping 进行了元素添加，否则，下面不会 mapping.forEach
		addAllMappings(annotationType, visitedAnnotationTypes);

		// 遍历每个 mapping，执行 afterAllMappingsSet
		this.mappings.forEach(AnnotationTypeMapping::afterAllMappingsSet);
	}

	private void addAllMappings(Class<? extends Annotation> annotationType,
								Set<Class<? extends Annotation>> visitedAnnotationTypes) {

		// 可能有点像递归，先加入一个 root 之类的根 annotation，再由其发散解析
		// 最终 queue 并没有返回，也就是一个临时容器而已
		Deque<AnnotationTypeMapping> queue = new ArrayDeque<>();

		// 添加 root
		addIfPossible(queue, null, annotationType, null, visitedAnnotationTypes);

		// 第一次，queue 肯定不是 empty，所以出队
		while (!queue.isEmpty()) {
			// 先把注解自己入队
			AnnotationTypeMapping mapping = queue.removeFirst();
			this.mappings.add(mapping);

			// 再入队元注解
			addMetaAnnotationsToQueue(queue, mapping);

			// 如果还发现了元注解，继续入队，下一次循环
		}
	}

	/**
	 * 方法名字叫添加元注解到队列，所以它不会把本身添加到队列(早添加好了)，而是获取元注解
	 */
	private void addMetaAnnotationsToQueue(Deque<AnnotationTypeMapping> queue, AnnotationTypeMapping source) {
		// 获取元注解 (defensive == false，因为内部调用，不需要担心)
		Annotation[] metaAnnotations = AnnotationsScanner.getDeclaredAnnotations(source.getAnnotationType(), false);

		for (Annotation metaAnnotation : metaAnnotations) {
			if (!isMappable(source, metaAnnotation)) {
				continue;
			}
			// 是否能够发现更多注解
			Annotation[] repeatedAnnotations = this.repeatableContainers.findRepeatedAnnotations(metaAnnotation);
			if (repeatedAnnotations != null) {
				for (Annotation repeatedAnnotation : repeatedAnnotations) {
					if (!isMappable(source, repeatedAnnotation)) {
						continue;
					}
					addIfPossible(queue, source, repeatedAnnotation);
				}
			}
			// 一般也是走这里吧，无法发现更多注解，直接入队
			else {
				addIfPossible(queue, source, metaAnnotation);
			}
		}
	}

	private void addIfPossible(Deque<AnnotationTypeMapping> queue, AnnotationTypeMapping source, Annotation ann) {
		addIfPossible(queue, source, ann.annotationType(), ann, new HashSet<>());
	}

	/**
	 *
	 * @param queue 队列，其实是上面 new 出来的
	 */
	private void addIfPossible(Deque<AnnotationTypeMapping> queue, @Nullable AnnotationTypeMapping source,
							   Class<? extends Annotation> annotationType, @Nullable Annotation ann,
							   Set<Class<? extends Annotation>> visitedAnnotationTypes) {

		try {
			queue.addLast(new AnnotationTypeMapping(source, annotationType, ann, visitedAnnotationTypes));
		} catch (Exception ex) {
			AnnotationUtils.rethrowAnnotationConfigurationException(ex);
			if (failureLogger.isEnabled()) {
				failureLogger.log("Failed to introspect meta-annotation " + annotationType.getName(),
						(source != null ? source.getAnnotationType() : null), ex);
			}
		}
	}

	private boolean isMappable(AnnotationTypeMapping source, @Nullable Annotation metaAnnotation) {
		// filter 不能被 filter 匹配
		return (metaAnnotation != null && !this.filter.matches(metaAnnotation) &&
				// 注解类型一定不能是 java lang spring lang
				!AnnotationFilter.PLAIN.matches(source.getAnnotationType()) &&
				// 也不能重复映射处理
				!isAlreadyMapped(source, metaAnnotation));
	}

	private boolean isAlreadyMapped(AnnotationTypeMapping source, Annotation metaAnnotation) {
		Class<? extends Annotation> annotationType = metaAnnotation.annotationType();
		AnnotationTypeMapping mapping = source;
		while (mapping != null) {
			if (mapping.getAnnotationType() == annotationType) {
				return true;
			}
			mapping = mapping.getSource();
		}
		return false;
	}

	/**
	 * Get the total number of contained mappings.
	 *
	 * @return the total number of mappings
	 */
	int size() {
		return this.mappings.size();
	}

	/**
	 * Get an individual mapping from this instance.
	 * <p>Index {@code 0} will always return the root mapping; higher indexes
	 * will return meta-annotation mappings.
	 *
	 * @param index the index to return
	 * @return the {@link AnnotationTypeMapping}
	 * @throws IndexOutOfBoundsException if the index is out of range
	 *                                   ({@code index < 0 || index >= size()})
	 */
	AnnotationTypeMapping get(int index) {
		return this.mappings.get(index);
	}

	/**
	 * Create {@link AnnotationTypeMappings} for the specified annotation type.
	 *
	 * @param annotationType the source annotation type
	 * @return type mappings for the annotation type
	 */
	static AnnotationTypeMappings forAnnotationType(Class<? extends Annotation> annotationType) {
		return forAnnotationType(annotationType, new HashSet<>());
	}

	/**
	 * Create {@link AnnotationTypeMappings} for the specified annotation type.
	 *
	 * @param annotationType         the source annotation type
	 * @param visitedAnnotationTypes the set of annotations that we have already
	 *                               visited; used to avoid infinite recursion for recursive annotations which
	 *                               some JVM languages support (such as Kotlin)
	 * @return type mappings for the annotation type
	 */
	static AnnotationTypeMappings forAnnotationType(Class<? extends Annotation> annotationType,
													Set<Class<? extends Annotation>> visitedAnnotationTypes) {

		// RepeatableContainers.standardRepeatables() -> 表示以 JDK 标准 @Repeatable 注解解析

		return forAnnotationType(annotationType, RepeatableContainers.standardRepeatables(),
				AnnotationFilter.PLAIN, visitedAnnotationTypes);
	}

	/**
	 * Create {@link AnnotationTypeMappings} for the specified annotation type.
	 *
	 * @param annotationType       the source annotation type
	 * @param repeatableContainers the repeatable containers that may be used by
	 *                             the meta-annotations
	 * @param annotationFilter     the annotation filter used to limit which
	 *                             annotations are considered
	 * @return type mappings for the annotation type
	 */
	static AnnotationTypeMappings forAnnotationType(Class<? extends Annotation> annotationType,
													RepeatableContainers repeatableContainers, AnnotationFilter annotationFilter) {

		return forAnnotationType(annotationType, repeatableContainers, annotationFilter, new HashSet<>());
	}

	/**
	 * Create {@link AnnotationTypeMappings} for the specified annotation type.
	 *
	 * @param annotationType         the source annotation type
	 * @param repeatableContainers   the repeatable containers that may be used by
	 *                               the meta-annotations
	 * @param annotationFilter       the annotation filter used to limit which
	 *                               annotations are considered
	 * @param visitedAnnotationTypes the set of annotations that we have already
	 *                               visited; used to avoid infinite recursion for recursive annotations which
	 *                               some JVM languages support (such as Kotlin)
	 * @return type mappings for the annotation type
	 */
	private static AnnotationTypeMappings forAnnotationType(Class<? extends Annotation> annotationType,
															RepeatableContainers repeatableContainers, AnnotationFilter annotationFilter,
															Set<Class<? extends Annotation>> visitedAnnotationTypes) {

		// 使用 JDK 标准的可重复注解支持
		if (repeatableContainers == RepeatableContainers.standardRepeatables()) {
			return standardRepeatablesCache.computeIfAbsent(annotationFilter,
					// repeatableContainers 一定是 standardRepeatables
					key -> new Cache(repeatableContainers, key)).get(annotationType, visitedAnnotationTypes);
		}

		// RepeatableContainers.none(): 完全忽略可重复注解
		if (repeatableContainers == RepeatableContainers.none()) {
			return noRepeatablesCache.computeIfAbsent(annotationFilter,
					key -> new Cache(repeatableContainers, key)).get(annotationType, visitedAnnotationTypes);
		}

		// 直接
		return new AnnotationTypeMappings(repeatableContainers, annotationFilter, annotationType, visitedAnnotationTypes);
	}

	static void clearCache() {
		standardRepeatablesCache.clear();
		noRepeatablesCache.clear();
	}

	/**
	 * Cache created per {@link AnnotationFilter}.
	 */
	private static class Cache {

		/**
		 * 可能是 {@link StandardRepeatableContainers} 也可能是 {@link }
		 */
		private final RepeatableContainers repeatableContainers;

		private final AnnotationFilter filter;

		private final Map<Class<? extends Annotation>, AnnotationTypeMappings> mappings;

		/**
		 * Create a cache instance with the specified filter.
		 *
		 * @param filter the annotation filter
		 */
		Cache(RepeatableContainers repeatableContainers, AnnotationFilter filter) {
			this.repeatableContainers = repeatableContainers;
			this.filter = filter;
			this.mappings = new ConcurrentReferenceHashMap<>();
		}

		/**
		 * Get or create {@link AnnotationTypeMappings} for the specified annotation type.
		 *
		 * @param annotationType         the annotation type
		 * @param visitedAnnotationTypes the set of annotations that we have already
		 *                               visited; used to avoid infinite recursion for recursive annotations which
		 *                               some JVM languages support (such as Kotlin)
		 * @return a new or existing {@link AnnotationTypeMappings} instance
		 */
		AnnotationTypeMappings get(Class<? extends Annotation> annotationType,
								   Set<Class<? extends Annotation>> visitedAnnotationTypes) {

			return this.mappings.computeIfAbsent(annotationType, key -> createMappings(key, visitedAnnotationTypes));
		}

		/**
		 * 最终的根据注解 Class 创建 AnnotationTypeMappings 的方法
		 */
		private AnnotationTypeMappings createMappings(Class<? extends Annotation> annotationType,
													  Set<Class<? extends Annotation>> visitedAnnotationTypes) {

			return new AnnotationTypeMappings(this.repeatableContainers, this.filter, annotationType, visitedAnnotationTypes);
		}

	}

}
