package com.project.proctorinterview.interview.dto;

import java.time.Instant;
import java.util.List;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;

public final class CandidateDtos {

    private CandidateDtos() {
    }

    /** One interview as shown on the candidate's own dashboard. */
    public record CandidateInterviewRow(
            Long interviewId,
            String displayName,
            Instant scheduledAt,
            String scheduledDateText,
            String scheduledTimeText,
            String domain,
            InterviewType interviewType,
            CandidateType candidateType,
            Integer experienceYears,
            String language,
            int questionCount,
            InterviewStatus status,
            String inviteToken,
            /** Whether the existing exam flow will currently accept this interview. */
            boolean startable,
            /** Non-null once the candidate has opened this interview at least once. */
            Long sessionId,
            /** True only when completed and the recruiter made the result visible. */
            boolean reportViewable) {
    }

    /**
     * The signed-in candidate's own interviews, grouped the way the dashboard
     * displays them. Every row here belongs to one candidate - see
     * {@link com.project.proctorinterview.interview.CandidateInterviewService}.
     */
    public record CandidateDashboardView(
            List<CandidateInterviewRow> today,
            List<CandidateInterviewRow> upcoming,
            List<CandidateInterviewRow> inProgress,
            List<CandidateInterviewRow> completed,
            List<CandidateInterviewRow> cancelled,
            int totalCount) {

        public boolean isEmpty() {
            return totalCount == 0;
        }

        /**
         * The one interview the candidate most likely came here for, or null if
         * there is nothing to attend.
         *
         * <p>Resume before start, and today before later: an interview already
         * in progress is the most urgent thing on the page, then one scheduled
         * for today, then the next one due. Derived rather than stored - the
         * lists are already ordered by the service, so this only picks which
         * one the dashboard leads with.
         */
        public CandidateInterviewRow nextUp() {
            if (!inProgress.isEmpty()) {
                return inProgress.get(0);
            }
            if (!today.isEmpty()) {
                return today.get(0);
            }
            if (!upcoming.isEmpty()) {
                return upcoming.get(0);
            }
            return null;
        }
    }
}
