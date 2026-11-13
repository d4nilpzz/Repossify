package dev.d4nilpzz.controllers;

import dev.d4nilpzz.http.PathSafety;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenControllerPathTest {

    @Test
    void decodesPercentEscapesAndLeavesPlusAlone() {
        assertEquals("1.0+build3/demo.jar", MavenController.decodePath("1.0+build3/demo.jar"));
        assertEquals("com/example/a b.jar", MavenController.decodePath("com/example/a%20b.jar"));
    }

    /**
     * decodePath runs on a path the servlet container has already decoded, so input that
     * reached it as an inert literal becomes a real traversal after this second decode.
     * Rejecting the decoded form is what stops the authorization string and the resolved
     * file from referring to different places.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "public/%2e%2e/private/secret.jar",
            "public/%2E%2E/private/secret.jar",
            "public/..%2fprivate%2fsecret.jar",
            "public/%2e%2e%2fprivate%2fsecret.jar"
    })
    void decodedTraversalIsDetected(String raw) {
        assertTrue(PathSafety.hasTraversalSegment(MavenController.decodePath(raw)),
                "decoded form must be recognised as traversal: " + raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "releases/com/example/demo/1.0.0/demo-1.0.0.jar",
            "releases/com/example/demo/1.0.0/demo-1.0.0.jar.sha1",
            "snapshots/com/example/demo/1.0.0-SNAPSHOT/maven-metadata.xml",
            "releases/com/example/demo/1.0+build3/demo-1.0+build3.jar"
    })
    void ordinaryMavenPathsAreNotFlagged(String raw) {
        assertFalse(PathSafety.hasTraversalSegment(MavenController.decodePath(raw)), raw);
    }
}
