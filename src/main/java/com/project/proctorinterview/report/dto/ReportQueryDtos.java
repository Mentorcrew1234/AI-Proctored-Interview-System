package com.project.proctorinterview.report.dto;

import java.util.List;

import com.project.proctorinterview.common.Enums.Recommendation;

/**
 * Read models for the Reports page.
 *
 * <p>The Reports page is a different <em>view</em> of interviews the system has
 * already finished, not a different dataset: it reuses the interview search -
 * the same filters, the same paging and, crucially, the same server-side
 * authorization scope - and joins each row to its report. Nothing here can
 * reach a report the interview list would not already show.
 */
public final class ReportQueryDtos {

    private ReportQueryDtos() {
    }

    /**
     * One completed interview and its result.
     *
     * <p>Every score is boxed and {@code recommendation} may be null: a
     * completed interview whose report was never generated is a real state the
     * system records (the "Missing report" attention item), and a blank cell
     * says that honestly where a 0 would read as "scored zero".
     */
    public record ReportRow(
            Long interviewId,
            String displayName,
            String candidateName,
            String candidateEmail,
            String recruiterName,
            String scheduledAtText,
            String domain,
            /** Null until the candidate has opened the interview at least once. */
            Long sessionId,
            boolean hasReport,
            Integer overallScore,
            Integer technicalScore,
            Integer problemSolvingScore,
            Integer communicationScore,
            Integer relevanceScore,
            Recommendation recommendation,
            /**
             * True when a proctoring observation of a type configured for human
             * review occurred. An observation, never an accusation - and it
             * never reduced the scores above.
             */
            boolean integrityFlag,
            String generatedAtText) {

        /** Lets the table style a band without repeating the enum name in Thymeleaf. */
        public String recommendationKey() {
            return recommendation == null ? "" : recommendation.name().toLowerCase();
        }
    }

    /** A page of {@link ReportRow}, shaped exactly like the interview page result. */
    public record ReportPageResult(
            List<ReportRow> rows,
            int page,
            int size,
            int totalPages,
            long totalElements,
            boolean hasPrevious,
            boolean hasNext,
            int firstItem,
            int lastItem) {
    }
}
