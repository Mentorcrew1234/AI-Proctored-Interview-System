package com.project.proctorinterview.interview.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.QuestionMode;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Scheduling the same interview for several candidates at once.
 *
 * <p>This is the single-schedule form with one field changed: a multi-select of
 * candidates instead of one. Everything else - name, date, domain, type,
 * question count, result visibility - is shared by every interview it creates,
 * which is what makes it useful for a drive where one panel sees several people
 * on the same terms.
 */
public final class MultiScheduleDtos {

    private MultiScheduleDtos() {
    }

    /**
     * The multi-schedule form.
     *
     * <p>A mutable bean rather than a record for the same reason as
     * {@link CreateInterviewRequest}: Thymeleaf's {@code th:field} needs
     * JavaBean accessors to re-render a rejected form with the user's
     * selections - including the selected candidates - still in it.
     *
     * <p>Deliberately <b>not</b> a subclass of {@code CreateInterviewRequest}:
     * that class's {@code candidateId} is {@code @NotNull}, so inheriting it
     * would demand a single candidate this form never collects.
     */
    @Getter
    @Setter
    public static class MultiScheduleRequest {

        @NotBlank(message = "Interview name is required")
        @Size(max = 150, message = "Keep the interview name under 150 characters")
        private String interviewName;

        /**
         * Every candidate to schedule. The browser posts one value per selected
         * option; Spring binds them into this list.
         */
        @NotEmpty(message = "Select at least one candidate")
        private List<Long> candidateIds = new ArrayList<>();

        @NotNull(message = "Select a date and time")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime scheduledAt;

        @NotNull(message = "Select fresher or experienced")
        private CandidateType candidateType = CandidateType.FRESHER;

        @Min(value = 0, message = "Experience cannot be negative")
        @Max(value = 50, message = "Experience looks too high")
        private Integer experienceYears;

        @NotBlank(message = "Domain is required")
        private String domain;

        @NotNull(message = "Select an interview type")
        private InterviewType interviewType = InterviewType.TECHNICAL;

        @NotNull(message = "Number of questions is required")
        @Min(value = 1, message = "At least 1 question")
        @Max(value = 10, message = "At most 10 questions in the prototype")
        private Integer questionCount = 5;

        /**
         * Applied to every interview in the batch. Null means each one inherits
         * the server default, exactly as on the single form.
         */
        private QuestionMode questionMode;

        /** Same bounds as the single form - see InterviewDtos. */
        @NotNull(message = "Test duration is required")
        @Min(value = InterviewDtos.MIN_DURATION_MINUTES, message = "At least 1 minute")
        @Max(value = InterviewDtos.MAX_DURATION_MINUTES, message = "At most 180 minutes")
        private Integer durationMinutes = InterviewDtos.DEFAULT_DURATION_MINUTES;

        private boolean resultVisibleToCandidate;

        /**
         * This form as the single-schedule request for one candidate.
         *
         * <p>The one place the two shapes are mapped, so a field added to
         * scheduling cannot be silently dropped by the multi path. Every
         * interview is then created by the same
         * {@code InterviewService.create} the single form uses - a
         * multi-scheduled interview is an ordinary interview.
         */
        public CreateInterviewRequest toCreateRequest(Long candidateId) {
            CreateInterviewRequest request = new CreateInterviewRequest();
            request.setInterviewName(interviewName);
            request.setCandidateId(candidateId);
            request.setScheduledAt(scheduledAt);
            request.setCandidateType(candidateType);
            request.setExperienceYears(experienceYears);
            request.setDomain(domain);
            request.setInterviewType(interviewType);
            request.setQuestionCount(questionCount);
            request.setQuestionMode(questionMode);
            request.setDurationMinutes(durationMinutes);
            request.setResultVisibleToCandidate(resultVisibleToCandidate);
            return request;
        }
    }

    /** One candidate's outcome: scheduled, or skipped and why. */
    public record MultiScheduleOutcome(
            Long candidateId,
            String candidateName,
            boolean succeeded,
            /** Null when it succeeded. */
            String reason,
            /** Null when it failed. */
            Long interviewId) {
    }

    /**
     * What the whole operation did.
     *
     * <p>Failures are collected and reported rather than swallowed, and a
     * failure for one candidate never undoes an interview already created for
     * another - see {@code MultiScheduleRowScheduler}.
     */
    public record MultiScheduleResult(int requested, int scheduled, List<MultiScheduleOutcome> outcomes) {

        public boolean allSucceeded() {
            return scheduled == requested;
        }

        public List<MultiScheduleOutcome> failures() {
            return outcomes.stream().filter(o -> !o.succeeded()).toList();
        }
    }
}
