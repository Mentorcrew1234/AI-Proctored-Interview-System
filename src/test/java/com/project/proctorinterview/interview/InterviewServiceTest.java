package com.project.proctorinterview.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.common.Enums.SessionStatus;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/** Interview scheduling rules: ownership, candidate validity, invite tokens. */
@SpringBootTest
@ActiveProfiles("test")
class InterviewServiceTest {

    @Autowired
    private com.project.proctorinterview.TestDatabaseCleaner cleaner;

    @Autowired
    private InterviewService service;
    @Autowired
    private UserRepository users;
    @Autowired
    private InterviewRepository interviews;
    @Autowired
    private InterviewSessionRepository sessions;
    @Autowired
    private com.project.proctorinterview.proctor.ProctorEventRepository events;

    private Long recruiterId;
    private Long otherRecruiterId;
    private Long candidateId;
    private Long disabledCandidateId;

    @BeforeEach
    void seed() {
        cleaner.clean();
        recruiterId = user("iv1@test.local", Role.RECRUITER, true);
        otherRecruiterId = user("iv2@test.local", Role.RECRUITER, true);
        candidateId = user("cand@test.local", Role.CANDIDATE, true);
        disabledCandidateId = user("gone@test.local", Role.CANDIDATE, false);
    }

    private Long user(String email, Role role, boolean enabled) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("$2a$10$notarealhash");
        u.setFullName("Test " + email);
        u.setRole(role);
        u.setEnabled(enabled);
        return users.save(u).getId();
    }

    private CreateInterviewRequest request() {
        CreateInterviewRequest r = new CreateInterviewRequest();

        r.setCandidateId(candidateId);
        r.setScheduledAt(LocalDateTime.now().plusDays(1));
        r.setCandidateType(CandidateType.FRESHER);
        r.setDomain("  Java  ");
        r.setInterviewType(InterviewType.TECHNICAL);
        r.setQuestionCount(5);
        return r;
    }

    @Test
    void createsScheduledInterviewWithInviteToken() {
        Interview created = service.create(recruiterId, request());

        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(InterviewStatus.SCHEDULED);
        assertThat(created.getInviteToken()).isNotBlank();
        assertThat(created.getDomain()).isEqualTo("Java"); // trimmed
        assertThat(created.getExperienceYears()).isNull(); // fresher
    }

    @Test
    void inviteTokensAreUniqueAndUnguessable() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            tokens.add(service.create(recruiterId, request()).getInviteToken());
        }

        assertThat(tokens).hasSize(25);
        // UUID-length, so not enumerable by trying small integers.
        assertThat(tokens).allSatisfy(t -> assertThat(t).hasSizeGreaterThanOrEqualTo(32));
    }

    @Test
    void experiencedCandidateMustHaveYears() {
        CreateInterviewRequest r = request();
        r.setCandidateType(CandidateType.EXPERIENCED);
        r.setExperienceYears(null);

        assertThatThrownBy(() -> service.create(recruiterId, r))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("years of experience");
    }

    @Test
    void experiencedYearsAreKeptButFresherYearsAreDiscarded() {
        CreateInterviewRequest experienced = request();
        experienced.setCandidateType(CandidateType.EXPERIENCED);
        experienced.setExperienceYears(4);
        assertThat(service.create(recruiterId, experienced).getExperienceYears()).isEqualTo(4);

        // A fresher never carries a years value, even if the form sent one.
        CreateInterviewRequest fresher = request();
        fresher.setCandidateType(CandidateType.FRESHER);
        fresher.setExperienceYears(9);
        assertThat(service.create(recruiterId, fresher).getExperienceYears()).isNull();
    }

    @Test
    void cannotScheduleAgainstANonCandidate() {
        CreateInterviewRequest r = request();
        r.setCandidateId(otherRecruiterId);

        assertThatThrownBy(() -> service.create(recruiterId, r))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not a candidate");
    }

    @Test
    void cannotScheduleAgainstADisabledCandidate() {
        CreateInterviewRequest r = request();
        r.setCandidateId(disabledCandidateId);

        assertThatThrownBy(() -> service.create(recruiterId, r))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void recruiterOnlySeesTheirOwnInterviews() {
        service.create(recruiterId, request());
        service.create(otherRecruiterId, request());

        assertThat(service.listForRecruiter(recruiterId)).hasSize(1);
        assertThat(service.listForRecruiter(otherRecruiterId)).hasSize(1);
        assertThat(service.listAll()).hasSize(2);
    }

    @Test
    void recruiterCannotTouchAnotherRecruitersInterview() {
        Long id = service.create(recruiterId, request()).getId();

        assertThatThrownBy(() -> service.getForActor(id, otherRecruiterId, Role.RECRUITER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("another recruiter");

        // An admin is allowed through.
        assertThat(service.getForActor(id, otherRecruiterId, Role.ADMIN)).isNotNull();
    }

    @Test
    void cancellingSetsStatusAndBlocksCompletedInterviews() {
        Interview interview = service.create(recruiterId, request());

        service.cancel(interview.getId(), recruiterId, Role.RECRUITER);
        assertThat(interviews.findById(interview.getId())).get()
                .extracting(Interview::getStatus)
                .isEqualTo(InterviewStatus.CANCELLED);

        Interview done = service.create(recruiterId, request());
        done.setStatus(InterviewStatus.COMPLETED);
        interviews.save(done);

        assertThatThrownBy(() -> service.cancel(done.getId(), recruiterId, Role.RECRUITER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot be cancelled");
    }

    // ---- edit -----------------------------------------------------------

    @Test
    void updateAppliesConfigurationChangesButNeverIdentity() {
        Interview interview = service.create(recruiterId, request());
        String originalToken = interview.getInviteToken();

        CreateInterviewRequest edit = request();
        edit.setInterviewName("Updated name");
        edit.setDomain("Python");
        edit.setQuestionCount(7);
        edit.setScheduledAt(LocalDateTime.now().plusDays(2));
        // Attempting to smuggle a different candidate through the request must
        // have no effect - the service never reads candidateId on update.
        edit.setCandidateId(otherRecruiterId);

        Interview updated = service.update(interview.getId(), recruiterId, Role.RECRUITER, edit);

        assertThat(updated.getInterviewName()).isEqualTo("Updated name");
        assertThat(updated.getDomain()).isEqualTo("Python");
        assertThat(updated.getQuestionCount()).isEqualTo(7);
        assertThat(updated.getInviteToken()).isEqualTo(originalToken);
        assertThat(updated.getCandidate().getId()).isEqualTo(candidateId);
        assertThat(updated.getRecruiter().getId()).isEqualTo(recruiterId);
    }

    @Test
    void cannotEditAnInterviewThatHasStarted() {
        Interview interview = service.create(recruiterId, request());
        interview.setStatus(InterviewStatus.IN_PROGRESS);
        interviews.save(interview);

        assertThatThrownBy(() -> service.update(interview.getId(), recruiterId, Role.RECRUITER, request()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("scheduled interview can be edited");
    }

    @Test
    void cannotEditToAPastScheduledTime() {
        Interview interview = service.create(recruiterId, request());
        CreateInterviewRequest edit = request();
        edit.setScheduledAt(LocalDateTime.now().minusHours(1));

        assertThatThrownBy(() -> service.update(interview.getId(), recruiterId, Role.RECRUITER, edit))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must be in the future");
    }

    @Test
    void recruiterCannotEditAnotherRecruitersInterview() {
        Interview interview = service.create(recruiterId, request());

        assertThatThrownBy(() -> service.update(interview.getId(), otherRecruiterId, Role.RECRUITER, request()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("another recruiter");
    }

    @Test
    void adminCanEditAnyScheduledInterview() {
        Interview interview = service.create(recruiterId, request());

        Interview updated = service.update(interview.getId(), otherRecruiterId, Role.ADMIN, request());

        assertThat(updated).isNotNull();
    }

    // ---- delete -----------------------------------------------------------

    @Test
    void deletesAScheduledInterviewWithNoSession() {
        Long id = service.create(recruiterId, request()).getId();

        service.delete(id, recruiterId, Role.RECRUITER);

        assertThat(interviews.findById(id)).isEmpty();
    }

    @Test
    void cannotDeleteAnInterviewThatHasStarted() {
        Interview interview = service.create(recruiterId, request());
        interview.setStatus(InterviewStatus.IN_PROGRESS);
        interviews.save(interview);

        InterviewSession session = new InterviewSession();
        session.setInterview(interview);
        session.setStatus(SessionStatus.ACTIVE);
        sessions.save(session);

        assertThatThrownBy(() -> service.delete(interview.getId(), recruiterId, Role.RECRUITER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already started or finished");

        assertThat(interviews.findById(interview.getId())).isPresent();
    }

    @Test
    void cannotDeleteACompletedInterview() {
        Interview interview = service.create(recruiterId, request());
        interview.setStatus(InterviewStatus.COMPLETED);
        interviews.save(interview);

        assertThatThrownBy(() -> service.delete(interview.getId(), recruiterId, Role.RECRUITER))
                .isInstanceOf(ApiException.class);

        assertThat(interviews.findById(interview.getId())).isPresent();
    }

    @Test
    void recruiterCannotDeleteAnotherRecruitersInterview() {
        Interview interview = service.create(recruiterId, request());

        assertThatThrownBy(() -> service.delete(interview.getId(), otherRecruiterId, Role.RECRUITER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("another recruiter");

        assertThat(interviews.findById(interview.getId())).isPresent();
    }

    @Test
    void adminCanDeleteAnyScheduledInterview() {
        Long id = service.create(recruiterId, request()).getId();

        service.delete(id, otherRecruiterId, Role.ADMIN);

        assertThat(interviews.findById(id)).isEmpty();
    }
}
