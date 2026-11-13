package dev.d4nilpzz.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessTokenExpiryTest {

    private static AccessToken withExpiry(String expiresAt) {
        return new AccessToken(1, "PERSISTENT", "ci", "hash", "test",
                List.of(), List.of(), Instant.now().toString(), expiresAt);
    }

    @Test
    void aTokenWithoutAnExpiryNeverExpires() {
        assertFalse(withExpiry(null).isExpired());
        assertFalse(withExpiry("").isExpired());
    }

    @Test
    void expiryIsComparedAgainstTheGivenMoment() {
        AccessToken token = withExpiry("2026-01-01T00:00:00Z");

        assertFalse(token.isExpiredAt(Instant.parse("2025-12-31T23:59:59Z")));
        assertTrue(token.isExpiredAt(Instant.parse("2026-01-01T00:00:01Z")));
    }

    @Test
    void theExpiryInstantItselfStillAuthenticates() {
        AccessToken token = withExpiry("2026-01-01T00:00:00Z");

        assertFalse(token.isExpiredAt(Instant.parse("2026-01-01T00:00:00Z")),
                "the comparison is exclusive, so the exact instant is not yet past");
    }

    @Test
    void anUnreadableExpiryIsTreatedAsPast() {
        assertTrue(withExpiry("not-a-timestamp").isExpired(),
                "a corrupt expiry must never be the reason a token keeps working");
    }
}
