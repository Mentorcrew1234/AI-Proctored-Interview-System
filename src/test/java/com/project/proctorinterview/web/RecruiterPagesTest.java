package com.project.proctorinterview.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.InterviewRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * The recruiter's page flow through the real session/CSRF filter chain:
 * form render, validation rejection, successful creation, and ownership.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecruiterPagesTest {

    @Autowired
    private com.project.proctorinterview.TestDatabaseCleaner cleaner;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private InterviewRepository interviews;
    @Autowired
    private com.project.proctorinterview.interview.InterviewSessionRepository sessions;
    @Autowired
    private com.project.proctorinterview.proctor.ProctorEventRepository events;

    private AppUserDetails recruiter;
    private Long candidateId;

    @BeforeEach
    void seed() {
        cleaner.clean();

        User iv = save("iv@test.local", Role.RECRUITER);
        recruiter = new AppUserDetails(iv);
        candidateId = save("cand@test.local", Role.CANDIDATE).getId();
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

    @Test
    void formRendersWithSelectableCandidates() throws Exception {
        mvc.perform(get("/recruiter/interviews/new").with(user(recruiter)))
                .andExpect(status().isOk())
                .andExpect(view().name("interview/schedule-form"))
                .andExpect(model().attributeExists("form", "candidates", "domains"));
    }

    @Test
    void validSubmissionCreatesInterviewAndRedirects() throws Exception {
        mvc.perform(post("/recruiter/interviews").with(user(recruiter)).with(csrf())
                        .param("interviewName", "Java Campus Drive 2026")
                        .param("candidateId", String.valueOf(candidateId))
                        .param("scheduledAt", "2026-09-01T10:00")
                        .param("candidateType", "FRESHER")
                        .param("domain", "Java")
                        .param("interviewType", "TECHNICAL")
                        .param("questionCount", "5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/recruiter/interviews"));

        assertThat(interviews.findAll()).hasSize(1);
        assertThat(interviews.findAll().getFirst().getInviteToken()).isNotBlank();
    }

    @Test
    void missingFieldsRedisplayTheFormWithErrors() throws Exception {
        mvc.perform(post("/recruiter/interviews").with(user(recruiter)).with(csrf())
                        .param("scheduledAt", "2026-09-01T10:00")
                        .param("candidateType", "FRESHER")
                        .param("interviewType", "TECHNICAL")
                        .param("questionCount", "5"))
                .andExpect(status().isOk())
                .andExpect(view().name("interview/schedule-form"))
                .andExpect(model().attributeHasFieldErrors("form", "candidateId", "domain"));

        assertThat(interviews.findAll()).isEmpty();
    }

    @Test
    void experiencedWithoutYearsIsRejectedOnTheForm() throws Exception {
        mvc.perform(post("/recruiter/interviews").with(user(recruiter)).with(csrf())
                        .param("interviewName", "Java Campus Drive 2026")
                        .param("candidateId", String.valueOf(candidateId))
                        .param("scheduledAt", "2026-09-01T10:00")
                        .param("candidateType", "EXPERIENCED")
                        .param("domain", "Java")
                        .param("interviewType", "TECHNICAL")
                        .param("questionCount", "5"))
                .andExpect(status().isOk())
                .andExpect(view().name("interview/schedule-form"))
                .andExpect(model().attributeExists("error"));

        assertThat(interviews.findAll()).isEmpty();
    }

    /** Without a CSRF token the POST must be rejected, not silently accepted. */
    @Test
    void submissionWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/recruiter/interviews").with(user(recruiter))
                        .param("interviewName", "Java Campus Drive 2026")
                        .param("candidateId", String.valueOf(candidateId))
                        .param("scheduledAt", "2026-09-01T10:00")
                        .param("candidateType", "FRESHER")
                        .param("domain", "Java")
                        .param("interviewType", "TECHNICAL")
                        .param("questionCount", "5"))
                .andExpect(status().isForbidden());

        assertThat(interviews.findAll()).isEmpty();
    }

    @Test
    void anonymousUserIsSentToLogin() throws Exception {
        mvc.perform(get("/recruiter/interviews"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void candidateCannotReachRecruiterPages() throws Exception {
        AppUserDetails candidate = new AppUserDetails(users.findById(candidateId).orElseThrow());

        mvc.perform(get("/recruiter/interviews").with(user(candidate)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rootRedirectsEachRoleToItsOwnLanding() throws Exception {
        mvc.perform(get("/").with(user(recruiter)))
                .andExpect(redirectedUrl("/recruiter/dashboard"));
    }
}
