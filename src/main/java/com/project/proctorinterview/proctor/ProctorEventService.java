package com.project.proctorinterview.proctor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.common.Enums.ProctorEventType;
import com.project.proctorinterview.interview.InterviewSession;
import com.project.proctorinterview.interview.InterviewSessionRepository;
import com.project.proctorinterview.proctor.dto.ProctorEventDtos.MonitoringStatusRequest;
import com.project.proctorinterview.proctor.dto.ProctorEventDtos.ProctorEventBatch;
import com.project.proctorinterview.proctor.dto.ProctorEventDtos.ProctorEventBatchResponse;
import com.project.proctorinterview.proctor.dto.ProctorEventDtos.ProctorEventRequest;
import com.project.proctorinterview.proctor.dto.ProctorEventDtos.ProctorEventSummary;

/**
 * Stores proctoring observations.
 *
 * <p>Two properties matter here. First, ingest is <b>idempotent</b>: the browser
 * retries a failed batch, and a duplicate {@code clientEventId} is skipped
 * rather than stored twice or rejected. Second, the server records observations
 * as-is and draws no conclusions - interpretation belongs to the report, and
 * even there an event is an observation, not proof of misconduct.
 */
@Service
@Transactional
public class ProctorEventService {

    private static final Logger log = LoggerFactory.getLogger(ProctorEventService.class);

    private final ProctorEventRepository events;
    /** Monitoring coverage is recorded on the session, alongside its events. */
    private final InterviewSessionRepository sessions;

    public ProctorEventService(ProctorEventRepository events, InterviewSessionRepository sessions) {
        this.events = events;
        this.sessions = sessions;
    }

    public ProctorEventBatchResponse ingest(InterviewSession session, ProctorEventBatch batch) {
        List<String> incomingIds = batch.events().stream()
                .map(ProctorEventRequest::clientEventId)
                .toList();

        // One query for the whole batch rather than one per event. A batch is
        // up to 200 events, so this is the difference between one round trip
        // and two hundred.
        Set<String> alreadyStored = events.findClientEventIdsIn(incomingIds);

        List<ProctorEvent> toSave = new ArrayList<>();
        // Guards against the same id appearing twice inside ONE batch, which
        // the pre-read cannot catch because neither copy is stored yet.
        Set<String> seenInBatch = new HashSet<>();
        int duplicates = 0;

        for (ProctorEventRequest request : batch.events()) {
            if (alreadyStored.contains(request.clientEventId())
                    || !seenInBatch.add(request.clientEventId())) {
                duplicates++;
                continue;
            }
            toSave.add(toEntity(session, request));
        }

        int accepted = toSave.size();
        if (!toSave.isEmpty()) {
            try {
                events.saveAll(toSave);
                // Force the inserts now, inside this try, rather than letting
                // them fire at commit where nothing can catch them.
                events.flush();
            } catch (DataIntegrityViolationException e) {
                // The read above is not atomic with the write below: two
                // batches carrying the same id can both pass it, and the
                // uk_proctor_events_client_event_id constraint is what actually
                // guarantees an event is stored once. Losing that race is a
                // duplicate - exactly what the caller was promised - not an
                // error, and certainly not a reason to reject a whole batch of
                // observations that are mostly new.
                //
                // The transaction is now unusable, so the retry has to happen
                // in a fresh one. Same proxy reason as BulkRowScheduler: it is
                // a separate bean.
                log.debug("Batch insert for session {} hit a unique constraint; retrying per event",
                        session.getId());
                // Counts from this attempt are deliberately not carried over:
                // the retry recomputes them against what is actually stored,
                // which is the only trustworthy answer after a rollback.
                throw new ProctorEventBatchConflict();
            }
        }
        if (duplicates > 0) {
            log.debug("Ignored {} duplicate proctor event(s) for session {}", duplicates, session.getId());
        }

        return new ProctorEventBatchResponse(accepted, duplicates, batch.events().size());
    }

    /**
     * Signals that a batch lost a race on the idempotency constraint.
     *
     * <p>Thrown rather than handled in place because the transaction is already
     * doomed at that point - a rolled-back transaction cannot be salvaged from
     * inside itself. {@link ProctorEventRetryService} redoes the work one event
     * at a time in fresh transactions. Same reasoning as the report-completion
     * race in {@code InterviewSessionController}.
     */
    public static class ProctorEventBatchConflict extends RuntimeException {
        ProctorEventBatchConflict() {
            super("Proctor event batch hit the idempotency constraint");
        }
    }

