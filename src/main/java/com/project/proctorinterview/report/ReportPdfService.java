package com.project.proctorinterview.report;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

import org.springframework.stereotype.Service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.project.proctorinterview.report.dto.ReportDtos.ObservationCount;
import com.project.proctorinterview.report.dto.ReportDtos.QuestionResult;
import com.project.proctorinterview.common.Enums.CompletionReason;
import com.project.proctorinterview.common.Enums.MonitoringCoverage;
import com.project.proctorinterview.report.dto.ReportDtos.ReportView;
import com.project.proctorinterview.report.dto.ReportDtos.TimingSummary;

/**
 * Renders a finished report as a PDF.
 *
 * <p>Reads the same {@link ReportView} the HTML report page renders, so the two
 * cannot disagree about a candidate's scores - the read model is the single
 * source of truth and this class only lays it out.
 *
 * <p>The wording rules of the HTML report are carried over deliberately:
 * proctoring appears as <b>observations</b>, never as accusations, and the
 * recommendation is stated as advisory input to a human decision.
 */
@Service
public class ReportPdfService {

    private static final Color INK = new Color(0x1f, 0x2d, 0x3d);
    private static final Color MUTED = new Color(0x6b, 0x75, 0x85);
    private static final Color RULE = new Color(0xd7, 0xdd, 0xe5);
    private static final Color HEADER_BG = new Color(0x1a, 0x3a, 0x6b);

