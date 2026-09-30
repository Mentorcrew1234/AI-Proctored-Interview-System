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

import com.project.proctorinterview.TestDatabaseCleaner;
import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.user.CandidateProfileRepository;
import com.project.proctorinterview.user.RecruiterProfileRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;
import com.project.proctorinterview.user.UserService;
import com.project.proctorinterview.user.dto.UserDtos.CreateUserRequest;
import com.project.proctorinterview.user.dto.UserDtos.UpdateUserRequest;

/**
 * Correcting the details on an existing account.
 *
 * <p>Until this existed a profile was written once at account creation and
 * never again, so a mistyped college, phone or domain could only be repaired in
 * the database. {@code LIMITATIONS.md} said so.
 *
 * <p>Most of what follows tests what the screen <b>cannot</b> do. The email,
 * role, password and enabled flag each have their own operation, and folding
 * any of them into "edit the details" is how an innocuous screen becomes the
 * one that can do anything - so the tests post them and assert nothing moved.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUserEditTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private UserService userService;
    @Autowired
    private CandidateProfileRepository candidateProfiles;
    @Autowired
    private RecruiterProfileRepository recruiterProfiles;
    @Autowired
    private TestDatabaseCleaner cleaner;

    private AppUserDetails admin;
    private AppUserDetails recruiter;
    private User candidate;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        admin = new AppUserDetails(save("edit.admin@demo.local", "System Admin", Role.ADMIN));
        recruiter = new AppUserDetails(save("edit.recruiter@demo.local", "Priya Raman", Role.RECRUITER));

        CreateUserRequest request = new CreateUserRequest();
        request.setFullName("Arun Kumr");
        request.setEmail("arun@demo.local");
        request.setPassword("Candidate@123");
        request.setRole(Role.CANDIDATE);
        request.setCollegeName("PGS College of Techology");
        request.setPhone("9000000000");
        request.setPrimaryDomain("Java");
        candidate = userService.create(request);
    }

    // ---- the correction the screen exists for --------------------------------

    @Test
    void aMistypedCollegeCanBeCorrected() throws Exception {
        mvc.perform(editPost(candidate.getId())
                        .param("fullName", "Arun Kumar")
                        .param("collegeName", "PSG College of Technology"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"));

        assertThat(candidateProfiles.findByUserId(candidate.getId()).orElseThrow().getCollegeName())
                .isEqualTo("PSG College of Technology");
        assertThat(users.findById(candidate.getId()).orElseThrow().getFullName())
                .isEqualTo("Arun Kumar");
    }

    @Test
    void theFormIsPrefilledWithWhatIsStored() throws Exception {
        mvc.perform(get("/admin/users/" + candidate.getId() + "/edit").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/user-edit"))
                .andExpect(model().attributeExists("form", "account"));

        UpdateUserRequest form = userService.editForm(candidate.getId());
        assertThat(form.getFullName()).isEqualTo("Arun Kumr");
        assertThat(form.getCollegeName()).isEqualTo("PGS College of Techology");
        assertThat(form.getPhone()).isEqualTo("9000000000");
        assertThat(form.getPrimaryDomain()).isEqualTo("Java");
    }

    /** Clearing a field means "not recorded", the same as never having filled it. */
    @Test
    void clearingAFieldStoresNullNotAnEmptyString() throws Exception {
        mvc.perform(editPost(candidate.getId())
                .param("fullName", "Arun Kumar")
                .param("collegeName", "")
                .param("phone", "   ")).andExpect(status().is3xxRedirection());

        var profile = candidateProfiles.findByUserId(candidate.getId()).orElseThrow();
        assertThat(profile.getCollegeName()).isNull();
        assertThat(profile.getPhone()).isNull();
    }

    @Test
    void candidateTypeAndYearsAreCorrectable() throws Exception {
        mvc.perform(editPost(candidate.getId())
                .param("fullName", "Arun Kumar")
                .param("candidateType", "EXPERIENCED")
                .param("experienceYears", "4")).andExpect(status().is3xxRedirection());

        var profile = candidateProfiles.findByUserId(candidate.getId()).orElseThrow();
        assertThat(profile.getCandidateType()).isEqualTo(CandidateType.EXPERIENCED);
        assertThat(profile.getExperienceYears()).isEqualTo(4);
    }

    @Test
    void aRecruitersOwnProfileFieldsAreCorrectable() throws Exception {
        mvc.perform(editPost(recruiter.getId())
                .param("fullName", "Priya Raman")
                .param("department", "Campus Hiring")
                .param("designation", "Lead")).andExpect(status().is3xxRedirection());

        var profile = recruiterProfiles.findByUserId(recruiter.getId()).orElseThrow();
        assertThat(profile.getDepartment()).isEqualTo("Campus Hiring");
        assertThat(profile.getDesignation()).isEqualTo("Lead");
    }

    /**
     * An account created before profiles existed, or seeded without one, has no
     * profile row. It must still be editable - those are exactly the accounts
     * whose details most need fixing.
     */
    @Test
    void anAccountWithNoProfileRowGainsOneOnFirstEdit() throws Exception {
        User bare = save("bare@demo.local", "Bare Account", Role.CANDIDATE);
        assertThat(candidateProfiles.findByUserId(bare.getId())).isEmpty();

        mvc.perform(editPost(bare.getId())
                .param("fullName", "Bare Account")
                .param("collegeName", "NIT Trichy")).andExpect(status().is3xxRedirection());

        assertThat(candidateProfiles.findByUserId(bare.getId()).orElseThrow().getCollegeName())
                .isEqualTo("NIT Trichy");
    }

    /** The seeded administrator has no profile of either kind; the name is all there is. */
    @Test
    void anAdministratorsNameIsCorrectableAndCreatesNoProfile() throws Exception {
        mvc.perform(editPost(admin.getId())
                .param("fullName", "System Administrator")).andExpect(status().is3xxRedirection());

        assertThat(users.findById(admin.getId()).orElseThrow().getFullName())
                .isEqualTo("System Administrator");
        assertThat(candidateProfiles.findByUserId(admin.getId())).isEmpty();
        assertThat(recruiterProfiles.findByUserId(admin.getId())).isEmpty();
    }

    // ---- what the screen must not be able to do -------------------------------

    /**
     * Posted anyway, and ignored. UpdateUserRequest has no property for any of
     * these, so they cannot bind however the request is shaped.
     */
    @Test
    void emailRoleStatusAndPasswordAreUnreachableFromTheForm() throws Exception {
        User before = users.findById(candidate.getId()).orElseThrow();
        String hashBefore = before.getPasswordHash();

        mvc.perform(editPost(candidate.getId())
                .param("fullName", "Arun Kumar")
                .param("email", "attacker@evil.local")
                .param("role", "ADMIN")
                .param("enabled", "false")
                .param("password", "Owned@123")
                .param("passwordHash", "$2a$10$whatever")).andExpect(status().is3xxRedirection());

        User after = users.findById(candidate.getId()).orElseThrow();
        assertThat(after.getEmail()).isEqualTo("arun@demo.local");
        assertThat(after.getRole()).isEqualTo(Role.CANDIDATE);
        assertThat(after.isEnabled()).isTrue();
        assertThat(after.getPasswordHash()).isEqualTo(hashBefore);
    }

    @Test
    void aRecruiterCannotReachTheEditScreen() throws Exception {
        mvc.perform(get("/admin/users/" + candidate.getId() + "/edit").with(user(recruiter)))
                .andExpect(status().isForbidden());

        mvc.perform(editPost(candidate.getId()).with(user(recruiter))
                .param("fullName", "Renamed By Recruiter"))
                .andExpect(status().isForbidden());

        assertThat(users.findById(candidate.getId()).orElseThrow().getFullName())
                .isEqualTo("Arun Kumr");
    }

    @Test
    void aPostWithoutACsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/admin/users/" + candidate.getId()).with(user(admin))
                        .param("fullName", "No Token"))
                .andExpect(status().isForbidden());

        assertThat(users.findById(candidate.getId()).orElseThrow().getFullName())
                .isEqualTo("Arun Kumr");
    }

    // ---- validation ----------------------------------------------------------

    @Test
    void aBlankNameRedisplaysTheFormWithTheError() throws Exception {
        mvc.perform(editPost(candidate.getId()).param("fullName", "  "))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/user-edit"))
                .andExpect(model().attributeHasFieldErrors("form", "fullName"))
                .andExpect(model().attributeExists("account"));

        assertThat(users.findById(candidate.getId()).orElseThrow().getFullName())
                .isEqualTo("Arun Kumr");
    }

    @Test
    void anOverlongCollegeNameRedisplaysTheFormWithTheError() throws Exception {
        mvc.perform(editPost(candidate.getId())
                        .param("fullName", "Arun Kumar")
                        .param("collegeName", "C".repeat(151)))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "collegeName"));

        assertThat(candidateProfiles.findByUserId(candidate.getId()).orElseThrow().getCollegeName())
                .isEqualTo("PGS College of Techology");
    }

    @Test
    void editingAnAccountThatDoesNotExistRedirectsRatherThanErroring() throws Exception {
        mvc.perform(get("/admin/users/999999/edit").with(user(admin)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"));
    }

    // ---- helpers -------------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder editPost(Long id) {
        return post("/admin/users/" + id).with(user(admin)).with(csrf());
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
