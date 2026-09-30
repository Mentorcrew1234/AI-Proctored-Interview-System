package com.project.proctorinterview.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.project.proctorinterview.TestDatabaseCleaner;
import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * The AI health page.
 *
 * <p>It reports infrastructure state, so the access rules matter as much as the
 * content: a candidate must never learn which provider graded them mid
 * interview, and no page may become a way to read the API key back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiHealthPageTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestDatabaseCleaner cleaner;
    @Autowired
    private UserRepository users;

    private AppUserDetails admin;
    private AppUserDetails recruiter;
    private AppUserDetails candidate;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        admin = new AppUserDetails(save("admin@test.local", Role.ADMIN));
        recruiter = new AppUserDetails(save("iv@test.local", Role.RECRUITER));
        candidate = new AppUserDetails(save("cand@test.local", Role.CANDIDATE));
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
    void anAdminSeesTheHealthPage() throws Exception {
        mvc.perform(get("/admin/ai-health").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/ai-health"));
    }

    /** Reuses the existing /admin/** rule - no second security mechanism. */
    @Test
    void aRecruiterIsForbidden() throws Exception {
        mvc.perform(get("/admin/ai-health").with(user(recruiter)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCandidateIsForbidden() throws Exception {
        mvc.perform(get("/admin/ai-health").with(user(candidate)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousUsersAreSentToLogin() throws Exception {
        mvc.perform(get("/admin/ai-health"))
                .andExpect(status().is3xxRedirection());
    }

    // ---- what the page may and may not contain ------------------------------

    @Test
    void thePageReportsConfigurationAndStateWithoutRevealingTheKey() throws Exception {
        String html = mvc.perform(get("/admin/ai-health").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The useful part: mode, model and whether a key is present at all.
        assertThat(html).contains("AI provider health");
        assertThat(html).contains("Configured model");
        assertThat(html).containsAnyOf("Configured", "Not configured");

        // The test profile configures no key, so the page must say so rather
        // than implying the provider is broken.
        assertThat(html).contains("offline fallback only");

        // Nothing that could be a credential.
        assertThat(html).doesNotContain("AIza");
        assertThat(html).doesNotContain("api-key");
        assertThat(html).doesNotContain("apiKey");
        assertThat(html).doesNotContain("key=");
    }

    /** The page must be honest that this is in-memory, not monitoring. */
    @Test
    void thePageStatesThatHealthIsRuntimeStateOnly() throws Exception {
        String html = mvc.perform(get("/admin/ai-health").with(user(admin)))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("in memory");
        assertThat(html).contains("lost");
    }
}
