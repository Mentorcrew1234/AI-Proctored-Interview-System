package com.project.proctorinterview.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
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
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;
import com.project.proctorinterview.user.UserService;
import com.project.proctorinterview.user.dto.UserDtos.UserRow;

/**
 * The admin user list's search and status filter.
 *
 * <p>Covers {@link UserService#search} directly (the rule the page relies on)
 * and the page route (that the rule is actually wired in, and that a bad query
 * string degrades to "no filter" rather than a 500).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUserSearchTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private UserService userService;
    @Autowired
    private TestDatabaseCleaner cleaner;

    private AppUserDetails admin;

    @BeforeEach
    void setUp() {
        cleaner.clean();

        admin = new AppUserDetails(save("search.admin@demo.local", "System Admin", Role.ADMIN, true));
        save("priya.recruiter@demo.local", "Priya Raman", Role.RECRUITER, true);
        save("arun.candidate@demo.local", "Arun Kumar", Role.CANDIDATE, true);
        save("meena.candidate@demo.local", "Meena S", Role.CANDIDATE, false);
    }

    private User save(String email, String fullName, Role role, boolean enabled) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("$2a$10$notarealhash");
        u.setFullName(fullName);
        u.setRole(role);
        u.setEnabled(enabled);
        return users.save(u);
    }

    // ---- UserService.search --------------------------------------------------

    @Test
    void searchMatchesFullNameCaseInsensitively() {
        List<UserRow> rows = userService.search(null, "priya", null);
        assertThat(rows).extracting(UserRow::email).containsExactly("priya.recruiter@demo.local");
    }

    @Test
    void searchMatchesEmailAsWellAsName() {
        List<UserRow> rows = userService.search(null, "arun.candidate@demo.local", null);
        assertThat(rows).extracting(UserRow::fullName).containsExactly("Arun Kumar");
    }

    @Test
    void statusFilterSeparatesEnabledFromDisabled() {
        assertThat(userService.search(Role.CANDIDATE, null, true))
                .extracting(UserRow::fullName).containsExactly("Arun Kumar");
        assertThat(userService.search(Role.CANDIDATE, null, false))
                .extracting(UserRow::fullName).containsExactly("Meena S");
    }

    @Test
    void roleSearchAndStatusCombine() {
        // Meena matches the search text but is disabled; asking for enabled
        // candidates named "meena" must return nothing, not ignore the status.
        assertThat(userService.search(Role.CANDIDATE, "meena", true)).isEmpty();
        assertThat(userService.search(Role.CANDIDATE, "meena", false))
                .extracting(UserRow::fullName).containsExactly("Meena S");
    }

    @Test
    void blankSearchTextIsTreatedAsNoFilter() {
        assertThat(userService.search(null, "   ", null)).hasSize(4);
    }

    @Test
    void aRoleWithNoMatchesReturnsAnEmptyListNotAnError() {
        assertThat(userService.search(Role.RECRUITER, "nobody-called-this", null)).isEmpty();
    }

    // ---- the page route --------------------------------------------------------

    @Test
    void thePageAppliesSearchAndStatusTogether() throws Exception {
        mvc.perform(get("/admin/users")
                        .param("role", "CANDIDATE")
                        .param("status", "ENABLED")
                        .with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users"))
                .andExpect(model().attribute("hasFilters", true))
                .andExpect(content().string(containsString("Arun Kumar")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Meena S"))));
    }

    @Test
    void noFiltersMeansHasFiltersIsFalse() throws Exception {
        mvc.perform(get("/admin/users").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasFilters", false));
    }

    /**
     * A hand-edited or stale query string must not 500 the page - it degrades
     * to showing everything, the same as omitting the parameter.
     */
    @Test
    void anUnrecognisedRoleOrStatusIsIgnoredRatherThanFailing() throws Exception {
        mvc.perform(get("/admin/users")
                        .param("role", "NOT_A_ROLE")
                        .param("status", "NOT_A_STATUS")
                        .with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users"));
    }

    @Test
    void aCandidateCannotReachTheAdminUserList() throws Exception {
        User candidateUser = save("plain.candidate@demo.local", "Plain Candidate", Role.CANDIDATE, true);
        mvc.perform(get("/admin/users").with(user(new AppUserDetails(candidateUser))))
                .andExpect(status().isForbidden());
    }
}
