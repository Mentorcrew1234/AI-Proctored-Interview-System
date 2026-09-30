package com.project.proctorinterview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google Gemini settings ({@code app.gemini}).
 *
 * <p>The API key belongs in the gitignored {@code config/application.yml}, never
 * in the packaged one. A blank key is not an error: the application runs in
 * offline fallback mode instead.
 */
@ConfigurationProperties(prefix = "app.gemini")
public record GeminiProperties(
        String apiKey,
        String model,
        String baseUrl,
        int timeoutSeconds) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
