package com.project.proctorinterview.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
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
import com.project.proctorinterview.bulk.BulkExcelService;
import com.project.proctorinterview.bulk.dto.BulkDtos.ValidationSummary;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.CandidateViewService;
import com.project.proctorinterview.interview.InterviewService;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.user.dto.UserDtos.CreateUserRequest;
import com.project.proctorinterview.user.dto.UserDtos.UserRow;

/**
 * College name on the candidate profile.
 *
 * <p>The platform is used mainly for college students, so this is a property of
 * the person rather than of one interview. The tests below follow it along
 * every path it can enter or leave the system by: the admin create form, the
 * bulk Excel upload, the admin user list, and the candidates page, which is
 * where the filter that comes next will read it from.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CandidateCollegeNameTest {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private UserService userService;
    @Autowired
    private CandidateProfileRepository candidateProfiles;
    @Autowired
    private CandidateViewService candidateView;
    @Autowired
    private InterviewService interviewService;
    @Autowired
    private TestDatabaseCleaner cleaner;

    private AppUserDetails recruiter;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        recruiter = new AppUserDetails(
                save("college.recruiter@demo.local", "Priya Raman", Role.RECRUITER));
    }

    // ---- creation ------------------------------------------------------------

    @Test
    void creatingACandidateStoresTheCollegeName() {
        User created = userService.create(candidateRequest(
                "student@demo.local", "Arun Kumar", "PSG College of Technology"));

        assertThat(candidateProfiles.findByUserId(created.getId()).orElseThrow().getCollegeName())
                .isEqualTo("PSG College of Technology");
    }

    /**
     * The field is optional, and an untyped-in text input submits "" rather
     * than null. Storing that would make "recorded as empty" look exactly like
     * "never asked", which is the distinction the nullable column exists for.
     */
    @Test
    void aBlankCollegeNameIsStoredAsNullNotAnEmptyString() {
        User blank = userService.create(candidateRequest("blank@demo.local", "Blank", "   "));
        User absent = userService.create(candidateRequest("absent@demo.local", "Absent", null));

        assertThat(candidateProfiles.findByUserId(blank.getId()).orElseThrow().getCollegeName())
                .isNull();
        assertThat(candidateProfiles.findByUserId(absent.getId()).orElseThrow().getCollegeName())
                .isNull();
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        User created = userService.create(
                candidateRequest("trim@demo.local", "Trim", "  Anna University  "));

        assertThat(candidateProfiles.findByUserId(created.getId()).orElseThrow().getCollegeName())
                .isEqualTo("Anna University");
    }

    /** A recruiter has no candidate profile, so there is nowhere to put one. */
    @Test
    void creatingARecruiterIsUnaffected() {
        CreateUserRequest request = new CreateUserRequest();
        request.setFullName("Meena S");
        request.setEmail("meena.recruiter@demo.local");
        request.setPassword("Recruiter@123");
        request.setRole(Role.RECRUITER);
        request.setCollegeName("Ignored College");

        User created = userService.create(request);

        assertThat(candidateProfiles.findByUserId(created.getId())).isEmpty();
        assertThat(userService.search(Role.RECRUITER, "Meena", null))
                .extracting(UserRow::collegeName).containsOnlyNulls();
    }

    // ---- the admin user list -------------------------------------------------

    @Test
    void theUserListCarriesEachCandidatesCollege() {
        userService.create(candidateRequest("a@demo.local", "A Student", "NIT Trichy"));
        userService.create(candidateRequest("b@demo.local", "B Student", null));

        List<UserRow> rows = userService.search(Role.CANDIDATE, null, null);

        assertThat(rows).extracting(UserRow::email, UserRow::collegeName)
                .containsExactlyInAnyOrder(
                        tuple("a@demo.local", "NIT Trichy"),
                        tuple("b@demo.local", null));
    }

    /**
     * Every candidate profile is read in one query rather than one per row, so
     * an account with no profile at all must still produce a row.
     */
    @Test
    void anAccountWithNoProfileStillListsWithANullCollege() {
        save("profileless@demo.local", "No Profile", Role.CANDIDATE);

        assertThat(userService.search(Role.CANDIDATE, "profileless", null))
                .singleElement()
                .extracting(UserRow::collegeName).isNull();
    }

    // ---- the candidates page, and what the filter will read ------------------

    @Test
    void theCandidatesListAndDetailCarryTheCollege() {
        User candidate = userService.create(
                candidateRequest("listed@demo.local", "Listed Student", "VIT Vellore"));
        scheduleFor(candidate);

        var row = candidateView.list(recruiter.getId(), Role.RECRUITER, null).getFirst();
        assertThat(row.collegeName()).isEqualTo("VIT Vellore");

        var detail = candidateView.detail(candidate.getId(), recruiter.getId(), Role.RECRUITER);
        assertThat(detail.collegeName()).isEqualTo("VIT Vellore");
    }

    /**
     * The summary query left-joins the profile. An inner join would silently
     * drop every candidate whose college was never recorded, which would look
     * like a scope bug rather than the listing bug it is.
     */
    @Test
    void aCandidateWithNoCollegeIsStillListed() {
        User candidate = userService.create(
                candidateRequest("nocollege@demo.local", "No College", null));
        scheduleFor(candidate);

        assertThat(candidateView.list(recruiter.getId(), Role.RECRUITER, null))
                .singleElement()
                .extracting(CandidateViewService.CandidateRow::collegeName).isNull();
    }

    /** What a college filter control will offer: the values actually in use. */
    @Test
    void distinctCollegeNamesListsOnlyRecordedValues() {
        userService.create(candidateRequest("d1@demo.local", "One", "Anna University"));
        userService.create(candidateRequest("d2@demo.local", "Two", "Anna University"));
        userService.create(candidateRequest("d3@demo.local", "Three", "NIT Trichy"));
        userService.create(candidateRequest("d4@demo.local", "Four", null));

        assertThat(candidateProfiles.findDistinctCollegeNames())
                .containsExactly("Anna University", "NIT Trichy");
    }

    // ---- the bulk Excel upload -----------------------------------------------

    @Test
    void anUploadedCollegeNameReachesTheCreatedAccount() throws Exception {
        MvcResult validated = upload(workbook(
                bulkRow("Bulk Student", "bulk.student@demo.local", "SSN College of Engineering")));
        assertThat(summaryOf(validated).validRowList().getFirst().collegeName())
                .isEqualTo("SSN College of Engineering");

        confirm(validated);

        User created = users.findByEmailIgnoreCase("bulk.student@demo.local").orElseThrow();
        assertThat(candidateProfiles.findByUserId(created.getId()).orElseThrow().getCollegeName())
                .isEqualTo("SSN College of Engineering");
    }

    /** The column is optional; a blank cell is not an error and stores nothing. */
    @Test
    void aBlankCollegeColumnUploadsAndStoresNull() throws Exception {
        MvcResult validated = upload(workbook(bulkRow("Blank Cell", "blank.cell@demo.local", "")));
        assertThat(summaryOf(validated).invalidRows()).isZero();
        assertThat(summaryOf(validated).validRowList().getFirst().collegeName()).isNull();

        confirm(validated);

        User created = users.findByEmailIgnoreCase("blank.cell@demo.local").orElseThrow();
        assertThat(candidateProfiles.findByUserId(created.getId()).orElseThrow().getCollegeName())
                .isNull();
    }

    @Test
    void anOverlongCollegeNameIsARowErrorNotAFailedBatch() throws Exception {
        MvcResult validated = upload(workbook(
                bulkRow("Too Long", "toolong@demo.local", "C".repeat(151))));

        ValidationSummary summary = summaryOf(validated);
        assertThat(summary.invalidRows()).isEqualTo(1);
        assertThat(summary.invalidRowList().getFirst().errorText()).contains("College Name");
    }

    /**
     * An upload never edits an account that already exists. It does not change
     * their name, phone or domain either, and a spreadsheet must not silently
     * overwrite a college recorded somewhere else.
     */
    @Test
    void anExistingCandidatesCollegeIsNotOverwritten() throws Exception {
        userService.create(candidateRequest("existing@demo.local", "Existing", "Original College"));

        MvcResult validated = upload(workbook(
                bulkRow("Existing", "existing@demo.local", "Replacement College")));
        confirm(validated);

        User existing = users.findByEmailIgnoreCase("existing@demo.local").orElseThrow();
        assertThat(candidateProfiles.findByUserId(existing.getId()).orElseThrow().getCollegeName())
                .isEqualTo("Original College");
    }

    /**
     * Every profile column is APPENDED rather than inserted, so a spreadsheet
     * saved from a template that predates all of them still uploads and
     * schedules exactly as it did before.
     *
     * <p>The header here is the original twelve columns, spelled out rather
     * than sliced off the end of {@code COLUMNS} - a slice would silently
     * follow along each time a column is added and stop testing anything.
     */
    @Test
    void aWorkbookWithoutTheProfileColumnsStillUploads() throws Exception {
        String[] oldHeaders = {
                "Interview Name", "Candidate Name", "Candidate Email", "Interview Date",
                "Interview Time", "Domain", "Experience Type", "Years of Experience",
                "Language", "Interview Type", "Question Count", "Test Duration (minutes)" };
        byte[] content = workbook(oldHeaders, new String[] {
                "Old Template Drive", "Legacy Person", "legacy@demo.local",
                LocalDate.now().plusDays(7).format(DATE), "10:00", "Java",
                "FRESHER", "", "English", "TECHNICAL", "5", "" });

        MvcResult validated = upload(content);
        assertThat(summaryOf(validated).invalidRows()).isZero();
        var row = summaryOf(validated).validRowList().getFirst();
        assertThat(row.collegeName()).isNull();
        assertThat(row.location()).isNull();
        assertThat(row.skills()).isNull();
    }

    /**
     * The downloaded template must offer the column. Its position is not the
     * contract - the parser matches headers by name - so this asserts presence
     * rather than that it comes last.
     */
    @Test
    void theTemplateOffersTheCollegeNameColumn() {
        assertThat(BulkExcelService.COLUMNS).contains("College Name");
    }

    // ---- helpers -------------------------------------------------------------

    private CreateUserRequest candidateRequest(String email, String fullName, String collegeName) {
        CreateUserRequest request = new CreateUserRequest();
        request.setFullName(fullName);
        request.setEmail(email);
        request.setPassword("Candidate@123");
        request.setRole(Role.CANDIDATE);
        request.setCollegeName(collegeName);
        return request;
    }

    private void scheduleFor(User candidate) {
        CreateInterviewRequest request = new CreateInterviewRequest();
        request.setInterviewName("College Test Drive");
        request.setCandidateId(candidate.getId());
        request.setScheduledAt(LocalDateTime.now().plusDays(3).withNano(0));
        request.setCandidateType(CandidateType.FRESHER);
        request.setDomain("Java");
        request.setInterviewType(InterviewType.TECHNICAL);
        request.setQuestionCount(5);
        request.setDurationMinutes(30);
        interviewService.create(recruiter.getId(), request);
    }

    private User save(String email, String fullName, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("$2a$10$notarealhash");
        u.setFullName(fullName);
        u.setRole(role);
        u.setEnabled(true);
        u.setCreatedAt(Instant.now());
        return users.save(u);
    }

    private static String[] bulkRow(String name, String email, String collegeName) {
        return new String[] { "College Drive 2026", name, email,
                LocalDate.now().plusDays(7).format(DATE), "10:00", "Java",
                "FRESHER", "", "English", "TECHNICAL", "5", "", collegeName };
    }

    private static byte[] workbook(String[] row) {
        return workbook(BulkExcelService.COLUMNS.toArray(String[]::new), row);
    }

    private static byte[] workbook(String[] headers, String[] values) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(BulkExcelService.SHEET_NAME);
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            Row row = sheet.createRow(1);
            for (int i = 0; i < values.length; i++) {
                row.createCell(i).setCellValue(values[i]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcResult upload(byte[] content) throws Exception {
        return mvc.perform(multipart("/scheduling/bulk/validate")
                        .file(new MockMultipartFile("file", "batch.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content))
                        .with(user(recruiter)).with(csrf()))
                .andReturn();
    }

    private void confirm(MvcResult validated) throws Exception {
        String batchId = (String) validated.getModelAndView().getModel().get("batchId");
        mvc.perform(post("/scheduling/bulk/confirm").with(user(recruiter)).with(csrf())
                .param("batchId", batchId)).andExpect(status().isOk());
    }

    private ValidationSummary summaryOf(MvcResult result) {
        return (ValidationSummary) result.getModelAndView().getModel().get("summary");
    }
}
