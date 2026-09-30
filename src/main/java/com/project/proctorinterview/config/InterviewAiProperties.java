package com.project.proctorinterview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which provider the interview engine uses ({@code app.interview}).
 *
 * <p>MOCK exists so the candidate flow can be tested without external latency.
 * Each answer is evaluated synchronously inside the submit request, so a slow
 * or failing LLM call is felt directly as a delay between questions - a failing
 * call costs the whole {@code app.gemini.timeout-seconds} before falling back.
 *
 * <p>MOCK does not remove or bypass any Gemini code: it simply never selects
 * that provider, so switching back to GEMINI restores the existing behaviour
 * exactly.
 */
@ConfigurationProperties(prefix = "app.interview")
public record InterviewAiProperties(AiMode aiMode) {

    public enum AiMode {
        /** Offline question bank and heuristic scorer. No external calls. */
        MOCK,
        /** Use Gemini when configured, falling back offline on any failure. */
        GEMINI
    }

    public InterviewAiProperties {
        aiMode = aiMode == null ? AiMode.MOCK : aiMode;
    }

    public boolean isMock() {
        return aiMode == AiMode.MOCK;
    }
}
