package com.project.proctorinterview.interview.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewType;

import lombok.Getter;
import lombok.Setter;

/** Bulk edit/delete for the interview management page's filtered results. */
public final class BulkEditDtos {

    private BulkEditDtos() {
    }

    /**
     * Which interviews a bulk action targets.
     *
     * <p>"Select all matching" is resolved server-side from the caller's
     * current filter and authorization scope - the browser only ever hands
     * over a filter description, never the resulting id list, so there is
     * nothing to tamper with to widen the target set.
     */
    @Getter
    @Setter
    public static class BulkSelection {
        private boolean selectAllMatching;
        private List<Long> ids = new ArrayList<>();
    }

    /**
     * Field-by-field partial update: only a field whose "apply" flag is set is
     * changed; everything else on each targeted interview is left as is.
     *
     * <p>{@code newDomain}/{@code newInterviewType} are named to avoid
     * colliding with the management page's own {@code domain}/{@code
     * interviewType} filter fields when both are submitted from the same page.
     */
    @Getter
    @Setter
    public static class BulkEditFields {

        private boolean applyInterviewName;
        private String interviewName;

        private boolean applyScheduledAt;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime scheduledAt;

        private boolean applyDomain;
        private String newDomain;

        private boolean applyInterviewType;
        private InterviewType newInterviewType;

        private boolean applyQuestionCount;
        private Integer questionCount;

        private boolean applyDurationMinutes;
        private Integer durationMinutes;

        private boolean applyResultVisibleToCandidate;
        private boolean resultVisibleToCandidate;

        private boolean applyCandidateType;
        private CandidateType candidateType;
        private Integer experienceYears;

        public boolean appliesAnything() {
            return applyInterviewName || applyScheduledAt || applyDomain || applyInterviewType
                    || applyQuestionCount || applyDurationMinutes || applyResultVisibleToCandidate
                    || applyCandidateType;
        }
    }

    /** One targeted interview's outcome: applied, or skipped and why. */
    public record BulkOutcome(Long interviewId, String displayName, boolean succeeded, String reason) {
    }

    /** Summary of a bulk operation, shown as a flash message after redirect. */
    public record BulkResult(int requested, int succeeded, List<BulkOutcome> outcomes) {

        public boolean allSucceeded() {
            return succeeded == requested;
        }

        public List<BulkOutcome> failedOutcomes() {
            return outcomes.stream().filter(o -> !o.succeeded()).toList();
        }
    }
}
