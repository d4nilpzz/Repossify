package dev.d4nilpzz.console;

import dev.d4nilpzz.Repossify;
import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.repos.GarbageCollector;
import dev.d4nilpzz.repos.MetadataService;
import dev.d4nilpzz.repos.RepositoryService;
import dev.d4nilpzz.repos.StatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console reports through SLF4J, so a bridge that captured stdout returned nothing for
 * every command but one. These assert the dashboard terminal actually receives output.
 */
class ConsoleBridgeTest {

    @TempDir
    Path workingDirectory;

    private ConsoleBridge bridge;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + workingDirectory.resolve("console-test.db").toAbsolutePath();

        TokenService tokenService = new TokenService(jdbcUrl);
        StatisticsService statisticsService = new StatisticsService(jdbcUrl);
        RepositoryService repositoryService = new RepositoryService(workingDirectory);
        MetadataService metadataService = new MetadataService();
        GarbageCollector garbageCollector = new GarbageCollector(repositoryService, metadataService);

        bridge = new ConsoleBridge(new CommandConsole(
                tokenService, repositoryService, metadataService, garbageCollector, statisticsService));
    }

    @Test
    void capturesSingleLineOutput() {
        assertEquals(Repossify.VERSION, bridge.execute("version").trim());
    }

    @Test
    void capturesMultiLineOutput() {
        String output = bridge.execute("help");

        assertTrue(output.contains("generate_token"), output);
        assertTrue(output.contains("list_tokens"), output);
    }

    @Test
    void reportsUnknownCommands() {
        assertTrue(bridge.execute("definitely-not-a-command").contains("Unknown command"));
    }

    @Test
    void emptyCommandProducesNoOutput() {
        assertEquals("", bridge.execute(""));
    }

    @Test
    void capturesOutputOfCommandsThatTouchTheDatabase() {
        bridge.execute("generate_token ci-test M");

        String listing = bridge.execute("list_tokens");
        assertTrue(listing.contains("ci-test"), listing);
    }

    @Test
    void leavesProcessStdoutAlone() {
        PrintStream before = System.out;

        bridge.execute("version");

        assertSame(before, System.out, "the bridge must not swap System.out");
    }

    @Test
    void stdoutSurvivesACommandThatFails() {
        PrintStream before = System.out;

        // Missing arguments: the command reports an error rather than completing.
        bridge.execute("token_rename");

        assertSame(before, System.out);
        assertEquals(Repossify.VERSION, bridge.execute("version").trim(),
                "capture must still work after a failing command");
    }
}
