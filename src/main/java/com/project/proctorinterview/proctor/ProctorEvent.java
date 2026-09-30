package com.project.proctorinterview.proctor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.project.proctorinterview.common.Enums.ProctorEventType;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A single proctoring observation with a start, an end and a duration.
 *
 * <p>Only completed, debounced events reach this table — the browser-side event
 * engine collapses continuous detection into one row. Video frames are never
 * uploaded.
 *
 * <p>An event is an <em>observation</em>, not an accusation.
 */
@Entity
@Table(name = "proctor_events")
@Getter
@Setter
public class ProctorEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private InterviewSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private ProctorEventType eventType;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    /** Null while the event is still open (e.g. the session ended mid-event). */
    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** Detector confidence 0.000-1.000, where the detector reports one. */
    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    /** Free-form extras: head direction, face count, bounding box. */
    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "TEXT")
    private Map<String, Object> details = new LinkedHashMap<>();

    /** Client-generated UUID. Makes event upload idempotent on retry. */
    @Column(name = "client_event_id", nullable = false, unique = true, length = 64)
    private String clientEventId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
