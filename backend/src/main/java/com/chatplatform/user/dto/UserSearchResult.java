package com.chatplatform.user.dto;

import java.util.UUID;

/** One user search hit (ADR-0012). Deliberately no email. */
public record UserSearchResult(UUID id, String username, String displayName, boolean isGuest) {
}
