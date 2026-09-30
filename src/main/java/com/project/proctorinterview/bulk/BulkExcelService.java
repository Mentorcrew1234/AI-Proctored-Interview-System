package com.project.proctorinterview.bulk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import com.project.proctorinterview.bulk.dto.BulkDtos.BulkScheduleResult;
import com.project.proctorinterview.bulk.dto.BulkDtos.FailedRow;
import com.project.proctorinterview.bulk.dto.BulkDtos.RawRow;
import com.project.proctorinterview.bulk.dto.BulkDtos.ScheduledRow;
import com.project.proctorinterview.bulk.dto.BulkDtos.ValidatedRow;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.interview.InterviewService;

/**
 * Reads and writes the bulk-scheduling spreadsheet.
 *
 * <p>Deliberately does no validating: it turns cells into raw strings and hands
 * them on. Keeping reading separate from validating means a malformed value
 * produces a precise, row-and-column error instead of an exception halfway
 * through the file.
 */
@Service
public class BulkExcelService {

    public static final String SHEET_NAME = "Interviews";
    static final int HEADER_ROW = 0;
    /** Data starts here; rows 1-2 of the template hold examples. */
    static final int FIRST_DATA_ROW = 1;

    /** Column order of the template. Header text is matched case-insensitively. */
    public static final List<String> COLUMNS = List.of(
            "Interview Name",
            "Candidate Name",
            "Candidate Email",
            "Interview Date",
            "Interview Time",
            "Domain",
            "Experience Type",
            "Years of Experience",
            "Language",
            "Interview Type",
            "Question Count",
            "Test Duration (minutes)",
            // Appended, never inserted. Column order is positional for anyone
            // who saved an older template, and a new column at the end is one
            // the parser simply reads as blank when it is absent.
            "College Name",
            "Location",
            "Skills");

    private static final List<String> REQUIRED_COLUMNS = List.of(
            "Interview Name", "Candidate Name", "Candidate Email", "Interview Date",
            "Interview Time", "Domain", "Experience Type", "Language", "Interview Type");

    private static final DateTimeFormatter DATE_OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DataFormatter FORMATTER = new DataFormatter();

    // ---- template -----------------------------------------------------------

