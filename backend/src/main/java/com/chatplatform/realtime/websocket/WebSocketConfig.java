package com.chatplatform.realtime.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket configuration.
 *
 * <p>Registers the {@code /ws} endpoint with {@link ChatWebSocketHandler} and attaches
 * {@link WsAuthHandshakeInterceptor} to enforce origin validation and one-time ticket consumption.
 * Origins are checked twice against the same explicit list: first by WsAuthHandshakeInterceptor
 * (401, before the ticket is consumed), then by Spring's own origin check as defense in depth.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final WsAuthHandshakeInterceptor wsAuthHandshakeInterceptor;

    public WebSocketConfig(
            ChatWebSocketHandler chatWebSocketHandler,
            WsAuthHandshakeInterceptor wsAuthHandshakeInterceptor) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.wsAuthHandshakeInterceptor = wsAuthHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws")
                .addInterceptors(wsAuthHandshakeInterceptor)
                .setAllowedOrigins(wsAuthHandshakeInterceptor.getAllowedOrigins().toArray(String[]::new));
    }
}
