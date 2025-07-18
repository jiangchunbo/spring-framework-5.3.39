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

package org.springframework.aop.framework;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.SmartClassLoader;
import org.springframework.lang.Nullable;

/**
 * Base class for {@link BeanPostProcessor} implementations that apply a
 * Spring AOP {@link Advisor} to specific beans.
 * <p>
 * Spring AOP 很多都是通过后置处理器进行增强的，这个就是一个通用的基类，如果你需要使用 Spring AOP Advisor 增强，那么可以使用这个
 *
 * @author Juergen Hoeller
 * @since 3.2
 */
@SuppressWarnings("serial")
public abstract class AbstractAdvisingBeanPostProcessor extends ProxyProcessorSupport implements BeanPostProcessor {

	@Nullable
	protected Advisor advisor;

	protected boolean beforeExistingAdvisors = false;

	private final Map<Class<?>, Boolean> eligibleBeans = new ConcurrentHashMap<>(256);


	/**
	 * Set whether this post-processor's advisor is supposed to apply before
	 * existing advisors when encountering a pre-advised object.
	 * <p>Default is "false", applying the advisor after existing advisors, i.e.
	 * as close as possible to the target method. Switch this to "true" in order
	 * for this post-processor's advisor to wrap existing advisors as well.
	 * <p>Note: Check the concrete post-processor's javadoc whether it possibly
	 * changes this flag by default, depending on the nature of its advisor.
	 */
	public void setBeforeExistingAdvisors(boolean beforeExistingAdvisors) {
		// 是否应该在已有的 advisor 之前处理
		this.beforeExistingAdvisors = beforeExistingAdvisors;
	}


	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) {
		return bean;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		// advisor 都没设置？！跳过
		// 或者，这个 bean 是一个 Aop 的基础设施 bean
		if (this.advisor == null || bean instanceof AopInfrastructureBean) {
			// Ignore AOP infrastructure such as scoped proxies.
			return bean;
		}

		// 判断是不是一个代理对象
		if (bean instanceof Advised) {
			Advised advised = (Advised) bean;

			// 如果代理对象没有冻结，并且，有资格处理
			if (!advised.isFrozen() && isEligible(AopUtils.getTargetClass(bean))) {
				// Add our local Advisor to the existing proxy's Advisor chain...
				// 将我们当前的 advisor 添加到现有的 proxy advisor 链前面
				if (this.beforeExistingAdvisors) {
					advised.addAdvisor(0, this.advisor);
				} else {
					advised.addAdvisor(this.advisor);
				}
				return bean;
			}
		}

		// 是否有资格切
		if (isEligible(bean, beanName)) {
			ProxyFactory proxyFactory = prepareProxyFactory(bean, beanName);
			if (!proxyFactory.isProxyTargetClass()) {
				evaluateProxyInterfaces(bean.getClass(), proxyFactory);
			}
			proxyFactory.addAdvisor(this.advisor);
			customizeProxyFactory(proxyFactory);

			// Use original ClassLoader if bean class not locally loaded in overriding class loader
			ClassLoader classLoader = getProxyClassLoader();
			if (classLoader instanceof SmartClassLoader && classLoader != bean.getClass().getClassLoader()) {
				classLoader = ((SmartClassLoader) classLoader).getOriginalClassLoader();
			}
			return proxyFactory.getProxy(classLoader);
		}

		// No proxy needed.
		return bean;
	}

	/**
	 * Check whether the given bean is eligible for advising with this
	 * post-processor's {@link Advisor}.
	 * <p>Delegates to {@link #isEligible(Class)} for target class checking.
	 * Can be overridden e.g. to specifically exclude certain beans by name.
	 * <p>Note: Only called for regular bean instances but not for existing
	 * proxy instances which implement {@link Advised} and allow for adding
	 * the local {@link Advisor} to the existing proxy's {@link Advisor} chain.
	 * For the latter, {@link #isEligible(Class)} is being called directly,
	 * with the actual target class behind the existing proxy (as determined
	 * by {@link AopUtils#getTargetClass(Object)}).
	 *
	 * @param bean     the bean instance
	 * @param beanName the name of the bean
	 * @see #isEligible(Class)
	 */
	protected boolean isEligible(Object bean, String beanName) {
		return isEligible(bean.getClass());
	}

	/**
	 * Check whether the given class is eligible for advising with this
	 * post-processor's {@link Advisor}.
	 * <p>Implements caching of {@code canApply} results per bean target class.
	 *
	 * @param targetClass the class to check against
	 * @see AopUtils#canApply(Advisor, Class)
	 */
	protected boolean isEligible(Class<?> targetClass) {
		Boolean eligible = this.eligibleBeans.get(targetClass);
		if (eligible != null) {
			return eligible;
		}
		if (this.advisor == null) {
			return false;
		}

		// 返回值是一个 true 或者 false
		eligible = AopUtils.canApply(this.advisor, targetClass);
		this.eligibleBeans.put(targetClass, eligible);
		return eligible;
	}

	/**
	 * Prepare a {@link ProxyFactory} for the given bean.
	 * <p>Subclasses may customize the handling of the target instance and in
	 * particular the exposure of the target class. The default introspection
	 * of interfaces for non-target-class proxies and the configured advisor
	 * will be applied afterwards; {@link #customizeProxyFactory} allows for
	 * late customizations of those parts right before proxy creation.
	 *
	 * @param bean     the bean instance to create a proxy for
	 * @param beanName the corresponding bean name
	 * @return the ProxyFactory, initialized with this processor's
	 * {@link ProxyConfig} settings and the specified bean
	 * @see #customizeProxyFactory
	 * @since 4.2.3
	 */
	protected ProxyFactory prepareProxyFactory(Object bean, String beanName) {
		ProxyFactory proxyFactory = new ProxyFactory();
		// 本身这个 bean post processor 也是一个 proxy config
		proxyFactory.copyFrom(this);
		proxyFactory.setTarget(bean);
		return proxyFactory;
	}

	/**
	 * Subclasses may choose to implement this: for example,
	 * to change the interfaces exposed.
	 * <p>The default implementation is empty.
	 *
	 * @param proxyFactory the ProxyFactory that is already configured with
	 *                     target, advisor and interfaces and will be used to create the proxy
	 *                     immediately after this method returns
	 * @see #prepareProxyFactory
	 * @since 4.2.3
	 */
	protected void customizeProxyFactory(ProxyFactory proxyFactory) {
	}

}
