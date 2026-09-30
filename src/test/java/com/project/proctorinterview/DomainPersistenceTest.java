package com.project.proctorinterview;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.project.proctorinterview.answer.Answer;
import com.project.proctorinterview.answer.AnswerRepository;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.EvaluatorType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.ProctorEventType;
import com.project.proctorinterview.common.Enums.QuestionSource;
import com.project.proctorinterview.common.Enums.Recommendation;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewRepository;
import com.project.proctorinterview.interview.InterviewSession;
import com.project.proctorinterview.interview.InterviewSessionRepository;
import com.project.proctorinterview.proctor.ProctorEvent;
import com.project.proctorinterview.proctor.ProctorEventRepository;
import com.project.proctorinterview.question.Question;
import com.project.proctorinterview.question.QuestionRepository;
import com.project.proctorinterview.report.Report;
import com.project.proctorinterview.report.ReportRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Phase 1 verification: every entity persists and reads back, the whole graph
 * hangs off one session id, and the JSON-in-TEXT converters round-trip.
 */
@DataJpaTest
@ActiveProfiles("test")
class DomainPersistenceTest {

    @Autowired
    private UserRepository users;
    @Autowired
    private InterviewRepository interviews;
    @Autowired
    private InterviewSessionRepository sessions;
    @Autowired
    private QuestionRepository questions;
    @Autowired
    private AnswerRepository answers;
    @Autowired
    private ProctorEventRepository events;
    @Autowired
    private ReportRepository reports;

    private User newUser(String email, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("$2a$10$notarealhash");
        u.setFullName("Test " + role);
        u.setRole(role);
        return users.save(u);
    }

    private InterviewSession newSession() {
        User recruiter = newUser("recruiter@test.local", Role.RECRUITER);
        User candidate = newUser("candidate@test.local", Role.CANDIDATE);

        Interview interview = new Interview();
        interview.setRecruiter(recruiter);
        interview.setCandidate(candidate);
        interview.setScheduledAt(Instant.now().plus(1, ChronoUnit.DAYS));
        interview.setCandidateType(CandidateType.FRESHER);
        interview.setDomain("Java");
        interview.setInterviewType(InterviewType.TECHNICAL);
        interview.setQuestionCount(5);
        interview.setStatus(InterviewStatus.SCHEDULED);
        interview.setInviteToken(UUID.randomUUID().toString());
        interviews.save(interview);

        InterviewSession session = new InterviewSession();
        session.setInterview(interview);
        session.setCameraGranted(true);
        session.setMicGranted(true);
        return sessions.save(session);
    }

