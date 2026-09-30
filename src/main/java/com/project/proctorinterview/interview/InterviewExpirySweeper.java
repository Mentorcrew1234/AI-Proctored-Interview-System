package com.project.proctorinterview.interview;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.common.Enums.SessionStatus;
import com.project.proctorinterview.report.ReportService;

/**
 * Closes interviews whose time ran out while nobody was looking.
 *
 * <p>Every other expiry path needs the candidate's browser: the countdown
 * finishing the interview, or a later request tripping the deadline check in
 * {@code ExamService.requireActiveSession}. A candidate who simply closes the
 * laptop at the 30 minute mark makes neither happen, and without this the
 * session would sit ACTIVE and the interview IN_PROGRESS for ever - never
 * finalised, never reported, and still showing as running to the recruiter.
 *
 * <p>That is the difference between "the interview cannot be <em>used</em>
 * after its deadline", which the request-time check already guaranteed, and
 * "the interview does not <em>remain active</em> after its deadline", which is
 * what this adds.
 *
 * <p><b>This is not a second deadline system.</b> It asks each session the same
 * {@code hasExpiredAt} the request path asks, and finalises through the same
 * {@link ReportService#completeSession} the Finish button uses - so an interview
 * closed here is indistinguishable from one closed any other way, and the
 * reason is decided in the one place that decides it.
 */
@Component
public class InterviewExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(InterviewExpirySweeper.class);

    private final InterviewSessionRepository sessions;
    private final ReportService reportService;

    public InterviewExpirySweeper(InterviewSessionRepository sessions, ReportService reportService) {
        this.sessions = sessions;
        this.reportService = reportService;
    }

    /**
     * Finalises every running session past its deadline.
     *
     * <p>Runs a minute after the last run finished rather than on a fixed rate,
     * so a slow sweep can never overlap itself. A minute is fine granularity
     * here: the candidate's own browser closes the interview the moment the
     * countdown ends, and the server refuses work past the deadline regardless.
     * This is the safety net for the abandoned case, not the primary mechanism.
     *
     * <p>Deliberately not transactional as a whole: each interview is finalised
     * independently so one failure cannot roll back the rest of the sweep, the
     * same reasoning as the per-row bulk schedulers.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void closeExpiredInterviews() {
        List<Long> expired = expiredSessionIds();
        if (expired.isEmpty()) {
            return;
        }

        int closed = 0;
        for (Long sessionId : expired) {
            try {
                finaliseOne(sessionId);
                closed++;
            } catch (Exception e) {
                // Logged and skipped: a single unfinishable session must not
                // stop the others from being closed. It will be retried on the
                // next sweep.
                log.warn("Could not finalise expired session {}: {}", sessionId, e.toString());
            }
        }

        if (closed > 0) {
            log.info("Expiry sweep finalised {} interview(s) that ran past their deadline", closed);
        }
    }

    /**
     * Ids of running sessions whose time has run out.
     *
     * <p>Read in its own transaction and reduced to ids, so nothing lazy escapes
     * into the loop that finalises them.
     */
    @Transactional(readOnly = true)
    public List<Long> expiredSessionIds() {
        Instant now = Instant.now();
        return sessions.findRunningWithInterview(SessionStatus.ACTIVE).stream()
                .filter(session -> session.hasExpiredAt(now))
                .map(InterviewSession::getId)
                .toList();
    }

    /**
     * Finalises one session through the ordinary completion path.
     *
     * <p>Delegates to {@code ReportService} rather than doing the work here.
     * That is not indirection for its own sake: {@code @Transactional} is applied
     * by a proxy, so a transactional method called from
     * {@link #closeExpiredInterviews} on <em>this</em> bean would silently run
     * with no transaction at all - and the session it loaded would be detached
     * by the time completion tried to read its interview. Crossing a bean
     * boundary is what gives the work a real transaction.
     *
     * <p>{@code completeSessionById} is idempotent and works out the reason
     * itself - it sees the deadline has passed and records TIME_EXPIRED - so
     * this neither duplicates that logic nor races the candidate's own Finish.
     */
    void finaliseOne(Long sessionId) {
        reportService.completeSessionById(sessionId);
    }
}
