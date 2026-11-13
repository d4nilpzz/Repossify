package dev.d4nilpzz.repos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolution must stay inside the repository named by the first path segment.
 * <p>
 * Anchoring at the repositories root instead was enough to pass the traversal guard while
 * landing in a different repository, because the result was still inside the root. Callers
 * authorize against that first segment, so the permission check and the file served could
 * refer to two different repositories.
 */
class RepositoryServiceResolveTest {

    @TempDir
    Path workingDirectory;

    private RepositoryService service;

    @BeforeEach
    void setUp() throws IOException {
        service = new RepositoryService(workingDirectory);
        Files.createDirectories(service.root().resolve("public"));
        Files.createDirectories(service.root().resolve("private"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "public/../private/secret.jar",
            "public/com/../../private/secret.jar",
            "public/../../repositories/private/secret.jar",
            "public/..\\private\\secret.jar"
    })
    void refusesToReachASiblingRepository(String candidate) {
        assertEquals(Optional.empty(), service.resolve(candidate),
                "must not resolve into a sibling repository: " + candidate);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "public/../../outside.jar",
            "public/../..",
            "../outside.jar",
            ".."
    })
    void refusesToLeaveTheRepositoriesDirectory(String candidate) {
        assertEquals(Optional.empty(), service.resolve(candidate),
                "must not escape the repositories directory: " + candidate);
    }

    @Test
    void resolvesInsideTheNamedRepository() {
        Path resolved = service.resolve("public/com/example/demo/1.0.0/demo-1.0.0.jar").orElseThrow();

        assertTrue(resolved.startsWith(repositoryRoot("public")));
        assertEquals("demo-1.0.0.jar", resolved.getFileName().toString());
    }

    @Test
    void innerDotDotStayingInsideTheRepositoryIsStillAllowed() {
        Path resolved = service.resolve("public/com/nested/../demo.jar").orElseThrow();

        assertEquals(repositoryRoot("public").resolve("com").resolve("demo.jar"), resolved);
    }

    @Test
    void repositoryNameAloneResolvesToItsRoot() {
        assertEquals(repositoryRoot("public"), service.resolve("public").orElseThrow());
    }

    @Test
    void unknownRepositoryStillResolvesSoCallersCanReportItThemselves() {
        assertTrue(service.resolve("absent/demo.jar").isPresent());
    }

    private Path repositoryRoot(String name) {
        return service.root().resolve(name).toAbsolutePath().normalize();
    }
}
