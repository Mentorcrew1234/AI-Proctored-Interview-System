package com.project.proctorinterview.report.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.CompletionReason;
import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.EvaluatorType;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.MonitoringCoverage;
import com.project.proctorinterview.common.Enums.ProctorEventType;
import com.project.proctorinterview.common.Enums.Recommendation;
import com.project.proctorinterview.common.Enums.ReviewDecision;

public final class ReportDtos {

    private ReportDtos() {
    }

    /** One question with its answer and evaluation, for the breakdown table. */
    public record QuestionResult(
            int sequenceNo,
            String questionText,
            Difficulty difficulty,
            List<String> expectedPoints,
            boolean answered,
            String rawTranscript,
            String cleanTranscript,
            int fillerCount,
            int wordCount,
            int durationSeconds,
            Integer technicalScore,
            Integer relevanceScore,
            Integer problemSolvingScore,
            Integer communicationScore,
            Integer overallScore,
            String feedback,
            List<String> strengths,
            List<String> weaknesses,
            /** LLM or FALLBACK - the report states how it was graded. */
            EvaluatorType evaluator,
            String modelName) {
    }

    /** A proctoring observation for the timeline. Never labelled as cheating. */
    public record ObservationRow(
            ProctorEventType type,
            Instant startTime,
            String startTimeText,
            Long durationMs,
            String durationText,
            java.math.BigDecimal confidence,
            Map<String, Object> details) {
    }

    public record ObservationCount(ProctorEventType type, long count, long totalDurationMs) {
    }

    /**
     * Factual timing for one interview, all of it derived from timestamps the
     * system already records. Nothing here is inferred or scored.
     *
     * <p>Per-answer figures come from the durations the browser reported with
     * each answer, so they are only as good as that: they measure how long the
     * candidate spent on the answering screen, not thinking time, and they are
     * absent for questions that were never answered.
     */
    public record TimingSummary(
            int allocatedMinutes,
            String allocatedText,
            Instant startedAt,
            Instant endedAt,
            /** Real time on the interview, from the session's own timestamps. */
            String actualText,
            long actualSeconds,
            /** Null for sessions that finished before the reason was recorded. */
            CompletionReason completionReason,
            String completionText,
            int answeredCount,
            int totalQuestions,
            int unansweredCount,
            /** Null when nothing was answered - an average of nothing is not zero. */
            String averageAnswerText,
            String longestAnswerText,
            String shortestAnswerText,
            /** True when the candidate used more than the allocated time. */
            boolean overran) {
    }

    /**
     * One recorded human decision.
     *
     * @param agreesWithModel whether this decision lines up with what the model
     *                        suggested. Derived, never stored - and worth
     *                        showing, because a system that never disagrees with
     *                        its own model is not really being reviewed.
     */
    public record ReviewEntry(
            ReviewDecision decision,
            String decisionLabel,
            String reviewerName,
            String note,
            Instant decidedAt,
            String decidedAtText,
            boolean agreesWithModel) {
    }

    /**
     * @param current the decision that stands, or null if nobody has decided yet
     * @param history every decision, newest first, so a revised one stays visible
     */
    public record ReviewSummary(ReviewEntry current, List<ReviewEntry> history) {

        public boolean decided() {
            return current != null;
        }

        /** True once a decision has been revised at least once. */
        public boolean revised() {
            return history.size() > 1;
        }
    }

    public record ReportView(
            Long sessionId,
            // candidate + interview
            String candidateName,
            String candidateEmail,
            String recruiterName,
            String domain,
            InterviewType interviewType,
            CandidateType candidateType,
            Integer experienceYears,
            Instant scheduledAt,
            String scheduledAtText,
            Instant startedAt,
            Instant endedAt,
            String durationText,
            /** Allocated vs actual time, completion reason and answer pacing. */
            TimingSummary timing,
            // scores
            int technicalScore,
            int communicationScore,
            int problemSolvingScore,
            int relevanceScore,
            int overallScore,
            int answeredCount,
            int totalQuestions,
            // outcome
            Recommendation recommendation,
            String explanation,
            boolean integrityFlag,
            /**
             * How much of the interview the monitoring actually saw. Says
             * nothing about the candidate: it is what stops an unwatched
             * session from reading as a clean one.
             */
            MonitoringCoverage monitoringCoverage,
            /** The browser's own reason for a monitoring failure, if any. */
            String monitoringNote,
            /** Observations the browser generated but never managed to upload. */
            int monitoringDroppedEvents,
            // detail
            List<QuestionResult> questionResults,
            List<ObservationCount> observationCounts,
            List<ObservationRow> observationTimeline,
            /** True when every answer was graded by a real LLM. */
            boolean aiEvaluated,
            Instant generatedAt) {
    }
}
