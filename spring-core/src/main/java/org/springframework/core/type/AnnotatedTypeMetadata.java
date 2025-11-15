/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.core.type;

import java.lang.annotation.Annotation;
import java.util.Map;

import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotation.Adapt;
import org.springframework.core.annotation.MergedAnnotationCollectors;
import org.springframework.core.annotation.MergedAnnotationPredicates;
import org.springframework.core.annotation.MergedAnnotationSelectors;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.lang.Nullable;
import org.springframework.util.MultiValueMap;

/**
 * Defines access to the annotations of a specific type ({@link AnnotationMetadata class}
 * or {@link MethodMetadata method}), in a form that does not necessarily require the
 * class-loading.
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Mark Pollack
 * @author Chris Beams
 * @author Phillip Webb
 * @author Sam Brannen
 * @see AnnotationMetadata
 * @see MethodMetadata
 * @since 4.0
 */
public interface AnnotatedTypeMetadata {

	/**
	 * Return annotation details based on the direct annotations of the
	 * underlying element.
	 * <p>
	 * 返回底层元素的直接注解合并后的注解
	 *
	 * @return merged annotations based on the direct annotations
	 * @since 5.2
	 */
	MergedAnnotations getAnnotations();

	/**
	 * Determine whether the underlying element has an annotation or meta-annotation
	 * of the given type defined.
	 * <p>If this method returns {@code true}, then
	 * {@link #getAnnotationAttributes} will return a non-null Map.
	 *
	 * @param annotationName the fully qualified class name of the annotation
	 *                       type to look for
	 * @return whether a matching annotation is defined
	 */
	default boolean isAnnotated(String annotationName) {
		return getAnnotations().isPresent(annotationName);
	}

	/**
	 * Retrieve the attributes of the annotation of the given type, if any (i.e. if
	 * defined on the underlying element, as direct annotation or meta-annotation),
	 * also taking attribute overrides on composed annotations into account.
	 * <p>
	 * 检索给定类型的注解的属性（如存在），也就是说，如果在底层元素上定义为直接注解或元注解时，
	 * 也会考虑组合注解中的属性覆盖。
	 *
	 * @param annotationName the fully qualified class name of the annotation
	 *                       type to look for
	 * @return a Map of attributes, with the attribute name as key (e.g. "value")
	 * and the defined attribute value as Map value. This return value will be
	 * {@code null} if no matching annotation is defined.
	 */
	@Nullable
	default Map<String, Object> getAnnotationAttributes(String annotationName) {
		// 返回注解属性时，如果
		return getAnnotationAttributes(annotationName, false);
	}

	/**
	 * Retrieve the attributes of the annotation of the given type, if any (i.e. if
	 * defined on the underlying element, as direct annotation or meta-annotation),
	 * also taking attribute overrides on composed annotations into account.
	 *
	 * @param annotationName      the fully qualified class name of the annotation
	 *                            type to look for
	 * @param classValuesAsString whether to convert class references to String
	 *                            class names for exposure as values in the returned Map, instead of Class
	 *                            references which might potentially have to be loaded first
	 *                            <p>是否要将类引用转换为字符串形式的类名，以在返回的 Map 中作为值暴露，
	 *                            而不是可能需要加载的 Class 引用
	 * @return a Map of attributes, with the attribute name as key (e.g. "value")
	 * and the defined attribute value as Map value. This return value will be
	 * {@code null} if no matching annotation is defined.
	 */
	@Nullable
	default Map<String, Object> getAnnotationAttributes(String annotationName,
														boolean classValuesAsString) {

		MergedAnnotation<Annotation> annotation = getAnnotations().get(annotationName,
				null, MergedAnnotationSelectors.firstDirectlyDeclared());
		if (!annotation.isPresent()) {
			return null;
		}
		return annotation.asAnnotationAttributes(Adapt.values(classValuesAsString, true));
	}

	/**
	 * Retrieve all attributes of all annotations of the given type, if any (i.e. if
	 * defined on the underlying element, as direct annotation or meta-annotation).
	 * Note that this variant does <i>not</i> take attribute overrides into account.
	 *
	 * @param annotationName the fully qualified class name of the annotation
	 *                       type to look for
	 * @return a MultiMap of attributes, with the attribute name as key (e.g. "value")
	 * and a list of the defined attribute values as Map value. This return value will
	 * be {@code null} if no matching annotation is defined.
	 * @see #getAllAnnotationAttributes(String, boolean)
	 */
	@Nullable
	default MultiValueMap<String, Object> getAllAnnotationAttributes(String annotationName) {
		return getAllAnnotationAttributes(annotationName, false);
	}

	/**
	 * Retrieve all attributes of all annotations of the given type, if any (i.e. if
	 * defined on the underlying element, as direct annotation or meta-annotation).
	 * Note that this variant does <i>not</i> take attribute overrides into account.
	 * <p>
	 * 记住，本类(本实例)代表的是一个可以被注解元素，因此调用该方法实际上是获取该注解元素上所有注解的属性
	 *
	 * @param annotationName      the fully qualified class name of the annotation
	 *                            type to look for
	 * @param classValuesAsString whether to convert class references to String
	 * @return a MultiMap of attributes, with the attribute name as key (e.g. "value")
	 * and a list of the defined attribute values as Map value. This return value will
	 * be {@code null} if no matching annotation is defined.
	 * @see #getAllAnnotationAttributes(String)
	 */
	@Nullable
	default MultiValueMap<String, Object> getAllAnnotationAttributes(
			String annotationName, boolean classValuesAsString) {

		// 适配策略 [第二个参数是 true，表示注解总是转换为 Map，否则，一般可能是注解对象 Proxy]
		Adapt[] adaptations = Adapt.values(classValuesAsString, true);

		return getAnnotations().stream(annotationName)
				// 下面这句话挺难理解
				// 首先去看 MergedAnnotationPredicates.unique 该静态方法返回一个 Predicate 对象
				// 即，每次迭代都会将 MergedAnnotation 对象传递给这个 Predicate 进行判断
				// 接着，断言的时候又将 MergedAnnotation 对象传递给 MergedAnnotation::getMetaTypes 得到一个 List<Class<? extends Annotation>>
				// 并尝试将 List<Class<? extends Annotation>> 添加到一个 Set 中，若添加成功(首次)，则继续处理，
				// --> 否则可能处理过了，跳过，这样估计可以确保去重
				.filter(MergedAnnotationPredicates.unique(MergedAnnotation::getMetaTypes))
				// 将 MergedAnnotation 转换为另一个 MergedAnnotation
				.map(MergedAnnotation::withNonMergedAttributes)
				.collect(MergedAnnotationCollectors.toMultiValueMap(map ->
						map.isEmpty() ? null : map, adaptations));
	}

}
