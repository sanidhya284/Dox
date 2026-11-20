package com.chatplatform.guest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Runbook 9a: admin mode's exit codes, and --remove-account refused outside admin mode. */
class RemoveAccountRunnerTest {

    private final AccountRemovalService removal = mock(AccountRemovalService.class);
    private final com.chatplatform.realtime.bus.RealtimeBus bus = mock(com.chatplatform.realtime.bus.RealtimeBus.class);

    private RemoveAccountRunner runner(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new RemoveAccountRunner(removal, mock(ConfigurableApplicationContext.class), env, bus);
    }

    @Test
    void removedIsZeroAndNoSuchAccountIsThree() {
        RemoveAccountRunner r = runner("prod", "admin");
        assertThat(r.exitCode(List.of("spammer"))).isEqualTo(0);
        verify(removal).removeAccount("spammer");

        doThrow(new IllegalArgumentException("No live account")).when(removal).removeAccount("ghost");
        assertThat(r.exitCode(List.of("ghost"))).isEqualTo(3);
    }

    @Test
    void theRemovalsCloseUserPublishIsFlushedBeforeTheProcessExits() {
        // ADR-0013: with REALTIME_BUS=redis the admin process publishes close_user, so live sockets close at once.
        runner("prod", "admin").exitCode(List.of("spammer"));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(removal, bus);
        order.verify(removal).removeAccount("spammer");
        order.verify(bus).flush(org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void withRedisUnreachableTheRunnerExitsWithinSecondsAndSaysTheCloseIsLost(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // Step 2, point 3: a real RedisBus pointed at a port nothing listens on (down; a pause is the same to the
        // breaker: a timeout instead of a refusal, covered in RedisOutageTest).
        org.springframework.boot.autoconfigure.data.redis.RedisProperties props =
                new org.springframework.boot.autoconfigure.data.redis.RedisProperties();
        props.setHost("localhost");
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            props.setPort(s.getLocalPort());
        }
        props.setTimeout(java.time.Duration.ofMillis(200));
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.chatplatform.realtime.bus.RedisBus realBus = new com.chatplatform.realtime.bus.RedisBus(props,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new com.chatplatform.shared.metrics.ChatMetrics(meters),
                new com.chatplatform.shared.redis.RedisGuard(meters), 100);
        org.mockito.Mockito.doAnswer(inv -> {
            realBus.publish(com.chatplatform.realtime.bus.Envelope.closeUser(realBus.instanceId(),
                    java.util.UUID.randomUUID(), 4002, "credentials revoked"));
            return null;
        }).when(removal).removeAccount("spammer");
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "admin");
        RemoveAccountRunner r = new RemoveAccountRunner(removal, mock(ConfigurableApplicationContext.class), env,
                realBus);
        try {
            long start = System.nanoTime();
            assertThat(r.exitCode(List.of("spammer"))).isEqualTo(0); // the account IS removed
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertThat(ms).as("exits within a few seconds").isLessThan(4_000);
            assertThat(output).contains("close_user was NOT delivered").contains("15-minute window");
        } finally {
            realBus.destroy();
        }
    }

    @Test
    void usageErrorsAreTwoAndRemoveNothing() {
        RemoveAccountRunner r = runner("prod", "admin");
        assertThat(r.exitCode(null)).isEqualTo(2);
        assertThat(r.exitCode(List.of("a", "b"))).isEqualTo(2);
        assertThat(r.exitCode(List.of(" "))).isEqualTo(2);
        verifyNoInteractions(removal);
    }

    @Test
    void theOptionWithoutAdminModeRefusesStartupAndRemovesNothing() {
        assertThatThrownBy(() -> runner("prod").run(new DefaultApplicationArguments("--remove-account=x")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("admin profile");
        verifyNoInteractions(removal);
    }

    @Test
    void mainRefusesTheOptionWithoutTheAdminProfileBeforeSpringStarts() {
        String[] removal = {"--remove-account=x"};
        assertThat(com.chatplatform.ChatPlatformApplicationAccess.allowed(removal, "prod", null)).isFalse();
        assertThat(com.chatplatform.ChatPlatformApplicationAccess.allowed(removal, null, null)).isFalse();
        assertThat(com.chatplatform.ChatPlatformApplicationAccess.allowed(removal, "prod,admin", null)).isTrue();
        assertThat(com.chatplatform.ChatPlatformApplicationAccess.allowed(removal, null, "prod, admin")).isTrue();
        assertThat(com.chatplatform.ChatPlatformApplicationAccess.allowed(new String[0], "prod", null)).isTrue();
    }

    @Test
    void theLiveAppWithoutTheOptionDoesNothing() {
        runner("prod").run(new DefaultApplicationArguments());
        verifyNoInteractions(removal);
    }
}
