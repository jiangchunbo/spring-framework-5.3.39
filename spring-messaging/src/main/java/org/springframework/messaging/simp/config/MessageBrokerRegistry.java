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

package org.springframework.messaging.simp.config;

import java.util.Arrays;
import java.util.Collection;

import org.springframework.context.event.SmartApplicationListener;
import org.springframework.lang.Nullable;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompBrokerRelayMessageHandler;
import org.springframework.util.Assert;
import org.springframework.util.PathMatcher;

/**
 * A registry for configuring message broker options.
 *
 * @author Rossen Stoyanchev
 * @author Sebastien Deleuze
 * @since 4.0
 */
public class MessageBrokerRegistry {

	private final SubscribableChannel clientInboundChannel;

	private final MessageChannel clientOutboundChannel;

	@Nullable
	private SimpleBrokerRegistration simpleBrokerRegistration;

	@Nullable
	private StompBrokerRelayRegistration brokerRelayRegistration;

	private final ChannelRegistration brokerChannelRegistration = new ChannelRegistration();

	@Nullable
	private String[] applicationDestinationPrefixes;

	@Nullable
	private String userDestinationPrefix;

	@Nullable
	private Integer userRegistryOrder;

	@Nullable
	private PathMatcher pathMatcher;

	@Nullable
	private Integer cacheLimit;

	private boolean preservePublishOrder;


	public MessageBrokerRegistry(SubscribableChannel clientInboundChannel, MessageChannel clientOutboundChannel) {
		Assert.notNull(clientInboundChannel, "Inbound channel must not be null");
		Assert.notNull(clientOutboundChannel, "Outbound channel must not be null");
		this.clientInboundChannel = clientInboundChannel;
		this.clientOutboundChannel = clientOutboundChannel;
	}


	/**
	 * Enable a simple message broker and configure one or more prefixes to filter
	 * destinations targeting the broker (e.g. destinations prefixed with "/topic").
	 * <p>
	 * 启用一个简单的消息代理，并且配置 1 个或多个前缀用于过滤发往该代理的目标地址（例如，以 /topic 为前缀的地址）
	 */
	public SimpleBrokerRegistration enableSimpleBroker(String... destinationPrefixes) {
		// 创建了一个简单代理的注册信息
		this.simpleBrokerRegistration = new SimpleBrokerRegistration(
				this.clientInboundChannel, this.clientOutboundChannel, destinationPrefixes);

		// 返回这种注册信息。后面继续链式调用。
		return this.simpleBrokerRegistration;
	}

	/**
	 * Enable a STOMP broker relay and configure the destination prefixes supported by the
	 * message broker. Check the STOMP documentation of the message broker for supported
	 * destinations.
	 */
	public StompBrokerRelayRegistration enableStompBrokerRelay(String... destinationPrefixes) {
		this.brokerRelayRegistration = new StompBrokerRelayRegistration(
				this.clientInboundChannel, this.clientOutboundChannel, destinationPrefixes);
		return this.brokerRelayRegistration;
	}

	/**
	 * Customize the channel used to send messages from the application to the message
	 * broker. By default, messages from the application to the message broker are sent
	 * synchronously, which means application code sending a message will find out
	 * if the message cannot be sent through an exception. However, this can be changed
	 * if the broker channel is configured here with task executor properties.
	 */
	public ChannelRegistration configureBrokerChannel() {
		return this.brokerChannelRegistration;
	}

	protected ChannelRegistration getBrokerChannelRegistration() {
		return this.brokerChannelRegistration;
	}

	@Nullable
	protected String getUserDestinationBroadcast() {
		return (this.brokerRelayRegistration != null ?
				this.brokerRelayRegistration.getUserDestinationBroadcast() : null);
	}

	@Nullable
	protected String getUserRegistryBroadcast() {
		return (this.brokerRelayRegistration != null ?
				this.brokerRelayRegistration.getUserRegistryBroadcast() : null);
	}

	/**
	 * Configure one or more prefixes to filter destinations targeting application
	 * annotated methods. For example destinations prefixed with "/app" may be
	 * processed by annotated methods while other destinations may target the
	 * message broker (e.g. "/topic", "/queue").
	 * <p>When messages are processed, the matching prefix is removed from the destination
	 * in order to form the lookup path. This means annotations should not contain the
	 * destination prefix.
	 * <p>Prefixes that do not have a trailing slash will have one automatically appended.
	 */
	public MessageBrokerRegistry setApplicationDestinationPrefixes(String... prefixes) {
		this.applicationDestinationPrefixes = prefixes;
		return this;
	}

	@Nullable
	protected Collection<String> getApplicationDestinationPrefixes() {
		return (this.applicationDestinationPrefixes != null ?
				Arrays.asList(this.applicationDestinationPrefixes) : null);
	}

