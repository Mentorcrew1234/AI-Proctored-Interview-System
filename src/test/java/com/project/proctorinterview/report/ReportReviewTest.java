package com.project.proctorinterview.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.project.proctorinterview.common.Enums.ReviewDecision;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.common.Enums.SessionStatus;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewRepository;
import com.project.proctorinterview.interview.InterviewSession;
import com.project.proctorinterview.interview.InterviewSessionRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Recording the human decision the report already defers to.
 *
 * <p>Two rules matter most here, and both are about keeping things apart that
 * would be damaging to conflate: a review must never reach the candidate it is
 * about, and it must never alter the scores or the recommendation it sits
 * beside.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportReviewTest {

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
    private ReportReviewRepository reviewRepo;
    @Autowired
    private ReportReviewService service;

    private AppUserDetails admin;
    private AppUserDetails recruiterA;
    private AppUserDetails recruiterB;
    private AppUserDetails candidatePrincipal;
    private User candidate;
    private Long sessionId;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        admin = new AppUserDetails(save("admin@test.local", Role.ADMIN));
        recruiterA = new AppUserDetails(save("a@test.local", Role.RECRUITER));
        recruiterB = new AppUserDetails(save("b@test.local", Role.RECRUITER));
        candidate = save("cand@test.local", Role.CANDIDATE);
        candidatePrincipal = new AppUserDetails(candidate);
        sessionId = reportedSession().getId();
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

    /** A completed interview owned by recruiterA, with a RECOMMENDED report. */
    private InterviewSession reportedSession() {
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
        // Visible to the candidate, so the tests below prove the review stays
        // hidden even when the SCORES are shared.
        interview.setResultVisibleToCandidate(true);
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

    // ---- recording ----------------------------------------------------------

    @Test
    void aRecruiterCanRecordADecisionOnTheirOwnReport() {
        service.record(sessionId, recruiterA.getId(), Role.RECRUITER,
                ReviewDecision.ADVANCED, "Strong on concurrency.");

        var summary = service.summaryFor(sessionId);
        assertThat(summary.decided()).isTrue();
        assertThat(summary.current().decision()).isEqualTo(ReviewDecision.ADVANCED);
        assertThat(summary.current().note()).isEqualTo("Strong on concurrency.");
        assertThat(summary.current().reviewerName()).isEqualTo("Test RECRUITER");
    }

    @Test
    void anAdminCanDecideOnAnyReport() {
        service.record(sessionId, admin.getId(), Role.ADMIN, ReviewDecision.ON_HOLD, null);

        assertThat(service.summaryFor(sessionId).current().decision())
                .isEqualTo(ReviewDecision.ON_HOLD);
    }

    @Test
    void anEmptyNoteIsStoredAsAbsentRatherThanBlank() {
        service.record(sessionId, admin.getId(), Role.ADMIN, ReviewDecision.DECLINED, "   ");

        assertThat(service.summaryFor(sessionId).current().note()).isNull();
    }

    @Test
    void aDecisionIsRequired() {
        assertThatThrownBy(() -> service.record(sessionId, admin.getId(), Role.ADMIN, null, "why"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Choose a decision");
    }

    // ---- the history --------------------------------------------------------

    @Test
    void revisingADecisionKeepsTheEarlierOne() {
        service.record(sessionId, recruiterA.getId(), Role.RECRUITER, ReviewDecision.ON_HOLD, "Wait for references.");
        service.record(sessionId, recruiterA.getId(), Role.RECRUITER, ReviewDecision.ADVANCED, "References fine.");

        var summary = service.summaryFor(sessionId);
        // Newest stands, and the change is still visible - a judgement about a
        // person that silently changed is the wrong thing to lose.
        assertThat(summary.current().decision()).isEqualTo(ReviewDecision.ADVANCED);
        assertThat(summary.revised()).isTrue();
        assertThat(summary.history()).hasSize(2);
        assertThat(summary.history().get(1).decision()).isEqualTo(ReviewDecision.ON_HOLD);
    }

    @Test
    void anUndecidedReportSaysSoRatherThanGuessing() {
        var summary = service.summaryFor(sessionId);

        assertThat(summary.decided()).isFalse();
        assertThat(summary.revised()).isFalse();
        assertThat(summary.current()).isNull();
        assertThat(summary.history()).isEmpty();
    }

    // ---- agreement with the model ------------------------------------------

    @Test
    void agreementWithTheModelIsDerivedNotAssumed() {
        // The report says RECOMMENDED.
        service.record(sessionId, admin.getId(), Role.ADMIN, ReviewDecision.ADVANCED, null);
        assertThat(service.summaryFor(sessionId).current().agreesWithModel()).isTrue();

        service.record(sessionId, admin.getId(), Role.ADMIN, ReviewDecision.DECLINED, null);
        assertThat(service.summaryFor(sessionId).current().agreesWithModel()).isFalse();
    }

    // ---- what a review must never do ---------------------------------------

    @Test
    void aReviewNeverAltersTheScoresOrTheRecommendation() {
        Report before = reports.findBySessionId(sessionId).orElseThrow();
        int overall = before.getOverallScore();
        Recommendation recommendation = before.getRecommendation();
        String explanation = before.getExplanation();

        service.record(sessionId, admin.getId(), Role.ADMIN, ReviewDecision.DECLINED,
                "Disagreeing with the model outright.");

        Report after = reports.findBySessionId(sessionId).orElseThrow();
        assertThat(after.getOverallScore()).isEqualTo(overall);
        assertThat(after.getRecommendation()).isEqualTo(recommendation);
        assertThat(after.getExplanation()).isEqualTo(explanation);
    }

    // ---- authorization ------------------------------------------------------

    @Test
    void anotherRecruitersReportCannotBeDecidedOn() {
        assertThatThrownBy(() -> service.record(sessionId, recruiterB.getId(), Role.RECRUITER,
                ReviewDecision.ADVANCED, null))
                .isInstanceOf(ApiException.class);

        assertThat(reviewRepo.findAll()).isEmpty();
    }

    @Test
    void aCandidateCanNeverDecideOnTheirOwnReport() {
        // Even though resultVisibleToCandidate is true, so they CAN see the
        // scores. Reviewing is a different thing with no flag to enable it.
        assertThatThrownBy(() -> service.record(sessionId, candidate.getId(), Role.CANDIDATE,
                ReviewDecision.ADVANCED, "Please hire me"))
                .isInstanceOf(ApiException.class);

        assertThat(reviewRepo.findAll()).isEmpty();
    }

    @Test
    void aCandidateIsToldNotFoundRatherThanForbidden() {
        // Consistent with the invite-link rule: do not confirm that a facility
        // exists for a report just because someone asked about it.
        assertThatThrownBy(() -> service.assertCanReview(sessionId, candidate.getId(), Role.CANDIDATE))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(404));
    }

    // ---- the page -----------------------------------------------------------

    @Test
    void staffSeeTheDecisionSectionOnTheReportPage() throws Exception {
        mvc.perform(get("/reports/{id}", sessionId).with(user(recruiterA)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("panel-decision")))
                .andExpect(content().string(Matchers.containsString("No decision recorded yet")));
    }

    @Test
    void theStandingDecisionShowsItsOwnReason() throws Exception {
        // Regression: the reason used to appear only once the decision had been
        // superseded, so the note on the decision that actually STANDS was the
        // one nobody could read.
        service.record(sessionId, recruiterA.getId(), Role.RECRUITER,
                ReviewDecision.ON_HOLD, "Wants a second opinion.");

        mvc.perform(get("/reports/{id}", sessionId).with(user(recruiterA)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Wants a second opinion")))
                .andExpect(content().string(Matchers.containsString("Differs from the recommendation")));
    }

    @Test
    void aStandingDecisionWithNoReasonSaysSo() throws Exception {
        service.record(sessionId, recruiterA.getId(), Role.RECRUITER, ReviewDecision.ADVANCED, null);

        mvc.perform(get("/reports/{id}", sessionId).with(user(recruiterA)))
                .andExpect(content().string(Matchers.containsString("None given")));
    }

    @Test
    void theCandidateNeverSeesTheDecisionSection() throws Exception {
        service.record(sessionId, recruiterA.getId(), Role.RECRUITER,
                ReviewDecision.DECLINED, "Not a fit for this role.");

        String page = mvc.perform(get("/reports/{id}", sessionId).with(user(candidatePrincipal)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Their scores are visible; the internal judgement about them is not,
        // and neither is the note.
        assertThat(page).contains("78");
        assertThat(page).doesNotContain("panel-decision");
        assertThat(page).doesNotContain("Not a fit for this role");
        assertThat(page).doesNotContain("Declined");
    }

    @Test
    void theFormRecordsADecisionAndReturnsToTheDecisionTab() throws Exception {
        mvc.perform(post("/reports/{id}/review", sessionId).with(user(recruiterA)).with(csrf())
                        .param("decision", "ADVANCED")
                        .param("note", "Good problem solving."))
                .andExpect(status().is3xxRedirection());

        assertThat(service.summaryFor(sessionId).current().decision())
                .isEqualTo(ReviewDecision.ADVANCED);
    }

    @Test
    void theReviewPostRequiresACsrfToken() throws Exception {
        // /reports/** is the session chain, so CSRF applies - and it matters
        // here, because this endpoint writes a judgement about a person.
        mvc.perform(post("/reports/{id}/review", sessionId).with(user(recruiterA))
                        .param("decision", "ADVANCED"))
                .andExpect(status().isForbidden());

        assertThat(reviewRepo.findAll()).isEmpty();
    }

    @Test
    void aRefusedDecisionIsReportedOnThePageRatherThanAsJson() throws Exception {
        // recruiterB does not own this interview. Page errors stay HTML.
        mvc.perform(post("/reports/{id}/review", sessionId).with(user(recruiterB)).with(csrf())
                        .param("decision", "ADVANCED"))
                .andExpect(status().is3xxRedirection());

        assertThat(reviewRepo.findAll()).isEmpty();
    }

    @Test
    void theReportsListMarksWhichHaveBeenDecided() throws Exception {
        String before = mvc.perform(get("/recruiter/reports").with(user(recruiterA)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(before).doesNotContain(">Decided<");

        service.record(sessionId, recruiterA.getId(), Role.RECRUITER, ReviewDecision.ADVANCED, null);

        String after = mvc.perform(get("/recruiter/reports").with(user(recruiterA)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(after).contains(">Decided<");
    }

    @Test
    void decidedAmongIsOneLookupForAWholePage() {
        service.record(sessionId, admin.getId(), Role.ADMIN, ReviewDecision.ADVANCED, null);

        assertThat(service.decidedAmong(java.util.List.of(sessionId))).containsExactly(sessionId);
        assertThat(service.decidedAmong(java.util.List.of(sessionId, 999999L)))
                .containsExactly(sessionId);
        assertThat(service.decidedAmong(java.util.List.of())).isEmpty();
    }
}
