package com.project.proctorinterview.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

import com.project.proctorinterview.TestDatabaseCleaner;
import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Recommendation;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.common.Enums.SessionStatus;
import com.project.proctorinterview.interview.CandidateInterviewService;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewRepository;
import com.project.proctorinterview.interview.InterviewSession;
import com.project.proctorinterview.interview.InterviewSessionRepository;
import com.project.proctorinterview.interview.dto.CandidateDtos.CandidateDashboardView;
import com.project.proctorinterview.interview.dto.CandidateDtos.CandidateInterviewRow;
import com.project.proctorinterview.report.Report;
import com.project.proctorinterview.report.ReportRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * The candidate's own dashboard: one permanent account, many independent
 * interviews, made visible without changing how any of them are created.
 *
 * <p>The guarantee these tests exist for: a candidate sees exactly their own
 * interviews, correctly grouped, and never anyone else's - including through
 * the existing report page, which candidates can now reach for the first time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CandidateDashboardTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mvc;
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
    private CandidateInterviewService dashboardService;

    private AppUserDetails recruiter;
    private AppUserDetails candidateA;
    private AppUserDetails candidateB;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        recruiter = new AppUserDetails(save("iv@test.local", Role.RECRUITER));
        candidateA = new AppUserDetails(save("a@test.local", Role.CANDIDATE));
        candidateB = new AppUserDetails(save("b@test.local", Role.CANDIDATE));
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

    /**
     * A fixed time on today's date.
     *
     * <p>Anchored to the date rather than expressed as "now plus a few hours",
     * which stops being today once the clock passes 21:00 and made these tests
     * fail only in the evening. Grouping is by calendar date, so a past time
     * today is still "today".
     */
    private static Instant todayAt(int hour, int minute) {
        return LocalDate.now(ZoneId.systemDefault())
                .atTime(hour, minute)
                .atZone(ZoneId.systemDefault())
                .toInstant();
    }

    private Interview interview(User candidate, InterviewStatus status, Instant scheduledAt) {
        Interview i = new Interview();
        i.setInterviewName("Java Developer - Campus Drive");
        i.setRecruiter(users.findById(recruiter.getId()).orElseThrow());
        i.setCandidate(candidate);
        i.setScheduledAt(scheduledAt);
        i.setCandidateType(CandidateType.FRESHER);
        i.setDomain("Java");
        i.setInterviewType(InterviewType.TECHNICAL);
        i.setQuestionCount(5);
        i.setStatus(status);
        i.setInviteToken(UUID.randomUUID().toString());
        i.setResultVisibleToCandidate(true);
        return interviews.save(i);
    }

    /** A finished session plus its report, for report-visibility tests. */
    private InterviewSession completedSessionWithReport(Interview interview) {
        InterviewSession session = new InterviewSession();
        session.setInterview(interview);
        session.setStatus(SessionStatus.COMPLETED);
        session.setStartedAt(Instant.now().minus(Duration.ofDays(1)));
        session.setEndedAt(Instant.now().minus(Duration.ofDays(1)).plusSeconds(600));
        session = sessions.save(session);

        Report report = new Report();
        report.setSession(session);
        report.setTechnicalScore(80);
        report.setCommunicationScore(80);
        report.setProblemSolvingScore(80);
        report.setRelevanceScore(80);
        report.setOverallScore(80);
        report.setRecommendation(Recommendation.RECOMMENDED);
        report.setExplanation("Solid answers throughout.");
        report.setIntegrityFlag(false);
        reports.save(report);

        return session;
    }

    // ---- empty state ----------------------------------------------------

    @Test
    void candidateWithNoInterviewsSeesAnEmptyState() throws Exception {
        mvc.perform(get("/").with(user(candidateA)))
                .andExpect(status().isOk())
                .andExpect(view().name("home"))
                .andExpect(content().string(containsString("You have no interviews yet")));
    }

    /**
     * The populated dashboard actually renders.
     *
     * <p>This is the case the suite used to miss. Only the empty state was
     * asserted, so a template fault in the row fragment - which is reached only
     * when there <em>is</em> a row - produced a half-written page at runtime
     * while every test stayed green. Rendering to completion is the assertion
     * here: the candidate's name reaches the page, and so does an interview.
     */
    @Test
    void candidateWithInterviewsSeesThemRendered() throws Exception {
        User candidate = users.findById(candidateA.getId()).orElseThrow();
        interview(candidate, InterviewStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(6)));
        interview(candidate, InterviewStatus.COMPLETED, Instant.now().minus(Duration.ofDays(3)));

        mvc.perform(get("/").with(user(candidateA)))
                .andExpect(status().isOk())
                .andExpect(view().name("home"))
                .andExpect(content().string(containsString("Java Developer - Campus Drive")))
                .andExpect(content().string(containsString("interview-row")))
                .andExpect(content().string(not(containsString("You have no interviews yet"))));
    }

    // ---- grouping ---------------------------------------------------------

    @Test
    void aScheduledInterviewAppearsUnderUpcoming() {
        User candidate = users.findById(candidateA.getId()).orElseThrow();
        interview(candidate, InterviewStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(10)));

        CandidateDashboardView dashboard = dashboardService.dashboard(candidateA.getId());
        assertThat(dashboard.totalCount()).isEqualTo(1);
        assertThat(dashboard.upcoming()).hasSize(1);
        assertThat(dashboard.today()).isEmpty();
    }

    @Test
    void aTodaysInterviewAppearsUnderTodayNotUpcoming() {
        User candidate = users.findById(candidateA.getId()).orElseThrow();

        // Midday today, not "now + 2 hours": the grouping compares calendar
        // dates, so a relative offset silently moves to tomorrow whenever the
        // suite runs late in the evening, and the test failed by the clock
        // rather than by a change in behaviour.
        Instant middayToday = LocalDate.now(ZoneId.systemDefault())
                .atTime(12, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant();
        interview(candidate, InterviewStatus.SCHEDULED, middayToday);

        CandidateDashboardView dashboard = dashboardService.dashboard(candidateA.getId());
        assertThat(dashboard.today()).hasSize(1);
        assertThat(dashboard.upcoming()).isEmpty();
    }

    @Test
    void aCompletedInterviewAppearsUnderCompleted() {
        User candidate = users.findById(candidateA.getId()).orElseThrow();
        interview(candidate, InterviewStatus.COMPLETED, Instant.now().minus(Duration.ofDays(2)));

        CandidateDashboardView dashboard = dashboardService.dashboard(candidateA.getId());
        assertThat(dashboard.completed()).hasSize(1);
    }

    @Test
    void anInProgressInterviewAppearsUnderInProgress() {
        User candidate = users.findById(candidateA.getId()).orElseThrow();
        interview(candidate, InterviewStatus.IN_PROGRESS, Instant.now());

        CandidateDashboardView dashboard = dashboardService.dashboard(candidateA.getId());
        assertThat(dashboard.inProgress()).hasSize(1);
    }

    @Test
    void aCancelledInterviewAppearsUnderCancelledAndIsNotStartable() {
        User candidate = users.findById(candidateA.getId()).orElseThrow();
        interview(candidate, InterviewStatus.CANCELLED, Instant.now().plus(Duration.ofDays(3)));

        CandidateDashboardView dashboard = dashboardService.dashboard(candidateA.getId());
        assertThat(dashboard.cancelled()).hasSize(1);
        assertThat(dashboard.cancelled().getFirst().startable()).isFalse();
    }

    @Test
    void multipleInterviewsOnDifferentDatesGroupCorrectlyAndSortNearestUpcomingFirst() {
        User candidate = users.findById(candidateA.getId()).orElseThrow();
        interview(candidate, InterviewStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(20))); // far
        interview(candidate, InterviewStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(5)));  // nearer
        interview(candidate, InterviewStatus.SCHEDULED, todayAt(9, 0));                           // today
        interview(candidate, InterviewStatus.COMPLETED, Instant.now().minus(Duration.ofDays(1)));

        CandidateDashboardView dashboard = dashboardService.dashboard(candidateA.getId());
        assertThat(dashboard.totalCount()).isEqualTo(4);
        assertThat(dashboard.today()).hasSize(1);
        assertThat(dashboard.upcoming()).hasSize(2);
        assertThat(dashboard.completed()).hasSize(1);
        assertThat(dashboard.upcoming().get(0).scheduledAt())
                .isBefore(dashboard.upcoming().get(1).scheduledAt());
    }

    @Test
    void aCandidateWithFiftyInterviewsSeesAllOfThem() {
        User candidate = users.findById(candidateA.getId()).orElseThrow();
        for (int i = 0; i < 50; i++) {
            interview(candidate, InterviewStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(1 + i)));
        }

        CandidateDashboardView dashboard = dashboardService.dashboard(candidateA.getId());
        assertThat(dashboard.totalCount()).isEqualTo(50);
    }

    // ---- start / resume eligibility ---------------------------------------

    @Test
    void aFutureScheduledInterviewIsNotStartableYet() {
        User candidate = users.findById(candidateA.getId()).orElseThrow();
        interview(candidate, InterviewStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(1)));
        interview(candidate, InterviewStatus.COMPLETED, Instant.now().minus(Duration.ofDays(1)));

        CandidateDashboardView dashboard = dashboardService.dashboard(candidateA.getId());
        assertThat(dashboard.upcoming().getFirst().startable()).isFalse();
        assertThat(dashboard.completed().getFirst().startable()).isFalse();
    }

    @Test
    void aScheduledInterviewBecomesStartableOnceItsTimeArrives() {
        User candidate = users.findById(candidateA.getId()).orElseThrow();
        // Its time has passed - the window is open.
        interview(candidate, InterviewStatus.SCHEDULED, Instant.now().minus(Duration.ofMinutes(5)));
        // Still ahead - not yet.
        interview(candidate, InterviewStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(1)));

        CandidateDashboardView dashboard = dashboardService.dashboard(candidateA.getId());

        // Across both buckets rather than assuming which one each lands in:
        // that depends on the time of day and is covered by the grouping tests.
        List<CandidateInterviewRow> open = new ArrayList<>(dashboard.today());
        open.addAll(dashboard.upcoming());

        assertThat(open).hasSize(2);
        assertThat(open.stream().filter(CandidateInterviewRow::startable).count()).isEqualTo(1);
    }

    @Test
    void anInProgressInterviewIsAlwaysStartableRegardlessOfScheduledTime() {
        User candidate = users.findById(candidateA.getId()).orElseThrow();
        interview(candidate, InterviewStatus.IN_PROGRESS, Instant.now().plus(Duration.ofDays(1)));

        CandidateDashboardView dashboard = dashboardService.dashboard(candidateA.getId());
        assertThat(dashboard.inProgress().getFirst().startable()).isTrue();
    }

    // ---- report visibility -------------------------------------------------

    @Test
    void aCompletedInterviewWithAVisibleReportOffersAReportLink() {
        User candidate = users.findById(candidateA.getId()).orElseThrow();
        Interview interview = interview(candidate, InterviewStatus.COMPLETED, Instant.now().minus(Duration.ofDays(1)));
        completedSessionWithReport(interview);

        CandidateDashboardView dashboard = dashboardService.dashboard(candidateA.getId());
        assertThat(dashboard.completed().getFirst().reportViewable()).isTrue();
        assertThat(dashboard.completed().getFirst().sessionId()).isNotNull();
    }

    @Test
    void aCompletedInterviewWithoutAVisibleReportOffersNoReportLink() {
        User candidate = users.findById(candidateA.getId()).orElseThrow();
        Interview interview = interview(candidate, InterviewStatus.COMPLETED, Instant.now().minus(Duration.ofDays(1)));
        interview.setResultVisibleToCandidate(false);
        interviews.save(interview);
        completedSessionWithReport(interview);

        CandidateDashboardView dashboard = dashboardService.dashboard(candidateA.getId());
        assertThat(dashboard.completed().getFirst().reportViewable()).isFalse();
    }

    // ---- isolation between candidates --------------------------------------

    @Test
    void candidateACannotSeeCandidateBsInterviewsOnTheDashboard() {
        User b = users.findById(candidateB.getId()).orElseThrow();
        interview(b, InterviewStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(5)));

        CandidateDashboardView dashboard = dashboardService.dashboard(candidateA.getId());
        assertThat(dashboard.totalCount()).isZero();
    }

    @Test
    void candidateACannotOpenCandidateBsReportEvenWhenVisible() throws Exception {
        User b = users.findById(candidateB.getId()).orElseThrow();
        Interview interview = interview(b, InterviewStatus.COMPLETED, Instant.now().minus(Duration.ofDays(1)));
        InterviewSession session = completedSessionWithReport(interview);

        // The report page does not confirm the session exists to a non-owner.
        mvc.perform(get("/reports/{id}", session.getId()).with(user(candidateA)))
                .andExpect(status().isOk())
                .andExpect(view().name("report/unavailable"));
    }

    @Test
    void candidateACannotReachCandidateBsReportThroughTheApiEither() throws Exception {
        User b = users.findById(candidateB.getId()).orElseThrow();
        Interview interview = interview(b, InterviewStatus.COMPLETED, Instant.now().minus(Duration.ofDays(1)));
        InterviewSession session = completedSessionWithReport(interview);

        mvc.perform(get("/api/reports/{id}", session.getId()).with(user(candidateA)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCandidateCanOpenTheirOwnVisibleReport() throws Exception {
        User b = users.findById(candidateB.getId()).orElseThrow();
        Interview interview = interview(b, InterviewStatus.COMPLETED, Instant.now().minus(Duration.ofDays(1)));
        InterviewSession session = completedSessionWithReport(interview);

        mvc.perform(get("/reports/{id}", session.getId()).with(user(candidateB)))
                .andExpect(status().isOk())
                .andExpect(view().name("report/view"));
    }

    // ---- scheduling compatibility ------------------------------------------

    @Test
    void anExistingCandidateAccountAndItsPreviousInterviewsAreUntouchedByANewOne() {
        User candidate = users.findById(candidateA.getId()).orElseThrow();
        Interview earlier = interview(candidate, InterviewStatus.COMPLETED, Instant.now().minus(Duration.ofDays(10)));
        String earlierToken = earlier.getInviteToken();
        String originalPasswordHash = candidate.getPasswordHash();

        // A second, independent interview for the same permanent account.
        interview(candidate, InterviewStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(3)));

        CandidateDashboardView dashboard = dashboardService.dashboard(candidateA.getId());
        assertThat(dashboard.totalCount()).isEqualTo(2);
        assertThat(dashboard.completed().getFirst().inviteToken()).isEqualTo(earlierToken);

        User reloaded = users.findById(candidateA.getId()).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isEqualTo(originalPasswordHash);
        assertThat(reloaded.getRole()).isEqualTo(Role.CANDIDATE);
        assertThat(users.findByRoleOrderByFullNameAsc(Role.CANDIDATE)).hasSize(2); // A and B, no new account
    }
}
