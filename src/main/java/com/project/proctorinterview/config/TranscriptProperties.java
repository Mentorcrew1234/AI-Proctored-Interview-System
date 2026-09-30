package com.project.proctorinterview.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Filler words removed before evaluation ({@code app.transcript}).
 *
 * <p>Externalised because the right list depends on the language and on how the
 * speech recogniser transcribes hesitation, so it should be tunable without a
 * code change.
 */
@ConfigurationProperties(prefix = "app.transcript")
public record TranscriptProperties(
        /** Multi-word fillers, stripped before single words. */
        List<String> fillerPhrases,
        List<String> fillerWords) {

    public TranscriptProperties {
        fillerPhrases = fillerPhrases == null ? List.of() : List.copyOf(fillerPhrases);
        fillerWords = fillerWords == null ? List.of() : List.copyOf(fillerWords);
    }
}
