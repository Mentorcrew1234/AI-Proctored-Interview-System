package com.project.proctorinterview.bulk.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewType;

public final class BulkDtos {

    private BulkDtos() {
    }

    /**
     * One spreadsheet row, exactly as read - every value a raw string.
     *
     * <p>Parsing and validation are kept separate so a malformed value produces a
     * precise error naming its column, rather than an exception during reading.
     */
    public record RawRow(
            int excelRowNumber,
            String interviewName,
            String candidateName,
            String candidateEmail,
            String interviewDate,
            String interviewTime,
            String domain,
            String experienceType,
            String yearsOfExperience,
            String language,
            String interviewType,
            String questionCount,
            String testDuration,
            String collegeName,
            String location,
            String skills) {

        public boolean isBlank() {
            return isEmpty(interviewName) && isEmpty(candidateName) && isEmpty(candidateEmail) && isEmpty(interviewDate)
                    && isEmpty(interviewTime) && isEmpty(domain) && isEmpty(experienceType)
                    && isEmpty(yearsOfExperience) && isEmpty(language) && isEmpty(interviewType)
                    && isEmpty(questionCount) && isEmpty(testDuration) && isEmpty(collegeName)
                    && isEmpty(location) && isEmpty(skills);
        }

        private static boolean isEmpty(String value) {
            return value == null || value.isBlank();
        }
    }

    /** A validation problem, tied to the exact column that caused it. */
    public record RowError(String field, String message) {
    }

    /**
     * A row after validation. Resolved values are populated only when the row is
     * valid; an invalid row carries its errors instead.
     */
    public record ValidatedRow(
            int excelRowNumber,
            String interviewName,
            String candidateName,
            String candidateEmail,
            /**
             * Optional, and only ever used when the account has to be created -
             * see {@code BulkRowScheduler}. Null when the cell was blank.
             */
            String collegeName,
            String location,
            /** Comma-separated; normalised by {@code UserService} on the way in. */
            String skills,
            LocalDateTime scheduledAt,
            String scheduledAtText,
            String domain,
            CandidateType candidateType,
            Integer experienceYears,
            InterviewLanguage language,
            InterviewType interviewType,
            int questionCount,
            int durationMinutes,
            boolean valid,
            List<RowError> errors,
            /** False when the candidate account will have to be created. */
            boolean candidateExists) {

        public String errorText() {
            return errors.stream()
                    .map(e -> e.field() + ": " + e.message())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
        }

        public String experienceText() {
            if (candidateType == CandidateType.FRESHER) {
                return "Fresher";
            }
            return experienceYears == null ? "Experienced" : experienceYears + " years";
        }
    }

    /**
     * The outcome of validating an upload. Held server-side under {@code batchId}
     * so the confirmation step does not have to trust anything the browser sends
     * back.
     */
    public record ValidationSummary(
            String batchId,
            String fileName,
            int totalRows,
            int validRows,
            int invalidRows,
            List<ValidatedRow> rows) {

        public List<ValidatedRow> validRowList() {
            return rows.stream().filter(ValidatedRow::valid).toList();
        }

        public List<ValidatedRow> invalidRowList() {
            return rows.stream().filter(r -> !r.valid()).toList();
        }

        public boolean hasSchedulableRows() {
            return validRows > 0;
        }
    }

    /** One interview that was actually created. */
    public record ScheduledRow(
            int excelRowNumber,
            String interviewName,
            String candidateName,
            String candidateEmail,
            String scheduledAtText,
            String domain,
            String inviteToken,
            boolean candidateCreated,
            /**
             * Only set when the account was created here. There is no email
             * integration, so the recruiter has to pass this on themselves.
             */
            String generatedPassword) {
    }

    /** A row that passed validation but failed while being written. */
    public record FailedRow(int excelRowNumber, String candidateName, String reason) {
    }

    public record BulkScheduleResult(
            String batchId,
            int scheduled,
            int failed,
            int skippedInvalid,
            List<ScheduledRow> scheduledRows,
            List<FailedRow> failures,
            Instant completedAt) {

        /**
         * Rows whose candidate account had to be created, and which therefore
         * carry a generated password to pass on.
         *
         * <p>Filtered here rather than in the template: Thymeleaf evaluates
         * {@code th:unless} before {@code th:with}, so a variable defined and
         * tested on the same element is still undefined when the test runs.
         */
        public List<ScheduledRow> createdAccounts() {
            return scheduledRows.stream().filter(ScheduledRow::candidateCreated).toList();
        }
    }
}
