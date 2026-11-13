package dev.d4nilpzz.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payload is assembled by formatting doubles into a string, which is locale sensitive.
 * On a host whose locale uses comma decimals the result stopped being JSON, and the dashboard
 * parses it inside a try/catch, so the metrics panel stayed empty with nothing logged.
 */
class MetricsCompilerTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"es-ES", "de-DE", "fr-FR", "pt-BR", "nl-NL", "en-US", "en-GB"})
    void producesValidJsonInEveryLocale(String tag) throws Exception {
        Locale.setDefault(Locale.forLanguageTag(tag));

        String json = MetricsCompiler.getMetricsJson();
        JsonNode root = new ObjectMapper().readTree(json);

        for (String section : new String[]{"cpu", "ram", "storage"}) {
            assertTrue(root.path(section).path("used").isNumber(),
                    section + ".used is not a number under " + tag + ": " + json);
            assertTrue(root.path(section).path("max").isNumber(),
                    section + ".max is not a number under " + tag + ": " + json);
        }
    }

    @Test
    void decimalsUseADotWhateverTheLocaleSays() {
        Locale.setDefault(Locale.forLanguageTag("es-ES"));

        String json = MetricsCompiler.getMetricsJson();

        assertFalse(json.matches("(?s).*\\d,\\d.*"),
                "a comma between two digits means this is no longer JSON: " + json);
    }
}
