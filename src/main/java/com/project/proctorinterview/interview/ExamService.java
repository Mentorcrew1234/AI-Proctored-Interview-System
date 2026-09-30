package com.project.proctorinterview.interview;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.SessionStatus;
import com.project.proctorinterview.config.InterviewModeProperties;
import com.project.proctorinterview.interview.dto.ExamDtos.ExamInfo;
import com.project.proctorinterview.interview.dto.ExamDtos.StartExamRequest;
import com.project.proctorinterview.interview.dto.ExamDtos.StartExamResponse;

/**
 * The candidate's side of an interview: opening an invite link and starting the
 * session that everything else hangs off.
 *
 * <p>Holding the invite token is not sufficient on its own - the signed-in
 * candidate must also be the one the interview was scheduled for. A leaked link
 * therefore does not let anyone else sit the interview.
 */
@Service
@Transactional
public class ExamService {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    private final InterviewRepository interviews;
    private final InterviewSessionRepository sessions;
    /**
     * Closes expired sessions in their own transaction. A separate bean on
     * purpose - see SessionExpiryService.
     */
    private final SessionExpiryService expiryService;
    /** Presentation only - see InterviewModeProperties. Never gates the flow. */
    private final InterviewModeProperties interviewMode;

    public ExamService(InterviewRepository interviews, InterviewSessionRepository sessions,
            SessionExpiryService expiryService, InterviewModeProperties interviewMode) {
        this.interviews = interviews;
        this.sessions = sessions;
        this.expiryService = expiryService;
        this.interviewMode = interviewMode;
    }

    /** Resolves the invite token and checks it really belongs to this candidate. */
    @Transactional(readOnly = true)
    public Interview requireInterview(String inviteToken, Long candidateId) {
        Interview interview = interviews.findByInviteToken(inviteToken)
                .orElseThrow(() -> ApiException.notFound("Interview link"));

        if (!interview.getCandidate().getId().equals(candidateId)) {
            // Deliberately the same shape as an unknown token: holding someone
            // else's link should not confirm that the link is real.
            throw ApiException.notFound("Interview link");
        }
        return interview;
    }

    @Transactional(readOnly = true)
    public ExamInfo describe(String inviteToken, Long candidateId) {
        Interview interview = requireInterview(inviteToken, candidateId);
        InterviewSession session = sessions.findByInterviewId(interview.getId()).orElse(null);

        return new ExamInfo(
                interview.getInviteToken(),
                interview.getCandidate().getFullName(),
                interview.getRecruiter().getFullName(),
                interview.getDomain(),
                interview.getInterviewType(),
                interview.getCandidateType(),
                interview.getExperienceYears(),
                interview.getQuestionCount(),
                interview.getDurationMinutes(),
                interview.getLanguage().name(),
                interview.getScheduledAt(),
                DISPLAY_FORMAT.format(interview.getScheduledAt()),
                interview.getStatus(),
                session == null ? null : session.getId(),
                session == null ? null : session.getStatus(),
                interview.isResultVisibleToCandidate(),
                // Always a valid mode name: an unrecognised configuration value
                // has already been resolved to NORMAL by this point.
                interviewMode.resolvedName());
    }

    /**
     * Starts the interview, or returns the existing session if the candidate
     * reloaded the page mid-interview.
     */
    public StartExamResponse start(String inviteToken, Long candidateId, StartExamRequest request) {
        Interview interview = requireInterview(inviteToken, candidateId);

        if (interview.getStatus() == InterviewStatus.CANCELLED) {
            throw ApiException.conflict("This interview was cancelled");
        }
        if (interview.getStatus() == InterviewStatus.COMPLETED) {
            throw ApiException.conflict("This interview has already been completed");
        }

        InterviewSession existing = sessions.findByInterviewId(interview.getId()).orElse(null);
        if (existing != null) {
            if (existing.getStatus() == SessionStatus.COMPLETED) {
                throw ApiException.conflict("This interview has already been completed");
            }
            // Resuming after a reload - keep the original session, its events and
            // crucially its ORIGINAL start time, so the countdown picks up where
            // it left off instead of restarting.
            if (existing.hasExpiredAt(Instant.now())) {
                expiryService.expire(existing.getId());
                throw ApiException.conflict(
                        "The time allowed for this interview has run out. "
                                + "Answers you already submitted have been saved.");
            }
            return startResponse(existing, interview);
        }

        if (!request.cameraGranted()) {
            throw ApiException.badRequest("Camera access is required to start the interview");
        }

        InterviewSession session = new InterviewSession();
        session.setInterview(interview);
        session.setStatus(SessionStatus.ACTIVE);
        session.setCameraGranted(request.cameraGranted());
        session.setMicGranted(request.micGranted());
        session.setBrowserInfo(trim(request.browserInfo()));
        // "Not confirmed yet", explicitly - not "unknown". A session that ends
        // still false is one whose detectors never reported in, which is a
        // different statement from a session that predates coverage recording
        // and reads as null. See InterviewSession.coverageWith.
        session.setMonitoringReady(false);
        sessions.save(session);

        interview.setStatus(InterviewStatus.IN_PROGRESS);
        interviews.save(interview);

        return startResponse(session, interview);
    }

    /** One shape for both a fresh start and a resume, so they cannot diverge. */
    private StartExamResponse startResponse(InterviewSession session, Interview interview) {
        return new StartExamResponse(
                session.getId(),
                interview.getQuestionCount(),
                session.getStartedAt(),
                interview.getDurationMinutes(),
                session.deadline(),
                session.remainingSecondsAt(Instant.now()));
    }

    /**
     * Loads a session and verifies the caller owns it. Used by every endpoint
     * that writes into a session (proctor events, answers, completion).
     */
    @Transactional(readOnly = true)
    public InterviewSession requireOwnedSession(Long sessionId, Long candidateId) {
        InterviewSession session = sessions.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Interview session"));

        if (!session.getInterview().getCandidate().getId().equals(candidateId)) {
            throw ApiException.forbidden("This interview session belongs to another candidate");
        }
        return session;
    }

    /**
     * As above, and additionally rejects a session that has finished <em>or run
     * out of time</em>.
     *
     * <p>This is where the duration stops being a display value and becomes a
     * rule. Every write into a session goes through here, so the browser's
     * countdown is only ever a convenience - if it says a minute remains but the
     * server disagrees, the server wins.
     *
     * <p>An expired session is finalised <b>before</b> the caller is refused, so
     * a candidate who kept a stale tab open does not leave a session stuck in
     * ACTIVE for ever. Already-submitted answers are untouched: finalising only
     * closes the session and records why.
     */
    public InterviewSession requireActiveSession(Long sessionId, Long candidateId) {
        InterviewSession session = requireOwnedSession(sessionId, candidateId);
        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw ApiException.conflict("This interview session is no longer active");
        }
        if (session.hasExpiredAt(Instant.now())) {
            expiryService.expire(session.getId());
            throw ApiException.conflict(
                    "The time allowed for this interview has run out. "
                            + "Answers you already submitted have been saved.");
        }
        return session;
    }

    /**
     * Whether this session's time has already run out, without changing
     * anything. For read paths that must not have a side effect.
     */
    @Transactional(readOnly = true)
    public boolean hasExpired(Long sessionId, Long candidateId) {
        InterviewSession session = requireOwnedSession(sessionId, candidateId);
        return session.getStatus() == SessionStatus.ACTIVE && session.hasExpiredAt(Instant.now());
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 400 ? value : value.substring(0, 400);
    }
}
