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

package org.springframework.context.event;

import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.ResolvableType;

/**
 * Extended variant of the standard {@link ApplicationListener} interface,
 * exposing further metadata such as the supported event and source type.
 * <p>
 * 这是标准 ApplicationListener 接口的扩展版本， 能够额外暴露诸如支持的事件类型、支持的事件源类型等元数据。
 *
 * <p>As of Spring Framework 4.2, this interface supersedes the Class-based
 * {@link SmartApplicationListener} with full handling of generic event types.
 * As of 5.3.5, it formally extends {@link SmartApplicationListener}, adapting
 * {@link #supportsEventType(Class)} to {@link #supportsEventType(ResolvableType)}
 * with a default method.
 * <p>
 * 自 Spring Framework 4.2 起， 该接口取代了基于 Class 判断的 SmartApplicationListener， 提供了对泛型事件类型的完整处理能力。
 * <p>
 * 自 5.3.5 起， 该接口正式 继承 SmartApplicationListener， 并通过默认方法将 supportsEventType(Class) 适配为 supportsEventType(ResolvableType)。
 *
 * @author Stephane Nicoll
 * @author Juergen Hoeller
 * @see SmartApplicationListener
 * @see GenericApplicationListenerAdapter
 * @since 4.2
 */
public interface GenericApplicationListener extends SmartApplicationListener {

	// SmartApplicationListener 的增强版，允许获得 ResolvableType

	/**
	 * Overrides {@link SmartApplicationListener#supportsEventType(Class)} with
	 * delegation to {@link #supportsEventType(ResolvableType)}.
	 */
	@Override
	default boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
		return supportsEventType(ResolvableType.forClass(eventType));
	}

	/**
	 * Determine whether this listener actually supports the given event type.
	 *
	 * @param eventType the event type (never {@code null})
	 */
	boolean supportsEventType(ResolvableType eventType);

}
