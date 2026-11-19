package com.chatplatform.health;

import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The load balancer's health check (ADR-0013 Decision 8): 200 while this instance accepts traffic, 503 from the
 * moment it starts shutting down (Spring Boot sets readiness to REFUSING_TRAFFIC; {@link ShutdownDrain} then keeps
 * serving for the drain). Separate from {@code /actuator/health} on purpose: that one stays "alive and the database
 * works", so the public health check (and anyone monitoring it) never sees a 503 because of a planned deploy.
 *
 * <p>Not public: Caddy routes only /api/*, /ws and /actuator/health to the app; it checks this path directly on
 * the internal network.
 */
@RestController
public class ReadinessController {

    public static final String PATH = "/internal/ready";

    private final ApplicationAvailability availability;

    public ReadinessController(ApplicationAvailability availability) {
        this.availability = availability;
    }

    @GetMapping(PATH)
    public ResponseEntity<Void> ready() {
        return availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(503).build();
    }
}
