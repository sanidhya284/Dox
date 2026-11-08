package com.chatplatform.shared.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileGuardTest {

    @Test
    void prodWithDevOrBenchRefusesToStart() {
        assertThatThrownBy(() -> ProfileGuard.check(new String[]{"prod", "dev"}))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'dev'");
        assertThatThrownBy(() -> ProfileGuard.check(new String[]{"bench", "prod"}))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'bench'");
    }

    @Test
    void otherCombinationsStart() {
        assertThatCode(() -> ProfileGuard.check(new String[]{"prod"})).doesNotThrowAnyException();
        assertThatCode(() -> ProfileGuard.check(new String[]{"dev"})).doesNotThrowAnyException();
        assertThatCode(() -> ProfileGuard.check(new String[]{})).doesNotThrowAnyException();
    }
}