    @Test
    void everyEntityPersistsAndReadsBack() {
        InterviewSession session = newSession();
        assertThat(session.getId()).isNotNull();
        assertThat(session.getStartedAt()).isNotNull();

        Question question = new Question();
        question.setSession(session);
        question.setSequenceNo(1);
        question.setText("You are designing an e-commerce app with several user types. "
                + "How would you structure it using object-oriented principles?");
        question.setDifficulty(Difficulty.MEDIUM);
        question.setExpectedPoints(List.of("inheritance", "interfaces", "role separation"));
        question.setSource(QuestionSource.LLM);
        questions.save(question);

        Answer answer = new Answer();
        answer.setQuestion(question);
        answer.setRawTranscript("um I would basically use uh interfaces");
        answer.setCleanTranscript("I would use interfaces");
        answer.setFillerCount(3);
        answer.setWordCount(4);
        answer.setDurationSeconds(42);
        answer.setTechnicalScore(80);
        answer.setRelevanceScore(85);
        answer.setProblemSolvingScore(75);
        answer.setCommunicationScore(70);
        answer.setOverallScore(78);
        answer.setStrengths(List.of("clear structure", "used interfaces"));
        answer.setWeaknesses(List.of("no mention of persistence"));
        answer.setEvaluator(EvaluatorType.LLM);
        answer.setModelName("gemini-2.5-flash");
        answer.setEvaluatedAt(Instant.now());
        answers.save(answer);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("bbox", List.of(220, 140, 90, 180));
        details.put("direction", "LEFT");

        ProctorEvent event = new ProctorEvent();
        event.setSession(session);
        event.setEventType(ProctorEventType.PHONE_DETECTED);
        event.setStartTime(Instant.now().minusSeconds(10));
        event.setEndTime(Instant.now().minusSeconds(5));
        event.setDurationMs(5235L);
        event.setConfidence(new BigDecimal("0.910"));
        event.setDetails(details);
        event.setClientEventId(UUID.randomUUID().toString());
        events.save(event);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("PHONE_DETECTED", 1);
        summary.put("NO_FACE", 0);

        Report report = new Report();
        report.setSession(session);
        report.setTechnicalScore(80);
        report.setCommunicationScore(70);
        report.setProblemSolvingScore(75);
        report.setRelevanceScore(85);
        report.setOverallScore(78);
        report.setRecommendation(Recommendation.RECOMMENDED);
        report.setExplanation("Strong practical understanding.");
        report.setProctorSummary(summary);
        report.setIntegrityFlag(true);
        reports.save(report);

        // Everything is reachable from the single session id.
        Long sessionId = session.getId();
        assertThat(questions.findBySessionIdOrderBySequenceNoAsc(sessionId)).hasSize(1);
        assertThat(answers.findByQuestionSessionIdOrderByQuestionSequenceNoAsc(sessionId)).hasSize(1);
        assertThat(events.findBySessionIdOrderByStartTimeAsc(sessionId)).hasSize(1);
        assertThat(reports.findBySessionId(sessionId)).isPresent();
    }

    @Test
    void jsonBackedColumnsRoundTrip() {
        InterviewSession session = newSession();

        Question question = new Question();
        question.setSession(session);
        question.setSequenceNo(1);
        question.setText("Describe how you would debug a slow query in production.");
        question.setDifficulty(Difficulty.HARD);
        question.setExpectedPoints(List.of("EXPLAIN plan", "indexes", "measure first"));
        question.setSource(QuestionSource.BANK);
        Long questionId = questions.saveAndFlush(question).getId();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("faceCount", 2);
        details.put("direction", "RIGHT");

        ProctorEvent event = new ProctorEvent();
        event.setSession(session);
        event.setEventType(ProctorEventType.MULTIPLE_FACES);
        event.setStartTime(Instant.now());
        event.setDetails(details);
        event.setClientEventId(UUID.randomUUID().toString());
        Long eventId = events.saveAndFlush(event).getId();

        assertThat(questions.findById(questionId)).get()
                .extracting(Question::getExpectedPoints)
                .isEqualTo(List.of("EXPLAIN plan", "indexes", "measure first"));

        assertThat(events.findById(eventId)).get()
                .extracting(ProctorEvent::getDetails)
                .isEqualTo(details);
    }

    @Test
    void inviteTokenAndSessionLookupsWork() {
        InterviewSession session = newSession();
        String token = session.getInterview().getInviteToken();

        assertThat(interviews.findByInviteToken(token)).isPresent();
        assertThat(sessions.findByInterviewInviteToken(token))
                .get()
                .extracting(InterviewSession::getId)
                .isEqualTo(session.getId());
    }

    @Test
    void duplicateClientEventIdIsDetected() {
        InterviewSession session = newSession();
        String clientEventId = UUID.randomUUID().toString();

        ProctorEvent first = new ProctorEvent();
        first.setSession(session);
        first.setEventType(ProctorEventType.NO_FACE);
        first.setStartTime(Instant.now());
        first.setClientEventId(clientEventId);
        events.saveAndFlush(first);

        assertThat(events.existsByClientEventId(clientEventId)).isTrue();
        assertThat(events.existsByClientEventId(UUID.randomUUID().toString())).isFalse();
        assertThat(events.countBySessionIdAndEventType(session.getId(), ProctorEventType.NO_FACE))
                .isEqualTo(1);
    }
}
