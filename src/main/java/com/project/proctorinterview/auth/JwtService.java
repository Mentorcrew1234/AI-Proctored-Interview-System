package com.project.proctorinterview.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.config.JwtProperties;
import com.project.proctorinterview.user.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** Issues and verifies the JWTs used by the stateless /api/** filter chain. */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expiryMinutes;

    public JwtService(JwtProperties props) {
        byte[] secretBytes = props.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 characters for HS256 (was " + secretBytes.length + ")");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.expiryMinutes = props.expiryMinutes();
    }

    /** Subject is the user id; email and role ride along as claims. */
    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("name", user.getFullName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expiryMinutes * 60)))
                .signWith(key)
                .compact();
    }

    /**
     * @return the verified claims, or null when the token is absent, expired,
     *         tampered with, or otherwise unusable. Callers treat null as
     *         "not authenticated" rather than as an error.
     */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public Long userId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    public Role role(Claims claims) {
        return Role.valueOf(claims.get("role", String.class));
    }

    public long expirySeconds() {
        return expiryMinutes * 60;
    }
}
