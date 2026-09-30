package com.project.proctorinterview.interview.dto;

import java.util.List;

import com.project.proctorinterview.common.Enums.Difficulty;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class SessionDtos {

    private SessionDtos() {
    }

    /**
     * A question as the candidate sees it.
     *
     * <p>{@code expectedPoints} is deliberately absent - it is the grading
     * rubric, and sending it to the browser would hand over the answer key.
     */
    public record QuestionView(
            Long id,
            int sequenceNo,
            String text,
            Difficulty difficulty,
            boolean answered) {
    }

    public record QuestionListResponse(
            Long sessionId,
            int totalQuestions,
            int answeredCount,
            /** True when a real LLM generated these rather than the offline bank. */
            boolean aiGenerated,
            /** True when the server is running in MOCK interview mode. */
            boolean mockMode,
            /**
             * Seconds left, from the server. The browser resyncs its countdown
             * to this on every load, which is what makes a refresh resume the
             * real remaining time instead of restarting.
             */
            long remainingSeconds,
            List<QuestionView> questions) {
    }

    public record SubmitAnswerRequest(
            @NotNull(message = "questionId is required") Long questionId,

            /** Raw speech-to-text output, stored verbatim and never modified. */
            @Size(max = 20000, message = "Answer is too long") String rawTranscript,

            @PositiveOrZero(message = "durationSeconds cannot be negative") int durationSeconds) {
    }

    /**
     * Feedback returned after submitting. Scores are intentionally withheld:
     * showing them mid-interview would let a candidate infer how they are doing
     * and change their behaviour for later questions.
     */
    /**
     * Result of finishing an interview. Score and recommendation are included
     * only when the recruiter chose to make the result visible to the
     * candidate; otherwise they are null and the UI just confirms submission.
     */
    public record CompleteSessionResponse(
            Long sessionId,
            boolean completed,
            boolean resultVisible,
            Integer overallScore,
            String recommendation,
            /**
             * Why the interview ended, so the closing screen can tell a
             * candidate whose time ran out that it was submitted for them
             * rather than showing the same message as a voluntary finish.
             */
            String completionReason) {
    }

    public record SubmitAnswerResponse(
            Long answerId,
            int sequenceNo,
            String cleanTranscript,
            int fillerCount,
            int wordCount,
            int answeredCount,
            int totalQuestions,
            boolean complete,
            /** Resync point: the countdown is corrected after every answer. */
            long remainingSeconds) {
    }
}
