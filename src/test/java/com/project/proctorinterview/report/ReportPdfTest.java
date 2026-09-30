package com.project.proctorinterview.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewRepository;
import com.project.proctorinterview.interview.InterviewSession;
import com.project.proctorinterview.interview.InterviewSessionRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * The detailed report as a PDF.
 *
 * <p>Authorization is the point of most of these: the PDF is a second door onto
 * report data, and it must be exactly as hard to walk through as the HTML page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportPdfTest {

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
    private ReportPdfService pdfService;
    @Autowired
    private ReportService reportService;

    private AppUserDetails admin;
    private AppUserDetails recruiterA;
    private AppUserDetails recruiterB;
    private User candidate;
    private Long sessionId;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        admin = new AppUserDetails(save("admin@test.local", Role.ADMIN));
        recruiterA = new AppUserDetails(save("a@test.local", Role.RECRUITER));
        recruiterB = new AppUserDetails(save("b@test.local", Role.RECRUITER));
        candidate = save("cand@test.local", Role.CANDIDATE);
        sessionId = reportedSession(true).getId();
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

    /** A completed interview owned by recruiterA, with a report. */
    private InterviewSession reportedSession(boolean visibleToCandidate) {
        Interview interview = new Interview();
        interview.setInterviewName("Java Developer - Campus Drive");
        interview.setRecruiter(users.findById(recruiterA.getId()).orElseThrow());
        interview.setCandidate(candidate);
        interview.setScheduledAt(Instant.now().minus(Duration.ofDays(1)));
        interview.setCandidateType(CandidateType.FRESHER);
        interview.setDomain("Java");
        interview.setInterviewType(InterviewType.TECHNICAL);
        interview.setQuestionCount(5);
        interview.setStatus(InterviewStatus.COMPLETED);
        interview.setInviteToken(UUID.randomUUID().toString());
        interview.setResultVisibleToCandidate(visibleToCandidate);
        interviews.save(interview);

        InterviewSession session = new InterviewSession();
        session.setInterview(interview);
        session.setStatus(SessionStatus.COMPLETED);
        session.setStartedAt(Instant.now().minus(Duration.ofDays(1)));
        session.setEndedAt(Instant.now().minus(Duration.ofDays(1)).plusSeconds(900));
        sessions.save(session);

        Report report = new Report();
        report.setSession(session);
        report.setTechnicalScore(80);
        report.setCommunicationScore(75);
        report.setProblemSolvingScore(70);
        report.setRelevanceScore(85);
        report.setOverallScore(78);
        report.setRecommendation(Recommendation.RECOMMENDED);
        report.setExplanation("Answered 5 of 5. Consistent, practical answers.");
        report.setIntegrityFlag(false);
        reports.save(report);

        return session;
    }

    // ---- generation ---------------------------------------------------------

    @Test
    void buildsARealPdfFromTheSameReadModelAsTheHtmlPage() {
        byte[] pdf = pdfService.build(reportService.view(sessionId));

        assertThat(pdf).isNotEmpty();
        // %PDF- magic.
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void theDownloadIsServedAsAPdfAttachment() throws Exception {
        var response = mvc.perform(get("/reports/{id}/export.pdf", sessionId).with(user(recruiterA)))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader("Content-Disposition")).contains("attachment").contains(".pdf");
        assertThat(new String(response.getContentAsByteArray(), 0, 5)).isEqualTo("%PDF-");
    }

    // ---- authorization ------------------------------------------------------

    @Test
    void theOwningRecruiterAndAnyAdminMayDownloadIt() throws Exception {
        mvc.perform(get("/reports/{id}/export.pdf", sessionId).with(user(recruiterA)))
                .andExpect(status().isOk());
        mvc.perform(get("/reports/{id}/export.pdf", sessionId).with(user(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void anotherRecruiterCannotDownloadIt() throws Exception {
        mvc.perform(get("/reports/{id}/export.pdf", sessionId).with(user(recruiterB)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theCandidateMayDownloadTheirOwnReportOnlyWhenTheRecruiterMadeItVisible() throws Exception {
        AppUserDetails asCandidate = new AppUserDetails(candidate);

        // Visible: allowed.
        mvc.perform(get("/reports/{id}/export.pdf", sessionId).with(user(asCandidate)))
                .andExpect(status().isOk());

        // A second interview whose result was not shared.
        Long hidden = reportedSession(false).getId();
        mvc.perform(get("/reports/{id}/export.pdf", hidden).with(user(asCandidate)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownSessionIsNotFound() throws Exception {
        mvc.perform(get("/reports/{id}/export.pdf", 999999).with(user(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousUsersAreSentToLogin() throws Exception {
        mvc.perform(get("/reports/{id}/export.pdf", sessionId))
                .andExpect(status().is3xxRedirection());
    }
}
