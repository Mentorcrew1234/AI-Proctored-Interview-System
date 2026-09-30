package com.project.proctorinterview.interview;

import java.time.Instant;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.QuestionMode;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A scheduled interview: who, when, and how it should be conducted.
 * The invite token is the candidate's entry point (/exam/{token}).
 */
@Entity
@Table(name = "interviews")
@Getter
@Setter
public class Interview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * What the recruiter calls this assessment, e.g. "Java Developer - Campus
     * Drive 2026". Not the candidate's name, and deliberately not unique: a
     * whole drive shares one name.
     *
     * <p>Nullable because interviews created before naming existed have none.
     * Use {@link #getDisplayName()} rather than reading this directly.
     */
    @Column(name = "interview_name", length = 150)
    private String interviewName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recruiter_id", nullable = false)
    private User recruiter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private User candidate;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_type", nullable = false, length = 20)
    private CandidateType candidateType;

    /** Null for a fresher. */
    @Column(name = "experience_years")
    private Integer experienceYears;

    @Column(nullable = false, length = 80)
    private String domain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InterviewLanguage language = InterviewLanguage.ENGLISH;

    @Enumerated(EnumType.STRING)
    @Column(name = "interview_type", nullable = false, length = 20)
    private InterviewType interviewType;

    @Column(name = "question_count", nullable = false)
    private int questionCount = 5;

    /**
     * How this interview's questions are produced, or {@code null} to inherit
     * the server-wide default.
     *
     * <p>Null is <b>"inherit"</b>, not "unknown". Interviews scheduled before
     * this column existed ran under whatever the server setting was at the
     * time, and stamping them FIXED would be a guess dressed as a record;
     * leaving them null keeps them behaving exactly as they always did.
     *
     * <p>Resolved through {@code QuestionModeProperties.effectiveFor}, which is
     * the single place the fallback is applied.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "question_mode", length = 20)
    private QuestionMode questionMode;

    /**
     * How long the candidate gets, in minutes, once they actually start.
     *
     * <p>Not related to {@link #scheduledAt}: the clock starts when the
     * candidate begins, not at the scheduled time, so arriving late costs them
     * nothing. The deadline is derived from the session's start
     * ({@code startedAt + durationMinutes}) rather than stored, so editing the
     * duration cannot leave a stale deadline behind.
     */
    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 30;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InterviewStatus status = InterviewStatus.SCHEDULED;

    @Column(name = "invite_token", nullable = false, unique = true, length = 64)
    private String inviteToken;

    @Column(name = "result_visible_to_candidate", nullable = false)
    private boolean resultVisibleToCandidate = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The name to show. Interviews created before naming existed fall back to
     * their id, so the column stays honest about what was actually entered.
     */
    public String getDisplayName() {
        return interviewName == null || interviewName.isBlank()
                ? "Interview #" + id
                : interviewName;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
