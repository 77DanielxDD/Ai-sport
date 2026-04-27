package com.example.aisport.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static class Counter {
        long minute;
        AtomicInteger count = new AtomicInteger(0);
    }

    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${app.rate-limit.max-requests-per-minute:300}")
    private int maxPerMinute;

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/media/") || path.equals("/api/users/login") || path.equals("/api/users/register");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = request.getRemoteAddr();
        long minute = Instant.now().getEpochSecond() / 60;

        Counter c = counters.computeIfAbsent(ip, k -> new Counter());
        synchronized (c) {
            if (c.minute != minute) {
                c.minute = minute;
                c.count.set(0);
            }
            if (c.count.incrementAndGet() > maxPerMinute) {
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                mapper.writeValue(response.getWriter(), Map.of(
                        "status", "TOO_MANY_REQUESTS",
                        "error", "Rate limit exceeded",
                        "retryAfterSeconds", 60
                ));
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}