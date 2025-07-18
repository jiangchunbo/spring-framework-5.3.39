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

package org.springframework.aop.framework;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.aopalliance.intercept.Interceptor;
import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.aop.Advisor;
import org.springframework.aop.IntroductionAdvisor;
import org.springframework.aop.IntroductionAwareMethodMatcher;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.adapter.AdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry;
import org.springframework.lang.Nullable;

/**
 * A simple but definitive way of working out an advice chain for a Method,
 * given an {@link Advised} object. Always rebuilds each advice chain;
 * caching can be provided by subclasses.
 *
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Adrian Colyer
 * @since 2.0.3
 */
@SuppressWarnings("serial")
public class DefaultAdvisorChainFactory implements AdvisorChainFactory, Serializable {

	@Override
	public List<Object> getInterceptorsAndDynamicInterceptionAdvice(
			Advised config, Method method, @Nullable Class<?> targetClass) {

		// This is somewhat tricky... We have to process introductions first,
		// but we need to preserve order in the ultimate list.

		// 拿到一个什么注册表
		AdvisorAdapterRegistry registry = GlobalAdvisorAdapterRegistry.getInstance();

		// Advised 这是一个配置，拿到里面配置的所有 Advisor
		// 这个就是我们通过 ProxyFactory 添加进来的所有的 Advisor
		Advisor[] advisors = config.getAdvisors();

		// 构造一个 Advisors 大小的数组。 这个数组用于收集所有的 MethodInterceptor
		// 并不一定所有的 advisor 都可以用，但是我们预留足够空间 advisors.length
		List<Object> interceptorList = new ArrayList<>(advisors.length);
		Class<?> actualClass = (targetClass != null ? targetClass : method.getDeclaringClass());

		// 初始是 null，属于惰性计算
		Boolean hasIntroductions = null;

		for (Advisor advisor : advisors) {
			// 如果是 PointcutAdvisor 类型
			if (advisor instanceof PointcutAdvisor) {
				// Add it conditionally.
				PointcutAdvisor pointcutAdvisor = (PointcutAdvisor) advisor;

				// 一些判断，如果不满足这几个判断，甚至还无法添加到 interceptors 里
				if (config.isPreFiltered() || pointcutAdvisor.getPointcut().getClassFilter().matches(actualClass)) {
					// 方法匹配器
					MethodMatcher mm = pointcutAdvisor.getPointcut().getMethodMatcher();

					// 计算 match
					boolean match;
					if (mm instanceof IntroductionAwareMethodMatcher) {
						// 如果还没有计算过，那么就算一下 true/false，判断这个类能不能使用 “引介”
						// 只计算一次，以后不用再计算了
						if (hasIntroductions == null) {
							hasIntroductions = hasMatchingIntroductions(advisors, actualClass);
						}

						// 使用 IntroductionAwareMethodMatcher 方法匹配器判断
						// 只是多了一个形参 hasIntroductions
						match = ((IntroductionAwareMethodMatcher) mm).matches(method, actualClass, hasIntroductions);
					} else {
						// 直接匹配
						match = mm.matches(method, actualClass);
					}

					// 如果匹配上，才能添加到拦截器里面
					if (match) {
						MethodInterceptor[] interceptors = registry.getInterceptors(advisor);
						if (mm.isRuntime()) {
							// Creating a new object instance in the getInterceptors() method
							// isn't a problem as we normally cache created chains.
							for (MethodInterceptor interceptor : interceptors) {
								interceptorList.add(new InterceptorAndDynamicMethodMatcher(interceptor, mm));
							}
						} else {
							interceptorList.addAll(Arrays.asList(interceptors));
						}
					}
				}
			} else if (advisor instanceof IntroductionAdvisor) {
				IntroductionAdvisor ia = (IntroductionAdvisor) advisor;
				if (config.isPreFiltered() || ia.getClassFilter().matches(actualClass)) {
					Interceptor[] interceptors = registry.getInterceptors(advisor);
					interceptorList.addAll(Arrays.asList(interceptors));
				}
			} else {
				Interceptor[] interceptors = registry.getInterceptors(advisor);
				interceptorList.addAll(Arrays.asList(interceptors));
			}
		}

		return interceptorList;
	}

	/**
	 * Determine whether the Advisors contain matching introductions.
	 * <p>
	 * 判断是否这一堆 Advisor 里面有没有“任何一个”当前这个类能使用的 IntroductionAdvisor
	 */
	private static boolean hasMatchingIntroductions(Advisor[] advisors, Class<?> actualClass) {
		// 遍历所有的 advisors
		for (Advisor advisor : advisors) {
			// 如果是 引介
			if (advisor instanceof IntroductionAdvisor) {
				IntroductionAdvisor ia = (IntroductionAdvisor) advisor;
				// 判断是否 ClassFilter 匹配
				if (ia.getClassFilter().matches(actualClass)) {
					return true;
				}
			}
		}
		return false;
	}

}
