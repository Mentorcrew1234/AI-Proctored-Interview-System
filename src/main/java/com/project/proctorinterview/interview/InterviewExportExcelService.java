package com.project.proctorinterview.interview;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import com.project.proctorinterview.interview.dto.InterviewExportDtos;
import com.project.proctorinterview.interview.dto.InterviewExportDtos.ExportRow;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.ActiveFilter;

/**
 * Writes the filtered interview results to a workbook.
 *
 * <p>The mirror image of {@link com.project.proctorinterview.bulk.BulkExcelService}:
 * that one reads a spreadsheet to create interviews, this one writes one from
 * interviews that already exist. Styling conventions are deliberately the same
 * so the two files look like they come from the same system.
 *
 * <p>Uses {@link SXSSFWorkbook} rather than XSSF: rows are flushed to a
 * temporary file as they are written, so memory stays flat whether the export
 * is 10 rows or 10,000. That makes {@code dispose()} mandatory - without it the
 * temp files leak.
 */
@Service
public class InterviewExportExcelService {

    static final String SHEET_NAME = "Interview Results";
    static final String FILTER_SHEET_NAME = "Filters Applied";

    /** Rows kept in memory before being flushed to disk. */
    private static final int WINDOW_SIZE = 200;

    /**
     * @param rows     the already-filtered, already-scoped result set
     * @param appliedFilters human-readable description of what produced it
     * @param exportedBy     who ran the export, recorded on the filter sheet
     */
    public byte[] build(List<ExportRow> rows, List<ActiveFilter> appliedFilters, String exportedBy) {
        // close() removes the temporary files SXSSF streams rows into; leaving
        // them behind is the one way this class can leak.
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW_SIZE);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);

            Sheet sheet = workbook.createSheet(SHEET_NAME);
            writeHeader(sheet, headerStyle);

            int rowNum = 1;
            for (ExportRow row : rows) {
                write(sheet.createRow(rowNum++), row);
            }

            // An empty result still produces a valid, openable workbook that
            // says so, rather than a zero-byte file or an error page.
            if (rows.isEmpty()) {
                Row empty = sheet.createRow(1);
                empty.createCell(0).setCellValue(
                        "No interviews matched the filters that were applied. "
                                + "See the '" + FILTER_SHEET_NAME + "' sheet.");
            }

            // SXSSF cannot auto-size (flushed rows are gone), so widths are set
            // explicitly - the same approach the bulk template takes.
            for (int i = 0; i < InterviewExportDtos.COLUMNS.size(); i++) {
                sheet.setColumnWidth(i, 20 * 256);
            }
            sheet.createFreezePane(0, 1);

            writeFilterSheet(workbook, headerStyle, appliedFilters, exportedBy, rows.size());

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the results export", e);
        }
    }

    private void writeHeader(Sheet sheet, CellStyle headerStyle) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < InterviewExportDtos.COLUMNS.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(InterviewExportDtos.COLUMNS.get(i));
            cell.setCellStyle(headerStyle);
        }
    }

    /**
     * One interview per row.
     *
     * <p>A missing score is left as a genuinely blank cell rather than 0: an
     * interview with no report has not scored zero, it has not been scored.
     */
    private void write(Row row, ExportRow data) {
        int c = 0;
        row.createCell(c++).setCellValue(data.interviewId());
        text(row, c++, data.interviewName());
        text(row, c++, data.candidateName());
        text(row, c++, data.candidateEmail());
        text(row, c++, data.recruiterName());
        text(row, c++, data.scheduledDate());
        text(row, c++, data.scheduledTime());
        text(row, c++, data.domain());
        text(row, c++, data.interviewType() == null ? null : data.interviewType().name().replace('_', ' '));
        text(row, c++, data.candidateType() == null ? null : data.candidateType().name());
        number(row, c++, data.experienceYears());
        text(row, c++, data.language());
        row.createCell(c++).setCellValue(data.questionCount());
        row.createCell(c++).setCellValue(data.durationMinutes());
        text(row, c++, data.status() == null ? null : data.status().name().replace('_', ' '));
        text(row, c++, data.sessionStatus());
        text(row, c++, data.startedAtText());
        text(row, c++, data.endedAtText());
        text(row, c++, data.durationText());
        text(row, c++, data.completionReason());
        row.createCell(c++).setCellValue(data.hasReport() ? "Yes" : "No");
        number(row, c++, data.technicalScore());
        number(row, c++, data.problemSolvingScore());
        number(row, c++, data.communicationScore());
        number(row, c++, data.relevanceScore());
        number(row, c++, data.overallScore());
        text(row, c++, data.recommendation() == null ? null : data.recommendation().name().replace('_', ' '));
        // "Flagged for review" is an observation-driven flag, never a verdict.
        text(row, c++, data.integrityFlag() == null ? null : (data.integrityFlag() ? "Yes" : "No"));
        number(row, c++, data.answeredCount());
        text(row, c, data.reportGeneratedAtText());
    }

    private static void text(Row row, int column, String value) {
        if (value != null) {
            row.createCell(column).setCellValue(value);
        }
    }

    private static void number(Row row, int column, Integer value) {
        if (value != null) {
            row.createCell(column).setCellValue(value);
        }
    }

    /**
     * Records exactly which filters produced this file.
     *
     * <p>Without it an exported spreadsheet is an anonymous list of numbers -
     * there is no way to tell a filtered extract from the full set months later.
     */
    private void writeFilterSheet(Workbook workbook, CellStyle headerStyle,
            List<ActiveFilter> appliedFilters, String exportedBy, int rowCount) {

        Sheet sheet = workbook.createSheet(FILTER_SHEET_NAME);

        Row header = sheet.createRow(0);
        Cell a = header.createCell(0);
        a.setCellValue("Filter");
        a.setCellStyle(headerStyle);
        Cell b = header.createCell(1);
        b.setCellValue("Value");
        b.setCellStyle(headerStyle);

        int rowNum = 1;
        Row exported = sheet.createRow(rowNum++);
        exported.createCell(0).setCellValue("Exported by");
        exported.createCell(1).setCellValue(exportedBy);

        Row at = sheet.createRow(rowNum++);
        at.createCell(0).setCellValue("Exported at");
        at.createCell(1).setCellValue(java.time.LocalDateTime.now().toString());

        Row count = sheet.createRow(rowNum++);
        count.createCell(0).setCellValue("Rows exported");
        count.createCell(1).setCellValue(rowCount);

        if (appliedFilters.isEmpty()) {
            Row none = sheet.createRow(rowNum);
            none.createCell(0).setCellValue("Filters");
            none.createCell(1).setCellValue("None - all interviews you can access");
        } else {
            for (ActiveFilter filter : appliedFilters) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(filter.field());
                row.createCell(1).setCellValue(filter.label());
            }
        }

        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 60 * 256);
    }

    /** Matches the bulk template's header styling. */
    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        return style;
    }
}
