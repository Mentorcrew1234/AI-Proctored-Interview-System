package com.project.proctorinterview.interview;

import java.time.Duration;
import java.time.Instant;

import com.project.proctorinterview.common.Enums.CompletionReason;
import com.project.proctorinterview.common.Enums.MonitoringCoverage;
import com.project.proctorinterview.common.Enums.SessionStatus;

import jakarta.persistence.Column;
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
 * The central entity joining Product A and Product B: proctor events, questions,
 * answers and the final report all hang off one session id.
 */
@Entity
@Table(name = "interview_sessions")
@Getter
@Setter
public class InterviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "interview_id", nullable = false, unique = true)
    private Interview interview;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status = SessionStatus.ACTIVE;

    /**
     * Why the session stopped being active. Null while it is still running, and
     * null for sessions that finished before this was recorded.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "completion_reason", length = 30)
    private CompletionReason completionReason;

    @Column(name = "browser_info", length = 400)
    private String browserInfo;

    @Column(name = "camera_granted", nullable = false)
    private boolean cameraGranted;

    @Column(name = "mic_granted", nullable = false)
    private boolean micGranted;

    // ---- monitoring coverage ------------------------------------------------
    //
    // What the OBSERVER did, not what the candidate did. Nothing here is
    // evidence about a person and nothing here changes a score. It exists so a
    // report can say "not observed" rather than implying "observed and clean".

    /**
     * Whether the browser confirmed both detection models loaded.
     *
     * <p>Three-valued, and the three are genuinely different. {@code null}
     * means the session predates coverage recording, so the answer is unknown
     * rather than bad. {@code false} is set when the session is created and
     * means the confirmation has not arrived - a session that ends still false
     * is one whose monitoring never started. {@code true} means it did.
     */
    @Column(name = "monitoring_ready")
    private Boolean monitoringReady;

    /**
     * Events the browser generated but abandoned after exhausting its upload
     * retries. Anything above zero means the stored event list is known to be
     * missing something.
     */
    @Column(name = "monitoring_dropped_events", nullable = false)
    private int monitoringDroppedEvents;

    /**
     * The browser's own message when monitoring failed to start, so a reader
     * sees why and not merely that. Diagnostic text for a human - no code
     * branches on it.
     */
    @Column(name = "monitoring_note", length = 300)
    private String monitoringNote;

    /**
     * How much of this interview was actually observed.
     *
     * <p>Derived rather than stored, for the same reason {@link #deadline()} is:
     * a stored verdict would be wrong the moment a late batch of events landed.
     *
     * <p>Order matters. An unknown coverage is reported as unknown before
     * anything else, because an old session must not be relabelled a failure.
     * A known loss then outranks the observation count, because five recorded
     * observations with three dropped is still an incomplete record - and
     * saying so is the whole point of this method.
     *
     * @param observationCount how many proctor events this session actually has
     */
    public MonitoringCoverage coverageWith(long observationCount) {
        if (monitoringReady == null) {
            return MonitoringCoverage.NOT_RECORDED;
        }
        if (!monitoringReady || monitoringDroppedEvents > 0) {
            return MonitoringCoverage.INCOMPLETE;
        }
        return observationCount > 0
                ? MonitoringCoverage.OBSERVATIONS_RECORDED
                : MonitoringCoverage.NONE_OBSERVED;
    }

    // ---- derived timing -----------------------------------------------------
    //
    // The single definition of when this interview runs out. Everything that
    // needs it - the enforcement check, the countdown the browser draws, the
    // report - asks here rather than recomputing the arithmetic, so they cannot
    // drift apart. Nothing below is stored: a persisted deadline would go stale
    // the moment the interview's duration was edited.

    /**
     * When this session's time runs out: the moment the candidate actually
     * started, plus the interview's configured duration.
     *
     * <p>Requires the {@code interview} association, so call it inside a
     * transaction - open-in-view is off.
     */
    public Instant deadline() {
        return startedAt.plus(Duration.ofMinutes(interview.getDurationMinutes()));
    }

    /** True once the configured duration has run out. */
    public boolean hasExpiredAt(Instant now) {
        return !now.isBefore(deadline());
    }

    /** Seconds left, floored at zero so a late request never reads as negative. */
    public long remainingSecondsAt(Instant now) {
        long remaining = Duration.between(now, deadline()).toSeconds();
        return Math.max(0, remaining);
    }

    @PrePersist
    void onCreate() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }
}
