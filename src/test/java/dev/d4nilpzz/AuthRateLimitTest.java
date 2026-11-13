package dev.d4nilpzz;

import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.config.ConfigService;
import dev.d4nilpzz.config.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Secret verification is deliberately slow, so one address must not be able to trigger it
 * without limit. A fresh server per test keeps each case on its own failure budget.
 */
class AuthRateLimitTest {

    private static final String MANAGER_SECRET = "manager-secret-for-rate-limit-test";

    @TempDir
    Path workingDirectory;

    private RepossifyServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        Files.createDirectories(workingDirectory.resolve("content"));
        Files.createDirectories(workingDirectory.resolve("repositories"));
        Files.writeString(workingDirectory.resolve("page.json"), """
                {
                  "title": "Test",
                  "author": "Test",
                  "group_id": "com.example",
                  "description": "rate limit test instance",
                  "avatar_url": "/public/repossify.png",
                  "domain_url": "http://localhost/",
                  "links": [],
                  "repositories": [
                    { "name": "releases", "path": "/releases", "visibility": "PUBLIC",  "redeployment": false },
                    { "name": "private",  "path": "/private",  "visibility": "PRIVATE", "redeployment": true  }
                  ]
                }
                """);

        ServerConfig config = new ServerConfig();
        config.hostname = "127.0.0.1";
        config.port = 0;
        config.garbageCollectorIntervalMinutes = 0;
        config.rateLimit.enabled = true;
        config.rateLimit.maxFailures = 3;
        config.rateLimit.windowSeconds = 60;

        server = new RepossifyServer(workingDirectory, config, new ConfigService(workingDirectory));
        TokenService tokens = server.tokenService();
        tokens.createToken("manager", List.of("M"), MANAGER_SECRET);

        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    void validCredentialsAreUnaffected() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(200, get("/repo/private", basic("manager", MANAGER_SECRET)).statusCode());
        }
    }

    @Test
    void wrongCredentialsGet401UntilTheBudgetIsSpent() throws Exception {
        // maxFailures is the number of verifications allowed per window, so with a budget of
        // three the third failure is the one answered with 429: its verification just spent
        // the last permit.
        for (int attempt = 1; attempt <= 2; attempt++) {
            assertEquals(401, get("/repo/private", basic("manager", "wrong")).statusCode(),
                    "attempt " + attempt + " is within the budget and should be a plain 401");
        }

        HttpResponse<String> blocked = get("/repo/private", basic("manager", "wrong"));
        assertEquals(429, blocked.statusCode(), "the third failure spends the budget");
        assertTrue(blocked.body().contains("Too many failed"), blocked.body());
    }

    @Test
    void aBlockAppliesToTheAddressNotTheCredential() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            get("/repo/private", basic("manager", "wrong"));
        }

        assertEquals(429, get("/repo/private", basic("manager", MANAGER_SECRET)).statusCode(),
                "once blocked, even the right secret waits out the window");
    }

    @Test
    void anonymousRequestsNeverSpendTheBudget() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            assertEquals(200, get("/repo", null).statusCode());
        }

        // Anonymous requests never reach verification, so the budget must be untouched.
        assertEquals(401, get("/repo/private", basic("manager", "wrong")).statusCode());
    }

    @Test
    void anUnknownTokenNameAlsoCounts() throws Exception {
        for (int attempt = 1; attempt <= 2; attempt++) {
            assertEquals(401, get("/repo/private", basic("no-such-token", "whatever")).statusCode(),
                    "attempt " + attempt);
        }

        assertEquals(429, get("/repo/private", basic("no-such-token", "whatever")).statusCode());
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String basic(String name, String secret) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((name + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
