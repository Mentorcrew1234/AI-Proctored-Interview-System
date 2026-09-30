package com.project.proctorinterview.retention;

import java.time.Instant;

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
 * The record that an erasure happened. Not the erasure itself.
 *
 * <p>Deliberately holds no identifying detail about its subject - no email, no
 * name, only the id of an account that by then may not exist. Keeping the
 * identity here would mean an erasure request left the identity behind in a new
 * table, which is erasure that does not erase.
 */
@Entity
@Table(name = "data_deletions")
@Getter
@Setter
public class DataDeletion {

    /** Whether the account went too, or only the interview data under it. */
    public enum Scope {
        INTERVIEW_DATA_ONLY,
        INTERVIEW_DATA_AND_ACCOUNT;

        public String label() {
            return this == INTERVIEW_DATA_ONLY
                    ? "Interview data only"
                    : "Interview data and the account";
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * No {@code @ManyToOne} on purpose: the account it names is usually gone,
     * and a foreign key would either block the deletion or cascade away this
     * very record.
     */
    @Column(name = "subject_user_id", nullable = false)
    private Long subjectUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Scope scope;

    /** A real reference: a destructive action stays attributable to someone. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performed_by", nullable = false)
    private User performedBy;

    @Column(length = 500)
    private String reason;

    @Column(name = "interviews_deleted", nullable = false)
    private int interviewsDeleted;
    @Column(name = "sessions_deleted", nullable = false)
    private int sessionsDeleted;
    @Column(name = "questions_deleted", nullable = false)
    private int questionsDeleted;
    @Column(name = "answers_deleted", nullable = false)
    private int answersDeleted;
    @Column(name = "proctor_events_deleted", nullable = false)
    private int proctorEventsDeleted;
    @Column(name = "reports_deleted", nullable = false)
    private int reportsDeleted;
    @Column(name = "reviews_deleted", nullable = false)
    private int reviewsDeleted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
