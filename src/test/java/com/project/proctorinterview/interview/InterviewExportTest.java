package com.project.proctorinterview.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.project.proctorinterview.TestDatabaseCleaner;
import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Recommendation;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.common.Enums.SessionStatus;
import com.project.proctorinterview.interview.dto.InterviewExportDtos;
import com.project.proctorinterview.interview.dto.InterviewExportDtos.ExportRow;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.InterviewFilter;
import com.project.proctorinterview.report.Report;
import com.project.proctorinterview.report.ReportRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * The filtered results export.
 *
 * <p>The two guarantees these tests exist for: an export contains
 * <b>everything matching the filter</b> rather than the page being viewed, and
 * it contains <b>nothing outside the caller's own scope</b> no matter what the
 * query string says.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InterviewExportTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestDatabaseCleaner cleaner;
    @Autowired
    private UserRepository users;
    @Autowired
    private InterviewRepository interviews;
    @Autowired
    private InterviewSessionRepository sessions;
    @Autowired
    private ReportRepository reports;
    @Autowired
    private InterviewQueryService queryService;

    private AppUserDetails admin;
    private AppUserDetails recruiterA;
    private AppUserDetails recruiterB;
    private User candidate;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        admin = new AppUserDetails(save("admin@test.local", Role.ADMIN));
        recruiterA = new AppUserDetails(save("a@test.local", Role.RECRUITER));
        recruiterB = new AppUserDetails(save("b@test.local", Role.RECRUITER));
        candidate = save("cand@test.local", Role.CANDIDATE);
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

    private Interview interview(AppUserDetails recruiter, String name, String domain,
            InterviewStatus status, Instant scheduledAt) {
        Interview i = new Interview();
        i.setInterviewName(name);
        i.setRecruiter(users.findById(recruiter.getId()).orElseThrow());
        i.setCandidate(candidate);
        i.setScheduledAt(scheduledAt);
        i.setCandidateType(CandidateType.FRESHER);
        i.setDomain(domain);
        i.setInterviewType(InterviewType.TECHNICAL);
        i.setQuestionCount(5);
        i.setStatus(status);
        i.setInviteToken(UUID.randomUUID().toString());
        return interviews.save(i);
    }

    private Report completeWithReport(Interview interview, int overall) {
        InterviewSession session = new InterviewSession();
        session.setInterview(interview);
        session.setStatus(SessionStatus.COMPLETED);
        session.setStartedAt(Instant.now().minus(Duration.ofHours(1)));
        session.setEndedAt(Instant.now().minus(Duration.ofMinutes(30)));
        session = sessions.save(session);

        Report report = new Report();
        report.setSession(session);
        report.setTechnicalScore(overall);
        report.setCommunicationScore(overall);
        report.setProblemSolvingScore(overall);
        report.setRelevanceScore(overall);
        report.setOverallScore(overall);
        report.setRecommendation(Recommendation.RECOMMENDED);
        report.setExplanation("Solid answers.");
        report.setIntegrityFlag(false);
        return reports.save(report);
    }

    private static InterviewFilter filter() {
        return new InterviewFilter();
    }

    // ---- the central guarantee: all matching rows, not one page -------------

    @Test
    void exportsEveryMatchingRowNotJustTheVisiblePage() {
        for (int i = 0; i < 45; i++) {
            interview(recruiterA, "Drive " + i, "Java", InterviewStatus.SCHEDULED,
                    Instant.now().plus(Duration.ofDays(1 + i)));
        }

        // The page the user is looking at shows 20.
        InterviewFilter paged = filter();
        paged.setSize(20);
        paged.setPage(0);
        assertThat(queryService.search(paged, recruiterA.getId(), Role.RECRUITER).rows()).hasSize(20);

        // The export ignores paging entirely.
        List<ExportRow> exported = queryService.exportRows(paged, recruiterA.getId(), Role.RECRUITER);
        assertThat(exported).hasSize(45);
    }

    @Test
    void exportRespectsTheAppliedFilters() {
        interview(recruiterA, "Java Drive", "Java", InterviewStatus.SCHEDULED,
                Instant.now().plus(Duration.ofDays(2)));
        interview(recruiterA, "Python Drive", "Python", InterviewStatus.SCHEDULED,
                Instant.now().plus(Duration.ofDays(3)));
        interview(recruiterA, "Another Java", "Java", InterviewStatus.CANCELLED,
                Instant.now().plus(Duration.ofDays(4)));

        InterviewFilter javaOnly = filter();
        javaOnly.setDomain("Java");

        List<ExportRow> rows = queryService.exportRows(javaOnly, recruiterA.getId(), Role.RECRUITER);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.domain()).isEqualTo("Java"));

        InterviewFilter javaScheduled = filter();
        javaScheduled.setDomain("Java");
        javaScheduled.setStatus(InterviewStatus.SCHEDULED);
        assertThat(queryService.exportRows(javaScheduled, recruiterA.getId(), Role.RECRUITER)).hasSize(1);
    }

    // ---- authorization scope ------------------------------------------------

    @Test
    void aRecruiterExportNeverContainsAnotherRecruitersInterviews() {
        interview(recruiterA, "Mine", "Java", InterviewStatus.SCHEDULED,
                Instant.now().plus(Duration.ofDays(1)));
        interview(recruiterB, "Theirs", "Java", InterviewStatus.SCHEDULED,
                Instant.now().plus(Duration.ofDays(2)));

        List<ExportRow> rows = queryService.exportRows(filter(), recruiterA.getId(), Role.RECRUITER);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().interviewName()).isEqualTo("Mine");
    }

    /** The filter must not be able to reach outside the caller's own scope. */
    @Test
    void aRecruiterCannotWidenTheExportByNamingAnotherRecruiter() {
        interview(recruiterB, "Theirs", "Java", InterviewStatus.SCHEDULED,
                Instant.now().plus(Duration.ofDays(2)));

        InterviewFilter forged = filter();
        forged.setRecruiterId(recruiterB.getId());

        assertThat(queryService.exportRows(forged, recruiterA.getId(), Role.RECRUITER)).isEmpty();
    }

    @Test
    void anAdminExportSpansEveryRecruiter() {
        interview(recruiterA, "A", "Java", InterviewStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(1)));
        interview(recruiterB, "B", "Java", InterviewStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(2)));

        assertThat(queryService.exportRows(filter(), admin.getId(), Role.ADMIN)).hasSize(2);
    }

    @Test
    void aCandidateCannotReachTheExportEndpointAtAll() throws Exception {
        AppUserDetails asCandidate = new AppUserDetails(candidate);

        mvc.perform(get("/admin/interviews/export.xlsx").with(user(asCandidate)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/recruiter/interviews/export.xlsx").with(user(asCandidate)))
                .andExpect(status().isForbidden());
    }

    // ---- report data --------------------------------------------------------

    @Test
    void scoresAppearForInterviewsWithAReportAndAreAbsentForThoseWithout() {
        Interview scored = interview(recruiterA, "Done", "Java", InterviewStatus.COMPLETED,
                Instant.now().minus(Duration.ofDays(1)));
        completeWithReport(scored, 82);
        interview(recruiterA, "Not started", "Java", InterviewStatus.SCHEDULED,
                Instant.now().plus(Duration.ofDays(5)));

        List<ExportRow> rows = queryService.exportRows(filter(), recruiterA.getId(), Role.RECRUITER);

        ExportRow withReport = rows.stream().filter(ExportRow::hasReport).findFirst().orElseThrow();
        assertThat(withReport.overallScore()).isEqualTo(82);
        assertThat(withReport.recommendation()).isEqualTo(Recommendation.RECOMMENDED);

        // An interview with no report has no scores - not zeroes.
        ExportRow without = rows.stream().filter(r -> !r.hasReport()).findFirst().orElseThrow();
        assertThat(without.overallScore()).isNull();
        assertThat(without.recommendation()).isNull();
        assertThat(without.sessionStatus()).isNull();
    }

    // ---- the workbook itself ------------------------------------------------

    @Test
    void theDownloadIsARealWorkbookWithTheExpectedHeaderAndRows() throws Exception {
        Interview scored = interview(recruiterA, "Java Campus Drive", "Java",
                InterviewStatus.COMPLETED, Instant.now().minus(Duration.ofDays(1)));
        completeWithReport(scored, 76);

        byte[] body = mvc.perform(get("/recruiter/interviews/export.xlsx").with(user(recruiterA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body[0]).isEqualTo((byte) 'P');
        assertThat(body[1]).isEqualTo((byte) 'K');

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            Sheet sheet = wb.getSheet("Interview Results");
            assertThat(sheet).isNotNull();

            Row header = sheet.getRow(0);
            for (int i = 0; i < InterviewExportDtos.COLUMNS.size(); i++) {
                assertThat(header.getCell(i).getStringCellValue())
                        .isEqualTo(InterviewExportDtos.COLUMNS.get(i));
            }

            Row first = sheet.getRow(1);
            assertThat(first.getCell(1).getStringCellValue()).isEqualTo("Java Campus Drive");
            assertThat(first.getCell(2).getStringCellValue()).isEqualTo(candidate.getFullName());

            // The filter sheet records what produced the file.
            assertThat(wb.getSheet("Filters Applied")).isNotNull();
        }
    }

    /** An empty result is still a valid, openable file that says so. */
    @Test
    void anEmptyResultStillProducesAValidWorkbook() throws Exception {
        byte[] body = mvc.perform(get("/recruiter/interviews/export.xlsx")
                        .param("domain", "Python")
                        .with(user(recruiterA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            Sheet sheet = wb.getSheet("Interview Results");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue())
                    .contains("No interviews matched");
        }
    }

    // ---- the large-export confirmation --------------------------------------

    @Test
    void aSmallExportNeedsNoConfirmation() {
        interview(recruiterA, "One", "Java", InterviewStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(1)));

        var preflight = queryService.exportPreflight(filter(), recruiterA.getId(), Role.RECRUITER);
        assertThat(preflight.matching()).isEqualTo(1);
        assertThat(preflight.needsConfirmation()).isFalse();
    }

    /**
     * The soft limit is enforced by the server, not by the page that offers the
     * link - a request without the acknowledgement is refused.
     */
    @Test
    void anOversizedExportIsRefusedWithoutConfirmationAndAllowedWithIt() throws Exception {
        List<Interview> batch = new ArrayList<>();
        for (int i = 0; i < InterviewQueryService.EXPORT_SOFT_LIMIT + 5; i++) {
            batch.add(interview(recruiterA, "Bulk " + i, "Java", InterviewStatus.SCHEDULED,
                    Instant.now().plus(Duration.ofDays(1)).plusSeconds(i)));
        }
        assertThat(batch).hasSize(InterviewQueryService.EXPORT_SOFT_LIMIT + 5);

        var preflight = queryService.exportPreflight(filter(), recruiterA.getId(), Role.RECRUITER);
        assertThat(preflight.needsConfirmation()).isTrue();

        mvc.perform(get("/recruiter/interviews/export.xlsx").with(user(recruiterA)))
                .andExpect(status().is(413));

        mvc.perform(get("/recruiter/interviews/export.xlsx")
                        .param("confirmLarge", "true")
                        .with(user(recruiterA)))
                .andExpect(status().isOk());
    }

    /** A bad filter combination should explain itself, not 500. */
    @Test
    void anInvalidDateRangeIsRejectedCleanly() throws Exception {
        mvc.perform(get("/recruiter/interviews/export.xlsx")
                        .param("fromDate", "2026-09-01")
                        .param("toDate", "2026-08-01")
                        .with(user(recruiterA)))
                .andExpect(status().isBadRequest());
    }
}