    ProctorEvent toEntity(InterviewSession session, ProctorEventRequest request) {
        ProctorEvent event = new ProctorEvent();
        event.setSession(session);
        event.setEventType(request.type());
        event.setStartTime(request.startTime());
        event.setEndTime(request.endTime());
        event.setDurationMs(resolveDuration(request));
        event.setConfidence(request.confidence());
        event.setDetails(request.details() == null ? new LinkedHashMap<>() : request.details());
        event.setClientEventId(request.clientEventId());
        return event;
    }

    /**
     * Trusts the client's duration when supplied, otherwise derives it. The
     * client is authoritative because it measured the event as it happened;
     * deriving is only a fallback so a duration is never silently missing.
     */
    private static Long resolveDuration(ProctorEventRequest request) {
        if (request.durationMs() != null) {
            return request.durationMs();
        }
        if (request.startTime() != null && request.endTime() != null) {
            return Math.max(0, request.endTime().toEpochMilli() - request.startTime().toEpochMilli());
        }
        return null;
    }

    /**
     * Records what the browser reports about its <b>own</b> monitoring.
     *
     * <p>Nothing here is an observation about the candidate, and nothing here
     * can change a score or a recommendation. It exists so the report can tell
     * "nothing happened" from "nothing was watched".
     *
     * <p>Both fields are folded rather than overwritten, so this is safe to
     * call repeatedly and in any order:
     *
     * <ul>
     *   <li><b>ready is sticky-true.</b> Once the detectors have loaded, a
     *       later report cannot un-say it - that fact happened.</li>
     *   <li><b>droppedEvents takes the maximum.</b> The browser sends a
     *       cumulative total, so a report arriving out of order can never
     *       erase a loss that was already recorded.</li>
     * </ul>
     *
     * <p>The note is kept only while monitoring has not succeeded: once the
     * detectors load, a stale failure message would be actively misleading.
     */
    public InterviewSession recordMonitoringStatus(InterviewSession session,
            MonitoringStatusRequest status) {

        if (Boolean.TRUE.equals(status.ready())) {
            session.setMonitoringReady(true);
            session.setMonitoringNote(null);
        } else if (status.note() != null && !status.note().isBlank()
                && !Boolean.TRUE.equals(session.getMonitoringReady())) {
            session.setMonitoringNote(status.note().trim());
        }

        session.setMonitoringDroppedEvents(
                Math.max(session.getMonitoringDroppedEvents(), status.droppedEvents()));

        if (session.getMonitoringDroppedEvents() > 0 || !Boolean.TRUE.equals(session.getMonitoringReady())) {
            log.info("Session {} monitoring: ready={} dropped={}",
                    session.getId(), session.getMonitoringReady(), session.getMonitoringDroppedEvents());
        }
        return sessions.save(session);
    }

    @Transactional(readOnly = true)
    public List<ProctorEvent> timeline(Long sessionId) {
        return events.findBySessionIdOrderByStartTimeAsc(sessionId);
    }

    /** How many observations this session actually has stored. */
    @Transactional(readOnly = true)
    public long observationCount(Long sessionId) {
        return events.countBySessionId(sessionId);
    }

    /** Per-type counts and total observed duration, in a stable display order. */
    @Transactional(readOnly = true)
    public List<ProctorEventSummary> summarise(Long sessionId) {
        List<ProctorEvent> all = events.findBySessionIdOrderByStartTimeAsc(sessionId);

        Map<ProctorEventType, long[]> tally = new LinkedHashMap<>();
        for (ProctorEventType type : ProctorEventType.values()) {
            tally.put(type, new long[] { 0, 0 });
        }
        for (ProctorEvent event : all) {
            long[] slot = tally.get(event.getEventType());
            slot[0]++;
            slot[1] += event.getDurationMs() == null ? 0 : event.getDurationMs();
        }

        return tally.entrySet().stream()
                .map(e -> new ProctorEventSummary(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    /** Compact {@code type -> count} map stored on the report. */
    @Transactional(readOnly = true)
    public Map<String, Object> countsByType(Long sessionId) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (ProctorEventSummary summary : summarise(sessionId)) {
            counts.put(summary.type().name(), summary.count());
        }
        return counts;
    }
}
