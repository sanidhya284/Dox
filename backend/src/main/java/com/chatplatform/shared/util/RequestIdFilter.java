package com.chatplatform.shared.util;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns a unique requestId to every incoming HTTP request.
 *
 * Puts it in MDC (Mapped Diagnostic Context) so it appears in all logs
 * for that thread, and exposes it via a ThreadLocal for GlobalExceptionHandler.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements Filter {

    private static final String REQUEST_ID_KEY = "requestId";
    private static final ThreadLocal<String> currentRequestId = new ThreadLocal<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        String requestId = UUID.randomUUID().toString();
        currentRequestId.set(requestId);
        MDC.put(REQUEST_ID_KEY, requestId);
        
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_KEY);
            currentRequestId.remove();
        }
    }

    public static String currentRequestId() {
        String id = currentRequestId.get();
        return id != null ? id : "unknown";
    }
}
