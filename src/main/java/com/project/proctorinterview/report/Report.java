package com.project.proctorinterview.report;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.project.proctorinterview.common.Enums.Recommendation;
import com.project.proctorinterview.common.JsonMapConverter;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The final assessment for a session.
 *
 * <p>Scores are aggregated arithmetically and the recommendation comes from
 * configured thresholds — the AI never decides the outcome. Proctoring counts
 * are reported as observations and can only ever cap a result at
 * FURTHER_REVIEW, never push it to NOT_RECOMMENDED.
 */
@Entity
@Table(name = "reports")
@Getter
@Setter
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, unique = true)
    private InterviewSession session;

    @Column(name = "technical_score", nullable = false)
    private int technicalScore;

    @Column(name = "communication_score", nullable = false)
    private int communicationScore;

    @Column(name = "problem_solving_score", nullable = false)
    private int problemSolvingScore;

    @Column(name = "relevance_score", nullable = false)
    private int relevanceScore;

    @Column(name = "overall_score", nullable = false)
    private int overallScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Recommendation recommendation;

    /** Plain-language justification, including any proctoring caveat. */
    @Column(columnDefinition = "TEXT")
    private String explanation;

    /** Event type -> count, e.g. {"NO_FACE":1,"PHONE_DETECTED":2}. */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "proctor_summary", columnDefinition = "TEXT")
    private Map<String, Object> proctorSummary = new LinkedHashMap<>();

    /** True when proctoring observations warranted a human review. */
    @Column(name = "integrity_flag", nullable = false)
    private boolean integrityFlag;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @PrePersist
    void onCreate() {
        if (generatedAt == null) {
            generatedAt = Instant.now();
        }
    }
}
