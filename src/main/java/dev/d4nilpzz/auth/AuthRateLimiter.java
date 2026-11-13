package dev.d4nilpzz.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps how often one client may present wrong credentials.
 * <p>
 * Secret verification is deliberately slow, which is what protects a stored secret but also
 * means an unauthenticated caller can spend the server's CPU at will: one request carrying an
 * existing token name and a wrong secret costs a full verification, and a handful of
 * concurrent ones saturate every core. Secrets carry 192 bits of entropy, so this is about
 * availability rather than guessing.
 * <p>
 * Only failures that actually reached verification are counted. An anonymous request costs
 * nothing and must never push a shared address towards a block.
 * <p>
 * {@code maxFailures} is the number of verifications allowed per window, so the request that
 * reaches the limit is itself answered as blocked: its verification spent the last permit.
 */
public class AuthRateLimiter {

    private final boolean enabled;
    private final int maxFailures;
    private final Duration window;
    private final Cache<String, AtomicInteger> failures;

    public AuthRateLimiter(boolean enabled, int maxFailures, int windowSeconds) {
        this.maxFailures = maxFailures;
        this.window = Duration.ofSeconds(Math.max(1, windowSeconds));
        this.enabled = enabled && maxFailures > 0 && windowSeconds > 0;
        this.failures = Caffeine.newBuilder()
                .expireAfterWrite(this.window)
                .maximumSize(100_000)
                .build();
    }

    /** A limiter that never blocks, for tests and for callers that front their own. */
    public static AuthRateLimiter disabled() {
        return new AuthRateLimiter(false, 0, 0);
    }

    public boolean isBlocked(String client) {
        if (!enabled || client == null) return false;
        AtomicInteger count = failures.getIfPresent(client);
        return count != null && count.get() >= maxFailures;
    }

    public void recordFailure(String client) {
        if (!enabled || client == null) return;
        // Counted in place rather than re-inserted, so the window runs from the first failure
        // and a client that keeps trying cannot extend its own block indefinitely.
        failures.get(client, key -> new AtomicInteger()).incrementAndGet();
    }

    public void recordSuccess(String client) {
        if (!enabled || client == null) return;
        failures.invalidate(client);
    }

    public Duration window() {
        return window;
    }

    public boolean enabled() {
        return enabled;
    }
}
