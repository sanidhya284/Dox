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

    private RemoveAccountRunner runner(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new RemoveAccountRunner(removal, mock(ConfigurableApplicationContext.class), env);
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
