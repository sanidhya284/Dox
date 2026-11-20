package com.chatplatform.realtime.bus;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The default (realtime.bus=local): one instance, nothing crosses, nothing contacts Redis. The live single-instance
 * server runs this, so merging Phase 4 changes nothing there.
 */
@Component
@ConditionalOnProperty(name = "realtime.bus", havingValue = "local", matchIfMissing = true)
public class LocalBus implements RealtimeBus {

    private final String instanceId = UUID.randomUUID().toString();

    @Override
    public void publish(Envelope envelope) {
        // one instance: local delivery already happened
    }

    @Override
    public String instanceId() {
        return instanceId;
    }
}
