package com.project.proctorinterview.proctor;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.interview.ExamService;
import com.project.proctorinterview.interview.InterviewSession;
import com.project.proctorinterview.proctor.ProctorEventService.ProctorEventBatchConflict;
import com.project.proctorinterview.proctor.dto.ProctorEventDtos.MonitoringStatusRequest;
import com.project.proctorinterview.proctor.dto.ProctorEventDtos.MonitoringStatusResponse;
import com.project.proctorinterview.proctor.dto.ProctorEventDtos.ProctorEventBatch;
import com.project.proctorinterview.proctor.dto.ProctorEventDtos.ProctorEventBatchResponse;
import com.project.proctorinterview.proctor.dto.ProctorEventDtos.ProctorEventSummary;

import jakarta.validation.Valid;

/**
 * Receives proctoring observations from the browser.
 *
 * <p>This endpoint takes <b>events only</b>. No video, no images, and no
 * per-frame detections are ever uploaded - the detection runs entirely in the
 * candidate's browser and only completed, debounced events reach the server.
 */
@RestController
@RequestMapping("/api/interview-sessions/{sessionId}")
public class ProctorEventController {

    private final ProctorEventService proctorEvents;
    private final ExamService examService;
    /** Separate bean: REQUIRES_NEW is ignored on self-invocation. */
    private final ProctorEventRetryService retryService;

    public ProctorEventController(ProctorEventService proctorEvents, ExamService examService,
            ProctorEventRetryService retryService) {
        this.proctorEvents = proctorEvents;
        this.examService = examService;
        this.retryService = retryService;
    }

    /**
     * Batch ingest. Safe to retry: an event whose {@code clientEventId} is
     * already stored is counted as a duplicate and skipped, not stored twice.
     */
    @PostMapping("/proctor-events")
    public ProctorEventBatchResponse upload(@PathVariable Long sessionId,
            @Valid @RequestBody ProctorEventBatch batch,
            @AuthenticationPrincipal AppUserDetails me) {

        InterviewSession session = examService.requireOwnedSession(sessionId, me.getId());
        try {
            return proctorEvents.ingest(session, batch);
        } catch (ProctorEventBatchConflict e) {
            // Two overlapping batches carried the same client id, so the fast
            // path lost the race on uk_proctor_events_client_event_id. The
            // unique constraint is what actually enforces idempotency; the
            // pre-read is only an optimisation, and losing to it is a
            // duplicate, not an error.
            //
            // Its transaction is already rolled back and cannot be salvaged
            // from inside itself, so the work is redone one event at a time in
            // fresh transactions. Rejecting the whole batch instead would
            // discard observations that were mostly new.
            return retryService.ingestOneByOne(sessionId, batch);
        }
    }

    /**
     * The browser reporting on its own monitoring.
     *
     * <p>Not an observation about the candidate: it says whether the detectors
     * loaded and whether any events were lost on the way here. Without it, a
     * session with no observations would be indistinguishable from a session
     * that was never watched, and the report would read as clean either way.
     *
     * <p>Uses the owned-session check rather than the active-session one on
     * purpose. The last and most important of these reports is sent as the
     * interview closes, which is exactly when the active check would start
     * refusing - and a coverage report refused is a coverage gap hidden.
     */
    @PostMapping("/monitoring-status")
    public MonitoringStatusResponse monitoringStatus(@PathVariable Long sessionId,
            @Valid @RequestBody MonitoringStatusRequest request,
            @AuthenticationPrincipal AppUserDetails me) {

        InterviewSession session = examService.requireOwnedSession(sessionId, me.getId());
        InterviewSession saved = proctorEvents.recordMonitoringStatus(session, request);

        return new MonitoringStatusResponse(
                sessionId,
                Boolean.TRUE.equals(saved.getMonitoringReady()),
                saved.getMonitoringDroppedEvents(),
                saved.coverageWith(proctorEvents.observationCount(sessionId)).name());
    }

    /** Live counts, used by the candidate's on-screen monitoring panel. */
    @PostMapping("/proctor-events/summary")
    public List<ProctorEventSummary> summary(@PathVariable Long sessionId,
            @AuthenticationPrincipal AppUserDetails me) {
        examService.requireOwnedSession(sessionId, me.getId());
        return proctorEvents.summarise(sessionId);
    }
}
