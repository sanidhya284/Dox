package com.chatplatform.auth.service;

import com.chatplatform.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationPolicyUnitTest {

    @Test
    void inviteModeWithNoConfiguredCodeIsClosedEvenForABlankCode() {
        RegistrationPolicy p = new RegistrationPolicy("invite", "");
        assertThatThrownBy(() -> p.check("")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> p.check(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> p.check("anything")).isInstanceOf(ApiException.class);
    }

    @Test
    void inviteModeAcceptsOnlyTheConfiguredCode() {
        RegistrationPolicy p = new RegistrationPolicy("invite", "s3cret-code");
        assertThatCode(() -> p.check("s3cret-code")).doesNotThrowAnyException();
        assertThatThrownBy(() -> p.check("s3cret-cod")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> p.check(null)).isInstanceOf(ApiException.class);
    }

    @Test
    void openModeAcceptsAnyoneAndUnknownModesRefuseToStart() {
        assertThatCode(() -> new RegistrationPolicy("open", "").check(null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new RegistrationPolicy("public", "")).isInstanceOf(IllegalStateException.class);
    }
}
