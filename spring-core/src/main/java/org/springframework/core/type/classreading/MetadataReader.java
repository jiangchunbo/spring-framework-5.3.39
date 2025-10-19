/*
 * Copyright 2002-2009 the original author or authors.
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

package org.springframework.core.type.classreading;

import org.springframework.core.io.Resource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.ClassMetadata;

/**
 * Simple facade for accessing class metadata,
 * as read by an ASM {@link org.springframework.asm.ClassReader}.
 * <p>
 * 用于访问类元数据的简单门面(门面设计模式)
 * <p>
 * 这些元数据由  ASM {@link org.springframework.asm.ClassReader} 读取
 *
 * @author Juergen Hoeller
 * @since 2.5
 */
public interface MetadataReader {

	/**
	 * Return the resource reference for the class file.
	 * <p>
	 * 返回关于这个 class 文件的 Resource
	 */
	Resource getResource();

	/**
	 * Read basic class metadata for the underlying class.
	 * <p>
	 * 用于读取底层 class 元数据。底层返回的都是同一个对象。
	 */
	ClassMetadata getClassMetadata();

	/**
	 * Read full annotation metadata for the underlying class,
	 * including metadata for annotated methods.
	 * <p>
	 * 读取底层 class 的完整注解元数据，包括被注解的方法。底层返回的都是同一个对象。
	 */
	AnnotationMetadata getAnnotationMetadata();

}
