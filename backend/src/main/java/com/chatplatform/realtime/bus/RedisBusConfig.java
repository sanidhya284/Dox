package com.chatplatform.realtime.bus;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis wiring (ADR-0013).
 * <ul>
 *   <li><b>Client options (always):</b> while disconnected, Lettuce REJECTS commands instead of buffering them
 *       (the default would queue every command in memory for the whole outage), and its request queue is bounded.
 *       Reconnects automatically.</li>
 *   <li><b>Subscriber (realtime.bus=redis only):</b> a listener container on {@code chat:rt}; Spring's container
 *       re-subscribes after a connection loss. It is started by {@link BusSubscription}, in the background with
 *       retries, so an instance starts (and is healthy) with Redis down.</li>
 * </ul>
 */
@Configuration
public class RedisBusConfig {

    static final int REQUEST_QUEUE_SIZE = 1000;

    static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(200);
    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(1);

    /** The same options on every Lettuce connection: shared (limiter, socket cap, subscriber) and the bus's own. */
    static ClientOptions clientOptions(RedisProperties properties) {
        Duration connect = properties.getConnectTimeout() != null ? properties.getConnectTimeout()
                : DEFAULT_CONNECT_TIMEOUT;
        return ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(REQUEST_QUEUE_SIZE)
                .autoReconnect(true)
                .socketOptions(SocketOptions.builder().connectTimeout(connect).build())
                .build();
    }

    @Bean
    LettuceClientConfigurationBuilderCustomizer boundedLettuce(RedisProperties properties) {
        return builder -> builder.clientOptions(clientOptions(properties));
    }

    /**
     * The bus's own connection factory (not a bean, so Boot's auto-configured factory stays the only one in the
     * context). Same host, port, timeouts and options as the shared one.
     */
    static LettuceConnectionFactory dedicatedFactory(RedisProperties properties) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(properties.getHost(),
                properties.getPort());
        server.setDatabase(properties.getDatabase());
        if (properties.getUsername() != null) {
            server.setUsername(properties.getUsername());
        }
        if (properties.getPassword() != null) {
            server.setPassword(properties.getPassword());
        }
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(properties.getTimeout() != null ? properties.getTimeout() : DEFAULT_TIMEOUT)
                .clientOptions(clientOptions(properties))
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(server, client);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    @Bean
    @ConditionalOnProperty(name = "realtime.bus", havingValue = "redis")
    RedisMessageListenerContainer busListenerContainer(RedisConnectionFactory factory, BusSubscriber subscriber) {
        // Not auto-started: BusSubscription starts it, so a missing Redis can't fail startup.
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override
            public boolean isAutoStartup() {
                return false;
            }
        };
        container.setConnectionFactory(factory);
        container.setRecoveryInterval(1000L);
        container.addMessageListener(subscriber, new ChannelTopic(RedisBus.CHANNEL));
        return container;
    }

    @Bean
    @ConditionalOnProperty(name = "realtime.bus", havingValue = "redis")
    BusSubscription busSubscription(RedisMessageListenerContainer busListenerContainer) {
        return new BusSubscription(busListenerContainer, 1000L);
    }
}
