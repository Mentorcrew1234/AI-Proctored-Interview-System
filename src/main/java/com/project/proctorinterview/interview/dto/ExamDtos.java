package com.project.proctorinterview.interview.dto;

import java.time.Instant;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.SessionStatus;

public final class ExamDtos {

    private ExamDtos() {
    }

    /** What the candidate's browser needs before starting. */
    public record ExamInfo(
            String inviteToken,
            String candidateName,
            String recruiterName,
            String domain,
            InterviewType interviewType,
            CandidateType candidateType,
            Integer experienceYears,
            int questionCount,
            /** Allocated test duration; the clock starts when the candidate starts. */
            int durationMinutes,
            String language,
            Instant scheduledAt,
            String scheduledAtText,
            InterviewStatus status,
            /** Non-null if the interview was already started (supports a reload). */
            Long sessionId,
            SessionStatus sessionStatus,
            boolean resultVisibleToCandidate,
            /**
             * NORMAL or DEMO, from {@code app.interview.mode}.
             *
             * <p>Decides what the exam screen draws, nothing else. It is not a
             * permission and carries no privileged data: DEMO only un-hides
             * values the candidate's own browser computed from their own
             * camera, so a candidate who tampered with the client to force the
             * panel on would learn nothing they did not already have.
             */
            String interviewMode) {
    }

    /** Device-check outcome, recorded when the session opens. */
    public record StartExamRequest(
            boolean cameraGranted,
            boolean micGranted,
            String browserInfo) {
    }

    /**
     * The started (or resumed) session.
     *
     * <p>{@code remainingSeconds} is what the countdown actually runs on. It is
     * a duration computed by the server, not an absolute deadline the browser
     * has to compare against its own clock - so a candidate whose machine clock
     * is wrong, or in another timezone, still sees the right number, and there
     * is nothing to gain by changing it. {@code startedAt} and {@code deadline}
     * are sent for display only.
     */
    public record StartExamResponse(
            Long sessionId,
            int questionCount,
            Instant startedAt,
            int durationMinutes,
            Instant deadline,
            long remainingSeconds) {
    }
}
