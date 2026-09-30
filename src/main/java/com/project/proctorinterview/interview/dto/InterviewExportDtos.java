package com.project.proctorinterview.interview.dto;

import java.util.List;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Recommendation;

/** Read models for the filtered results export. */
public final class InterviewExportDtos {

    private InterviewExportDtos() {
    }

    /**
     * One interview, flattened for a spreadsheet row.
     *
     * <p>Every score is a boxed type and every text field may be null: an
     * interview without a report has no scores, and a blank cell says that
     * honestly where a 0 would read as "scored zero".
     */
    public record ExportRow(
            Long interviewId,
            String interviewName,
            String candidateName,
            String candidateEmail,
            String recruiterName,
            String scheduledDate,
            String scheduledTime,
            String domain,
            InterviewType interviewType,
            CandidateType candidateType,
            Integer experienceYears,
            String language,
            int questionCount,
            int durationMinutes,
            InterviewStatus status,
            // session
            String sessionStatus,
            String startedAtText,
            String endedAtText,
            String durationText,
            /** Why the interview ended; blank when it never ran or predates the field. */
            String completionReason,
            // report - all null when the interview has no report yet
            boolean hasReport,
            Integer technicalScore,
            Integer problemSolvingScore,
            Integer communicationScore,
            Integer relevanceScore,
            Integer overallScore,
            Recommendation recommendation,
            Boolean integrityFlag,
            Integer answeredCount,
            String reportGeneratedAtText) {
    }

    /**
     * How many interviews a filter matches, and whether that is more than the
     * export will produce without an explicit go-ahead.
     *
     * <p>Exists so the page can say "this matches 3,214" <em>before</em>
     * building a workbook of that size.
     */
    public record ExportPreflight(long matching, int softLimit, boolean needsConfirmation,
            boolean exceedsHardLimit, int hardLimit) {
    }

    /** A built workbook plus what went into it. */
    public record ExportWorkbook(byte[] bytes, int rowCount, String fileName) {
    }

    /** Column headings, in order. Shared by the writer and its tests. */
    public static final List<String> COLUMNS = List.of(
            "Interview ID",
            "Interview Name",
            "Candidate Name",
            "Candidate Email",
            "Recruiter",
            "Scheduled Date",
            "Scheduled Time",
            "Domain",
            "Interview Type",
            "Experience Type",
            "Years of Experience",
            "Language",
            "Question Count",
            "Test Duration (min)",
            "Status",
            "Session Status",
            "Started At",
            "Ended At",
            "Duration",
            "Completion Reason",
            "Report Available",
            "Technical",
            "Problem Solving",
            "Communication",
            "Relevance",
            "Overall",
            "Recommendation",
            "Flagged for Review",
            "Questions Answered",
            "Report Generated At");
}
