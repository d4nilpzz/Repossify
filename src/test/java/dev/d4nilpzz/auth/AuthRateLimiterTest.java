package dev.d4nilpzz.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthRateLimiterTest {

    @Test
    void blocksOnlyAfterTheLimitIsReached() {
        AuthRateLimiter limiter = new AuthRateLimiter(true, 3, 60);

        assertFalse(limiter.isBlocked("10.0.0.1"));
        limiter.recordFailure("10.0.0.1");
        limiter.recordFailure("10.0.0.1");
        assertFalse(limiter.isBlocked("10.0.0.1"), "two failures is under the limit of three");

        limiter.recordFailure("10.0.0.1");
        assertTrue(limiter.isBlocked("10.0.0.1"));
    }

    @Test
    void countsEachClientSeparately() {
        AuthRateLimiter limiter = new AuthRateLimiter(true, 2, 60);

        limiter.recordFailure("10.0.0.1");
        limiter.recordFailure("10.0.0.1");

        assertTrue(limiter.isBlocked("10.0.0.1"));
        assertFalse(limiter.isBlocked("10.0.0.2"));
    }

    @Test
    void successClearsTheCount() {
        AuthRateLimiter limiter = new AuthRateLimiter(true, 2, 60);

        limiter.recordFailure("10.0.0.1");
        limiter.recordSuccess("10.0.0.1");
        limiter.recordFailure("10.0.0.1");

        assertFalse(limiter.isBlocked("10.0.0.1"), "the earlier failure should no longer count");
    }

    @Test
    void theWindowExpiresTheCount() throws Exception {
        AuthRateLimiter limiter = new AuthRateLimiter(true, 1, 1);

        limiter.recordFailure("10.0.0.1");
        assertTrue(limiter.isBlocked("10.0.0.1"));

        Thread.sleep(1_300);
        assertFalse(limiter.isBlocked("10.0.0.1"), "the count should lapse with the window");
    }

    @Test
    void disabledNeverBlocks() {
        AuthRateLimiter limiter = AuthRateLimiter.disabled();

        for (int i = 0; i < 100; i++) {
            limiter.recordFailure("10.0.0.1");
        }

        assertFalse(limiter.isBlocked("10.0.0.1"));
        assertFalse(limiter.enabled());
    }

    @Test
    void nonPositiveSettingsDisableIt() {
        assertFalse(new AuthRateLimiter(true, 0, 60).enabled());
        assertFalse(new AuthRateLimiter(true, 5, 0).enabled());
    }

    @Test
    void toleratesAMissingClientAddress() {
        AuthRateLimiter limiter = new AuthRateLimiter(true, 1, 60);

        limiter.recordFailure(null);
        limiter.recordSuccess(null);

        assertFalse(limiter.isBlocked(null));
    }
}
