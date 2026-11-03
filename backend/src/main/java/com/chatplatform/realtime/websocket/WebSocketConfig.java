package com.chatplatform.realtime.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket configuration for Phase 2b-i.
 *
 * <p>Registers the {@code /ws} endpoint with {@link NoOpPlaceholderHandler} and attaches
 * {@link WsAuthHandshakeInterceptor} to enforce origin validation and one-time ticket consumption.
 * Origins are checked twice against the same explicit list: first by WsAuthHandshakeInterceptor
 * (401, before the ticket is consumed), then by Spring's own origin check as defense in depth.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final NoOpPlaceholderHandler noOpPlaceholderHandler;
    private final WsAuthHandshakeInterceptor wsAuthHandshakeInterceptor;

    public WebSocketConfig(
            NoOpPlaceholderHandler noOpPlaceholderHandler,
            WsAuthHandshakeInterceptor wsAuthHandshakeInterceptor) {
        this.noOpPlaceholderHandler = noOpPlaceholderHandler;
        this.wsAuthHandshakeInterceptor = wsAuthHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(noOpPlaceholderHandler, "/ws")
                .addInterceptors(wsAuthHandshakeInterceptor)
                .setAllowedOrigins(wsAuthHandshakeInterceptor.getAllowedOrigins().toArray(String[]::new));
    }
}
