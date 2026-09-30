package com.project.proctorinterview.proctor.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.project.proctorinterview.common.Enums.ProctorEventType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class ProctorEventDtos {

    private ProctorEventDtos() {
    }

    /**
     * One completed observation from the browser event engine.
     *
     * <p>Only closed, debounced events are uploaded - never raw frames and never
     * per-frame detections.
     */
    public record ProctorEventRequest(
            /** Client-generated UUID. Makes a retried batch idempotent. */
            @NotNull(message = "clientEventId is required")
            @Size(min = 8, max = 64, message = "clientEventId must be 8-64 characters")
            String clientEventId,

            @NotNull(message = "type is required") ProctorEventType type,

            @NotNull(message = "startTime is required") Instant startTime,

            /** Null only if the session ended while the event was still open. */
            Instant endTime,

            @PositiveOrZero(message = "durationMs cannot be negative") Long durationMs,

            @DecimalMin(value = "0.0", message = "confidence must be between 0 and 1")
            @DecimalMax(value = "1.0", message = "confidence must be between 0 and 1")
            BigDecimal confidence,

            /** Optional extras: head direction, face count, bounding box. */
            Map<String, Object> details) {
    }

    /** Events are uploaded in batches to keep the request count low. */
    public record ProctorEventBatch(
            @NotEmpty(message = "At least one event is required")
            @Size(max = 200, message = "At most 200 events per batch")
            @Valid List<ProctorEventRequest> events) {
    }

    /**
     * @param accepted  newly stored events
     * @param duplicates events already stored, ignored rather than errored so a
     *                   client retry is safe
     */
    public record ProctorEventBatchResponse(int accepted, int duplicates, int total) {
    }

    /** Per-type counts plus total time, used by the report and the live UI. */
    public record ProctorEventSummary(
            ProctorEventType type, long count, long totalDurationMs) {
    }

    /**
     * The browser reporting on its own monitoring, not on the candidate.
     *
     * <p>Sent when the detectors load or fail to load, whenever a batch of
     * events is abandoned, and once more as the interview ends. Without it a
     * session with no observations is indistinguishable from a session nobody
     * watched.
     */
    public record MonitoringStatusRequest(
            /** True once both detection models have loaded successfully. */
            @NotNull(message = "ready is required") Boolean ready,

            /**
             * Cumulative count of events the browser generated but gave up on
             * uploading. Cumulative rather than incremental so a lost report
             * costs nothing - the next one carries the same total.
             */
            @NotNull(message = "droppedEvents is required")
            @PositiveOrZero(message = "droppedEvents cannot be negative")
            Integer droppedEvents,

            /** Why monitoring failed, in the browser's own words. Optional. */
            @Size(max = 300, message = "note must be at most 300 characters")
            String note) {
    }

    /**
     * @param coverage the derived verdict, echoed back so the caller can see
     *                 what the server concluded from what it just sent
     */
    public record MonitoringStatusResponse(
            Long sessionId, boolean ready, int droppedEvents, String coverage) {
    }
}
