/*
 * Copyright 2002-2024 the original author or authors.
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

package org.springframework.core;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Defines the algorithm for searching for metadata-associated methods exhaustively
 * including interfaces and parent classes while also dealing with parameterized methods
 * as well as common scenarios encountered with interface and class-based proxies.
 *
 * <p>Typically, but not necessarily, used for finding annotated handler methods.
 *
 * @author Juergen Hoeller
 * @author Rossen Stoyanchev
 * @author Sam Brannen
 * @since 4.2.3
 */
public final class MethodIntrospector {

	private MethodIntrospector() {
	}


	/**
	 * Select methods on the given target type based on the lookup of associated metadata.
	 * <p>Callers define methods of interest through the {@link MetadataLookup} parameter,
	 * allowing to collect the associated metadata into the result map.
	 *
	 * @param targetType     the target type to search methods on
	 * @param metadataLookup a {@link MetadataLookup} callback to inspect methods of interest,
	 *                       returning non-null metadata to be associated with a given method if there is a match,
	 *                       or {@code null} for no match
	 * @return the selected methods associated with their metadata (in the order of retrieval),
	 * or an empty map in case of no match
	 */
	public static <T> Map<Method, T> selectMethods(Class<?> targetType, final MetadataLookup<T> metadataLookup) {
		// 准备用于结果收集的容器 Map
		final Map<Method, T> methodMap = new LinkedHashMap<>();

		// Class 集合，可能要遍历很多 Class
		Set<Class<?>> handlerTypes = new LinkedHashSet<>();
		Class<?> specificHandlerType = null;

		if (!Proxy.isProxyClass(targetType)) {
			specificHandlerType = ClassUtils.getUserClass(targetType);
			handlerTypes.add(specificHandlerType);
		}
		// 获得所有的接口丢进去
		handlerTypes.addAll(ClassUtils.getAllInterfacesForClassAsSet(targetType));

		for (Class<?> currentHandlerType : handlerTypes) {
			final Class<?> targetClass = (specificHandlerType != null ? specificHandlerType : currentHandlerType);

			ReflectionUtils.doWithMethods(currentHandlerType, method -> {
				// 获得方法
				Method specificMethod = ClassUtils.getMostSpecificMethod(method, targetClass);

				// 回调函数审查一下，如果不是 null，接下来就可以 put 进去
				T result = metadataLookup.inspect(specificMethod);
				if (result != null) {

					// 一些桥接方法
					Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(specificMethod);
					if (bridgedMethod == specificMethod || bridgedMethod == method ||
							bridgedMethod.equals(specificMethod) || bridgedMethod.equals(method) ||
							metadataLookup.inspect(bridgedMethod) == null) {
						methodMap.put(specificMethod, result);
					}
				}
			}, ReflectionUtils.USER_DECLARED_METHODS);
		}

		return methodMap;
	}

	/**
	 * Select methods on the given target type based on a filter.
	 * <p>Callers define methods of interest through the {@code MethodFilter} parameter.
	 *
	 * @param targetType   the target type to search methods on
	 * @param methodFilter a {@code MethodFilter} to help
	 *                     recognize handler methods of interest
	 * @return the selected methods, or an empty set in case of no match
	 */
	public static Set<Method> selectMethods(Class<?> targetType, final ReflectionUtils.MethodFilter methodFilter) {
		return selectMethods(targetType,
				(MetadataLookup<Boolean>) method -> (methodFilter.matches(method) ? Boolean.TRUE : null)).keySet();
	}

	/**
	 * Select an invocable method on the target type: either the given method itself
	 * if actually exposed on the target type, or otherwise a corresponding method
	 * on one of the target type's interfaces or on the target type itself.
	 * <p>Matches on user-declared interfaces will be preferred since they are likely
	 * to contain relevant metadata that corresponds to the method on the target class.
	 *
	 * <p>在目标类型上选择一个可调用的方法。
	 * 如果该方法实际暴露在目标类型上，那么这个方法就是方法自身。
	 * 如果是目标类型某个接口的方法，则返回那个方法。
	 * 或者，是目标类型上的那个方法（这里其实有问题，因为可能无法调用）
	 *
	 * @param method     the method to check
	 * @param targetType the target type to search methods on
	 *                   (typically an interface-based JDK proxy)
	 *                   用于搜索方法的目标类型（通常就是基于接口的 JDK 代理，你乱用就报错）
	 * @return a corresponding invocable method on the target type
	 * @throws IllegalStateException if the given method is not invocable on the given
	 *                               target type (typically due to a proxy mismatch)
	 */
	public static Method selectInvocableMethod(Method method, Class<?> targetType) {
		// 这个情况就是说 method 可能是父类声明的，targetType 是子类，那么这是一个可以调用的方法
		if (method.getDeclaringClass().isAssignableFrom(targetType)) {
			return method;
		}

		try {
			// 也有可能 targetType 根本没有继承我上面所说的父类
			// 但是它实现了其他接口，这些接口里面有一个方法的的名字，以及参数跟他一模一样
			String methodName = method.getName();
			Class<?>[] parameterTypes = method.getParameterTypes();
			for (Class<?> ifc : targetType.getInterfaces()) {
				try {
					return ifc.getMethod(methodName, parameterTypes);
				} catch (NoSuchMethodException ex) {
					// Alright, not on this interface then...
				}
			}

			// 还是不行，那么我直接看是不是有一个方法名字完全一样，参数也一样的方法
			// A final desperate attempt on the proxy class itself...
			return targetType.getMethod(methodName, parameterTypes);
		} catch (NoSuchMethodException ex) {
			throw new IllegalStateException(String.format(
					"Need to invoke method '%s' declared on target class '%s', " +
							"but not found in any interface(s) of the exposed proxy type. " +
							"Either pull the method up to an interface or switch to CGLIB " +
							"proxies by enforcing proxy-target-class mode in your configuration.",
					method.getName(), method.getDeclaringClass().getSimpleName()));
		}
	}


	/**
	 * A callback interface for metadata lookup on a given method.
	 *
	 * @param <T> the type of metadata returned
	 */
	@FunctionalInterface
	public interface MetadataLookup<T> {

		/**
		 * Perform a lookup on the given method and return associated metadata, if any.
		 *
		 * @param method the method to inspect
		 * @return non-null metadata to be associated with a method if there is a match,
		 * or {@code null} for no match
		 */
		@Nullable
		T inspect(Method method);
	}

}
