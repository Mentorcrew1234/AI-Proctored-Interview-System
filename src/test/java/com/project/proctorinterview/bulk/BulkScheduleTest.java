package com.project.proctorinterview.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.project.proctorinterview.TestDatabaseCleaner;
import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.bulk.dto.BulkDtos.BulkScheduleResult;
import com.project.proctorinterview.bulk.dto.BulkDtos.ValidationSummary;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Bulk scheduling, end to end through the real security chain.
 *
 * <p>The guarantee these tests exist for: <b>upload is not scheduling and
 * validation is not scheduling</b>. Only an explicit confirmation creates
 * interview records, and what it creates is indistinguishable from a
 * single-scheduled interview.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BulkScheduleTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestDatabaseCleaner cleaner;
    @Autowired
    private UserRepository users;
    @Autowired
    private InterviewRepository interviews;
    @Autowired
    private BulkExcelService excelService;

    private AppUserDetails recruiter;
    private AppUserDetails admin;
    private AppUserDetails candidate;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @BeforeEach
    void setUp() {
        cleaner.clean();
        recruiter = new AppUserDetails(save("iv@test.local", Role.RECRUITER));
        admin = new AppUserDetails(save("admin@test.local", Role.ADMIN));
        candidate = new AppUserDetails(save("cand@test.local", Role.CANDIDATE));
    }

    private User save(String email, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("$2a$10$notarealhash");
        u.setFullName("Test " + role);
        u.setRole(role);
        u.setEnabled(true);
        return users.save(u);
    }

    // ---- workbook building --------------------------------------------------

    private static String futureDate(int daysAhead) {
        return LocalDate.now().plusDays(daysAhead).format(DATE);
    }

    /** Builds a workbook with the template header plus the supplied rows. */
    private byte[] workbook(List<String[]> rows) {
        return workbook(BulkExcelService.COLUMNS.toArray(String[]::new), rows);
    }

    private byte[] workbook(String[] headers, List<String[]> rows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(BulkExcelService.SHEET_NAME);
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            int r = 1;
            for (String[] values : rows) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < values.length; i++) {
                    row.createCell(i).setCellValue(values[i]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Typed so String[] is not mistaken for varargs of String. */
    private static List<String[]> rows(String[]... values) {
        return List.of(values);
    }

    /** interviewName, name, email, date, time, domain, expType, years, language, type, questions */
    private static String[] row(String name, String email, String date, String time,
            String domain, String expType, String years, String language, String type, String questions) {
        return row("Test Drive 2026", name, email, date, time, domain, expType, years, language, type, questions);
    }

    private static String[] row(String interviewName, String name, String email, String date, String time,
            String domain, String expType, String years, String language, String type, String questions) {
        // Duration left blank: the column is optional, and a blank cell must
        // produce the same default the scheduling form uses.
        return new String[] { interviewName, name, email, date, time, domain, expType, years,
                language, type, questions, "" };
    }

    /** As above, with an explicit test duration in the last column. */
    private static String[] rowWithDuration(String name, String email, int daysAhead, String duration) {
        return new String[] { "Test Drive 2026", name, email, futureDate(daysAhead), "10:00", "Java",
                "FRESHER", "", "English", "TECHNICAL", "5", duration };
    }

    private static String[] validRow(String name, String email, int daysAhead) {
        return row(name, email, futureDate(daysAhead), "10:00", "Java",
                "FRESHER", "", "English", "TECHNICAL", "5");
    }

    private MvcResult upload(byte[] content, AppUserDetails as) throws Exception {
        return mvc.perform(multipart("/scheduling/bulk/validate")
                        .file(new MockMultipartFile("file", "batch.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content))
                        .with(user(as)).with(csrf()))
                .andReturn();
    }

    private ValidationSummary summaryOf(MvcResult result) {
        return (ValidationSummary) result.getModelAndView().getModel().get("summary");
    }

    private String batchIdOf(MvcResult result) {
        return (String) result.getModelAndView().getModel().get("batchId");
    }

    // ---- the central guarantee ---------------------------------------------

    @Test
    void uploadingAndValidatingCreatesNothing() throws Exception {
        MvcResult result = upload(workbook(rows(
                validRow("Arun Kumar", "arun@example.com", 7),
                validRow("Priya Raman", "priya@example.com", 8))), recruiter);

        assertThat(summaryOf(result).validRows()).isEqualTo(2);
        // The whole point: checking a file must not schedule anything.
        assertThat(interviews.findAll()).isEmpty();
        assertThat(users.findByEmailIgnoreCase("arun@example.com")).isEmpty();
    }

    @Test
    void onlyConfirmationCreatesInterviews() throws Exception {
        MvcResult validated = upload(workbook(rows(
                validRow("Arun Kumar", "arun@example.com", 7))), recruiter);
        assertThat(interviews.findAll()).isEmpty();

        mvc.perform(post("/scheduling/bulk/confirm").with(user(recruiter)).with(csrf())
                        .param("batchId", batchIdOf(validated)))
                .andExpect(status().isOk())
                .andExpect(view().name("scheduling/bulk-result"));

        assertThat(interviews.findAll()).hasSize(1);
    }

    @Test
    void abandoningAfterValidationLeavesNothingBehind() throws Exception {
        upload(workbook(rows(validRow("Arun Kumar", "arun@example.com", 7))), recruiter);
        // The user simply navigates away and never confirms.

        assertThat(interviews.findAll()).isEmpty();
        assertThat(users.findByEmailIgnoreCase("arun@example.com")).isEmpty();
    }

    // ---- bulk-created interviews are ordinary interviews ---------------------

    @Test
    void aBulkCreatedInterviewIsIndistinguishableFromASingleScheduledOne() throws Exception {
        MvcResult validated = upload(workbook(rows(
                row("Arun Kumar", "arun@example.com", futureDate(7), "14:30", "Python",
                        "EXPERIENCED", "4", "English", "HR_GENERAL", "3"))), recruiter);

        mvc.perform(post("/scheduling/bulk/confirm").with(user(recruiter)).with(csrf())
                .param("batchId", batchIdOf(validated))).andExpect(status().isOk());

        Interview created = interviews.findAll().getFirst();
        assertThat(created.getStatus()).isEqualTo(InterviewStatus.SCHEDULED);
        assertThat(created.getInviteToken()).isNotBlank().hasSizeGreaterThanOrEqualTo(32);
        assertThat(created.getRecruiter().getId()).isEqualTo(recruiter.getId());
        // Compare by id: the association is lazy, so reading the email off the
        // proxy outside a session would fail for reasons unrelated to the test.
        assertThat(created.getCandidate().getId())
                .isEqualTo(users.findByEmailIgnoreCase("arun@example.com").orElseThrow().getId());
        assertThat(created.getDomain()).isEqualTo("Python");
        assertThat(created.getInterviewType()).isEqualTo(InterviewType.HR_GENERAL);
        assertThat(created.getCandidateType()).isEqualTo(CandidateType.EXPERIENCED);
        assertThat(created.getExperienceYears()).isEqualTo(4);
        assertThat(created.getQuestionCount()).isEqualTo(3);
    }

    @Test
    void aMissingCandidateAccountIsCreatedWithACandidateRole() throws Exception {
        MvcResult validated = upload(workbook(rows(
                validRow("New Person", "newbie@example.com", 7))), recruiter);
        assertThat(summaryOf(validated).validRowList().getFirst().candidateExists()).isFalse();

        MvcResult confirmed = mvc.perform(post("/scheduling/bulk/confirm")
                .with(user(recruiter)).with(csrf())
                .param("batchId", batchIdOf(validated))).andReturn();

        User created = users.findByEmailIgnoreCase("newbie@example.com").orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.CANDIDATE);
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getPasswordHash()).startsWith("$2a$"); // hashed, not plaintext

        BulkScheduleResult result =
                (BulkScheduleResult) confirmed.getModelAndView().getModel().get("result");
        var row = result.scheduledRows().getFirst();
        assertThat(row.candidateCreated()).isTrue();
        // Surfaced because there is no email integration.
        assertThat(row.generatedPassword()).isNotBlank();
    }

    @Test
    void anExistingCandidateIsReusedNotDuplicated() throws Exception {
        MvcResult validated = upload(workbook(rows(
                validRow("Test CANDIDATE", "cand@test.local", 7))), recruiter);
        assertThat(summaryOf(validated).validRowList().getFirst().candidateExists()).isTrue();

        mvc.perform(post("/scheduling/bulk/confirm").with(user(recruiter)).with(csrf())
                .param("batchId", batchIdOf(validated))).andExpect(status().isOk());

        assertThat(users.findByRoleOrderByFullNameAsc(Role.CANDIDATE)).hasSize(1);
        assertThat(interviews.findAll().getFirst().getCandidate().getId())
                .isEqualTo(candidate.getId());
    }

    // ---- validation ---------------------------------------------------------

    @Test
    void rejectsAFileMissingARequiredColumn() throws Exception {
        String[] headers = { "Interview Name", "Candidate Name", "Interview Date", "Interview Time",
                "Domain", "Experience Type", "Language", "Interview Type" }; // no Candidate Email
        MvcResult result = upload(workbook(headers, rows(
                new String[] { "Drive", "Arun", futureDate(7), "10:00", "Java", "FRESHER", "English", "TECHNICAL" })),
                recruiter);

        assertThat(result.getModelAndView().getViewName()).isEqualTo("scheduling/bulk-upload");
        assertThat((String) result.getModelAndView().getModel().get("error"))
                .contains("Candidate Email");
    }

    @Test
    void rejectsAnEmptyFileAndANonExcelFile() throws Exception {
        MvcResult empty = upload(workbook(rows()), recruiter);
        assertThat((String) empty.getModelAndView().getModel().get("error"))
                .contains("no data rows");

        MvcResult notExcel = mvc.perform(multipart("/scheduling/bulk/validate")
                        .file(new MockMultipartFile("file", "notes.xlsx", "application/octet-stream",
                                "this is definitely not a workbook".getBytes()))
                        .with(user(recruiter)).with(csrf()))
                .andReturn();
        assertThat((String) notExcel.getModelAndView().getModel().get("error"))
                .contains("could not be read");
    }

    @Test
    void rejectsTheWrongFileExtension() throws Exception {
        MvcResult result = mvc.perform(multipart("/scheduling/bulk/validate")
                        .file(new MockMultipartFile("file", "candidates.csv", "text/csv", "a,b,c".getBytes()))
                        .with(user(recruiter)).with(csrf()))
                .andReturn();

        assertThat((String) result.getModelAndView().getModel().get("error")).contains(".xlsx");
    }

    @Test
    void flagsEveryKindOfBadRowWithItsOwnMessage() throws Exception {
        MvcResult result = upload(workbook(rows(
                validRow("Good One", "good@example.com", 7),                                      // valid
                row("", "noname@example.com", futureDate(7), "10:00", "Java", "FRESHER", "", "English", "TECHNICAL", ""),
                row("Bad Email", "not-an-email", futureDate(7), "10:00", "Java", "FRESHER", "", "English", "TECHNICAL", ""),
                row("Past Date", "past@example.com", "2020-01-01", "10:00", "Java", "FRESHER", "", "English", "TECHNICAL", ""),
                row("Bad Time", "time@example.com", futureDate(7), "25:99", "Java", "FRESHER", "", "English", "TECHNICAL", ""),
                // Domains are free text now, so an unfamiliar one is NOT an
                // error - see anyDomainIsAcceptedFromASpreadsheet below. What
                // is still rejected is one too long for the column.
                row("Long Domain", "domain@example.com", futureDate(7), "10:00",
                        "x".repeat(81), "FRESHER", "", "English", "TECHNICAL", ""),
                row("Bad Lang", "lang@example.com", futureDate(7), "10:00", "Java", "FRESHER", "", "Klingon", "TECHNICAL", ""),
                row("Bad Type", "type@example.com", futureDate(7), "10:00", "Java", "FRESHER", "", "English", "PHILOSOPHICAL", ""),
                row("No Years", "years@example.com", futureDate(7), "10:00", "Java", "EXPERIENCED", "", "English", "TECHNICAL", ""),
                row("No Date", "nodate@example.com", "", "10:00", "Java", "FRESHER", "", "English", "TECHNICAL", "")
        )), recruiter);

        ValidationSummary summary = summaryOf(result);
        assertThat(summary.totalRows()).isEqualTo(10);
        assertThat(summary.validRows()).isEqualTo(1);
        assertThat(summary.invalidRows()).isEqualTo(9);

        // Errors name the field and the exact problem.
        assertThat(errorFor(summary, "noname@example.com")).contains("Candidate Name");
        assertThat(errorFor(summary, "not-an-email")).contains("valid email");
        assertThat(errorFor(summary, "past@example.com")).contains("cannot be in the past");
        assertThat(errorFor(summary, "time@example.com")).contains("valid time");
        assertThat(errorFor(summary, "domain@example.com")).contains("80 characters");
        assertThat(errorFor(summary, "lang@example.com")).contains("English");
        assertThat(errorFor(summary, "type@example.com")).contains("TECHNICAL");
        assertThat(errorFor(summary, "years@example.com")).contains("EXPERIENCED");
        assertThat(errorFor(summary, "nodate@example.com")).contains("Interview Date");

        assertThat(interviews.findAll()).isEmpty();
    }

    /**
     * Domains are free text, and a spreadsheet may say anything the scheduling
     * form would accept.
     *
     * <p>This used to reject anything off a fixed list of six, which meant bulk
     * could do <em>less</em> than the single form - the opposite of the rule
     * that bulk must never do more. The AI generator takes the domain straight
     * into its prompt, so it was only ever the offline bank that needed a
     * familiar name.
     */
    @Test
    void anyDomainIsAcceptedFromASpreadsheet() throws Exception {
        MvcResult result = upload(workbook(rows(
                row("Rust Dev", "rust@example.com", futureDate(7), "10:00",
                        "Rust and WebAssembly", "FRESHER", "", "English", "TECHNICAL", ""),
                row("Kafka Dev", "kafka@example.com", futureDate(7), "10:00",
                        "Spring Boot + Kafka", "FRESHER", "", "English", "TECHNICAL", "")
        )), recruiter);

        ValidationSummary summary = summaryOf(result);
        assertThat(summary.validRows()).isEqualTo(2);
        assertThat(summary.invalidRows()).isZero();
    }

    /**
     * Casing from a spreadsheet is folded onto the suggested spelling, so one
     * domain does not fragment into three in the filter list.
     */
    @Test
    void aKnownDomainKeepsItsCanonicalSpellingWhateverTheSpreadsheetSaid() throws Exception {
        MvcResult result = upload(workbook(rows(
                row("Lower Case", "lower@example.com", futureDate(7), "10:00",
                        "  java  ", "FRESHER", "", "English", "TECHNICAL", "")
        )), recruiter);

        ValidationSummary summary = summaryOf(result);
        assertThat(summary.validRows()).isEqualTo(1);
    }

    private String errorFor(ValidationSummary summary, String email) {
        return summary.rows().stream()
                .filter(r -> email.equalsIgnoreCase(r.candidateEmail()))
                .findFirst().orElseThrow().errorText();
    }

    @Test
    void aFresherWithoutYearsIsValidAndStoresNoYears() throws Exception {
        MvcResult validated = upload(workbook(rows(
                row("Fresher A", "f1@example.com", futureDate(7), "10:00", "Java", "FRESHER", "", "English", "TECHNICAL", ""),
                row("Fresher B", "f2@example.com", futureDate(8), "10:00", "Java", "FRESHER", "0", "English", "TECHNICAL", "")
        )), recruiter);

        assertThat(summaryOf(validated).validRows()).isEqualTo(2);

        mvc.perform(post("/scheduling/bulk/confirm").with(user(recruiter)).with(csrf())
                .param("batchId", batchIdOf(validated))).andExpect(status().isOk());

        // Null, never 0 - the same rule the single form applies.
        assertThat(interviews.findAll()).allSatisfy(
                i -> assertThat(i.getExperienceYears()).isNull());
    }

    @Test
    void detectsDuplicateRowsWithinTheFile() throws Exception {
        String date = futureDate(7);
        MvcResult result = upload(workbook(rows(
                row("Aswin", "aswin@example.com", date, "10:00", "Java", "FRESHER", "", "English", "TECHNICAL", ""),
                row("Other", "other@example.com", date, "11:00", "Java", "FRESHER", "", "English", "TECHNICAL", ""),
                row("Aswin", "aswin@example.com", date, "10:00", "Java", "FRESHER", "", "English", "TECHNICAL", "")
        )), recruiter);

        ValidationSummary summary = summaryOf(result);
        assertThat(summary.validRows()).isEqualTo(2);
        assertThat(summary.invalidRows()).isEqualTo(1);
        // The second occurrence is flagged, the first is kept.
        assertThat(summary.invalidRowList().getFirst().excelRowNumber()).isEqualTo(4);
        assertThat(summary.invalidRowList().getFirst().errorText()).contains("Duplicate");
    }

    @Test
    void detectsAClashWithAnAlreadyScheduledInterview() throws Exception {
        String date = futureDate(7);
        MvcResult first = upload(workbook(rows(
                row("Test CANDIDATE", "cand@test.local", date, "10:00", "Java", "FRESHER", "", "English", "TECHNICAL", ""))),
                recruiter);
        mvc.perform(post("/scheduling/bulk/confirm").with(user(recruiter)).with(csrf())
                .param("batchId", batchIdOf(first))).andExpect(status().isOk());

        // The same candidate, same slot, uploaded again.
        MvcResult second = upload(workbook(rows(
                row("Test CANDIDATE", "cand@test.local", date, "10:00", "Java", "FRESHER", "", "English", "TECHNICAL", ""))),
                recruiter);

        assertThat(summaryOf(second).invalidRows()).isEqualTo(1);
        assertThat(summaryOf(second).invalidRowList().getFirst().errorText())
                .contains("already has an interview");
        assertThat(interviews.findAll()).hasSize(1);
    }

    @Test
    void schedulesOnlyTheValidRowsOfAMixedFile() throws Exception {
        var rows = new java.util.ArrayList<String[]>();
        for (int i = 0; i < 8; i++) {
            rows.add(validRow("Good " + i, "good" + i + "@example.com", 7 + i));
        }
        rows.add(row("Bad Email", "nope", futureDate(7), "10:00", "Java", "FRESHER", "", "English", "TECHNICAL", ""));
        rows.add(row("Past", "past@example.com", "2020-01-01", "10:00", "Java", "FRESHER", "", "English", "TECHNICAL", ""));

        MvcResult validated = upload(workbook(rows), recruiter);
        assertThat(summaryOf(validated).validRows()).isEqualTo(8);
        assertThat(summaryOf(validated).invalidRows()).isEqualTo(2);

        MvcResult confirmed = mvc.perform(post("/scheduling/bulk/confirm")
                .with(user(recruiter)).with(csrf())
                .param("batchId", batchIdOf(validated))).andReturn();

        BulkScheduleResult result =
                (BulkScheduleResult) confirmed.getModelAndView().getModel().get("result");
        assertThat(result.scheduled()).isEqualTo(8);
        assertThat(result.failed()).isZero();
        assertThat(result.skippedInvalid()).isEqualTo(2);

        // The invalid rows were never created.
        assertThat(interviews.findAll()).hasSize(8);
        assertThat(users.findByEmailIgnoreCase("past@example.com")).isEmpty();
    }

    // ---- test duration ------------------------------------------------------

    @Test
    void aBlankDurationColumnFallsBackToTheStandardDefault() throws Exception {
        MvcResult validated = upload(workbook(rows(
                validRow("Arun Kumar", "arun@example.com", 7))), recruiter);
        assertThat(summaryOf(validated).validRows()).isEqualTo(1);

        mvc.perform(post("/scheduling/bulk/confirm").with(user(recruiter)).with(csrf())
                .param("batchId", batchIdOf(validated))).andExpect(status().isOk());

        assertThat(interviews.findAll().getFirst().getDurationMinutes())
                .isEqualTo(com.project.proctorinterview.interview.dto.InterviewDtos.DEFAULT_DURATION_MINUTES);
    }

    @Test
    void anExplicitDurationInTheFileIsStoredOnTheInterview() throws Exception {
        MvcResult validated = upload(workbook(rows(
                rowWithDuration("Arun Kumar", "arun@example.com", 7, "45"))), recruiter);
        assertThat(summaryOf(validated).validRows()).isEqualTo(1);

        mvc.perform(post("/scheduling/bulk/confirm").with(user(recruiter)).with(csrf())
                .param("batchId", batchIdOf(validated))).andExpect(status().isOk());

        assertThat(interviews.findAll().getFirst().getDurationMinutes()).isEqualTo(45);
    }

    /** Bulk must not be able to store a duration the single form would refuse. */
    @Test
    void anOutOfRangeOrNonNumericDurationIsRejectedPerRow() throws Exception {
        MvcResult result = upload(workbook(rows(
                rowWithDuration("Too long", "long@example.com", 7, "500"),
                rowWithDuration("Zero", "zero@example.com", 8, "0"),
                rowWithDuration("Words", "words@example.com", 9, "half an hour"),
                rowWithDuration("Fine", "fine@example.com", 10, "30"))), recruiter);

        var summary = summaryOf(result);
        assertThat(summary.validRows()).isEqualTo(1);
        assertThat(summary.invalidRows()).isEqualTo(3);
        assertThat(errorFor(summary, "long@example.com")).contains("Test Duration");
        assertThat(errorFor(summary, "zero@example.com")).contains("Test Duration");
        assertThat(errorFor(summary, "words@example.com")).contains("whole number");

        assertThat(interviews.findAll()).isEmpty();
    }

    // ---- volume -------------------------------------------------------------

    @Test
    void handlesAHundredRows() throws Exception {
        var rows = new java.util.ArrayList<String[]>();
        for (int i = 0; i < 100; i++) {
            rows.add(row("Candidate " + i, "bulk" + i + "@example.com",
                    futureDate(7 + (i % 20)), "10:00", "Java", "FRESHER", "", "English", "TECHNICAL", "5"));
        }

        MvcResult validated = upload(workbook(rows), recruiter);
        assertThat(summaryOf(validated).validRows()).isEqualTo(100);

        MvcResult confirmed = mvc.perform(post("/scheduling/bulk/confirm")
                .with(user(recruiter)).with(csrf())
                .param("batchId", batchIdOf(validated))).andReturn();

        BulkScheduleResult result =
                (BulkScheduleResult) confirmed.getModelAndView().getModel().get("result");
        assertThat(result.scheduled()).isEqualTo(100);
        assertThat(interviews.findAll()).hasSize(100);
        // Every interview gets its own invite token.
        assertThat(interviews.findAll().stream().map(Interview::getInviteToken).distinct().count())
                .isEqualTo(100);
    }

    // ---- double submission --------------------------------------------------

    @Test
    void confirmingTwiceSchedulesOnlyOnce() throws Exception {
        MvcResult validated = upload(workbook(rows(
                validRow("Arun Kumar", "arun@example.com", 7),
                validRow("Priya Raman", "priya@example.com", 8))), recruiter);
        String batchId = batchIdOf(validated);

        mvc.perform(post("/scheduling/bulk/confirm").with(user(recruiter)).with(csrf())
                .param("batchId", batchId)).andExpect(status().isOk());

        // Exactly what a double-clicked button sends.
        MvcResult second = mvc.perform(post("/scheduling/bulk/confirm")
                .with(user(recruiter)).with(csrf())
                .param("batchId", batchId)).andExpect(status().isOk()).andReturn();

        assertThat(interviews.findAll()).hasSize(2);
        assertThat(second.getModelAndView().getModel().get("alreadyScheduled")).isEqualTo(true);
    }

    @Test
    void anUnknownOrExpiredBatchSchedulesNothing() throws Exception {
        mvc.perform(post("/scheduling/bulk/confirm").with(user(recruiter)).with(csrf())
                        .param("batchId", "does-not-exist"))
                .andExpect(view().name("scheduling/bulk-upload"))
                .andExpect(model().attributeExists("error"));

        assertThat(interviews.findAll()).isEmpty();
    }

    @Test
    void anotherUserCannotConfirmSomeoneElsesUpload() throws Exception {
        MvcResult validated = upload(workbook(rows(
                validRow("Arun Kumar", "arun@example.com", 7))), recruiter);

        // The admin is a legitimate bulk user, but this batch is not theirs.
        mvc.perform(post("/scheduling/bulk/confirm").with(user(admin)).with(csrf())
                        .param("batchId", batchIdOf(validated)))
                .andExpect(model().attributeExists("error"));

        assertThat(interviews.findAll()).isEmpty();
    }

    // ---- permissions --------------------------------------------------------

    @Test
    void candidatesCannotReachBulkSchedulingAtAll() throws Exception {
        mvc.perform(get("/scheduling/bulk").with(user(candidate)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/scheduling/bulk/template").with(user(candidate)))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/scheduling/bulk/validate")
                        .file(new MockMultipartFile("file", "b.xlsx", "application/octet-stream",
                                workbook(rows(validRow("X", "x@example.com", 7)))))
                        .with(user(candidate)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/scheduling/bulk/confirm").with(user(candidate)).with(csrf())
                        .param("batchId", "anything"))
                .andExpect(status().isForbidden());

        assertThat(interviews.findAll()).isEmpty();
    }

    @Test
    void anonymousUsersAreSentToLogin() throws Exception {
        mvc.perform(get("/scheduling/bulk")).andExpect(status().is3xxRedirection());
    }

    @Test
    void bothStaffRolesCanUseIt() throws Exception {
        mvc.perform(get("/scheduling/bulk").with(user(recruiter))).andExpect(status().isOk());
        mvc.perform(get("/scheduling/bulk").with(user(admin))).andExpect(status().isOk());
    }

    @Test
    void aPostWithoutACsrfTokenIsRejected() throws Exception {
        mvc.perform(multipart("/scheduling/bulk/validate")
                        .file(new MockMultipartFile("file", "b.xlsx", "application/octet-stream",
                                workbook(rows(validRow("X", "x@example.com", 7)))))
                        .with(user(recruiter)))
                .andExpect(status().isForbidden());
    }

    // ---- template -----------------------------------------------------------

    @Test
    void theGeneratedTemplateCanBeFilledInAndUploadedBack() throws Exception {
        byte[] template = excelService.buildTemplate();

        // Its own header row must satisfy the parser, or the template is broken.
        try (Workbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(template))) {
            Sheet sheet = wb.getSheet(BulkExcelService.SHEET_NAME);
            assertThat(sheet).isNotNull();
            Row header = sheet.getRow(0);
            for (int i = 0; i < BulkExcelService.COLUMNS.size(); i++) {
                assertThat(header.getCell(i).getStringCellValue())
                        .isEqualTo(BulkExcelService.COLUMNS.get(i));
            }
            assertThat(wb.getSheet("Instructions")).isNotNull();
        }

        // The example rows are dated in the future, so they validate as-is.
        MvcResult result = upload(template, recruiter);
        assertThat(summaryOf(result).validRows()).isEqualTo(2);
        assertThat(interviews.findAll()).isEmpty();
    }

    @Test
    void theTemplateDownloadsAsAWorkbook() throws Exception {
        byte[] body = mvc.perform(get("/scheduling/bulk/template").with(user(recruiter)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isNotEmpty();
        // PK zip magic - a real xlsx.
        assertThat(body[0]).isEqualTo((byte) 'P');
        assertThat(body[1]).isEqualTo((byte) 'K');
    }

    @Test
    void anErrorReportIsAvailableForInvalidRows() throws Exception {
        MvcResult validated = upload(workbook(rows(
                row("Bad", "not-an-email", futureDate(7), "10:00", "Java", "FRESHER", "", "English", "TECHNICAL", ""))),
                recruiter);

        byte[] report = mvc.perform(get("/scheduling/bulk/errors/{id}", batchIdOf(validated))
                        .with(user(recruiter)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(report).isNotEmpty();
        assertThat(report[0]).isEqualTo((byte) 'P');
    }

    @Test
    void aResultReportIsDownloadableAsARealWorkbookAfterConfirming() throws Exception {
        MvcResult validated = upload(workbook(rows(
                validRow("New Person", "wb-newbie@example.com", 7))), recruiter);
        String batchId = batchIdOf(validated);

        mvc.perform(post("/scheduling/bulk/confirm").with(user(recruiter)).with(csrf())
                .param("batchId", batchId)).andExpect(status().isOk());

        byte[] report = mvc.perform(get("/scheduling/bulk/report/{id}", batchId)
                        .with(user(recruiter)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        // PK zip magic - a real xlsx, not CSV text.
        assertThat(report[0]).isEqualTo((byte) 'P');
        assertThat(report[1]).isEqualTo((byte) 'K');

        try (Workbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(report))) {
            Sheet sheet = wb.getSheet("Result");
            assertThat(sheet).isNotNull();
            Row dataRow = sheet.getRow(1);
            assertThat(dataRow.getCell(0).getStringCellValue()).isEqualTo("SCHEDULED");
            assertThat(dataRow.getCell(4).getStringCellValue()).isEqualTo("wb-newbie@example.com");
            assertThat(dataRow.getCell(8).getStringCellValue()).isEqualTo("yes"); // account created
            assertThat(dataRow.getCell(9).getStringCellValue()).isNotBlank(); // generated password
        }
    }

    // ---- date and time tolerance -------------------------------------------

    @Test
    void acceptsCommonDateAndTimeFormats() throws Exception {
        LocalDateTime future = LocalDateTime.now().plusDays(10);
        String dmy = future.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));

        MvcResult result = upload(workbook(rows(
                row("A", "a@example.com", dmy, "09:30", "Java", "FRESHER", "", "English", "TECHNICAL", ""),
                row("B", "b@example.com", futureDate(11), "2:15 PM", "Java", "FRESHER", "", "English", "TECHNICAL", ""),
                row("C", "c@example.com", futureDate(12), "10:00", "java", "fresher", "", "english", "technical", "")
        )), recruiter);

        // Lower case values and alternative formats should not punish a recruiter.
        assertThat(summaryOf(result).validRows()).isEqualTo(3);
    }

    @Test
    void ignoresBlankRowsInTheMiddleOfTheFile() throws Exception {
        MvcResult result = upload(workbook(rows(
                validRow("Arun", "arun@example.com", 7),
                row("", "", "", "", "", "", "", "", "", "", ""),
                validRow("Priya", "priya@example.com", 8))), recruiter);

        assertThat(summaryOf(result).totalRows()).isEqualTo(2);
        assertThat(summaryOf(result).validRows()).isEqualTo(2);
    }
}
