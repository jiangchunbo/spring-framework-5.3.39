/*
 * Copyright 2002-2016 the original author or authors.
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

package org.aopalliance.intercept;

import java.lang.reflect.Method;

import javax.annotation.Nonnull;

/**
 * Description of an invocation to a method, given to an interceptor
 * upon method-call.
 * <p>
 * 这个类代表了对方法的调用描述，在方法调用发生时会被提供给拦截器。
 *
 * <p>A method invocation is a joinpoint and can be intercepted by a
 * method interceptor.
 * <p>
 * 通常 MethodInvocation 这种名字的 AOP 编程范式，也会收集该方法包含的所有拦截器。
 * 具体见 {@link org.springframework.aop.framework.ReflectiveMethodInvocation}
 *
 * @author Rod Johnson
 * @see MethodInterceptor
 */
public interface MethodInvocation extends Invocation {

	/**
	 * Get the method being called.
	 * <p>
	 * 获取被调用的方法。
	 *
	 * <p>This method is a friendly implementation of the
	 * {@link Joinpoint#getStaticPart()} method (same result).
	 * <p>
	 * 这个方法是 {@link Joinpoint#getStaticPart()} 方法的 [友好实现]，结果是相等的，语义上更清晰。
	 *
	 * @return the method being called
	 */
	@Nonnull
	Method getMethod();

}
