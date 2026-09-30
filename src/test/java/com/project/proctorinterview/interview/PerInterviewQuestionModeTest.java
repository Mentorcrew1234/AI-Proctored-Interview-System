package com.project.proctorinterview.interview;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.project.proctorinterview.TestDatabaseCleaner;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.QuestionMode;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.config.QuestionModeProperties;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Question mode as a property of one interview rather than of the server.
 *
 * <p>The behaviour that matters most is the fallback: {@code null} means
 * "inherit the server default", not "unknown". Every interview scheduled before
 * the column existed carries null, and must keep behaving exactly as it did.
 */
@SpringBootTest
@ActiveProfiles("test")
class PerInterviewQuestionModeTest {

    @Autowired
    private TestDatabaseCleaner cleaner;
    @Autowired
    private UserRepository users;
    @Autowired
    private InterviewRepository interviews;
    @Autowired
    private InterviewService interviewService;

    private Long recruiterId;
    private Long candidateId;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        recruiterId = save("rec@test.local", Role.RECRUITER).getId();
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

    private CreateInterviewRequest form(QuestionMode mode) {
        CreateInterviewRequest f = new CreateInterviewRequest();
        f.setInterviewName("Java Developer");
        f.setCandidateId(candidateId);
        f.setScheduledAt(LocalDateTime.now().plusDays(1));
        f.setCandidateType(CandidateType.FRESHER);
        f.setDomain("Java");
        f.setInterviewType(InterviewType.TECHNICAL);
        f.setQuestionCount(3);
        f.setDurationMinutes(30);
        f.setQuestionMode(mode);
        return f;
    }

    // ---- the fallback -------------------------------------------------------

    @Test
    void nullMeansInheritNotUnknown() {
        var properties = new QuestionModeProperties("ADAPTIVE");

        // An interview that names nothing follows the server.
        assertThat(properties.effectiveFor(null)).isEqualTo(QuestionMode.ADAPTIVE);
        // One that names a mode wins, even against the server's own choice.
        assertThat(properties.effectiveFor(QuestionMode.FIXED)).isEqualTo(QuestionMode.FIXED);
    }

    @Test
    void theServerDefaultStillAppliesWhenNothingIsConfigured() {
        var properties = new QuestionModeProperties(null);

        assertThat(properties.effectiveFor(null)).isEqualTo(QuestionMode.FIXED);
        assertThat(properties.effectiveFor(QuestionMode.ADAPTIVE)).isEqualTo(QuestionMode.ADAPTIVE);
    }

    @Test
    void anUnrecognisedServerValueStillFallsBackSafely() {
        // Unchanged behaviour: a bad config value must not stop the application
        // and must not silently opt an interview into the less-verified path.
        var properties = new QuestionModeProperties("NONSENSE");

        assertThat(properties.effectiveFor(null)).isEqualTo(QuestionMode.FIXED);
    }

    // ---- persistence --------------------------------------------------------

    @Test
    void schedulingWithoutAModeStoresNullRatherThanGuessing() {
        Interview created = interviewService.create(recruiterId, form(null));

        assertThat(interviews.findById(created.getId()).orElseThrow().getQuestionMode()).isNull();
    }

    @Test
    void schedulingWithAModeStoresIt() {
        Interview created = interviewService.create(recruiterId, form(QuestionMode.ADAPTIVE));

        assertThat(interviews.findById(created.getId()).orElseThrow().getQuestionMode())
                .isEqualTo(QuestionMode.ADAPTIVE);
    }

    @Test
    void twoInterviewsCanUseDifferentModesAtTheSameTime() {
        // The whole point of the change: this was impossible when the mode was
        // a single server-wide setting.
        Interview fixed = interviewService.create(recruiterId, form(QuestionMode.FIXED));
        Interview adaptive = interviewService.create(recruiterId, form(QuestionMode.ADAPTIVE));

        assertThat(interviews.findById(fixed.getId()).orElseThrow().getQuestionMode())
                .isEqualTo(QuestionMode.FIXED);
        assertThat(interviews.findById(adaptive.getId()).orElseThrow().getQuestionMode())
                .isEqualTo(QuestionMode.ADAPTIVE);
    }

    // ---- editing ------------------------------------------------------------

    @Test
    void editingCanSetAModeOnAnInheritingInterview() {
        Interview created = interviewService.create(recruiterId, form(null));

        interviewService.update(created.getId(), recruiterId, Role.RECRUITER,
                form(QuestionMode.ADAPTIVE));

        assertThat(interviews.findById(created.getId()).orElseThrow().getQuestionMode())
                .isEqualTo(QuestionMode.ADAPTIVE);
    }

    @Test
    void editingCanReturnAnInterviewToInheriting() {
        Interview created = interviewService.create(recruiterId, form(QuestionMode.ADAPTIVE));

        interviewService.update(created.getId(), recruiterId, Role.RECRUITER, form(null));

        // Back to null, not stuck on the last explicit choice.
        assertThat(interviews.findById(created.getId()).orElseThrow().getQuestionMode()).isNull();
    }

    // ---- what a reader is shown ---------------------------------------------

    @Test
    void theSummaryResolvesInheritIntoAConcreteMode() {
        Interview created = interviewService.create(recruiterId, form(null));

        // A page should never have to explain "inherit" to a reader; it shows
        // what will actually happen. Read through getSummary rather than
        // toSummary directly - the latter walks lazy associations and is meant
        // to be called inside the service transaction.
        assertThat(interviewService.getSummary(created.getId()).questionMode())
                .isEqualTo(QuestionMode.FIXED);
    }

    @Test
    void theSummaryShowsAnExplicitModeUnchanged() {
        Interview created = interviewService.create(recruiterId, form(QuestionMode.ADAPTIVE));

        assertThat(interviewService.getSummary(created.getId()).questionMode())
                .isEqualTo(QuestionMode.ADAPTIVE);
    }

    @Test
    void bothModesHaveAReadableLabel() {
        assertThat(QuestionMode.FIXED.label()).isEqualTo("Fixed set");
        assertThat(QuestionMode.ADAPTIVE.label()).isEqualTo("Adaptive");
    }
}
