package dev.d4nilpzz.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenServiceExpiryTest {

    @TempDir
    Path directory;

    private TokenService tokens;

    @BeforeEach
    void setUp() throws Exception {
        tokens = new TokenService("jdbc:sqlite:" + directory.resolve("expiry.db").toAbsolutePath());
    }

    @Test
    void aTokenWithoutExpiryKeepsWorking() throws Exception {
        tokens.createToken("forever", List.of(), "secret-forever");

        assertNotNull(tokens.authenticate("forever", "secret-forever"));
        assertNull(tokens.getTokenByName("forever").expiresAt);
    }

    @Test
    void aTokenExpiringLaterStillAuthenticates() throws Exception {
        tokens.createToken("future", List.of(), "secret-future", "test",
                Instant.now().plus(1, ChronoUnit.DAYS));

        assertNotNull(tokens.authenticate("future", "secret-future"));
        assertNotNull(tokens.getTokenBySecret("secret-future"));
    }

    @Test
    void anExpiredTokenNoLongerAuthenticates() throws Exception {
        tokens.createToken("past", List.of(), "secret-past", "test",
                Instant.now().minus(1, ChronoUnit.MINUTES));

        assertNull(tokens.authenticate("past", "secret-past"), "by name and secret");
        assertNull(tokens.getTokenBySecret("secret-past"), "by secret alone");
    }

    @Test
    void anExpiredTokenIsStillListedSoItCanBeSeenAndRemoved() throws Exception {
        tokens.createToken("past", List.of(), "secret-past", "test",
                Instant.now().minus(1, ChronoUnit.MINUTES));

        AccessToken listed = tokens.getTokenByName("past");
        assertNotNull(listed, "an expired token must not vanish from the token list");
        assertTrue(listed.isExpired());
        assertEquals(1, tokens.listTokens().size());
    }

    @Test
    void shorteningTheExpiryRevokesImmediately() throws Exception {
        tokens.createToken("ci", List.of(), "secret-ci");
        assertNotNull(tokens.authenticate("ci", "secret-ci"), "cached as a success first");

        tokens.setTokenExpiry("ci", Instant.now().minus(1, ChronoUnit.SECONDS));

        assertNull(tokens.authenticate("ci", "secret-ci"),
                "setting an expiry must also drop the cached authentication");
    }

    @Test
    void clearingTheExpiryBringsTheTokenBack() throws Exception {
        tokens.createToken("past", List.of(), "secret-past", "test",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        assertNull(tokens.authenticate("past", "secret-past"));

        tokens.setTokenExpiry("past", null);

        assertNotNull(tokens.authenticate("past", "secret-past"));
    }

    @Test
    void settingTheExpiryOfAMissingTokenFails() {
        assertThrows(IllegalArgumentException.class,
                () -> tokens.setTokenExpiry("absent", Instant.now()));
    }

    @Test
    void theBootstrappedAdminNeverExpires() throws Exception {
        assertNotNull(tokens.bootstrapAdminIfEmpty());

        assertNull(tokens.getTokenByName("admin").expiresAt,
                "the only way back in must not expire on its own");
    }

    @Test
    void regeneratingASecretKeepsTheExpiry() throws Exception {
        Instant expiry = Instant.now().plus(2, ChronoUnit.DAYS);
        tokens.createToken("ci", List.of(), "secret-ci", "test", expiry);

        String regenerated = tokens.regenerateTokenSecret("ci");

        assertNotNull(tokens.authenticate("ci", regenerated));
        assertEquals(expiry.toString(), tokens.getTokenByName("ci").expiresAt);
    }
}
