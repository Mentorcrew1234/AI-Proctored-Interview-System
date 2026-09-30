package com.project.proctorinterview.auth;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end checks of the stateless /api chain: login, token use, and the
 * role matrix. These run through the real Spring Security filter chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiTest {

    @Autowired
    private com.project.proctorinterview.TestDatabaseCleaner cleaner;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void seed() {
        cleaner.clean();
        createUser("admin@test.local", "Admin@123", Role.ADMIN, true);
        createUser("recruiter@test.local", "Recruiter@123", Role.RECRUITER, true);
        createUser("candidate@test.local", "Candidate@123", Role.CANDIDATE, true);
        createUser("disabled@test.local", "Disabled@123", Role.ADMIN, false);
    }

    private void createUser(String email, String rawPassword, Role role, boolean enabled) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setFullName("Test " + role);
        u.setRole(role);
        u.setEnabled(enabled);
        users.save(u);
    }

    private String loginAndGetToken(String email, String password) throws Exception {
        String response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginBody(email, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return (String) json.readValue(response, java.util.Map.class).get("token");
    }

    private record LoginBody(String email, String password) {
    }

    // ---- login ------------------------------------------------------------

    @Test
    void loginWithValidCredentialsReturnsToken() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginBody("admin@test.local", "Admin@123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.user.role", is("ADMIN")))
                .andExpect(jsonPath("$.user.email", is("admin@test.local")))
                // the hash must never be serialised
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginBody("admin@test.local", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Invalid email or password")));
    }

    @Test
    void unknownEmailGivesTheSameMessageAsWrongPassword() throws Exception {
        // Identical response prevents account enumeration.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginBody("nobody@test.local", "whatever"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Invalid email or password")));
    }

    @Test
    void disabledAccountCannotLogIn() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginBody("disabled@test.local", "Disabled@123"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedLoginBodyIsRejectedWithFieldErrors() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginBody("not-an-email", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.password", notNullValue()));
    }

    // ---- token use --------------------------------------------------------

    @Test
    void meReturnsTheAuthenticatedUser() throws Exception {
        String token = loginAndGetToken("recruiter@test.local", "Recruiter@123");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("recruiter@test.local")))
                .andExpect(jsonPath("$.role", is("RECRUITER")));
    }

    @Test
    void protectedEndpointWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)));
    }

    @Test
    void protectedEndpointWithGarbageTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    // ---- role matrix ------------------------------------------------------

    @Test
    void adminCanReachAdminOnlyEndpoints() throws Exception {
        String token = loginAndGetToken("admin@test.local", "Admin@123");

        // Not 403: the ADMIN role is accepted (404/200 both prove authorization passed).
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(result -> {
                    int code = result.getResponse().getStatus();
                    if (code == 403) {
                        throw new AssertionError("ADMIN should not be forbidden from /api/users");
                    }
                });
    }

    @Test
    void candidateIsForbiddenFromAdminEndpoints() throws Exception {
        String token = loginAndGetToken("candidate@test.local", "Candidate@123");

        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)));
    }

    @Test
    void recruiterIsForbiddenFromCandidateExamEndpoints() throws Exception {
        String token = loginAndGetToken("recruiter@test.local", "Recruiter@123");

        mvc.perform(get("/api/exam/some-token").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void candidateIsForbiddenFromInterviewManagement() throws Exception {
        String token = loginAndGetToken("candidate@test.local", "Candidate@123");

        mvc.perform(get("/api/interviews").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenStopsWorkingOnceTheAccountIsDisabled() throws Exception {
        String token = loginAndGetToken("recruiter@test.local", "Recruiter@123");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        User user = users.findByEmailIgnoreCase("recruiter@test.local").orElseThrow();
        user.setEnabled(false);
        users.save(user);

        // The filter re-reads the account, so revocation is immediate rather than
        // waiting for the token to expire.
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
