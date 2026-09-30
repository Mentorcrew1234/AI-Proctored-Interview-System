package com.project.proctorinterview.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDateTime;
import java.util.List;

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
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.CandidateViewService.CandidateRow;
import com.project.proctorinterview.interview.dto.CandidateQueryDtos;
import com.project.proctorinterview.interview.dto.CandidateQueryDtos.CandidateFilter;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;
import com.project.proctorinterview.user.UserService;
import com.project.proctorinterview.user.dto.UserDtos.CreateUserRequest;
import com.project.proctorinterview.user.dto.UserDtos.SelectableCandidate;

/**
 * Dynamic filtering on the candidate list.
 *
 * <p>Every filter is optional, they combine with AND, and the options each
 * dropdown offers come from the candidate data rather than a fixed list. The
 * tests below cover each filter alone, several together, clearing them, and -
 * the part that is a security property rather than a feature - that a filter
 * can never widen what a recruiter is allowed to see.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CandidateFilterTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private UserService userService;
    @Autowired
    private CandidateViewService candidateView;
    @Autowired
    private InterviewService interviewService;
    @Autowired
    private TestDatabaseCleaner cleaner;

    private AppUserDetails recruiter;
    private AppUserDetails otherRecruiter;
    private AppUserDetails admin;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        admin = new AppUserDetails(save("filter.admin@demo.local", "Admin", Role.ADMIN));
        recruiter = new AppUserDetails(save("filter.recruiter@demo.local", "Priya", Role.RECRUITER));
        otherRecruiter = new AppUserDetails(save("other.recruiter@demo.local", "Other", Role.RECRUITER));

        // Three candidates the main recruiter has interviewed.
        scheduleWith(recruiter, candidate("arun@demo.local", "Arun Kumar",
                "ABC College", "Coimbatore", "Java, Spring Boot, SQL", "Java",
                CandidateType.FRESHER, null));
        scheduleWith(recruiter, candidate("priya@demo.local", "Priya Raman",
                "ABC College", "Chennai", "Python, Django", "Python",
                CandidateType.EXPERIENCED, 4));
        scheduleWith(recruiter, candidate("meena@demo.local", "Meena S",
                "XYZ Institute", "Coimbatore", "Java, React", "Java",
                CandidateType.EXPERIENCED, 7));
    }

    // ---- one filter at a time ------------------------------------------------

    @Test
    void filteringByCollegeReturnsOnlyThatCollege() {
        assertThat(emailsFrom(filter(f -> f.setCollegeName("ABC College"))))
                .containsExactlyInAnyOrder("arun@demo.local", "priya@demo.local");
    }

    @Test
    void filteringByLocationReturnsOnlyThatLocation() {
        assertThat(emailsFrom(filter(f -> f.setLocation("Coimbatore"))))
                .containsExactlyInAnyOrder("arun@demo.local", "meena@demo.local");
    }

    @Test
    void filteringByExperienceTypeSplitsFreshersFromExperienced() {
        assertThat(emailsFrom(filter(f -> f.setExperienceType(CandidateType.FRESHER))))
                .containsExactly("arun@demo.local");
        assertThat(emailsFrom(filter(f -> f.setExperienceType(CandidateType.EXPERIENCED))))
                .containsExactlyInAnyOrder("priya@demo.local", "meena@demo.local");
    }

    @Test
    void filteringByPrimaryDomainReturnsOnlyThatDomain() {
        assertThat(emailsFrom(filter(f -> f.setDomain("Python"))))
                .containsExactly("priya@demo.local");
    }

    @Test
    void filteringByYearsRangeIsInclusiveOfBothBounds() {
        assertThat(emailsFrom(filter(f -> f.setMinExperienceYears(4))))
                .containsExactlyInAnyOrder("priya@demo.local", "meena@demo.local");
        assertThat(emailsFrom(filter(f -> f.setMaxExperienceYears(4))))
                .containsExactly("priya@demo.local");
        assertThat(emailsFrom(filter(f -> {
            f.setMinExperienceYears(5);
            f.setMaxExperienceYears(10);
        }))).containsExactly("meena@demo.local");
    }

    /**
     * A fresher has no recorded years, so a years bound cannot be satisfied by
     * one. Counting them as zero would put every fresher in "0 to 2 years",
     * which is a different question from the one the user asked.
     */
    @Test
    void aFresherIsNotSweptIntoAYearsRange() {
        assertThat(emailsFrom(filter(f -> f.setMinExperienceYears(0))))
                .doesNotContain("arun@demo.local");
    }

    // ---- skills, which are a list in one column ------------------------------

    @Test
    void filteringBySkillMatchesAnyEntryInTheList() {
        assertThat(emailsFrom(filter(f -> f.setSkill("Java"))))
                .containsExactlyInAnyOrder("arun@demo.local", "meena@demo.local");
        assertThat(emailsFrom(filter(f -> f.setSkill("SQL"))))
                .containsExactly("arun@demo.local");
    }

    /**
     * The reason the skill filter splits rather than running a LIKE: "Java"
     * must not match "JavaScript".
     */
    @Test
    void aSkillFilterIsNotASubstringMatch() {
        scheduleWith(recruiter, candidate("js@demo.local", "JS Person",
                "ABC College", "Chennai", "JavaScript, CSS", "JavaScript",
                CandidateType.FRESHER, null));

        assertThat(emailsFrom(filter(f -> f.setSkill("Java"))))
                .doesNotContain("js@demo.local");
        assertThat(emailsFrom(filter(f -> f.setSkill("JavaScript"))))
                .containsExactly("js@demo.local");
    }

    @Test
    void skillMatchingIgnoresCase() {
        assertThat(emailsFrom(filter(f -> f.setSkill("java"))))
                .containsExactlyInAnyOrder("arun@demo.local", "meena@demo.local");
    }

    // ---- combining, changing and clearing ------------------------------------

    /** The example from the brief: College = ABC + Experience = Fresher. */
    @Test
    void filtersCombineWithAnd() {
        assertThat(emailsFrom(filter(f -> {
            f.setCollegeName("ABC College");
            f.setExperienceType(CandidateType.FRESHER);
        }))).containsExactly("arun@demo.local");
    }

    @Test
    void threeFiltersTogetherNarrowFurtherStill() {
        assertThat(emailsFrom(filter(f -> {
            f.setCollegeName("ABC College");
            f.setLocation("Coimbatore");
            f.setSkill("Java");
        }))).containsExactly("arun@demo.local");
    }

    @Test
    void acombinationThatMatchesNobodyReturnsEmptyRatherThanEverybody() {
        assertThat(filter(f -> {
            f.setCollegeName("XYZ Institute");
            f.setExperienceType(CandidateType.FRESHER);
        })).isEmpty();
    }

    @Test
    void changingAFilterReplacesTheResultRatherThanAccumulating() {
        assertThat(emailsFrom(filter(f -> f.setLocation("Coimbatore"))))
                .containsExactlyInAnyOrder("arun@demo.local", "meena@demo.local");
        assertThat(emailsFrom(filter(f -> f.setLocation("Chennai"))))
                .containsExactly("priya@demo.local");
    }

    @Test
    void clearingEveryFilterRestoresTheFullList() {
        assertThat(filter(f -> f.setCollegeName("ABC College"))).hasSize(2);
        assertThat(filter(f -> {
        })).hasSize(3);
    }

    @Test
    void aBlankFilterValueIsNotAFilter() {
        assertThat(filter(f -> {
            f.setCollegeName("   ");
            f.setLocation("");
            f.setSkill(null);
        })).hasSize(3);
    }

    @Test
    void searchStillWorksAndCombinesWithTheOtherFilters() {
        assertThat(emailsFrom(filter(f -> f.setSearch("arun")))).containsExactly("arun@demo.local");
        assertThat(filter(f -> {
            f.setSearch("arun");
            f.setCollegeName("XYZ Institute");
        })).isEmpty();
    }

    // ---- options come from the data ------------------------------------------

    @Test
    void filterOptionsAreBuiltFromTheCandidatesThemselves() {
        var options = candidateView.filterOptions(recruiter.getId(), Role.RECRUITER);

        assertThat(options.colleges()).containsExactly("ABC College", "XYZ Institute");
        assertThat(options.locations()).containsExactly("Chennai", "Coimbatore");
        assertThat(options.domains()).containsExactly("Java", "Python");
        // Split out of the comma-separated column, de-duplicated across candidates.
        assertThat(options.skills())
                // Case-insensitive order, so "Spring Boot" precedes "SQL" (sp < sq).
                .containsExactly("Django", "Java", "Python", "React", "Spring Boot", "SQL");
    }

    /**
     * Applying a filter must not shrink the dropdowns, or the control needed to
     * widen or clear the filter would disappear along with the results.
     */
    @Test
    void optionsDoNotShrinkWhenAFilterIsApplied() {
        var before = candidateView.filterOptions(recruiter.getId(), Role.RECRUITER);
        assertThat(filter(f -> f.setCollegeName("XYZ Institute"))).hasSize(1);
        var after = candidateView.filterOptions(recruiter.getId(), Role.RECRUITER);

        assertThat(after.colleges()).isEqualTo(before.colleges());
        assertThat(after.skills()).isEqualTo(before.skills());
    }

    @Test
    void aCandidateWithNothingRecordedContributesNoOptionsButIsStillListed() {
        scheduleWith(recruiter, candidate("bare@demo.local", "Bare Person",
                null, null, null, null, CandidateType.FRESHER, null));

        var options = candidateView.filterOptions(recruiter.getId(), Role.RECRUITER);
        assertThat(options.colleges()).containsExactly("ABC College", "XYZ Institute");
        assertThat(emailsFrom(filter(f -> {
        }))).contains("bare@demo.local");
    }

    /** Filtering on a field excludes candidates who have nothing recorded for it. */
    @Test
    void aCandidateWithNoCollegeIsExcludedByACollegeFilter() {
        scheduleWith(recruiter, candidate("bare2@demo.local", "Bare Two",
                null, null, null, null, CandidateType.FRESHER, null));

        assertThat(emailsFrom(filter(f -> f.setCollegeName("ABC College"))))
                .doesNotContain("bare2@demo.local");
    }

    // ---- scope: a filter can never widen what a recruiter may see ------------

    @Test
    void aFilterCannotReachAnotherRecruitersCandidate() {
        User theirs = candidate("hidden@demo.local", "Hidden Person",
                "ABC College", "Coimbatore", "Java", "Java", CandidateType.FRESHER, null);
        scheduleWith(otherRecruiter, theirs);

        // Every filter that candidate would match, applied by the wrong recruiter.
        assertThat(emailsFrom(filter(f -> f.setCollegeName("ABC College"))))
                .doesNotContain("hidden@demo.local");
        assertThat(emailsFrom(filter(f -> f.setSkill("Java"))))
                .doesNotContain("hidden@demo.local");

        // And the admin, whose scope is unrestricted, does see them.
        CandidateFilter byCollege = new CandidateFilter();
        byCollege.setCollegeName("ABC College");
        assertThat(emailsFrom(candidateView.list(admin.getId(), Role.ADMIN, byCollege)))
                .contains("hidden@demo.local");
    }

    @Test
    void theOptionsARecruiterIsOfferedNeverNameAnotherRecruitersData() {
        scheduleWith(otherRecruiter, candidate("elsewhere@demo.local", "Elsewhere",
                "Secret College", "Madurai", "Rust", "Rust", CandidateType.FRESHER, null));

        var options = candidateView.filterOptions(recruiter.getId(), Role.RECRUITER);
        assertThat(options.colleges()).doesNotContain("Secret College");
        assertThat(options.locations()).doesNotContain("Madurai");
        assertThat(options.skills()).doesNotContain("Rust");
    }

    // ---- the page itself -----------------------------------------------------

    @Test
    void thePageAppliesTheFilterFromTheQueryString() throws Exception {
        mvc.perform(get("/recruiter/candidates")
                        .param("collegeName", "ABC College")
                        .param("experienceType", "FRESHER")
                        .with(user(recruiter)))
                .andExpect(status().isOk())
                .andExpect(view().name("interview/candidates"))
                .andExpect(result -> {
                    @SuppressWarnings("unchecked")
                    List<CandidateRow> rows = (List<CandidateRow>) result.getModelAndView()
                            .getModel().get("candidates");
                    assertThat(rows).extracting(CandidateRow::email)
                            .containsExactly("arun@demo.local");
                });
    }

    @Test
    void thePageOffersTheFilterOptionsAndTheActiveChips() throws Exception {
        mvc.perform(get("/admin/candidates").param("skill", "Java").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var model = result.getModelAndView().getModel();
                    assertThat(model.get("filterOptions")).isNotNull();
                    assertThat(model.get("activeFilters")).isNotNull();
                    assertThat(((List<?>) model.get("activeFilters"))).hasSize(1);
                });
    }

    /** An unfiltered request is exactly the page that existed before filtering. */
    @Test
    void anUnfilteredRequestListsEveryCandidateInScope() throws Exception {
        mvc.perform(get("/recruiter/candidates").with(user(recruiter)))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    @SuppressWarnings("unchecked")
                    List<CandidateRow> rows = (List<CandidateRow>) result.getModelAndView()
                            .getModel().get("candidates");
                    assertThat(rows).hasSize(3);
                });
    }

    // ---- the scheduling pool -------------------------------------------------

    /**
     * The pool filter is applied in the browser, so what the server owes the
     * page is the attributes to filter on and the options to offer.
     */
    @Test
    void theSchedulingPoolCarriesTheFilterableFields() {
        List<SelectableCandidate> pool = userService.selectableCandidates();

        SelectableCandidate arun = pool.stream()
                .filter(c -> c.email().equals("arun@demo.local")).findFirst().orElseThrow();
        assertThat(arun.collegeName()).isEqualTo("ABC College");
        assertThat(arun.location()).isEqualTo("Coimbatore");
        assertThat(arun.skills()).isEqualTo("Java, Spring Boot, SQL");
        assertThat(arun.primaryDomain()).isEqualTo("Java");
        assertThat(arun.experienceKey()).isEqualTo("FRESHER");
    }

    @Test
    void theSchedulingPoolOptionsComeFromThePool() {
        var options = userService.selectablePoolOptions();
        assertThat(options.colleges()).containsExactly("ABC College", "XYZ Institute");
        assertThat(options.skills()).contains("Java", "Spring Boot", "SQL");
    }

    @Test
    void theSchedulingFormsAreGivenThePoolAndItsOptions() throws Exception {
        mvc.perform(get("/recruiter/interviews/new").with(user(recruiter)))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var model = result.getModelAndView().getModel();
                    assertThat((List<?>) model.get("candidates")).isNotEmpty();
                    assertThat(model.get("poolOptions")).isNotNull();
                });

        mvc.perform(get("/recruiter/interviews/new-multi").with(user(recruiter)))
                .andExpect(status().isOk())
                .andExpect(result ->
                        assertThat(result.getModelAndView().getModel().get("poolOptions")).isNotNull());
    }

    // ---- the skill column's own contract -------------------------------------

    @Test
    void skillsAreNormalisedOnTheWayInSoTheOptionListIsClean() {
        User messy = candidate("messy@demo.local", "Messy",
                "ABC College", "Chennai", "  java , Java,  SQL ,, ", "Java",
                CandidateType.FRESHER, null);

        assertThat(userService.selectableCandidates().stream()
                .filter(c -> c.id().equals(messy.getId())).findFirst().orElseThrow().skills())
                .isEqualTo("java, SQL");
    }

    @Test
    void splittingASkillListDropsBlanksAndTrims() {
        assertThat(CandidateQueryDtos.splitSkills("Java,  Spring Boot ,, SQL"))
                .containsExactly("Java", "Spring Boot", "SQL");
        assertThat(CandidateQueryDtos.splitSkills(null)).isEmpty();
        assertThat(CandidateQueryDtos.splitSkills("   ")).isEmpty();
    }

    // ---- helpers -------------------------------------------------------------

    private List<CandidateRow> filter(java.util.function.Consumer<CandidateFilter> setUp) {
        CandidateFilter f = new CandidateFilter();
        setUp.accept(f);
        return candidateView.list(recruiter.getId(), Role.RECRUITER, f);
    }

    private static List<String> emailsFrom(List<CandidateRow> rows) {
        return rows.stream().map(CandidateRow::email).toList();
    }

    private User candidate(String email, String name, String college, String location,
            String skills, String domain, CandidateType type, Integer years) {
        CreateUserRequest request = new CreateUserRequest();
        request.setFullName(name);
        request.setEmail(email);
        request.setPassword("Candidate@123");
        request.setRole(Role.CANDIDATE);
        request.setCollegeName(college);
        request.setLocation(location);
        request.setSkills(skills);
        request.setPrimaryDomain(domain);
        request.setCandidateType(type);
        request.setExperienceYears(years);
        return userService.create(request);
    }

    private void scheduleWith(AppUserDetails who, User candidate) {
        CreateInterviewRequest request = new CreateInterviewRequest();
        request.setInterviewName("Filter Drive");
        request.setCandidateId(candidate.getId());
        request.setScheduledAt(LocalDateTime.now().plusDays(3).withNano(0));
        request.setCandidateType(CandidateType.FRESHER);
        request.setDomain("Java");
        request.setInterviewType(InterviewType.TECHNICAL);
        request.setQuestionCount(5);
        request.setDurationMinutes(30);
        interviewService.create(who.getId(), request);
    }

    private User save(String email, String fullName, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("$2a$10$notarealhash");
        u.setFullName(fullName);
        u.setRole(role);
        u.setEnabled(true);
        return users.save(u);
    }
}