    /** Builds the blank template, with two example rows and a reference sheet. */
    public byte[] buildTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);

            CellStyle headerStyle = headerStyle(workbook);
            CellStyle exampleStyle = exampleStyle(workbook);

            Row header = sheet.createRow(HEADER_ROW);
            for (int i = 0; i < COLUMNS.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(COLUMNS.get(i));
                cell.setCellStyle(headerStyle);
            }

            // Dates in the examples are in the future so the template can be
            // filled in and uploaded without immediately failing validation.
            String d1 = LocalDate.now().plusDays(7).format(DATE_OUT);
            String d2 = LocalDate.now().plusDays(8).format(DATE_OUT);
            // Both examples share one interview name on purpose: a drive spans
            // many candidates, which is exactly what the column is for.
            writeExample(sheet, 1, exampleStyle,
                    "Java Developer - Campus Drive 2026",
                    "Arun Kumar", "arun.kumar@example.com", d1, "10:00",
                    "Java", "FRESHER", "", "English", "TECHNICAL", "5", "30",
                    "PSG College of Technology", "Coimbatore", "Java, Spring Boot, SQL");
            writeExample(sheet, 2, exampleStyle,
                    "Java Developer - Campus Drive 2026",
                    "Priya Raman", "priya.raman@example.com", d2, "14:30",
                    "Python", "EXPERIENCED", "3", "English", "HR_GENERAL", "5", "45",
                    "Anna University", "Chennai", "Python, Django, PostgreSQL");

            for (int i = 0; i < COLUMNS.size(); i++) {
                sheet.setColumnWidth(i, 22 * 256);
            }
            sheet.createFreezePane(0, 1);

            addInstructionsSheet(workbook);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the Excel template", e);
        }
    }

    private void writeExample(Sheet sheet, int rowNum, CellStyle style, String... values) {
        Row row = sheet.createRow(rowNum);
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(values[i]);
            cell.setCellStyle(style);
        }
    }

    /** A second sheet explaining accepted values, so a recruiter needs no other doc. */
    private void addInstructionsSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Instructions");
        CellStyle bold = workbook.createCellStyle();
        Font boldFont = workbook.createFont();
        boldFont.setBold(true);
        bold.setFont(boldFont);

        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("HOW TO USE", "");
        lines.put("1.", "Fill one row per interview on the '" + SHEET_NAME + "' sheet.");
        lines.put("2.", "Delete the two grey example rows before uploading.");
        lines.put("3.", "Upload the file. Nothing is scheduled until you confirm.");
        lines.put("", "");
        lines.put("REQUIRED COLUMNS", String.join(", ", REQUIRED_COLUMNS));
        lines.put(" ", "");
        lines.put("Interview Name", "Your name for this drive or assessment, e.g. "
                + "'Java Developer - Campus Drive 2026'. Many rows may share one.");
        lines.put("Interview Date", "YYYY-MM-DD (e.g. 2026-09-01). Must not be in the past.");
        lines.put("Interview Time", "24-hour HH:MM (e.g. 14:30).");
        lines.put("Domain", String.join(" | ", InterviewService.availableDomains()));
        lines.put("Experience Type", "FRESHER | EXPERIENCED");
        lines.put("Years of Experience", "Required when Experience Type is EXPERIENCED. Leave blank for a fresher.");
        lines.put("Language", "English");
        lines.put("Interview Type", "TECHNICAL | HR_GENERAL");
        lines.put("Question Count", "Optional. 1-10, defaults to 5.");
        lines.put("Test Duration (minutes)", "Optional. 1-180, defaults to 30. The clock starts when the candidate begins the interview, not at the scheduled time.");
        lines.put("College Name", "Optional. Up to 150 characters. Only used when the candidate "
                + "account has to be created here - an existing account is never altered by an upload.");
        lines.put("Location", "Optional. Up to 120 characters, e.g. Coimbatore. Same rule as "
                + "College Name: only used when the account is created here.");
        lines.put("Skills", "Optional. Comma-separated, e.g. 'Java, Spring Boot, SQL'. Each one "
                + "becomes a filter option on the candidates page. Same rule: creation only.");
        lines.put("  ", "");
        lines.put("NOTE", "Do not add interview questions. They are generated by the system.");
        lines.put("NOTE ", "If a candidate account does not exist it will be created, and the "
                + "generated password shown on the results page.");

        int rowNum = 0;
        for (Map.Entry<String, String> entry : lines.entrySet()) {
            Row row = sheet.createRow(rowNum++);
            Cell label = row.createCell(0);
            label.setCellValue(entry.getKey());
            label.setCellStyle(bold);
            row.createCell(1).setCellValue(entry.getValue());
        }
        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 90 * 256);
    }

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

    private CellStyle exampleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setItalic(true);
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFont(font);
        return style;
    }

    // ---- parsing ------------------------------------------------------------

    /**
     * Reads the uploaded workbook into raw rows.
     *
     * <p>Throws {@link ApiException} with a message meant for the user on any
     * file-level problem (level 1 validation): unreadable, wrong sheet, missing
     * required headers.
     */
    public List<RawRow> parse(InputStream input) {
        try (Workbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                // Fall back to the first sheet, so a renamed tab is not a hard failure.
                sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            }
            if (sheet == null) {
                throw ApiException.badRequest("The workbook contains no sheets.");
            }

            Row header = sheet.getRow(HEADER_ROW);
            if (header == null) {
                throw ApiException.badRequest(
                        "The first row must be the header row from the template.");
            }

            Map<String, Integer> columnIndex = readHeader(header);
            List<String> missing = REQUIRED_COLUMNS.stream()
                    .filter(c -> !columnIndex.containsKey(c.toLowerCase()))
                    .toList();
            if (!missing.isEmpty()) {
                throw ApiException.badRequest("Missing required column(s): " + String.join(", ", missing)
                        + ". Download a fresh template and use its header row.");
            }

            List<RawRow> rows = new ArrayList<>();
            int lastRow = sheet.getLastRowNum();
            for (int r = FIRST_DATA_ROW; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                RawRow raw = new RawRow(
                        r + 1, // 1-based, matching what the user sees in Excel
                        cell(row, columnIndex, "Interview Name"),
                        cell(row, columnIndex, "Candidate Name"),
                        cell(row, columnIndex, "Candidate Email"),
                        cell(row, columnIndex, "Interview Date"),
                        cell(row, columnIndex, "Interview Time"),
                        cell(row, columnIndex, "Domain"),
                        cell(row, columnIndex, "Experience Type"),
                        cell(row, columnIndex, "Years of Experience"),
                        cell(row, columnIndex, "Language"),
                        cell(row, columnIndex, "Interview Type"),
                        cell(row, columnIndex, "Question Count"),
                        cell(row, columnIndex, "Test Duration (minutes)"),
                        cell(row, columnIndex, "College Name"),
                        cell(row, columnIndex, "Location"),
                        cell(row, columnIndex, "Skills"));

                // Blank rows are skipped silently - trailing empties are normal
                // in a spreadsheet and are not the user's mistake.
                if (!raw.isBlank()) {
                    rows.add(raw);
                }
            }

            if (rows.isEmpty()) {
                throw ApiException.badRequest(
                        "The file contains no data rows. Fill in at least one interview.");
            }
            return rows;

        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Covers a non-Excel file, a corrupt file, and the legacy .xls format.
            throw ApiException.badRequest(
                    "The file could not be read as an .xlsx workbook. "
                            + "Please upload the downloaded template, saved as .xlsx.");
        }
    }

    private Map<String, Integer> readHeader(Row header) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int c = 0; c < header.getLastCellNum(); c++) {
            String name = FORMATTER.formatCellValue(header.getCell(c)).trim();
            if (!name.isEmpty()) {
                index.putIfAbsent(name.toLowerCase(), c);
            }
        }
        return index;
    }

    /** Reads one cell as trimmed text, normalising dates, times and numbers. */
    private String cell(Row row, Map<String, Integer> columnIndex, String columnName) {
        Integer index = columnIndex.get(columnName.toLowerCase());
        if (index == null) {
            return "";
        }
        Cell cell = row.getCell(index);
        if (cell == null) {
            return "";
        }

        // A real date or time cell is numeric underneath; format it into the
        // canonical text form the validator expects.
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            var dateTime = cell.getLocalDateTimeCellValue();
            if (dateTime == null) {
                return "";
            }
            boolean looksLikeTimeOnly = dateTime.toLocalDate().getYear() <= 1900;
            return looksLikeTimeOnly
                    ? dateTime.toLocalTime().toString()
                    : dateTime.toLocalDate().format(DATE_OUT);
        }

        if (cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            // "5" not "5.0" - question count and years are whole numbers.
            if (value == Math.floor(value) && !Double.isInfinite(value)) {
                return String.valueOf((long) value);
            }
        }

        return FORMATTER.formatCellValue(cell).trim();
    }

    // ---- error report -------------------------------------------------------

    /** The invalid rows and their errors, so they can be corrected and re-uploaded. */
    public byte[] buildErrorReport(List<ValidatedRow> invalidRows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Errors");
            CellStyle headerStyle = headerStyle(workbook);

            List<String> headers = List.of("Excel Row", "Interview Name", "Candidate Name",
                    "Candidate Email", "Error");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            for (ValidatedRow row : invalidRows) {
                Row out2 = sheet.createRow(rowNum++);
                out2.createCell(0).setCellValue(row.excelRowNumber());
                out2.createCell(1).setCellValue(nullSafe(row.interviewName()));
                out2.createCell(2).setCellValue(nullSafe(row.candidateName()));
                out2.createCell(3).setCellValue(nullSafe(row.candidateEmail()));
                out2.createCell(4).setCellValue(row.errorText());
            }

            sheet.setColumnWidth(0, 12 * 256);
            sheet.setColumnWidth(1, 30 * 256);
            sheet.setColumnWidth(2, 26 * 256);
            sheet.setColumnWidth(3, 32 * 256);
            sheet.setColumnWidth(4, 90 * 256);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the error report", e);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    // ---- result report --------------------------------------------------------

    /**
     * The full result of a completed batch: what was scheduled, what failed, and
     * any generated passwords for newly-created candidate accounts.
     *
     * <p>Only reachable while the batch is still staged (30 minutes) - the
     * plaintext password is never persisted, so this is the only place it can be
     * downloaded from.
     */
    public byte[] buildResultReport(BulkScheduleResult result) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Result");
            CellStyle headerStyle = headerStyle(workbook);

            List<String> headers = List.of("Status", "Excel Row", "Interview Name", "Candidate", "Email",
                    "Scheduled At", "Domain", "Invite Token", "Account Created", "Generated Password", "Reason");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            for (ScheduledRow row : result.scheduledRows()) {
                Row out2 = sheet.createRow(rowNum++);
                out2.createCell(0).setCellValue("SCHEDULED");
                out2.createCell(1).setCellValue(row.excelRowNumber());
                out2.createCell(2).setCellValue(nullSafe(row.interviewName()));
                out2.createCell(3).setCellValue(nullSafe(row.candidateName()));
                out2.createCell(4).setCellValue(nullSafe(row.candidateEmail()));
                out2.createCell(5).setCellValue(nullSafe(row.scheduledAtText()));
                out2.createCell(6).setCellValue(nullSafe(row.domain()));
                out2.createCell(7).setCellValue(nullSafe(row.inviteToken()));
                out2.createCell(8).setCellValue(row.candidateCreated() ? "yes" : "no");
                out2.createCell(9).setCellValue(nullSafe(row.generatedPassword()));
                out2.createCell(10).setCellValue("");
            }
            for (FailedRow row : result.failures()) {
                Row out2 = sheet.createRow(rowNum++);
                out2.createCell(0).setCellValue("FAILED");
                out2.createCell(1).setCellValue(row.excelRowNumber());
                out2.createCell(2).setCellValue("");
                out2.createCell(3).setCellValue(nullSafe(row.candidateName()));
                out2.createCell(4).setCellValue("");
                out2.createCell(5).setCellValue("");
                out2.createCell(6).setCellValue("");
                out2.createCell(7).setCellValue("");
                out2.createCell(8).setCellValue("");
                out2.createCell(9).setCellValue("");
                out2.createCell(10).setCellValue(nullSafe(row.reason()));
            }

            for (int i = 0; i < headers.size(); i++) {
                sheet.setColumnWidth(i, 22 * 256);
            }
            sheet.createFreezePane(0, 1);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the result report", e);
        }
    }
}