	/**
	 * Configure the prefix used to identify user destinations. User destinations
	 * provide the ability for a user to subscribe to queue names unique to their
	 * session as well as for others to send messages to those unique,
	 * user-specific queues.
	 * <p>For example when a user attempts to subscribe to "/user/queue/position-updates",
	 * the destination may be translated to "/queue/position-updatesi9oqdfzo" yielding a
	 * unique queue name that does not collide with any other user attempting to do the same.
	 * Subsequently when messages are sent to "/user/{username}/queue/position-updates",
	 * the destination is translated to "/queue/position-updatesi9oqdfzo".
	 * <p>The default prefix used to identify such destinations is "/user/".
	 */
	public MessageBrokerRegistry setUserDestinationPrefix(String destinationPrefix) {
		this.userDestinationPrefix = destinationPrefix;
		return this;
	}

	@Nullable
	protected String getUserDestinationPrefix() {
		return this.userDestinationPrefix;
	}

	/**
	 * Set the order for the
	 * {@link org.springframework.messaging.simp.user.SimpUserRegistry
	 * SimpUserRegistry} to use as a {@link SmartApplicationListener}.
	 *
	 * @param order the order value
	 * @since 5.0.8
	 */
	public void setUserRegistryOrder(int order) {
		this.userRegistryOrder = order;
	}

	@Nullable
	protected Integer getUserRegistryOrder() {
		return this.userRegistryOrder;
	}

	/**
	 * Configure the PathMatcher to use to match the destinations of incoming
	 * messages to {@code @MessageMapping} and {@code @SubscribeMapping} methods.
	 * <p>By default {@link org.springframework.util.AntPathMatcher} is configured.
	 * However applications may provide an {@code AntPathMatcher} instance
	 * customized to use "." (commonly used in messaging) instead of "/" as path
	 * separator or provide a completely different PathMatcher implementation.
	 * <p>Note that the configured PathMatcher is only used for matching the
	 * portion of the destination after the configured prefix. For example given
	 * application destination prefix "/app" and destination "/app/price.stock.**",
	 * the message might be mapped to a controller with "price" and "stock.**"
	 * as its type and method-level mappings respectively.
	 * <p>When the simple broker is enabled, the PathMatcher configured here is
	 * also used to match message destinations when brokering messages.
	 *
	 * @see org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry#setPathMatcher
	 * @since 4.1
	 */
	public MessageBrokerRegistry setPathMatcher(PathMatcher pathMatcher) {
		this.pathMatcher = pathMatcher;
		return this;
	}

	@Nullable
	protected PathMatcher getPathMatcher() {
		return this.pathMatcher;
	}

	/**
	 * Configure the cache limit to apply for registrations with the broker.
	 * <p>This is currently only applied for the destination cache in the
	 * subscription registry. The default cache limit there is 1024.
	 *
	 * @see org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry#setCacheLimit
	 * @since 4.3.2
	 */
	public MessageBrokerRegistry setCacheLimit(int cacheLimit) {
		this.cacheLimit = cacheLimit;
		return this;
	}

	/**
	 * Whether the client must receive messages in the order of publication.
	 * <p>By default messages sent to the {@code "clientOutboundChannel"} may
	 * not be processed in the same order because the channel is backed by a
	 * ThreadPoolExecutor that in turn does not guarantee processing in order.
	 * <p>When this flag is set to {@code true} messages within the same session
	 * will be sent to the {@code "clientOutboundChannel"} one at a time in
	 * order to preserve the order of publication. Enable this only if needed
	 * since there is some performance overhead to keep messages in order.
	 *
	 * @since 5.1
	 */
	public MessageBrokerRegistry setPreservePublishOrder(boolean preservePublishOrder) {
		this.preservePublishOrder = preservePublishOrder;
		return this;
	}

	@Nullable
	protected SimpleBrokerMessageHandler getSimpleBroker(SubscribableChannel brokerChannel) {
		if (this.simpleBrokerRegistration == null && this.brokerRelayRegistration == null) {
			enableSimpleBroker();
		}
		if (this.simpleBrokerRegistration != null) {
			SimpleBrokerMessageHandler handler = this.simpleBrokerRegistration.getMessageHandler(brokerChannel);
			handler.setPathMatcher(this.pathMatcher);
			handler.setCacheLimit(this.cacheLimit);
			handler.setPreservePublishOrder(this.preservePublishOrder);
			return handler;
		}
		return null;
	}

	@Nullable
	protected StompBrokerRelayMessageHandler getStompBrokerRelay(SubscribableChannel brokerChannel) {
		if (this.brokerRelayRegistration != null) {
			StompBrokerRelayMessageHandler relay = this.brokerRelayRegistration.getMessageHandler(brokerChannel);
			relay.setPreservePublishOrder(this.preservePublishOrder);
			return relay;
		}
		return null;
	}

}
