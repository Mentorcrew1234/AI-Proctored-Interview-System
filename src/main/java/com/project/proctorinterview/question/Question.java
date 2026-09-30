package com.project.proctorinterview.question;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.QuestionSource;
import com.project.proctorinterview.common.StringListConverter;
import com.project.proctorinterview.interview.InterviewSession;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * One generated interview question, fixed in order for the session.
 *
 * <p>{@code uniqueConstraints} mirrors {@code uk_questions_session_sequence}
 * from {@code V1__baseline.sql} - it does not create a second constraint in
 * MySQL (Flyway already did, and {@code ddl-auto: validate} only checks
 * compatibility there), but it is what makes the constraint exist at all
 * against the test profile's H2 schema, which is generated from these
 * annotations rather than the migration. Adaptive question generation
 * (Phase 6.2) relies on this being real and enforced under concurrency, not
 * merely documented in SQL a test database never runs.
 */
@Entity
@Table(name = "questions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_questions_session_sequence",
                columnNames = {"session_id", "sequence_no"}))
@Getter
@Setter
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private InterviewSession session;

    /** 1-based position within the session. */
    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    /**
     * A stable identity for {@link #text}, so a future selector can look up
     * "has this text been used before" with an indexed equality check
     * instead of scanning a TEXT column, which MySQL cannot index directly.
     * Computed by this entity itself in {@link #onCreate()} - a caller can
     * never forget to set it, the same guarantee {@link #createdAt} already
     * has.
     */
    @Column(name = "text_hash", nullable = false, length = 64)
    private String textHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Difficulty difficulty;

    /** Key points a good answer should touch; also drives the fallback scorer. */
    @Convert(converter = StringListConverter.class)
    @Column(name = "expected_points", columnDefinition = "TEXT")
    private List<String> expectedPoints = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuestionSource source;

    /**
     * Which model produced this question: the configured Gemini model when
     * {@link #source} is {@code LLM}, or the fallback's fixed name when it is
     * {@code BANK}. Null only for rows persisted before this column existed.
     */
    @Column(name = "model_name", length = 60)
    private String modelName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (textHash == null && text != null) {
            textHash = sha256Hex(text.trim().toLowerCase(Locale.ROOT));
        }
    }

    /** Matches MySQL's {@code SHA2(str, 256)}, which V6's backfill uses on existing rows. */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }
}
