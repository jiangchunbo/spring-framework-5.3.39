/*
 * Copyright 2002-2020 the original author or authors.
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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

/**
 * Internal class for managing injection metadata.
 * Not intended for direct use in applications.
 *
 * <p>Used by {@link AutowiredAnnotationBeanPostProcessor},
 * {@link org.springframework.context.annotation.CommonAnnotationBeanPostProcessor} and
 * {@link org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor}.
 *
 * @author Juergen Hoeller
 * @since 2.5
 */
public class InjectionMetadata {

	/**
	 * An empty {@code InjectionMetadata} instance with no-op callbacks.
	 *
	 * @since 5.2
	 */
	public static final InjectionMetadata EMPTY = new InjectionMetadata(Object.class, Collections.emptyList()) {
		@Override
		protected boolean needsRefresh(Class<?> clazz) {
			return false;
		}

		@Override
		public void checkConfigMembers(RootBeanDefinition beanDefinition) {
		}

		@Override
		public void inject(Object target, @Nullable String beanName, @Nullable PropertyValues pvs) {
		}

		@Override
		public void clear(@Nullable PropertyValues pvs) {
		}
	};

	private final Class<?> targetClass;

	/**
	 * 这是一个 final 方法，说明一旦这个对象构造出来，里面一定有注入元素（方法、字段）
	 * <p>
	 * 这个对象要不就是 {@link Collections#emptyList()} 要不就是 List，反正我看到 AutowiredAnnotationBeanPostProcessor 就是用的 List
	 * <p>
	 * 对于 AutowiredAnnotation 来说这里就只有两种可能的元素 AutowiredFieldElement 和 AutowiredMethodElement
	 */
	private final Collection<InjectedElement> injectedElements;

	@Nullable
	private volatile Set<InjectedElement> checkedElements;

	/**
	 * Create a new {@code InjectionMetadata instance}.
	 * <p>Preferably use {@link #forElements} for reusing the {@link #EMPTY}
	 * instance in case of no elements.
	 *
	 * @param targetClass the target class
	 * @param elements    the associated elements to inject
	 * @see #forElements
	 */
	public InjectionMetadata(Class<?> targetClass, Collection<InjectedElement> elements) {
		this.targetClass = targetClass;
		this.injectedElements = elements;
	}

	/**
	 * Determine whether this metadata instance needs to be refreshed.
	 *
	 * @param clazz the current target class
	 * @return {@code true} indicating a refresh, {@code false} otherwise
	 * @since 5.2.4
	 */
	protected boolean needsRefresh(Class<?> clazz) {
		return this.targetClass != clazz;
	}

	/**
	 * 进行什么检查。其实就是向 Bean Definition 填充属性
	 */
	public void checkConfigMembers(RootBeanDefinition beanDefinition) {
		// 为什么用 injectedElements 长度作为 Set 初始大小，因为最多也就这么多元素
		// InjectionMetadata.injectedElements 是一个初始传入的不变量

		// checkedElements 是一层过滤，也意味着是 ExternallyManagedConfigMember
		Set<InjectedElement> checkedElements = new LinkedHashSet<>(this.injectedElements.size());

		// 遍历每个 element
		for (InjectedElement element : this.injectedElements) {
			// 获得 Field 或者 Method
			Member member = element.getMember();

			// 注册 ExternallyManagedConfigMember 并添加到 checkedElements
			if (!beanDefinition.isExternallyManagedConfigMember(member)) {
				beanDefinition.registerExternallyManagedConfigMember(member);
				checkedElements.add(element);
			}
		}
		this.checkedElements = checkedElements;
	}

	public void inject(Object target, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
		Collection<InjectedElement> checkedElements = this.checkedElements;

		// 这里是一个决策，到底用 checked 还是 injected，反正优先用 checked (因为 checked 是 injected 的过滤)
		Collection<InjectedElement> elementsToIterate =
				(checkedElements != null ? checkedElements : this.injectedElements);

		// 判断是否非空，这样可以避免创建一个 Iterator 迭代器对象
		if (!elementsToIterate.isEmpty()) {
			for (InjectedElement element : elementsToIterate) {
				element.inject(target, beanName, pvs);
			}
		}
	}

	/**
	 * Clear property skipping for the contained elements.
	 *
	 * @since 3.2.13
	 */
	public void clear(@Nullable PropertyValues pvs) {
		// injectedElements 是初始的时候传入的，不变量
		// checkedElements 是经过检查过滤出来的

		Collection<InjectedElement> checkedElements = this.checkedElements;
		Collection<InjectedElement> elementsToIterate =
				(checkedElements != null ? checkedElements : this.injectedElements);
		if (!elementsToIterate.isEmpty()) {
			for (InjectedElement element : elementsToIterate) {
				element.clearPropertySkipping(pvs);
			}
		}
	}

	/**
	 * Return an {@code InjectionMetadata} instance, possibly for empty elements.
	 *
	 * @param elements the elements to inject (possibly empty)
	 * @param clazz    the target class
	 * @return a new {@link #InjectionMetadata(Class, Collection)} instance
	 * @since 5.2
	 */
	public static InjectionMetadata forElements(Collection<InjectedElement> elements, Class<?> clazz) {
		// 这个方法的意图在于，Spring 并不想将一个长度是 0 的 Collection 存储进去，转而使用常量 emptyList
		// 这样估计可以减少一些内存消耗吧
		// 或者可以强制让这个集合无法进行 add 或者 get 等
		return (elements.isEmpty() ?
				new InjectionMetadata(clazz, Collections.emptyList()) :
				new InjectionMetadata(clazz, elements));
	}

