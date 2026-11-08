package com.chatplatform.auth.service;

import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Who may register (ADR-0011). {@code open}: anyone (dev, tests, bench). {@code invite} (the default):
 * the request must carry the configured invite code; with no code configured, registration is closed.
 * Checked first in {@link AuthService#register}, before the username/email conflict checks, so that
 * without the code nobody can learn which usernames or emails exist.
 */
@Component
public class RegistrationPolicy {

    private final boolean open;
    private final byte[] inviteCode;

    public RegistrationPolicy(@Value("${registration.mode:invite}") String mode,
                              @Value("${registration.invite-code:}") String inviteCode) {
        if (!"open".equals(mode) && !"invite".equals(mode)) {
            throw new IllegalStateException("registration.mode must be 'open' or 'invite', not: " + mode);
        }
        this.open = "open".equals(mode);
        this.inviteCode = inviteCode == null ? new byte[0] : inviteCode.trim().getBytes(StandardCharsets.UTF_8);
    }

    public void check(String presentedCode) {
        if (open) {
            return;
        }
        byte[] presented = presentedCode == null ? new byte[0] : presentedCode.trim().getBytes(StandardCharsets.UTF_8);
        // Constant time for equal lengths; a configured-but-blank code means closed, never "blank matches blank".
        if (inviteCode.length == 0 || !MessageDigest.isEqual(inviteCode, presented)) {
            throw new ApiException(ErrorCode.REGISTRATION_CLOSED);
        }
    }
}
