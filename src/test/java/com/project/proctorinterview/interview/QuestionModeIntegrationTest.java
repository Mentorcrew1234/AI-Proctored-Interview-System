package com.project.proctorinterview.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.question.QuestionRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 6.3: an explicit, named regression check that {@code FIXED} mode -
 * the default, and the only behaviour ever verified against real Gemini -
 * still front-loads the whole question set through the exact controller this
 * phase changed.
 *
 * <p>Default {@code @SpringBootTest} properties, same as {@link
 * SessionLifecycleTest}, so this reuses that class's cached Spring context
 * rather than starting a new one. {@code SessionLifecycleTest} itself was not
 * modified by this phase and is the broader regression suite; this class adds
 * one focused assertion this phase's own requirements call for by name.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuestionModeIntegrationTest {

    @Autowired
    private com.project.proctorinterview.TestDatabaseCleaner cleaner;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private QuestionRepository questions;
    @Autowired
    private InterviewService interviewService;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    private String candidateJwt;
    private Long recruiterId;
    private Long candidateId;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        recruiterId = user("iv@test.local", "Recruiter@1", Role.RECRUITER);
        candidateId = user("cand@test.local", "Candidate@1", Role.CANDIDATE);
        candidateJwt = login("cand@test.local", "Candidate@1");
    }

    private Long user(String email, String password, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(password));
        u.setFullName("Test " + role);
        u.setRole(role);
        u.setEnabled(true);
        return users.save(u).getId();
    }

    private String login(String email, String password) {
        try {
            String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                    .andReturn().getResponse().getContentAsString();
            return (String) json.readValue(body, Map.class).get("token");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void fixedModeStillFrontLoadsTheWholeSetThroughTheController() throws Exception {
        CreateInterviewRequest req = new CreateInterviewRequest();
        req.setCandidateId(candidateId);
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        req.setCandidateType(CandidateType.FRESHER);
        req.setDomain("Java");
        req.setInterviewType(InterviewType.TECHNICAL);
        req.setQuestionCount(3);
        String token = interviewService.create(recruiterId, req).getInviteToken();

        String startBody = mvc.perform(post("/api/exam/{t}/start", token)
                        .header("Authorization", "Bearer " + candidateJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cameraGranted\":true,\"micGranted\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long sessionId = ((Number) json.readValue(startBody, Map.class).get("sessionId")).longValue();

        String questionsBody = mvc.perform(get("/api/interview-sessions/{id}/questions", sessionId)
                        .header("Authorization", "Bearer " + candidateJwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> returned =
                (List<Map<String, Object>>) json.readValue(questionsBody, Map.class).get("questions");

        // All 3 exist after exactly one GET - not generated one at a time.
        assertThat(returned).hasSize(3);
        assertThat(questions.countBySessionId(sessionId)).isEqualTo(3);
    }
}
