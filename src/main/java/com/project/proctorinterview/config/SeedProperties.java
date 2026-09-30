package com.project.proctorinterview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bootstrap accounts, created only when the users table is empty.
 * Configured under {@code app.seed} in application.yml.
 */
@ConfigurationProperties(prefix = "app.seed")
public record SeedProperties(
        boolean enabled,
        String adminEmail,
        String adminPassword,
        String adminName,
        boolean demoUsers) {
}
