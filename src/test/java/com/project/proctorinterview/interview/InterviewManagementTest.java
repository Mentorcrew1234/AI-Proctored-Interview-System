package com.project.proctorinterview.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.project.proctorinterview.TestDatabaseCleaner;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.interview.dto.InterviewDtos.InterviewSummary;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.InterviewFilter;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Search, filtering, paging and insights for the interview management page.
 *
 * <p>The behaviour these tests care about most is that <b>filtering cannot widen
 * what a user may see</b>. A recruiter searching for another recruiter's
 * candidate must get nothing back, no matter what they put in the form.
 */
@SpringBootTest
@ActiveProfiles("test")
class InterviewManagementTest {

    @Autowired
    private InterviewQueryService queryService;
    @Autowired
    private InterviewService interviewService;
    @Autowired
    private InterviewRepository interviews;
    @Autowired
    private UserRepository users;
    @Autowired
    private TestDatabaseCleaner cleaner;

    private Long recruiterA;
    private Long recruiterB;
    private Long candidateAlpha;
    private Long candidateBeta;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        recruiterA = user("rec.a@test.local", "Recruiter A", Role.RECRUITER);
        recruiterB = user("rec.b@test.local", "Recruiter B", Role.RECRUITER);
        candidateAlpha = user("alpha@test.local", "Aswin Senthil", Role.CANDIDATE);
        candidateBeta = user("beta@test.local", "Priya Raman", Role.CANDIDATE);
    }

    private Long user(String email, String name, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("$2a$10$notarealhash");
        u.setFullName(name);
        u.setRole(role);
        u.setEnabled(true);
        return users.save(u).getId();
    }

    private Interview schedule(Long recruiterId, Long candidateId, String name,
            LocalDateTime when, String domain, CandidateType type, InterviewType interviewType) {
        CreateInterviewRequest r = new CreateInterviewRequest();
        r.setInterviewName(name);
        r.setCandidateId(candidateId);
        r.setScheduledAt(when);
        r.setCandidateType(type);
        if (type == CandidateType.EXPERIENCED) {
            r.setExperienceYears(3);
        }
        r.setDomain(domain);
        r.setInterviewType(interviewType);
        r.setQuestionCount(5);
        return interviewService.create(recruiterId, r);
    }

    private Interview withStatus(Interview interview, InterviewStatus status) {
        interview.setStatus(status);
        return interviews.save(interview);
    }

    private static InterviewFilter filter() {
        return new InterviewFilter();
    }

    private List<InterviewSummary> resultsFor(InterviewFilter f, Long userId, Role role) {
        return queryService.search(f, userId, role).rows();
    }

    // ---- interview naming ---------------------------------------------------

    @Test
    void theInterviewNameIsStoredAndReturned() {
        schedule(recruiterA, candidateAlpha, "Java Developer - Campus Drive 2026",
                LocalDateTime.now().plusDays(3), "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);

        var row = resultsFor(filter(), recruiterA, Role.RECRUITER).getFirst();
        assertThat(row.interviewName()).isEqualTo("Java Developer - Campus Drive 2026");
        assertThat(row.displayName()).isEqualTo("Java Developer - Campus Drive 2026");
    }

    @Test
    void manyCandidatesCanShareOneInterviewName() {
        // A drive spans candidates; the name is not unique by design.
        schedule(recruiterA, candidateAlpha, "Campus Drive 2026",
                LocalDateTime.now().plusDays(3), "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);
        schedule(recruiterA, candidateBeta, "Campus Drive 2026",
                LocalDateTime.now().plusDays(4), "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);

        InterviewFilter f = filter();
        f.setSearch("Campus Drive");
        assertThat(resultsFor(f, recruiterA, Role.RECRUITER)).hasSize(2);
    }

    @Test
    void anInterviewWithoutANameFallsBackToItsId() {
        // Rows created before naming existed have a null name.
        Interview interview = schedule(recruiterA, candidateAlpha, "Temp",
                LocalDateTime.now().plusDays(3), "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);
        interview.setInterviewName(null);
        interviews.save(interview);

        var row = resultsFor(filter(), recruiterA, Role.RECRUITER).getFirst();
        assertThat(row.interviewName()).isNull();
        assertThat(row.displayName()).isEqualTo("Interview #" + interview.getId());
    }

    // ---- authorization ------------------------------------------------------

    @Test
    void aRecruiterSeesOnlyTheirOwnInterviews() {
        schedule(recruiterA, candidateAlpha, "A's drive",
                LocalDateTime.now().plusDays(3), "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);
        schedule(recruiterB, candidateBeta, "B's drive",
                LocalDateTime.now().plusDays(3), "Python", CandidateType.FRESHER, InterviewType.TECHNICAL);

        assertThat(resultsFor(filter(), recruiterA, Role.RECRUITER)).hasSize(1);
        assertThat(resultsFor(filter(), recruiterB, Role.RECRUITER)).hasSize(1);
        assertThat(resultsFor(filter(), null, Role.ADMIN)).hasSize(2);
    }

    @Test
    void searchingForAnotherRecruitersCandidateReturnsNothing() {
        schedule(recruiterB, candidateBeta, "B's private drive",
                LocalDateTime.now().plusDays(3), "Python", CandidateType.FRESHER, InterviewType.TECHNICAL);

        // Recruiter A knows the candidate's exact name, email and the drive name.
        for (String term : List.of("Priya Raman", "beta@test.local", "B's private drive")) {
            InterviewFilter f = filter();
            f.setSearch(term);
            assertThat(resultsFor(f, recruiterA, Role.RECRUITER))
                    .as("search for '%s' must not leak another recruiter's interview", term)
                    .isEmpty();
        }
    }

    @Test
    void aRecruiterCannotWidenTheirScopeWithTheRecruiterFilter() {
        schedule(recruiterB, candidateBeta, "B's drive",
                LocalDateTime.now().plusDays(3), "Python", CandidateType.FRESHER, InterviewType.TECHNICAL);

        // Explicitly asking for the other recruiter's id still yields nothing:
        // the ownership predicate is ANDed on top, not replaced.
        InterviewFilter f = filter();
        f.setRecruiterId(recruiterB);
        assertThat(resultsFor(f, recruiterA, Role.RECRUITER)).isEmpty();
    }

    @Test
    void insightsAreScopedToTheCaller() {
        schedule(recruiterA, candidateAlpha, "A1", LocalDateTime.now().plusDays(3),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);
        schedule(recruiterB, candidateBeta, "B1", LocalDateTime.now().plusDays(3),
                "Python", CandidateType.FRESHER, InterviewType.TECHNICAL);
        schedule(recruiterB, candidateBeta, "B2", LocalDateTime.now().plusDays(4),
                "Python", CandidateType.FRESHER, InterviewType.TECHNICAL);

        assertThat(queryService.insights(recruiterA, Role.RECRUITER).total()).isEqualTo(1);
        assertThat(queryService.insights(recruiterB, Role.RECRUITER).total()).isEqualTo(2);
        assertThat(queryService.insights(null, Role.ADMIN).total()).isEqualTo(3);
    }

    // ---- search -------------------------------------------------------------

    @Test
    void searchMatchesNameCandidateAndEmailCaseInsensitively() {
        schedule(recruiterA, candidateAlpha, "Java Developer - Campus Drive 2026",
                LocalDateTime.now().plusDays(3), "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);
        schedule(recruiterA, candidateBeta, "Python Hiring 2026",
                LocalDateTime.now().plusDays(4), "Python", CandidateType.FRESHER, InterviewType.TECHNICAL);

        assertThat(searchCount("java developer")).isEqualTo(1);   // interview name, lower case
        assertThat(searchCount("ASWIN")).isEqualTo(1);            // candidate name, upper case
        assertThat(searchCount("beta@test.local")).isEqualTo(1);  // candidate email
        assertThat(searchCount("2026")).isEqualTo(2);             // partial across both
        assertThat(searchCount("nothing here")).isZero();
    }

    private int searchCount(String term) {
        InterviewFilter f = filter();
        f.setSearch(term);
        return resultsFor(f, recruiterA, Role.RECRUITER).size();
    }

    // ---- date range ---------------------------------------------------------

    @Test
    void dateRangeIsInclusiveOfBothEnds() {
        LocalDate day = LocalDate.now().plusDays(10);
        schedule(recruiterA, candidateAlpha, "On the boundary", day.atTime(23, 30),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);

        InterviewFilter f = filter();
        f.setFromDate(day);
        f.setToDate(day);
        // A late-evening interview on the "to" day must still be included.
        assertThat(resultsFor(f, recruiterA, Role.RECRUITER)).hasSize(1);
    }

    @Test
    void fromOrToAloneBothWork() {
        schedule(recruiterA, candidateAlpha, "Early", LocalDateTime.now().plusDays(2),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);
        schedule(recruiterA, candidateBeta, "Late", LocalDateTime.now().plusDays(20),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);

        InterviewFilter fromOnly = filter();
        fromOnly.setFromDate(LocalDate.now().plusDays(10));
        assertThat(resultsFor(fromOnly, recruiterA, Role.RECRUITER)).hasSize(1);

        InterviewFilter toOnly = filter();
        toOnly.setToDate(LocalDate.now().plusDays(10));
        assertThat(resultsFor(toOnly, recruiterA, Role.RECRUITER)).hasSize(1);

        assertThat(resultsFor(filter(), recruiterA, Role.RECRUITER)).hasSize(2);
    }

    @Test
    void fromAfterToIsRejectedWithAClearMessage() {
        InterviewFilter f = filter();
        f.setFromDate(LocalDate.now().plusDays(20));
        f.setToDate(LocalDate.now().plusDays(1));

        assertThatThrownBy(() -> queryService.search(f, recruiterA, Role.RECRUITER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot be after");
    }

    // ---- filters and combination -------------------------------------------

    @Test
    void filtersCombineWithAnd() {
        withStatus(schedule(recruiterA, candidateAlpha, "Java Campus", LocalDateTime.now().plusDays(3),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL), InterviewStatus.COMPLETED);
        withStatus(schedule(recruiterA, candidateBeta, "Java Campus", LocalDateTime.now().plusDays(4),
                "Java", CandidateType.EXPERIENCED, InterviewType.TECHNICAL), InterviewStatus.COMPLETED);
        schedule(recruiterA, candidateAlpha, "Java Campus", LocalDateTime.now().plusDays(5),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);

        // "Java Campus" + Completed + Fresher must match exactly one.
        InterviewFilter f = filter();
        f.setSearch("Java Campus");
        f.setStatus(InterviewStatus.COMPLETED);
        f.setExperienceType(CandidateType.FRESHER);

        assertThat(resultsFor(f, recruiterA, Role.RECRUITER)).hasSize(1);
    }

    @Test
    void statusDomainAndTypeFiltersEachNarrowTheResult() {
        schedule(recruiterA, candidateAlpha, "A", LocalDateTime.now().plusDays(3),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);
        schedule(recruiterA, candidateBeta, "B", LocalDateTime.now().plusDays(4),
                "Python", CandidateType.EXPERIENCED, InterviewType.HR_GENERAL);

        InterviewFilter domain = filter();
        domain.setDomain("Python");
        assertThat(resultsFor(domain, recruiterA, Role.RECRUITER)).hasSize(1);

        InterviewFilter type = filter();
        type.setInterviewType(InterviewType.HR_GENERAL);
        assertThat(resultsFor(type, recruiterA, Role.RECRUITER)).hasSize(1);

        InterviewFilter experience = filter();
        experience.setExperienceType(CandidateType.FRESHER);
        assertThat(resultsFor(experience, recruiterA, Role.RECRUITER)).hasSize(1);
    }

    @Test
    void anAdminCanFilterByRecruiter() {
        schedule(recruiterA, candidateAlpha, "A's", LocalDateTime.now().plusDays(3),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);
        schedule(recruiterB, candidateBeta, "B's", LocalDateTime.now().plusDays(3),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);

        InterviewFilter f = filter();
        f.setRecruiterId(recruiterB);
        assertThat(resultsFor(f, null, Role.ADMIN)).hasSize(1);
        assertThat(resultsFor(f, null, Role.ADMIN).getFirst().interviewName()).isEqualTo("B's");
    }

    // ---- tabs ---------------------------------------------------------------

    @Test
    void theQuickTabsSelectTheRightInterviews() {
        // Today, later, and one of each finished state.
        //
        // "Today" is anchored to the end of today's date rather than written as
        // "now plus a few hours": it has to be both today (for the TODAY tab)
        // and still in the future (for UPCOMING), and now+3h stops being today
        // once the clock passes 21:00.
        schedule(recruiterA, candidateAlpha, "Today", LocalDate.now().atTime(23, 59),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);
        schedule(recruiterA, candidateBeta, "Next week", LocalDateTime.now().plusDays(7),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);
        withStatus(schedule(recruiterA, candidateAlpha, "Done", LocalDateTime.now().plusDays(2),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL), InterviewStatus.COMPLETED);
        withStatus(schedule(recruiterA, candidateBeta, "Called off", LocalDateTime.now().plusDays(2),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL), InterviewStatus.CANCELLED);
        withStatus(schedule(recruiterA, candidateAlpha, "Running", LocalDateTime.now().plusDays(2),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL), InterviewStatus.IN_PROGRESS);

        assertThat(tabCount("ALL")).isEqualTo(5);
        assertThat(tabCount("COMPLETED")).isEqualTo(1);
        assertThat(tabCount("CANCELLED")).isEqualTo(1);
        assertThat(tabCount("IN_PROGRESS")).isEqualTo(1);
        // Today: only the one scheduled within today's date.
        assertThat(tabCount("TODAY")).isEqualTo(1);
        // Upcoming: in the future AND still open. That is Today, Next week and
        // Running - the completed and cancelled ones are excluded even though
        // their scheduled time is also in the future.
        assertThat(tabCount("UPCOMING")).isEqualTo(3);
    }

    private int tabCount(String tab) {
        InterviewFilter f = filter();
        f.setTab(tab);
        return resultsFor(f, recruiterA, Role.RECRUITER).size();
    }

    @Test
    void anUnknownTabFallsBackToAllRatherThanFailing() {
        schedule(recruiterA, candidateAlpha, "A", LocalDateTime.now().plusDays(3),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);

        assertThat(tabCount("NOT_A_REAL_TAB")).isEqualTo(1);
    }

    // ---- sorting and paging -------------------------------------------------

    @Test
    void sortingByNameAndDateBothWork() {
        schedule(recruiterA, candidateAlpha, "Zebra drive", LocalDateTime.now().plusDays(9),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);
        schedule(recruiterA, candidateBeta, "Alpha drive", LocalDateTime.now().plusDays(3),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);

        InterviewFilter byName = filter();
        byName.setSortBy("interviewName");
        byName.setSortDirection("asc");
        assertThat(resultsFor(byName, recruiterA, Role.RECRUITER).getFirst().interviewName())
                .isEqualTo("Alpha drive");

        InterviewFilter byDate = filter();
        byDate.setSortBy("scheduledAt");
        byDate.setSortDirection("asc");
        assertThat(resultsFor(byDate, recruiterA, Role.RECRUITER).getFirst().interviewName())
                .isEqualTo("Alpha drive");

        byDate.setSortDirection("desc");
        assertThat(resultsFor(byDate, recruiterA, Role.RECRUITER).getFirst().interviewName())
                .isEqualTo("Zebra drive");
    }

    @Test
    void upcomingDefaultsToChronologicalAndCompletedToNewestFirst() {
        schedule(recruiterA, candidateAlpha, "Sooner", LocalDateTime.now().plusDays(2),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);
        schedule(recruiterA, candidateBeta, "Later", LocalDateTime.now().plusDays(9),
                "Java", CandidateType.FRESHER, InterviewType.TECHNICAL);

        InterviewFilter upcoming = filter();
        upcoming.setTab("UPCOMING");
        assertThat(resultsFor(upcoming, recruiterA, Role.RECRUITER).getFirst().interviewName())
                .isEqualTo("Sooner");

        withStatus(interviews.findAll().get(0), InterviewStatus.COMPLETED);
        withStatus(interviews.findAll().get(1), InterviewStatus.COMPLETED);

        InterviewFilter completed = filter();
        completed.setTab("COMPLETED");
        assertThat(resultsFor(completed, recruiterA, Role.RECRUITER).getFirst().interviewName())
                .isEqualTo("Later");
    }

    @Test
    void paginationSplitsResultsAndReportsTotalsCorrectly() {
        for (int i = 0; i < 25; i++) {
            schedule(recruiterA, candidateAlpha, "Drive " + i,
                    LocalDateTime.now().plusDays(2 + i), "Java",
                    CandidateType.FRESHER, InterviewType.TECHNICAL);
        }

        InterviewFilter page0 = filter();
        page0.setSize(20);
        var first = queryService.search(page0, recruiterA, Role.RECRUITER);
        assertThat(first.rows()).hasSize(20);
        assertThat(first.totalElements()).isEqualTo(25);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.hasPrevious()).isFalse();

        InterviewFilter page1 = filter();
        page1.setSize(20);
        page1.setPage(1);
        var second = queryService.search(page1, recruiterA, Role.RECRUITER);
        assertThat(second.rows()).hasSize(5);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.firstItem()).isEqualTo(21);
        assertThat(second.lastItem()).isEqualTo(25);
    }

    @Test
    void filteringAndPagingWorkTogether() {
        for (int i = 0; i < 15; i++) {
            schedule(recruiterA, candidateAlpha, "Java drive " + i,
                    LocalDateTime.now().plusDays(2 + i), "Java",
                    CandidateType.FRESHER, InterviewType.TECHNICAL);
        }
        for (int i = 0; i < 5; i++) {
            schedule(recruiterA, candidateBeta, "Python drive " + i,
                    LocalDateTime.now().plusDays(2 + i), "Python",
                    CandidateType.FRESHER, InterviewType.TECHNICAL);
        }

        InterviewFilter f = filter();
        f.setDomain("Java");
        f.setSize(20);
        // Total must reflect the filter, not the whole table.
        assertThat(queryService.search(f, recruiterA, Role.RECRUITER).totalElements()).isEqualTo(15);
    }

    // ---- active filters -----------------------------------------------------

    @Test
    void activeFiltersAreListedAndClearedByDefault() {
        InterviewFilter none = filter();
        assertThat(none.hasActiveFilters()).isFalse();
        assertThat(none.activeFilters()).isEmpty();

        InterviewFilter some = filter();
        some.setSearch("Java Campus");
        some.setStatus(InterviewStatus.COMPLETED);
        some.setExperienceType(CandidateType.FRESHER);
        some.setTab("COMPLETED");

        assertThat(some.hasActiveFilters()).isTrue();
        assertThat(some.activeFilters()).hasSize(4);
    }
}