	/**
	 * Check whether the given injection metadata needs to be refreshed.
	 * <p>
	 * 给定 InjectionMetadata 以及 Class 检查是否需要刷新
	 *
	 * @param metadata the existing metadata instance
	 * @param clazz    the current target class
	 * @return {@code true} indicating a refresh, {@code false} otherwise
	 * @see #needsRefresh(Class)
	 */
	public static boolean needsRefresh(@Nullable InjectionMetadata metadata, Class<?> clazz) {
		// 条件1: metadata 如果根本不存在，那肯定是要刷新，或者叫初始化
		// 条件2: 如果 targetClass 已经变了，也需要刷新 (还没想通)
		return (metadata == null || metadata.needsRefresh(clazz));
	}

	/**
	 * A single injected element.
	 */
	public abstract static class InjectedElement {

		protected final Member member;

		protected final boolean isField;

		@Nullable
		protected final PropertyDescriptor pd;

		@Nullable
		protected volatile Boolean skip;

		protected InjectedElement(Member member, @Nullable PropertyDescriptor pd) {
			this.member = member;
			this.isField = (member instanceof Field);
			this.pd = pd;
		}

		public final Member getMember() {
			return this.member;
		}

		protected final Class<?> getResourceType() {
			if (this.isField) {
				return ((Field) this.member).getType();
			} else if (this.pd != null) {
				return this.pd.getPropertyType();
			} else {
				return ((Method) this.member).getParameterTypes()[0];
			}
		}

		protected final void checkResourceType(Class<?> resourceType) {
			if (this.isField) {
				Class<?> fieldType = ((Field) this.member).getType();
				if (!(resourceType.isAssignableFrom(fieldType) || fieldType.isAssignableFrom(resourceType))) {
					throw new IllegalStateException("Specified field type [" + fieldType +
							"] is incompatible with resource type [" + resourceType.getName() + "]");
				}
			} else {
				Class<?> paramType =
						(this.pd != null ? this.pd.getPropertyType() : ((Method) this.member).getParameterTypes()[0]);
				if (!(resourceType.isAssignableFrom(paramType) || paramType.isAssignableFrom(resourceType))) {
					throw new IllegalStateException("Specified parameter type [" + paramType +
							"] is incompatible with resource type [" + resourceType.getName() + "]");
				}
			}
		}

		/**
		 * Either this or {@link #getResourceToInject} needs to be overridden.
		 */
		protected void inject(Object target, @Nullable String requestingBeanName, @Nullable PropertyValues pvs)
				throws Throwable {

			if (this.isField) {
				Field field = (Field) this.member;
				ReflectionUtils.makeAccessible(field);
				field.set(target, getResourceToInject(target, requestingBeanName));
			} else {
				if (checkPropertySkipping(pvs)) {
					return;
				}
				try {
					Method method = (Method) this.member;
					ReflectionUtils.makeAccessible(method);
					method.invoke(target, getResourceToInject(target, requestingBeanName));
				} catch (InvocationTargetException ex) {
					throw ex.getTargetException();
				}
			}
		}

		/**
		 * Check whether this injector's property needs to be skipped due to
		 * an explicit property value having been specified. Also marks the
		 * affected property as processed for other processors to ignore it.
		 */
		protected boolean checkPropertySkipping(@Nullable PropertyValues pvs) {
			Boolean skip = this.skip;
			if (skip != null) {
				return skip;
			}
			if (pvs == null) {
				this.skip = false;
				return false;
			}
			synchronized (pvs) {
				skip = this.skip;
				if (skip != null) {
					return skip;
				}
				if (this.pd != null) {
					if (pvs.contains(this.pd.getName())) {
						// Explicit value provided as part of the bean definition.
						this.skip = true;
						return true;
					} else if (pvs instanceof MutablePropertyValues) {
						((MutablePropertyValues) pvs).registerProcessedProperty(this.pd.getName());
					}
				}
				this.skip = false;
				return false;
			}
		}

		/**
		 * Clear property skipping for this element.
		 *
		 * @since 3.2.13
		 */
		protected void clearPropertySkipping(@Nullable PropertyValues pvs) {
			if (pvs == null) {
				return;
			}
			synchronized (pvs) {
				if (Boolean.FALSE.equals(this.skip) && this.pd != null && pvs instanceof MutablePropertyValues) {
					((MutablePropertyValues) pvs).clearProcessedProperty(this.pd.getName());
				}
			}
		}

		/**
		 * Either this or {@link #inject} needs to be overridden.
		 */
		@Nullable
		protected Object getResourceToInject(Object target, @Nullable String requestingBeanName) {
			return null;
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof InjectedElement)) {
				return false;
			}
			InjectedElement otherElement = (InjectedElement) other;
			return this.member.equals(otherElement.member);
		}

		@Override
		public int hashCode() {
			return this.member.getClass().hashCode() * 29 + this.member.getName().hashCode();
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + " for " + this.member;
		}

	}

}
