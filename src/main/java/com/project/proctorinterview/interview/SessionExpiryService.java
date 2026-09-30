package com.project.proctorinterview.interview;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.common.Enums.CompletionReason;
import com.project.proctorinterview.common.Enums.SessionStatus;

/**
 * Closes a session whose allowed time has run out.
 *
 * <p>This lives in its own bean for a specific and easily-missed reason. The
 * caller that discovers the expiry - {@link ExamService#requireActiveSession} -
 * marks the session and then <b>throws</b> to refuse the request. That
 * exception rolls its transaction back, and the expiry would be rolled back with
 * it, leaving the session ACTIVE for ever while every request it made was
 * refused.
 *
 * <p>{@code REQUIRES_NEW} commits the expiry in its own transaction so it
 * survives that rollback. Spring applies {@code @Transactional} through a proxy,
 * so a self-invoked private method would silently run in the caller's
 * transaction instead - which is exactly the failure this class exists to
 * prevent, and the same reason {@code BulkRowScheduler} is a separate bean.
 */
@Service
public class SessionExpiryService {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryService.class);

    private final InterviewSessionRepository sessions;

    public SessionExpiryService(InterviewSessionRepository sessions) {
        this.sessions = sessions;
    }

    /**
     * Marks the session as finished because its time ran out.
     *
     * <p>Re-reads the session inside the new transaction rather than trusting
     * the instance handed in, which belongs to a transaction that is about to
     * roll back.
     *
     * <p>Only the session is closed. The report is produced by the existing
     * completion path, which is idempotent and runs when the browser calls
     * {@code /complete} - so this never duplicates report generation, and
     * answers already submitted are untouched.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expire(Long sessionId) {
        InterviewSession session = sessions.findById(sessionId).orElse(null);
        if (session == null || session.getStatus() != SessionStatus.ACTIVE) {
            // Another request got there first; expiring twice is not an error.
            return;
        }

        session.setStatus(SessionStatus.COMPLETED);
        // The deadline, not "now": a candidate who closed their laptop at the
        // 30 minute mark and came back an hour later did not sit a 90 minute
        // interview.
        session.setEndedAt(session.deadline());
        session.setCompletionReason(CompletionReason.TIME_EXPIRED);
        sessions.save(session);

        log.info("Session {} expired at its {} minute deadline", sessionId,
                session.getInterview().getDurationMinutes());
    }

    /** Whether this session has run past its deadline. */
    @Transactional(readOnly = true)
    public boolean hasExpired(Long sessionId, Instant now) {
        return sessions.findById(sessionId)
                .filter(s -> s.getStatus() == SessionStatus.ACTIVE)
                .map(s -> s.hasExpiredAt(now))
                .orElse(false);
    }
}
