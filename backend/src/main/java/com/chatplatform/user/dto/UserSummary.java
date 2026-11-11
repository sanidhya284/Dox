package com.chatplatform.user.dto;

import java.util.UUID;

/** What other users may see about an account, for rendering names (ADR-0012). Deliberately no email. */
public record UserSummary(UUID id, String username, String displayName, boolean isGuest, boolean isBot,
                          boolean isDeleted) {
}
