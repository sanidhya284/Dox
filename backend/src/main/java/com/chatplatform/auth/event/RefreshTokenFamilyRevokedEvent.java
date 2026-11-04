package com.chatplatform.auth.event;

import java.util.UUID;

/**
 * Published by auth when a replayed (already revoked) refresh token causes its whole token family to
 * be revoked: a theft signal. Part of auth's public API; other modules may listen, auth never knows
 * who does. Listeners that act on the revocation should use
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}: the revocation commits even though the
 * request itself answers 401.
 */
public record RefreshTokenFamilyRevokedEvent(UUID userId, UUID familyId) {
}
