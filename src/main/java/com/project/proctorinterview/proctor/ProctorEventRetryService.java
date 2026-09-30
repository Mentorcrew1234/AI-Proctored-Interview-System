package com.project.proctorinterview.proctor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.interview.InterviewSession;
import com.project.proctorinterview.interview.InterviewSessionRepository;
import com.project.proctorinterview.proctor.dto.ProctorEventDtos.ProctorEventBatch;
import com.project.proctorinterview.proctor.dto.ProctorEventDtos.ProctorEventBatchResponse;
import com.project.proctorinterview.proctor.dto.ProctorEventDtos.ProctorEventRequest;

/**
 * Re-ingests a batch one event at a time, after the fast path lost a race on
 * the idempotency constraint.
 *
 * <p>Why a separate bean, and not a private method on {@link
 * ProctorEventService}: {@code @Transactional} is proxy-based, so
 * {@code REQUIRES_NEW} is ignored on self-invocation. This is the same reason
 * {@code BulkRowScheduler} and {@code BulkInterviewRowMutator} exist. It matters
 * more here than usual, because the transaction that called us is already
 * rolled back - a fresh one is not an optimisation, it is the only way any of
 * this work can be committed.
 *
 * <p>Why per event rather than retrying the batch: a retried batch could lose
 * the same race again, and again. One event per transaction means the worst
 * case is one event conceding to the copy that beat it, which is precisely the
 * outcome the idempotency contract promises anyway.
 *
 * <p>This path is rare by design. The browser's uploader serialises its own
 * requests, so reaching it needs two tabs, a resumed session, or a genuinely
 * overlapping retry. It exists so that when it does happen the answer is
 * {@code {accepted, duplicates}} rather than a 500 that discards a whole batch
 * of observations.
 */
@Service
public class ProctorEventRetryService {

    private static final Logger log = LoggerFactory.getLogger(ProctorEventRetryService.class);

    private final ProctorEventRepository events;
    private final ProctorEventService eventService;
    private final InterviewSessionRepository sessions;

    public ProctorEventRetryService(ProctorEventRepository events, ProctorEventService eventService,
            InterviewSessionRepository sessions) {
        this.events = events;
        this.eventService = eventService;
        this.sessions = sessions;
    }

    public ProctorEventBatchResponse ingestOneByOne(Long sessionId, ProctorEventBatch batch) {
        int accepted = 0;
        int duplicates = 0;

        for (ProctorEventRequest request : batch.events()) {
            if (saveOne(sessionId, request)) {
                accepted++;
            } else {
                duplicates++;
            }
        }

        log.info("Recovered proctor batch for session {} event by event: {} accepted, {} duplicate",
                sessionId, accepted, duplicates);
        return new ProctorEventBatchResponse(accepted, duplicates, batch.events().size());
    }

    /**
     * @return true if this call stored the event, false if it was already there
     *         - whether the pre-check saw it or the constraint did.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean saveOne(Long sessionId, ProctorEventRequest request) {
        if (events.existsByClientEventId(request.clientEventId())) {
            return false;
        }
        try {
            // The session is reloaded inside THIS transaction: an entity from
            // the rolled-back one cannot be attached here.
            InterviewSession session = sessions.findById(sessionId).orElse(null);
            if (session == null) {
                return false;
            }
            events.save(eventService.toEntity(session, request));
            events.flush();
            return true;
        } catch (DataIntegrityViolationException e) {
            // Lost the race even at this granularity. The other writer stored
            // an identical event, so the observation is not lost - which is the
            // whole promise of the client-generated id.
            return false;
        }
    }
}
