package com.chatplatform;

/** Test access to the package-private start-up check in {@link ChatPlatformApplication}. */
public final class ChatPlatformApplicationAccess {

    private ChatPlatformApplicationAccess() {
    }

    public static boolean allowed(String[] args, String envProfiles, String propertyProfiles) {
        return ChatPlatformApplication.adminArgumentsAllowed(args, envProfiles, propertyProfiles);
    }
}
