package com.project.proctorinterview.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Single scheduling for an admin, and multi-candidate scheduling for both staff
 * roles.
 *
 * <p>The point of most of these is that the multi path cannot do anything the
 * single form could not: every candidate goes through the same
 * {@code InterviewService.create}, and a candidate it refuses is reported and
 * skipped rather than taking the rest of the batch down with it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MultiScheduleTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private InterviewRepository interviews;
    @Autowired
    private TestDatabaseCleaner cleaner;

    private AppUserDetails admin;
    private AppUserDetails recruiter;
    private AppUserDetails candidatePrincipal;
    private Long candidateA;
    private Long candidateB;
    private Long disabledCandidate;

    @BeforeEach
    void setUp() {
        cleaner.clean();

        User adminUser = save("multi.admin@demo.local", Role.ADMIN, true);
        User recruiterUser = save("multi.recruiter@demo.local", Role.RECRUITER, true);
        User a = save("multi.a@demo.local", Role.CANDIDATE, true);
        User b = save("multi.b@demo.local", Role.CANDIDATE, true);
        User off = save("multi.off@demo.local", Role.CANDIDATE, false);

        admin = new AppUserDetails(adminUser);
        recruiter = new AppUserDetails(recruiterUser);
        candidatePrincipal = new AppUserDetails(a);
        candidateA = a.getId();
        candidateB = b.getId();
        disabledCandidate = off.getId();
    }

    private User save(String email, Role role, boolean enabled) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("$2a$10$notarealhash");
        u.setFullName("Test " + role + " " + email);
        u.setRole(role);
        u.setEnabled(enabled);
        return users.save(u);
    }

    // ---- admin single scheduling -------------------------------------------

    @Test
    void adminCanOpenTheSingleScheduleForm() throws Exception {
        mvc.perform(get("/admin/interviews/new").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(view().name("interview/schedule-form"))
                .andExpect(model().attributeExists("form", "candidates", "domains", "actionUrl"));
    }

    @Test
    void adminCanScheduleOneInterviewAndBecomesItsRecruiterOfRecord() throws Exception {
        mvc.perform(post("/admin/interviews").with(user(admin)).with(csrf())
                        .param("interviewName", "Admin Scheduled Drive")
                        .param("candidateId", String.valueOf(candidateA))
                        .param("scheduledAt", "2026-12-01T10:00")
                        .param("candidateType", "FRESHER")
                        .param("domain", "Java")
                        .param("interviewType", "TECHNICAL")
                        .param("questionCount", "5"))
                .andExpect(status().is3xxRedirection());

        List<Interview> all = interviews.findAll();
        assertThat(all).hasSize(1);
        // Whoever schedules is the recruiter of record - the honest record of
        // who created it, and what the interview list then shows.
        //
        // Compared by id, not by reading a field off the association: this runs
        // outside a transaction and open-in-view is off, so the lazy proxy
        // answers getId() from what it already holds but would need a session
        // for anything else.
        assertThat(all.getFirst().getRecruiter().getId()).isEqualTo(admin.getId());
        assertThat(all.getFirst().getInviteToken()).isNotBlank();
    }

    @Test
    void aCandidateCannotReachAdminScheduling() throws Exception {
        mvc.perform(get("/admin/interviews/new").with(user(candidatePrincipal)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/interviews/new-multi").with(user(candidatePrincipal)))
                .andExpect(status().isForbidden());
    }

    // ---- multi scheduling ---------------------------------------------------

    @Test
    void multiFormRendersForBothStaffRoles() throws Exception {
        mvc.perform(get("/recruiter/interviews/new-multi").with(user(recruiter)))
                .andExpect(status().isOk())
                .andExpect(view().name("interview/multi-schedule-form"))
                .andExpect(model().attributeExists("form", "candidates", "domains", "maxCandidates"));

        mvc.perform(get("/admin/interviews/new-multi").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(view().name("interview/multi-schedule-form"));
    }

    @Test
    void schedulingForTwoCandidatesCreatesOneInterviewEach() throws Exception {
        mvc.perform(post("/recruiter/interviews/multi").with(user(recruiter)).with(csrf())
                        .param("interviewName", "Campus Drive 2026")
                        .param("candidateIds", String.valueOf(candidateA))
                        .param("candidateIds", String.valueOf(candidateB))
                        .param("scheduledAt", "2026-12-01T10:00")
                        .param("candidateType", "FRESHER")
                        .param("domain", "Java")
                        .param("interviewType", "TECHNICAL")
                        .param("questionCount", "5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/recruiter/interviews?search=*"));

        List<Interview> all = interviews.findAll();
        assertThat(all).hasSize(2);
        assertThat(all).allSatisfy(i -> {
            assertThat(i.getInterviewName()).isEqualTo("Campus Drive 2026");
            assertThat(i.getStatus()).isEqualTo(InterviewStatus.SCHEDULED);
            assertThat(i.getQuestionCount()).isEqualTo(5);
        });
        // Each interview is an ordinary interview with its own entry credential.
        assertThat(all.stream().map(Interview::getInviteToken).distinct()).hasSize(2);
        assertThat(all.stream().map(i -> i.getCandidate().getId()))
                .containsExactlyInAnyOrder(candidateA, candidateB);
    }

    @Test
    void theSameCandidateSelectedTwiceGetsOneInterview() throws Exception {
        mvc.perform(post("/admin/interviews/multi").with(user(admin)).with(csrf())
                        .param("interviewName", "Deduplicated Drive")
                        .param("candidateIds", String.valueOf(candidateA))
                        .param("candidateIds", String.valueOf(candidateA))
                        .param("scheduledAt", "2026-12-01T10:00")
                        .param("candidateType", "FRESHER")
                        .param("domain", "Java")
                        .param("interviewType", "TECHNICAL")
                        .param("questionCount", "5"))
                .andExpect(status().is3xxRedirection());

        assertThat(interviews.findAll()).hasSize(1);
    }

    /**
     * The behaviour that makes REQUIRES_NEW per candidate worth having: one
     * refusal must not discard the interviews already created for the others.
     */
    @Test
    void aRefusedCandidateIsSkippedWithoutLosingTheRest() throws Exception {
        mvc.perform(post("/recruiter/interviews/multi").with(user(recruiter)).with(csrf())
                        .param("interviewName", "Partly Valid Drive")
                        .param("candidateIds", String.valueOf(candidateA))
                        .param("candidateIds", String.valueOf(disabledCandidate))
                        .param("candidateIds", String.valueOf(candidateB))
                        .param("scheduledAt", "2026-12-01T10:00")
                        .param("candidateType", "FRESHER")
                        .param("domain", "Java")
                        .param("interviewType", "TECHNICAL")
                        .param("questionCount", "5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("message", "error"));

        List<Interview> all = interviews.findAll();
        assertThat(all).hasSize(2);
        assertThat(all.stream().map(i -> i.getCandidate().getId()))
                .containsExactlyInAnyOrder(candidateA, candidateB)
                .doesNotContain(disabledCandidate);
    }

    @Test
    void selectingNobodyIsRejectedAndCreatesNothing() throws Exception {
        mvc.perform(post("/recruiter/interviews/multi").with(user(recruiter)).with(csrf())
                        .param("interviewName", "Nobody Selected")
                        .param("scheduledAt", "2026-12-01T10:00")
                        .param("candidateType", "FRESHER")
                        .param("domain", "Java")
                        .param("interviewType", "TECHNICAL")
                        .param("questionCount", "5"))
                .andExpect(status().isOk())
                .andExpect(view().name("interview/multi-schedule-form"))
                .andExpect(model().attributeHasFieldErrors("form", "candidateIds"));

        assertThat(interviews.findAll()).isEmpty();
    }

    /**
     * The multi path must not be able to produce a record the single form would
     * have rejected: an experienced candidate needs years of experience, so
     * every row is refused rather than saved with a null.
     */
    @Test
    void aRuleTheSingleFormEnforcesStillAppliesToEveryCandidate() throws Exception {
        mvc.perform(post("/recruiter/interviews/multi").with(user(recruiter)).with(csrf())
                        .param("interviewName", "Experienced Without Years")
                        .param("candidateIds", String.valueOf(candidateA))
                        .param("candidateIds", String.valueOf(candidateB))
                        .param("scheduledAt", "2026-12-01T10:00")
                        .param("candidateType", "EXPERIENCED")
                        .param("domain", "Java")
                        .param("interviewType", "TECHNICAL")
                        .param("questionCount", "5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));

        assertThat(interviews.findAll()).isEmpty();
    }

    @Test
    void multiSchedulingRequiresCsrf() throws Exception {
        mvc.perform(post("/recruiter/interviews/multi").with(user(recruiter))
                        .param("interviewName", "No Token")
                        .param("candidateIds", String.valueOf(candidateA))
                        .param("scheduledAt", "2026-12-01T10:00")
                        .param("candidateType", "FRESHER")
                        .param("domain", "Java")
                        .param("interviewType", "TECHNICAL")
                        .param("questionCount", "5"))
                .andExpect(status().isForbidden());

        assertThat(interviews.findAll()).isEmpty();
    }
}
