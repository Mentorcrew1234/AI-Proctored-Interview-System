package com.project.proctorinterview.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;

import com.project.proctorinterview.TestDatabaseCleaner;
import com.project.proctorinterview.bulk.BulkRowScheduler;
import com.project.proctorinterview.bulk.dto.BulkDtos.ValidatedRow;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.InterviewService;
import com.project.proctorinterview.interview.MultiScheduleService;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.interview.dto.MultiScheduleDtos.MultiScheduleRequest;
import com.project.proctorinterview.mail.MailEvents.CandidateAccountCreated;
import com.project.proctorinterview.mail.MailEvents.InterviewScheduled;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Every scheduling path must invite the candidate.
 *
 * <p>This is the test that makes "it works on all scenarios" a property of the
 * system rather than a claim. Scheduling happens through three different
 * entry points - the single form, the multi-candidate form and the Excel bulk
 * upload - and a future fourth would be easy to add without remembering the
 * email. All of them funnel through {@code InterviewService.create}, which is
 * where the event is published, and these tests assert that for each path
 * independently rather than trusting the shared call.
 *
 * <p>The listener is registered as a plain {@code @EventListener} here, not a
 * transactional one: the point is to observe that the event was published at
 * all. That it is <em>delivered</em> only after commit is
 * {@link SchedulingMailListener}'s own annotation, and is what keeps a rolled
 * back row from mailing anybody.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(SchedulingEmailCoverageTest.RecordingListener.class)
class SchedulingEmailCoverageTest {

    /** Collects what the scheduling code announced, in publication order. */
    @Component
    static class RecordingListener {
        final List<InterviewScheduled> invites = new CopyOnWriteArrayList<>();
        final List<CandidateAccountCreated> accounts = new CopyOnWriteArrayList<>();

        @EventListener
        void onInvite(InterviewScheduled event) {
            invites.add(event);
        }

        @EventListener
        void onAccount(CandidateAccountCreated event) {
            accounts.add(event);
        }

        void clear() {
            invites.clear();
            accounts.clear();
        }
    }

    @TestConfiguration
    static class Config {
    }

    @Autowired
    private TestDatabaseCleaner cleaner;
    @Autowired
    private UserRepository users;
    @Autowired
    private InterviewService interviewService;
    @Autowired
    private MultiScheduleService multiScheduleService;
    @Autowired
    private BulkRowScheduler bulkRowScheduler;
    @Autowired
    private RecordingListener listener;

