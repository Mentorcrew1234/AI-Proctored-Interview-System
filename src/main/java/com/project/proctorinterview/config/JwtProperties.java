package com.project.proctorinterview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT signing configuration ({@code app.jwt}).
 *
 * <p>The secret must be at least 32 characters for HS256. The default in
 * application.yml is a development value and is overridden by the JWT_SECRET
 * environment variable in any real deployment.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, long expiryMinutes) {
}
