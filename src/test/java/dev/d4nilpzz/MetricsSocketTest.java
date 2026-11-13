package dev.d4nilpzz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.config.ConfigService;
import dev.d4nilpzz.config.ServerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both sockets authenticate on the upgrade request. Checking inside onConnect instead left a
 * socket that opened and then sent nothing, and on Jetty 12 it threw outright, because the
 * servlet request is detached once the upgrade completes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricsSocketTest {

    private static final String MANAGER_SECRET = "manager-secret-for-socket-test";
    private static final String READER_SECRET = "reader-secret-for-socket-test";

    @TempDir
    static Path workingDirectory;

    private RepossifyServer server;
    private HttpClient client;
    private String baseUrl;
    private String managerCookie;
    private String readerCookie;

    @BeforeAll
    void startServer() throws Exception {
        Files.createDirectories(workingDirectory.resolve("content"));
        Files.createDirectories(workingDirectory.resolve("repositories"));
        Files.writeString(workingDirectory.resolve("page.json"), """
                {
                  "title": "Test", "author": "Test", "group_id": "com.example",
                  "description": "socket test", "avatar_url": "/public/repossify.png",
                  "domain_url": "http://localhost/", "links": [],
                  "repositories": [
                    { "name": "releases", "path": "/releases", "visibility": "PUBLIC", "redeployment": true }
                  ]
                }
                """);

        ServerConfig config = new ServerConfig();
        config.hostname = "127.0.0.1";
        config.port = 0;
        config.garbageCollectorIntervalMinutes = 0;

        server = new RepossifyServer(workingDirectory, config, new ConfigService(workingDirectory));

        TokenService tokens = server.tokenService();
        tokens.createToken("manager", List.of("M"), MANAGER_SECRET);
        tokens.createToken("reader", List.of(), READER_SECRET);
        tokens.addRouteToToken("reader", "/repo/releases", "r");

        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
        client = HttpClient.newHttpClient();

        managerCookie = signIn(MANAGER_SECRET);
        readerCookie = signIn(READER_SECRET);
    }

    @AfterAll
    void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    void aManagerReceivesParseableMetricFrames() throws Exception {
        Collector collector = new Collector();
        WebSocket socket = connect("/api/metrics", managerCookie, collector);

        assertTrue(collector.awaitFrame(20), "no frame arrived within 20s");

        JsonNode root = new ObjectMapper().readTree(collector.payload());
        for (String section : new String[]{"cpu", "ram", "storage"}) {
            assertTrue(root.path(section).path("used").isNumber(), section + ": " + collector.payload());
            assertTrue(root.path(section).path("max").isNumber(), section + ": " + collector.payload());
        }
        socket.abort();
    }

    @Test
    void anAnonymousMetricsUpgradeIsRefused() {
        assertEquals(401, refusedStatus("/api/metrics", null));
    }

    @Test
    void aNonManagerMetricsUpgradeIsRefused() {
        assertEquals(403, refusedStatus("/api/metrics", readerCookie),
                "a read-only token must not reach the host metrics");
    }

    @Test
    void theConsoleSocketAlsoRequiresAManager() {
        assertEquals(401, refusedStatus("/api/console/ws", null));
        assertEquals(403, refusedStatus("/api/console/ws", readerCookie));
    }

    /**
     * The status the handshake was refused with. A refusal has to arrive as an HTTP status
     * on the upgrade, because a socket that opens and then stays silent is invisible to the
     * browser and to the dashboard.
     */
    private int refusedStatus(String path, String cookie) {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> connect(path, cookie, new Collector()));

        WebSocketHandshakeException handshake = assertInstanceOf(
                WebSocketHandshakeException.class, failure.getCause(),
                "the upgrade must be refused with a status: " + failure.getCause());

        return handshake.getResponse().statusCode();
    }

    @Test
    void aManagerCanOpenTheConsoleSocket() {
        WebSocket socket = connect("/api/console/ws", managerCookie, new Collector());

        assertTrue(socket != null && !socket.isOutputClosed());
        socket.abort();
    }

    /* ===================== helpers ===================== */

    private WebSocket connect(String path, String cookie, Collector collector) {
        WebSocket.Builder builder = client.newWebSocketBuilder();
        if (cookie != null) builder = builder.header("Cookie", cookie);
        return builder.buildAsync(URI.create(baseUrl.replaceFirst("^http", "ws") + path), collector).join();
    }

    private String signIn(String secret) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/signin"))
                        .header("Authorization", "Bearer " + secret)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "sign in failed: " + response.body());

        return response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("repossify_session="))
                .map(value -> value.split(";")[0])
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no session cookie returned"));
    }

    private static final class Collector implements WebSocket.Listener {

        private final CountDownLatch frame = new CountDownLatch(1);
        private final StringBuilder text = new StringBuilder();

        boolean awaitFrame(int seconds) throws InterruptedException {
            return frame.await(seconds, TimeUnit.SECONDS);
        }

        String payload() {
            return text.toString();
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) frame.countDown();
            return WebSocket.Listener.super.onText(socket, data, last);
        }
    }
}