    private User recruiter;
    private User candidate;
    private User secondCandidate;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        listener.clear();
        recruiter = save("recruiter@mail-test.local", "Priya Raman", Role.RECRUITER);
        candidate = save("candidate@mail-test.local", "Arun Kumar", Role.CANDIDATE);
        secondCandidate = save("second@mail-test.local", "Meena S", Role.CANDIDATE);
    }

    private User save(String email, String name, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(name);
        user.setRole(role);
        user.setPasswordHash("{noop}x");
        user.setEnabled(true);
        return users.save(user);
    }

    private CreateInterviewRequest request(Long candidateId) {
        CreateInterviewRequest request = new CreateInterviewRequest();
        request.setInterviewName("Java Campus Drive");
        request.setCandidateId(candidateId);
        request.setScheduledAt(LocalDateTime.now().plusDays(1));
        request.setCandidateType(CandidateType.FRESHER);
        request.setDomain("Java");
        request.setInterviewType(InterviewType.TECHNICAL);
        request.setQuestionCount(5);
        request.setDurationMinutes(30);
        request.setResultVisibleToCandidate(true);
        return request;
    }

    // ---- scenario 1: a single interview -------------------------------------

    @Test
    void schedulingOneInterviewAnnouncesTheInvitation() {
        interviewService.create(recruiter.getId(), request(candidate.getId()));

        assertThat(listener.invites).hasSize(1);
        InterviewScheduled event = listener.invites.getFirst();
        assertThat(event.candidateEmail()).isEqualTo("candidate@mail-test.local");
        assertThat(event.candidateName()).isEqualTo("Arun Kumar");
        assertThat(event.recruiterName()).isEqualTo("Priya Raman");
        assertThat(event.interviewName()).isEqualTo("Java Campus Drive");
        assertThat(event.questionCount()).isEqualTo(5);
        assertThat(event.durationMinutes()).isEqualTo(30);
    }

    /**
     * The snapshot has to be complete on its own: the listener runs after the
     * transaction closes, so anything it needs that was not copied here would
     * be unreachable rather than merely missing.
     */
    @Test
    void theInvitationCarriesAUsableInviteToken() {
        var interview = interviewService.create(recruiter.getId(), request(candidate.getId()));

        assertThat(listener.invites.getFirst().inviteToken())
                .isNotBlank()
                .isEqualTo(interview.getInviteToken());
        assertThat(listener.invites.getFirst().interviewId()).isEqualTo(interview.getId());
    }

    // ---- scenario 2: several candidates at once ------------------------------

    @Test
    void schedulingSeveralCandidatesAnnouncesOneInvitationEach() {
        MultiScheduleRequest request = new MultiScheduleRequest();
        request.setInterviewName("Java Campus Drive");
        request.setCandidateIds(new ArrayList<>(List.of(candidate.getId(), secondCandidate.getId())));
        request.setScheduledAt(LocalDateTime.now().plusDays(1));
        request.setCandidateType(CandidateType.FRESHER);
        request.setDomain("Java");
        request.setInterviewType(InterviewType.TECHNICAL);
        request.setQuestionCount(5);
        request.setDurationMinutes(30);
        request.setResultVisibleToCandidate(false);

        multiScheduleService.schedule(recruiter.getId(), request);

        assertThat(listener.invites).hasSize(2);
        assertThat(listener.invites).extracting(InterviewScheduled::candidateEmail)
                .containsExactlyInAnyOrder("candidate@mail-test.local", "second@mail-test.local");
    }

    // ---- scenario 3: the Excel bulk upload -----------------------------------

    @Test
    void bulkSchedulingAnExistingCandidateAnnouncesOnlyTheInvitation() {
        bulkRowScheduler.scheduleOne(bulkRow("candidate@mail-test.local", "Arun Kumar"), recruiter.getId());

        assertThat(listener.invites).hasSize(1);
        assertThat(listener.invites.getFirst().candidateEmail()).isEqualTo("candidate@mail-test.local");
        // An existing candidate already has a password; re-sending credentials
        // for every new interview would be both wrong and impossible - the
        // stored hash cannot be read back.
        assertThat(listener.accounts).isEmpty();
    }

    /**
     * The bulk upload is the only path that creates accounts, and a generated
     * password is the only thing that makes the invitation usable to someone
     * who has never signed in.
     */
    @Test
    void bulkSchedulingANewCandidateAnnouncesBothTheAccountAndTheInvitation() {
        bulkRowScheduler.scheduleOne(bulkRow("brand.new@mail-test.local", "Brand New"), recruiter.getId());

        assertThat(listener.accounts).hasSize(1);
        CandidateAccountCreated account = listener.accounts.getFirst();
        assertThat(account.candidateEmail()).isEqualTo("brand.new@mail-test.local");
        assertThat(account.generatedPassword()).isNotBlank();

        assertThat(listener.invites).hasSize(1);
        assertThat(listener.invites.getFirst().candidateEmail()).isEqualTo("brand.new@mail-test.local");
    }

    private ValidatedRow bulkRow(String email, String name) {
        LocalDateTime when = LocalDateTime.now().plusDays(1).withNano(0);
        return new ValidatedRow(
                2, "Bulk Drive", name, email, "Test College", "Test City", "Java, SQL",
                when, when.toString(), "Java",
                CandidateType.FRESHER, null, InterviewLanguage.ENGLISH, InterviewType.TECHNICAL,
                5, 30, true, List.of(), true);
    }
}
