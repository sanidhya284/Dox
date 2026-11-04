package com.chatplatform.realtime.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
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

    /**
     * Explicit frame-size limits on the servlet WebSocket container (Tomcat's default is 8 KiB).
     * 32 KiB fits a maximal send: a 4,000-char body is up to 12,000 bytes in UTF-8, but a client may
     * legally write every char as a 6-byte JSON escape (\\uXXXX), about 24,000 bytes, plus the envelope.
     * Larger frames are closed by the container with 1009. Binary frames are not part of the protocol.
     *
     * <p>Set through Tomcat's servlet-context init parameters, which its WsServerContainer reads when it
     * is created (keys taken from Tomcat's own constants). Spring's ServletServerContainerFactoryBean
     * was not used: it requires a live ServerContainer and breaks every mock-web application context.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> webSocketFrameLimits(
            @Value("${ws.max-text-frame-bytes:32768}") int maxTextFrameBytes) {
        return factory -> factory.addContextCustomizers(context -> {
            context.addParameter(org.apache.tomcat.websocket.server.Constants.TEXT_BUFFER_SIZE_SERVLET_CONTEXT_INIT_PARAM,
                    Integer.toString(maxTextFrameBytes));
            context.addParameter(org.apache.tomcat.websocket.server.Constants.BINARY_BUFFER_SIZE_SERVLET_CONTEXT_INIT_PARAM,
                    "1024");
        });
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws")
                .addInterceptors(wsAuthHandshakeInterceptor)
                .setAllowedOrigins(wsAuthHandshakeInterceptor.getAllowedOrigins().toArray(String[]::new));
    }
}
