package com.chatplatform.realtime.bus;

import io.lettuce.core.ClientOptions;
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
 *       re-subscribes after a connection loss.</li>
 * </ul>
 */
@Configuration
public class RedisBusConfig {

    static final int REQUEST_QUEUE_SIZE = 1000;

    @Bean
    LettuceClientConfigurationBuilderCustomizer boundedLettuce() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(REQUEST_QUEUE_SIZE)
                .autoReconnect(true)
                .build());
    }

    @Bean
    @ConditionalOnProperty(name = "realtime.bus", havingValue = "redis")
    RedisMessageListenerContainer busListenerContainer(RedisConnectionFactory factory, BusSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.setRecoveryInterval(1000L);
        container.addMessageListener(subscriber, new ChannelTopic(RedisBus.CHANNEL));
        return container;
    }
}
