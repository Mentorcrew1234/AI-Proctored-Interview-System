package com.project.proctorinterview.report;

import java.time.Instant;

import com.project.proctorinterview.common.Enums.ReviewDecision;
import com.project.proctorinterview.user.User;

import jakarta.persistence.Column;
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
import lombok.Getter;
import lombok.Setter;

/**
 * One recorded human decision about one report.
 *
 * <p>The system has always said its output is "advisory input for a human
 * decision, not a hiring decision". This is where that human decision goes -
 * previously it went nowhere.
 *
 * <p><b>Append-only.</b> There is no unique constraint on {@code report_id}: a
 * decision can be revised, and the most recent row is the current one. Keeping
 * the earlier rows means a changed decision stays visible rather than being
 * quietly replaced, which matters more here than in any other table in the
 * schema, because this is the only one that records a judgement about a person.
 */
@Entity
@Table(name = "report_reviews")
@Getter
@Setter
public class ReportReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    /** Who decided. The point of the table is that a named person took it. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private User reviewer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewDecision decision;

    /** Optional: forcing a justification mostly produces empty ones. */
    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
