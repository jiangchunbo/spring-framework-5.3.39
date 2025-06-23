/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.web.socket.server.standard;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerContainer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.tomcat.websocket.server.WsServerContainer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.adapter.standard.StandardToWebSocketExtensionAdapter;
import org.springframework.web.socket.adapter.standard.StandardWebSocketHandlerAdapter;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import org.springframework.web.socket.adapter.standard.WebSocketToStandardExtensionAdapter;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.RequestUpgradeStrategy;

/**
 * A base class for {@link RequestUpgradeStrategy} implementations that build
 * on the standard WebSocket API for Java (JSR-356).
 *
 * @author Rossen Stoyanchev
 * @since 4.0
 */
public abstract class AbstractStandardUpgradeStrategy implements RequestUpgradeStrategy {

	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	private volatile List<WebSocketExtension> extensions;


	/**
	 * @see WsServerContainer Tomcat 的 WS 容器
	 */
	protected ServerContainer getContainer(HttpServletRequest request) {
		ServletContext servletContext = request.getServletContext();
		String attrName = "javax.websocket.server.ServerContainer";
		ServerContainer container = (ServerContainer) servletContext.getAttribute(attrName);
		Assert.notNull(container, "No 'javax.websocket.server.ServerContainer' ServletContext attribute. " +
				"Are you running in a Servlet container that supports JSR-356?");
		return container;
	}

	protected final HttpServletRequest getHttpServletRequest(ServerHttpRequest request) {
		Assert.isInstanceOf(ServletServerHttpRequest.class, request, "ServletServerHttpRequest required");
		return ((ServletServerHttpRequest) request).getServletRequest();
	}

	protected final HttpServletResponse getHttpServletResponse(ServerHttpResponse response) {
		Assert.isInstanceOf(ServletServerHttpResponse.class, response, "ServletServerHttpResponse required");
		return ((ServletServerHttpResponse) response).getServletResponse();
	}


	@Override
	public List<WebSocketExtension> getSupportedExtensions(ServerHttpRequest request) {
		List<WebSocketExtension> extensions = this.extensions;
		if (extensions == null) {
			HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
			extensions = getInstalledExtensions(getContainer(servletRequest));
			this.extensions = extensions;
		}
		return extensions;
	}

	protected List<WebSocketExtension> getInstalledExtensions(WebSocketContainer container) {
		List<WebSocketExtension> result = new ArrayList<>();
		for (Extension extension : container.getInstalledExtensions()) {
			result.add(new StandardToWebSocketExtensionAdapter(extension));
		}
		return result;
	}


	/**
	 * 处理 HTTP 请求升级为 WebSocket 连接的一部分
	 *
	 * @param request            the current request
	 * @param response           the current response
	 * @param selectedProtocol   the selected sub-protocol, if any
	 * @param selectedExtensions the selected WebSocket protocol extensions
	 * @param user               the user to associate with the WebSocket session
	 * @param wsHandler          the handler for WebSocket messages
	 * @param attrs              handshake request specific attributes to be set on the WebSocket
	 *                           session via {@link org.springframework.web.socket.server.HandshakeInterceptor} and
	 *                           thus made available to the {@link org.springframework.web.socket.WebSocketHandler}
	 * @throws HandshakeFailureException
	 */
	@Override
	public void upgrade(ServerHttpRequest request, ServerHttpResponse response,
						@Nullable String selectedProtocol, List<WebSocketExtension> selectedExtensions,
						@Nullable Principal user, WebSocketHandler wsHandler, Map<String, Object> attrs)
			throws HandshakeFailureException {

		// 从传入的 HTTP 请求中获取 HTTP 头信息、本地地址和远程地址
		HttpHeaders headers = request.getHeaders();
		InetSocketAddress localAddr = null;
		try {
			localAddr = request.getLocalAddress();
		} catch (Exception ex) {
			// Ignore
		}
		InetSocketAddress remoteAddr = null;
		try {
			remoteAddr = request.getRemoteAddress();
		} catch (Exception ex) {
			// Ignore
		}

		// 创建 WebSocket 会话
		// 使用上面获得的 headers、2 个address、用户身份信息和其他自定义属性，创建一个 StandardWebSocketSession 实例
		// 这个会话实例代表简历成功后的 WebSocket 会话
		StandardWebSocketSession session = new StandardWebSocketSession(headers, attrs, localAddr, remoteAddr, user);

		// 创建一个适配器
		// 这里接收了 wsHandler 这是应用中用来处理 WebSocket 消息的核心逻辑
		// 将 wsHandler 和 session 一起包装成一个适配器，这个适配器扮演了 Endpoint 的角色
		StandardWebSocketHandlerAdapter endpoint = new StandardWebSocketHandlerAdapter(wsHandler, session);

		// 将 Spring 框架定义的、在握手阶段协商选定的 WebSocketExtension 列表，逐个转换成 Java 标准 API 所要求的 Extension 对象
		List<Extension> extensions = new ArrayList<>();
		for (WebSocketExtension extension : selectedExtensions) {
			extensions.add(new WebSocketToStandardExtensionAdapter(extension));
		}

		// 调用抽象方法 upgradeInternal
		upgradeInternal(request, response, selectedProtocol, extensions, endpoint);
	}

	protected abstract void upgradeInternal(ServerHttpRequest request, ServerHttpResponse response,
											@Nullable String selectedProtocol, List<Extension> selectedExtensions, Endpoint endpoint)
			throws HandshakeFailureException;

}
