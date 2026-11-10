package com.chatplatform.shared.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All rate-limit values ({@code ratelimit.*}; ADR-0011, ADR-0012). The defaults are the production values;
 * dev, test and bench profiles raise them. {@code RateLimitDefaultsTest} pins the production values.
 */
@ConfigurationProperties(prefix = "ratelimit")
public record RateLimitProperties(
        @DefaultValue("5") int registerPerIpPerHour,
        @DefaultValue("20") int loginPerIpPerMinute,
        @DefaultValue("5") int loginPerIpAndUserPerMinute,
        @DefaultValue("30") int ticketPerUserPerMinute,
        @DefaultValue("20") int failedHandshakesPerIpPerMinute,
        @DefaultValue("30") int searchPerUserPerMinute,
        @DefaultValue("3") int guestPerIpPerHour,
        @DefaultValue("200") int signupGlobalPerHour,
        @DefaultValue("100000") int maxKeys) {

    /** Effectively no limits (unit tests that construct components by hand). */
    public static RateLimitProperties unlimited() {
        int max = Integer.MAX_VALUE;
        return new RateLimitProperties(max, max, max, max, max, max, max, max, 1000);
    }
}
