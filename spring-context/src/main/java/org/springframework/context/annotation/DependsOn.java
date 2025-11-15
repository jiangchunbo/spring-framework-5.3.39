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

package org.springframework.context.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Beans on which the current bean depends. Any beans specified are guaranteed to be
 * created by the container before this bean. Used infrequently in cases where a bean
 * does not explicitly depend on another through properties or constructor arguments,
 * but rather depends on the side effects of another bean's initialization.
 * <p>
 * 当前 bean 依赖的 bean。
 * 任何被指定的 bean 都确保在该 bean 之前由容器创建(我依赖你，你需要先创建)。
 * 使用场景较少，通常时因为某个 bean 的初始化需要另一个 bean 的副作用，而不是属性或构造参数显式依赖。
 *
 * <p>A depends-on declaration can specify both an initialization-time dependency and,
 * in the case of singleton beans only, a corresponding destruction-time dependency.
 * Dependent beans that define a depends-on relationship with a given bean are destroyed
 * first, prior to the given bean itself being destroyed. Thus, a depends-on declaration
 * can also control shutdown order.
 * <p>
 * depends-on 声明可以同时指定初始化时的依赖，以及在单例 bean 的情况下，对应的销毁时依赖。
 * 定义了 depends-on 关系的相关 bean 会在被依赖的 bean 销毁之前先被销毁。
 * 因此，depends-on 声明也可以控制关闭顺序。
 *
 * <p>May be used on any class directly or indirectly annotated with
 * {@link org.springframework.stereotype.Component} or on methods annotated
 * with {@link Bean}.
 * <p>
 * 可以用于任何直接或间接使用 @Component 注解的类，或标注有 @Bean 的方法。
 *
 * <p>Using {@link DependsOn} at the class level has no effect unless component-scanning
 * is being used. If a {@link DependsOn}-annotated class is declared via XML,
 * {@link DependsOn} annotation metadata is ignored, and
 * {@code <bean depends-on="..."/>} is respected instead.
 * <p>
 * 在类级别使用 @DependsOn 只有在进行组件扫描时才会生效。
 * 如果通过 XML 声明了带有 @DependsOn 注解的类，则会忽略 @DependsOn 注解元数据，而应遵循 depends-on 属性
 *
 * @author Juergen Hoeller
 * @since 3.0
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DependsOn {

	String[] value() default {};

}