    private static final Font H1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, INK);
    private static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, INK);
    private static final Font H3 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, INK);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10, INK);
    private static final Font BODY_MUTED = FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED);
    private static final Font TABLE_HEAD =
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);

    public byte[] build(ReportView report) {
        Document document = new Document(PageSize.A4, 42, 42, 46, 46);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfWriter.getInstance(document, out);
            document.addTitle("Interview Report - " + report.candidateName());
            document.addCreator("AI-Based Proctored Interview System");
            document.open();

            writeHeader(document, report);
            writeMeta(document, report);
            writeTiming(document, report);
            writeScores(document, report);
            writeRecommendation(document, report);
            writeObservations(document, report);
            writeQuestions(document, report);
            writeFooter(document, report);

            document.close();
            return out.toByteArray();
        } catch (DocumentException | java.io.IOException e) {
            throw new IllegalStateException("Could not build the report PDF", e);
        }
    }

    /** The candidate this report is about. */
    private void writeHeader(Document document, ReportView report) throws DocumentException {
        document.add(new Paragraph(report.candidateName(), H1));

        Paragraph subtitle = new Paragraph(report.candidateEmail(), BODY_MUTED);
        subtitle.setSpacingAfter(4);
        document.add(subtitle);

        Paragraph context = new Paragraph(
                "Interview report  ·  session #" + report.sessionId(), BODY_MUTED);
        context.setSpacingAfter(14);
        document.add(context);
    }

    private void writeMeta(Document document, ReportView report) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] { 1f, 1.6f, 1f, 1.6f });
        table.setWidthPercentage(100);
        table.setSpacingAfter(16);

        meta(table, "Domain", report.domain());
        meta(table, "Interview type",
                report.interviewType() == com.project.proctorinterview.common.Enums.InterviewType.TECHNICAL
                        ? "Technical"
                        : "HR / General");
        meta(table, "Candidate type", candidateTypeText(report));
        meta(table, "Recruiter", report.recruiterName());
        meta(table, "Scheduled", report.scheduledAtText());
        meta(table, "Session length", report.durationText());
        meta(table, "Questions answered",
                report.answeredCount() + " of " + report.totalQuestions());
        meta(table, "Graded by", report.aiEvaluated() ? "Language model" : "Offline fallback scorer");

        document.add(table);
    }

    private static String candidateTypeText(ReportView report) {
        String type = report.candidateType() == null ? "-" : report.candidateType().name();
        return report.experienceYears() == null ? type : type + " (" + report.experienceYears() + " yrs)";
    }

    private void meta(PdfPTable table, String label, String value) {
        PdfPCell key = new PdfPCell(new Phrase(label, BODY_MUTED));
        key.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
        key.setPaddingBottom(5);
        table.addCell(key);

        PdfPCell val = new PdfPCell(new Phrase(value == null ? "-" : value, BODY));
        val.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
        val.setPaddingBottom(5);
        table.addCell(val);
    }

    /**
     * Interview timing, all of it derived from recorded timestamps.
     *
     * <p>Stated as facts. An overrun is not flagged and a fast finish is not
     * praised - how long someone took is context for a human, not a score.
     */
    private void writeTiming(Document document, ReportView report) throws DocumentException {
        TimingSummary timing = report.timing();
        document.add(section("Interview timing"));

        PdfPTable table = new PdfPTable(new float[] { 1f, 1.6f, 1f, 1.6f });
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSpacingAfter(14);

        meta(table, "Allocated", timing.allocatedText());
        meta(table, "Actual", timing.actualText());
        meta(table, "Completion", timing.completionText());
        meta(table, "Questions answered",
                timing.answeredCount() + " / " + timing.totalQuestions());

        // Only when something was answered - an average of nothing is not zero.
        if (timing.averageAnswerText() != null) {
            meta(table, "Average per answer", timing.averageAnswerText());
            meta(table, "Longest answer", timing.longestAnswerText());
            meta(table, "Shortest answer", timing.shortestAnswerText());
            meta(table, "Unanswered", String.valueOf(timing.unansweredCount()));
        }

        document.add(table);

        if (timing.completionReason() == CompletionReason.TIME_EXPIRED) {
            Paragraph note = new Paragraph(
                    "The allocated time ran out. Answers submitted before then were kept; any "
                            + "remaining questions count as unanswered, exactly as they would if the "
                            + "candidate had finished without attempting them.",
                    BODY_MUTED);
            note.setSpacingAfter(12);
            document.add(note);
        }
    }

    private void writeScores(Document document, ReportView report) throws DocumentException {
        document.add(section("Assessment"));

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSpacingAfter(14);

        headerCell(table, "Technical");
        headerCell(table, "Problem solving");
        headerCell(table, "Communication");
        headerCell(table, "Relevance");
        headerCell(table, "Overall");

        scoreCell(table, report.technicalScore(), false);
        scoreCell(table, report.problemSolvingScore(), false);
        scoreCell(table, report.communicationScore(), false);
        scoreCell(table, report.relevanceScore(), false);
        scoreCell(table, report.overallScore(), true);

        document.add(table);
    }

    private void writeRecommendation(Document document, ReportView report) throws DocumentException {
        Paragraph recommendation = new Paragraph();
        recommendation.add(new Phrase("Recommendation:  ", H3));
        recommendation.add(new Phrase(report.recommendation().name().replace('_', ' '),
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, INK)));
        recommendation.setSpacingAfter(6);
        document.add(recommendation);

        Paragraph explanation = new Paragraph(report.explanation(), BODY);
        explanation.setSpacingAfter(6);
        document.add(explanation);

        Paragraph caveat = new Paragraph(
                "This recommendation is advisory input for a human decision. It is produced by fixed, "
                        + "configurable thresholds applied to the scores above - not by the AI.",
                BODY_MUTED);
        caveat.setSpacingAfter(16);
        document.add(caveat);
    }

    /**
     * Proctoring, stated as observations.
     *
     * <p>The disclaimer is not decoration: a detection means "this was
     * detected", never "the candidate cheated", and a PDF that leaves the
     * building without that sentence invites exactly that misreading.
     */
    private void writeObservations(Document document, ReportView report) throws DocumentException {
        document.add(section("Proctoring observations"));

        Paragraph disclaimer = new Paragraph(
                "These are observations, not proof of misconduct. They come from automated detection "
                        + "running in the candidate's browser, which can be wrong - a family member walking "
                        + "past, a dark object mistaken for a phone, or poor lighting can all produce an "
                        + "observation. They are provided so a human can judge whether anything here matters.",
                BODY_MUTED);
        disclaimer.setSpacingBefore(4);
        disclaimer.setSpacingAfter(8);
        document.add(disclaimer);

        // Coverage first, because it decides how everything below should be
        // read. A PDF is the copy that gets forwarded and filed, so the caveat
        // has to travel with the numbers rather than living only on the page.
        String coverageCaveat = switch (report.monitoringCoverage()) {
            case INCOMPLETE -> "MONITORING DID NOT COVER THE WHOLE INTERVIEW"
                    + (report.monitoringDroppedEvents() > 0
                            ? " (" + report.monitoringDroppedEvents() + " observation(s) never reached the server)"
                            : "")
                    + (report.monitoringNote() != null ? ". The browser reported: " + report.monitoringNote() : "")
                    + ". Anything below is an incomplete record, and the absence of an observation is "
                    + "NOT evidence that nothing happened. This has not affected the scores or the "
                    + "recommendation - it is a failure of the monitoring, not of the candidate.";
            case NOT_RECORDED -> "Monitoring coverage was not recorded for this interview, so the "
                    + "observations below cannot be confirmed as a complete record.";
            case OBSERVATIONS_RECORDED, NONE_OBSERVED -> null;
        };
        if (coverageCaveat != null) {
            Paragraph caveat = new Paragraph(coverageCaveat, BODY);
            caveat.setSpacingAfter(12);
            document.add(caveat);
        }

        List<ObservationCount> counts = report.observationCounts();
        if (counts.isEmpty()) {
            // "Nothing was observed" and "nothing was watched" are different
            // statements and must not share a sentence.
            Paragraph none = new Paragraph(
                    report.monitoringCoverage() == MonitoringCoverage.NONE_OBSERVED
                            ? "Monitoring ran for this interview and no observations were recorded."
                            : "No observations reached the server. Read this as \"not observed\", "
                                    + "not as \"observed and clear\".",
                    BODY);
            none.setSpacingAfter(16);
            document.add(none);
            return;
        }

        PdfPTable table = new PdfPTable(new float[] { 2f, 1f, 1.4f });
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);
        headerCell(table, "Observation");
        headerCell(table, "Times");
        headerCell(table, "Total duration");

        for (ObservationCount count : counts) {
            bodyCell(table, count.type().name().replace('_', ' '), Element.ALIGN_LEFT);
            bodyCell(table, String.valueOf(count.count()), Element.ALIGN_CENTER);
            bodyCell(table, count.totalDurationMs() > 0
                    ? "%.1f s".formatted(count.totalDurationMs() / 1000.0)
                    : "-", Element.ALIGN_CENTER);
        }
        document.add(table);

        if (report.integrityFlag()) {
            Paragraph flagged = new Paragraph(
                    "One or more observations were of a type flagged for human review. "
                            + "The scores above were NOT reduced because of them.",
                    BODY);
            flagged.setSpacingAfter(16);
            document.add(flagged);
        }
    }

    private void writeQuestions(Document document, ReportView report) throws DocumentException {
        document.add(section("Question by question"));

        for (QuestionResult question : report.questionResults()) {
            Paragraph heading = new Paragraph(
                    "Q" + question.sequenceNo() + ". " + question.questionText(), H3);
            heading.setSpacingBefore(12);
            heading.setSpacingAfter(4);
            // Keeps a question's heading with at least the start of its answer.
            heading.setKeepTogether(true);
            document.add(heading);

            if (!question.answered()) {
                document.add(new Paragraph(
                        "Not answered. This question counts as zero in the overall score.", BODY_MUTED));
                continue;
            }

            Paragraph scores = new Paragraph(
                    "Technical %d  ·  Problem solving %d  ·  Communication %d  ·  Relevance %d  ·  Overall %d"
                            .formatted(question.technicalScore(), question.problemSolvingScore(),
                                    question.communicationScore(), question.relevanceScore(),
                                    question.overallScore()),
                    BODY_MUTED);
            scores.setSpacingAfter(4);
            document.add(scores);

            document.add(new Paragraph("Answer", H3));
            document.add(new Paragraph(question.cleanTranscript(), BODY));

            Paragraph stats = new Paragraph(
                    "%d words  ·  %d filler words removed  ·  %d seconds taken".formatted(
                            question.wordCount(), question.fillerCount(), question.durationSeconds()),
                    BODY_MUTED);
            stats.setSpacingAfter(4);
            document.add(stats);

            if (question.feedback() != null && !question.feedback().isBlank()) {
                document.add(new Paragraph("Feedback", H3));
                document.add(new Paragraph(question.feedback(), BODY));
            }
            bulletList(document, "Strengths", question.strengths());
            bulletList(document, "Areas to improve", question.weaknesses());
        }
    }

    private void bulletList(Document document, String heading, List<String> items)
            throws DocumentException {
        if (items == null || items.isEmpty()) {
            return;
        }
        document.add(new Paragraph(heading, H3));
        for (String item : items) {
            Paragraph bullet = new Paragraph("  •  " + item, BODY);
            bullet.setSpacingAfter(1);
            document.add(bullet);
        }
    }

    private void writeFooter(Document document, ReportView report) throws DocumentException {
        Paragraph footer = new Paragraph();
        footer.setSpacingBefore(20);
        if (!report.aiEvaluated()) {
            footer.add(new Phrase(
                    "Note: some or all answers were graded by the offline fallback scorer rather than a "
                            + "language model. That scorer measures keyword coverage and answer structure, "
                            + "not meaning, so treat these scores as indicative only.\n",
                    BODY_MUTED));
        }
        footer.add(new Phrase(
                "Report generated " + report.generatedAt() + "  ·  session #" + report.sessionId(),
                BODY_MUTED));
        document.add(footer);
    }

    private Paragraph section(String title) {
        Paragraph heading = new Paragraph(title, H2);
        heading.setSpacingBefore(8);
        heading.setSpacingAfter(4);
        return heading;
    }

    private void headerCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, TABLE_HEAD));
        cell.setBackgroundColor(HEADER_BG);
        cell.setBorderColor(RULE);
        cell.setPadding(6);
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.addCell(cell);
    }

    private void bodyCell(PdfPTable table, String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BODY));
        cell.setBorderColor(RULE);
        cell.setPadding(6);
        cell.setHorizontalAlignment(alignment);
        table.addCell(cell);
    }

    private void scoreCell(PdfPTable table, int score, boolean emphasised) {
        Font font = emphasised
                ? FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, INK)
                : FontFactory.getFont(FontFactory.HELVETICA, 15, INK);
        PdfPCell cell = new PdfPCell(new Phrase(score + "%", font));
        cell.setBorderColor(RULE);
        cell.setPadding(8);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(cell);
    }
}
