package com.project.proctorinterview.interview.dto;

import java.time.Instant;
import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.QuestionMode;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

public final class InterviewDtos {

    private InterviewDtos() {
    }

    /**
     * Test-duration bounds, declared once and referenced by the form
     * annotations, the service, the bulk validator and the templates.
     *
     * <p>1 minute is deliberately permissive: it is the only way to demonstrate
     * expiry in a viva without waiting half an hour. 180 minutes is a sanity
     * ceiling rather than a policy - long enough for any realistic interview,
     * short enough that a typo like 6000 is caught.
     */
    public static final int MIN_DURATION_MINUTES = 1;
    public static final int MAX_DURATION_MINUTES = 180;
    public static final int DEFAULT_DURATION_MINUTES = 30;

    /**
     * Interview configuration, bound by both the Thymeleaf form and the REST
     * endpoint so the validation rules are declared once.
     *
     * <p>A mutable bean rather than a record because Thymeleaf's {@code th:field}
     * needs JavaBean accessors to re-render a rejected form with the user's input
     * still in it.
     *
     * <p>{@code scheduledAt} is a LocalDateTime because that is what the browser's
     * datetime-local input produces; it is interpreted in the server's zone.
     */
    @Getter
    @Setter
    public static class CreateInterviewRequest {

        /**
         * What the recruiter calls this assessment, e.g. "Java Developer -
         * Campus Drive 2026". Not the candidate's name; many interviews in one
         * drive share it.
         */
        @NotBlank(message = "Interview name is required")
        @Size(max = 150, message = "Keep the interview name under 150 characters")
        private String interviewName;

        @NotNull(message = "Select a candidate")
        private Long candidateId;

        @NotNull(message = "Select a date and time")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime scheduledAt;

        @NotNull(message = "Select fresher or experienced")
        private CandidateType candidateType = CandidateType.FRESHER;

        @Min(value = 0, message = "Experience cannot be negative")
        @Max(value = 50, message = "Experience looks too high")
        private Integer experienceYears;

        /**
         * Free text: any topic or stack. Suggestions are offered on the form,
         * but nothing is rejected for being off the list - see
         * {@code InterviewService.availableDomains}.
         */
        @NotBlank(message = "Domain is required")
        @Size(max = 80, message = "Keep the domain under 80 characters")
        private String domain;

        @NotNull(message = "Select an interview type")
        private InterviewType interviewType = InterviewType.TECHNICAL;

        @NotNull(message = "Number of questions is required")
        @Min(value = 1, message = "At least 1 question")
        @Max(value = 10, message = "At most 10 questions in the prototype")
        private Integer questionCount = 5;

        /**
         * How questions are produced for this interview.
         *
         * <p>Deliberately <b>not</b> {@code @NotNull}: null is a real choice on
         * the form ("use the server default"), not a missing answer. An
         * unrecognised value is refused by Spring's binder before it reaches
         * here, so no validation annotation is needed for that either.
         */
        private QuestionMode questionMode;

        /**
         * How long the candidate gets once they start, in minutes.
         *
         * <p>The bounds are the same ones {@code InterviewService} enforces, so
         * a request that bypasses this form still cannot store a duration the
         * form would have rejected.
         */
        @NotNull(message = "Test duration is required")
        @Min(value = MIN_DURATION_MINUTES, message = "At least 1 minute")
        @Max(value = MAX_DURATION_MINUTES, message = "At most 180 minutes")
        private Integer durationMinutes = DEFAULT_DURATION_MINUTES;

        private boolean resultVisibleToCandidate;
    }

    /**
     * Read model for lists and detail pages.
     *
     * <p>{@code scheduledAtText} is pre-formatted in the service so the templates
     * stay free of date-formatting logic and locale handling.
     */
    public record InterviewSummary(
            Long id,
            /** Raw value; null for interviews created before naming existed. */
            String interviewName,
            /** Always safe to display - falls back to "Interview #id". */
            String displayName,
            String candidateName,
            String candidateEmail,
            String recruiterName,
            Instant scheduledAt,
            String scheduledAtText,
            CandidateType candidateType,
            Integer experienceYears,
            String domain,
            InterviewType interviewType,
            int questionCount,
            /**
             * How questions are produced, already resolved: an interview that
             * names no mode shows the server default rather than a blank, so
             * the page never has to explain "inherit" to a reader.
             */
            QuestionMode questionMode,
            /** Allocated test duration in minutes; the clock starts when the candidate does. */
            int durationMinutes,
            InterviewStatus status,
            String inviteToken,
            boolean resultVisibleToCandidate,
            Long sessionId,
            /** True when a report exists, so the table can offer "View report". */
            boolean hasReport) {
    }
}
