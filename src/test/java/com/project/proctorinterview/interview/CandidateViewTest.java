package com.project.proctorinterview.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.project.proctorinterview.TestDatabaseCleaner;
import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Recommendation;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.dto.CandidateQueryDtos.CandidateFilter;
import com.project.proctorinterview.common.Enums.SessionStatus;
import com.project.proctorinterview.report.Report;
import com.project.proctorinterview.report.ReportRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * One candidate, their interviews and their results.
 *
 * <p>Almost everything here is about <b>scope</b>. A recruiter must see the
 * candidates they scheduled and no others, and their counts must reflect their
 * own interviews rather than the candidate's whole history - otherwise the page
 * quietly discloses that someone else is also interviewing this person, which
 * the interview list has never done.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CandidateViewTest {

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
    private CandidateViewService service;

    private AppUserDetails admin;
    private AppUserDetails recruiterA;
    private AppUserDetails recruiterB;
    private AppUserDetails candidatePrincipal;
    private User shared;
    private User onlyForA;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        admin = new AppUserDetails(save("admin@test.local", Role.ADMIN, "Admin"));
        recruiterA = new AppUserDetails(save("a@test.local", Role.RECRUITER, "Recruiter A"));
        recruiterB = new AppUserDetails(save("b@test.local", Role.RECRUITER, "Recruiter B"));
        shared = save("shared@test.local", Role.CANDIDATE, "Shared Candidate");
        onlyForA = save("onlya@test.local", Role.CANDIDATE, "Only For A");
        candidatePrincipal = new AppUserDetails(shared);

        // The shared candidate has been interviewed by BOTH recruiters.
        scheduled(recruiterA.getId(), shared, "A first");
        scheduled(recruiterB.getId(), shared, "B first");
        scheduled(recruiterA.getId(), onlyForA, "A only");
    }

    private User save(String email, Role role, String name) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("$2a$10$notarealhash");
        u.setFullName(name);
        u.setRole(role);
        u.setEnabled(true);
        return users.save(u);
    }

    private Interview scheduled(Long recruiterId, User candidate, String name) {
        Interview i = new Interview();
        i.setInterviewName(name);
        i.setRecruiter(users.findById(recruiterId).orElseThrow());
        i.setCandidate(candidate);
        i.setScheduledAt(Instant.now().minus(Duration.ofDays(1)));
        i.setCandidateType(CandidateType.FRESHER);
        i.setDomain("Java");
        i.setInterviewType(InterviewType.TECHNICAL);
        i.setQuestionCount(3);
        i.setStatus(InterviewStatus.SCHEDULED);
        i.setInviteToken(UUID.randomUUID().toString());
        return interviews.save(i);
    }

    /** Completes an interview and gives it a report. */
    private void completeWithReport(Interview interview, int score) {
        interview.setStatus(InterviewStatus.COMPLETED);
        interviews.save(interview);

        InterviewSession session = new InterviewSession();
        session.setInterview(interview);
        session.setStatus(SessionStatus.COMPLETED);
        session.setStartedAt(Instant.now().minus(Duration.ofDays(1)));
        session.setEndedAt(Instant.now().minus(Duration.ofDays(1)).plusSeconds(600));
        sessions.save(session);

        Report report = new Report();
        report.setSession(session);
        report.setTechnicalScore(score);
        report.setCommunicationScore(score);
        report.setProblemSolvingScore(score);
        report.setRelevanceScore(score);
        report.setOverallScore(score);
        report.setRecommendation(Recommendation.FURTHER_REVIEW);
        report.setExplanation("Test.");
        report.setIntegrityFlag(false);
        reports.save(report);
    }

    // ---- scope --------------------------------------------------------------

    @Test
    void aRecruiterSeesOnlyCandidatesTheyScheduled() {
        var list = service.list(recruiterA.getId(), Role.RECRUITER, null);

        assertThat(list).extracting(CandidateViewService.CandidateRow::email)
                .containsExactlyInAnyOrder("shared@test.local", "onlya@test.local");
    }

    @Test
    void anotherRecruiterSeesADifferentSet() {
        var list = service.list(recruiterB.getId(), Role.RECRUITER, null);

        assertThat(list).extracting(CandidateViewService.CandidateRow::email)
                .containsExactly("shared@test.local");
    }

    @Test
    void anAdminSeesEveryCandidate() {
        var list = service.list(admin.getId(), Role.ADMIN, null);

        assertThat(list).extracting(CandidateViewService.CandidateRow::email)
                .containsExactlyInAnyOrder("shared@test.local", "onlya@test.local");
    }

    /**
     * The counts must be the viewer's own, not the candidate's whole history -
     * otherwise the page discloses that another recruiter is also interviewing
     * this person, which nothing else in the system does.
     */
    @Test
    void countsAreScopedToTheViewerNotTheCandidate() {
        var forA = service.list(recruiterA.getId(), Role.RECRUITER, null).stream()
                .filter(c -> c.email().equals("shared@test.local")).findFirst().orElseThrow();
        var forAdmin = service.list(admin.getId(), Role.ADMIN, null).stream()
                .filter(c -> c.email().equals("shared@test.local")).findFirst().orElseThrow();

        assertThat(forA.interviewCount()).isEqualTo(1);
        assertThat(forAdmin.interviewCount()).isEqualTo(2);
    }

    @Test
    void theDetailShowsOnlyTheViewersOwnInterviews() {
        var detail = service.detail(shared.getId(), recruiterA.getId(), Role.RECRUITER);

        assertThat(detail.interviews())
                .extracting(CandidateViewService.CandidateInterviewRow::displayName)
                .containsExactly("A first");
    }

    @Test
    void theAdminDetailShowsThemAll() {
        var detail = service.detail(shared.getId(), admin.getId(), Role.ADMIN);

        assertThat(detail.interviews())
                .extracting(CandidateViewService.CandidateInterviewRow::displayName)
                .containsExactlyInAnyOrder("A first", "B first");
    }

    /**
     * Not 403: a recruiter has no business learning that a candidate exists in
     * someone else's pipeline. Same reasoning as a stranger's invite link.
     */
    @Test
    void aCandidateTheRecruiterHasNeverInterviewedIsNotFound() {
        assertThatThrownBy(() -> service.detail(onlyForA.getId(), recruiterB.getId(), Role.RECRUITER))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void aCandidateCanNeverUseThisAtAll() {
        assertThatThrownBy(() -> service.list(shared.getId(), Role.CANDIDATE, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.detail(shared.getId(), shared.getId(), Role.CANDIDATE))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aStaffAccountIsNotItselfACandidate() {
        assertThatThrownBy(() -> service.detail(recruiterB.getId(), admin.getId(), Role.ADMIN))
                .isInstanceOf(ApiException.class);
    }

    // ---- search -------------------------------------------------------------

    @Test
    void searchMatchesNameAndEmailCaseInsensitively() {
        assertThat(service.list(recruiterA.getId(), Role.RECRUITER, searching("ONLY")))
                .extracting(CandidateViewService.CandidateRow::email).containsExactly("onlya@test.local");
        assertThat(service.list(recruiterA.getId(), Role.RECRUITER, searching("shared@")))
                .extracting(CandidateViewService.CandidateRow::email).containsExactly("shared@test.local");
    }

    @Test
    void searchNeverWidensTheScope() {
        // recruiterB has never interviewed onlyForA; searching for them by name
        // must not surface them.
        assertThat(service.list(recruiterB.getId(), Role.RECRUITER, searching("Only For A"))).isEmpty();
    }

    /** Free-text search is now one field on the filter; the rule is unchanged. */
    private static CandidateFilter searching(String term) {
        CandidateFilter filter = new CandidateFilter();
        filter.setSearch(term);
        return filter;
    }

    // ---- results ------------------------------------------------------------

    @Test
    void aCompletedInterviewCarriesItsResult() {
        Interview aFirst = interviews.findAll().stream()
                .filter(i -> "A first".equals(i.getInterviewName())).findFirst().orElseThrow();
        completeWithReport(aFirst, 72);

        var row = service.detail(shared.getId(), recruiterA.getId(), Role.RECRUITER)
                .interviews().getFirst();

        assertThat(row.hasReport()).isTrue();
        assertThat(row.overallScore()).isEqualTo(72);
        assertThat(row.sessionId()).isNotNull();
    }

    @Test
    void anInterviewWithNoReportSaysSoRatherThanScoringZero() {
        var row = service.detail(onlyForA.getId(), recruiterA.getId(), Role.RECRUITER)
                .interviews().getFirst();

        assertThat(row.hasReport()).isFalse();
        assertThat(row.overallScore()).isNull();
        assertThat(row.recommendation()).isNull();
    }

    // ---- the pages ----------------------------------------------------------

    @Test
    void theRecruiterListRendersWithTheirCandidatesOnly() throws Exception {
        String page = mvc.perform(get("/recruiter/candidates").with(user(recruiterB)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Shared Candidate");
        assertThat(page).doesNotContain("Only For A");
    }

    @Test
    void theAdminListRendersEveryone() throws Exception {
        mvc.perform(get("/admin/candidates").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Shared Candidate")))
                .andExpect(content().string(Matchers.containsString("Only For A")));
    }

    @Test
    void theDetailPageRenders() throws Exception {
        Long id = shared.getId();
        mvc.perform(get("/recruiter/candidates/{id}", id).with(user(recruiterA)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Shared Candidate")))
                .andExpect(content().string(Matchers.containsString("A first")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("B first"))));
    }

    @Test
    void anOutOfScopeDetailPageStaysHtmlRatherThanBecomingJson() throws Exception {
        mvc.perform(get("/recruiter/candidates/{id}", onlyForA.getId()).with(user(recruiterB)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("Only For A"))));
    }

    @Test
    void aCandidateIsRefusedBothPages() throws Exception {
        mvc.perform(get("/recruiter/candidates").with(user(candidatePrincipal)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/candidates").with(user(candidatePrincipal)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRecruiterIsRefusedTheAdminVariant() throws Exception {
        mvc.perform(get("/admin/candidates").with(user(recruiterA)))
                .andExpect(status().isForbidden());
    }
}
