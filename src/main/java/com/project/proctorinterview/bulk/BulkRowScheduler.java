package com.project.proctorinterview.bulk;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.mail.MailEvents.CandidateAccountCreated;

import com.project.proctorinterview.bulk.dto.BulkDtos.RawRow;
import com.project.proctorinterview.bulk.dto.BulkDtos.ScheduledRow;
import com.project.proctorinterview.bulk.dto.BulkDtos.ValidatedRow;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewService;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;
import com.project.proctorinterview.user.UserService;
import com.project.proctorinterview.user.dto.UserDtos.CreateUserRequest;

/**
 * Creates one interview from one validated row, in its own transaction.
 *
 * <p>This lives in its own bean rather than as a method on
 * {@link BulkScheduleService} for a specific reason: Spring applies
 * {@code @Transactional} through a proxy, so a self-invoked method would silently
 * run in the caller's transaction instead of its own. A single failing row would
 * then roll back the whole batch — exactly the behaviour REQUIRES_NEW is here to
 * prevent.
 */
@Service
public class BulkRowScheduler {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BulkValidationService validationService;
    private final InterviewService interviewService;
    private final UserService userService;
    private final UserRepository users;
    /** Announces a newly created account so its password can be emailed once. */
    private final ApplicationEventPublisher events;

    public BulkRowScheduler(BulkValidationService validationService, InterviewService interviewService,
            UserService userService, UserRepository users, ApplicationEventPublisher events) {
        this.validationService = validationService;
        this.interviewService = interviewService;
        this.userService = userService;
        this.users = users;
        this.events = events;
    }

    /**
     * Re-validates and creates. Rolls back only this row on failure.
     *
     * <p>The re-check matters: the batch was validated at upload time, but the
     * date may since have passed or the candidate may have been disabled or
     * scheduled elsewhere. Trusting the earlier verdict could create a row the
     * single-schedule form would reject today.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ScheduledRow scheduleOne(ValidatedRow row, Long actorUserId) {
        ValidatedRow rechecked = validationService.validate(List.of(toRawRow(row))).getFirst();
        if (!rechecked.valid()) {
            throw new IllegalStateException(rechecked.errorText());
        }

        CandidateResolution candidate = resolveCandidate(row);

        CreateInterviewRequest request = new CreateInterviewRequest();
        request.setInterviewName(row.interviewName());
        request.setCandidateId(candidate.user().getId());
        request.setScheduledAt(row.scheduledAt());
        request.setCandidateType(row.candidateType());
        request.setExperienceYears(row.experienceYears());
        request.setDomain(row.domain());
        request.setInterviewType(row.interviewType());
        request.setQuestionCount(row.questionCount());
        request.setDurationMinutes(row.durationMinutes());
        request.setResultVisibleToCandidate(false);

        // The existing scheduling service: same rules, same invite token, same
        // SCHEDULED status. Nothing about this interview is "bulk".
        Interview interview = interviewService.create(actorUserId, request);

        return new ScheduledRow(
                row.excelRowNumber(),
                row.interviewName(),
                candidate.user().getFullName(),
                candidate.user().getEmail(),
                row.scheduledAtText(),
                row.domain(),
                interview.getInviteToken(),
                candidate.created(),
                candidate.generatedPassword());
    }

    private record CandidateResolution(User user, boolean created, String generatedPassword) {
    }

    /**
     * Finds the candidate by email, or creates the account through the existing
     * {@link UserService} so profile creation and password hashing stay in one
     * place.
     *
     * <p>There is no email integration in this system, so the generated password
     * is returned and shown on the results page for the recruiter to pass on.
     */
    private CandidateResolution resolveCandidate(ValidatedRow row) {
        User existing = users.findByEmailIgnoreCase(row.candidateEmail()).orElse(null);
        if (existing != null) {
            return new CandidateResolution(existing, false, null);
        }

        String password = generatePassword();
        CreateUserRequest request = new CreateUserRequest();
        request.setFullName(row.candidateName());
        request.setEmail(row.candidateEmail());
        request.setPassword(password);
        request.setRole(Role.CANDIDATE);
        request.setCandidateType(row.candidateType());
        request.setExperienceYears(row.experienceYears());
        request.setPrimaryDomain(row.domain());
        // Only on creation. An upload never edits an account that already
        // exists - it does not change their name, phone or domain either, and
        // silently overwriting a college recorded elsewhere would be the
        // spreadsheet quietly winning an argument nobody knew it was having.
        request.setCollegeName(row.collegeName());
        request.setLocation(row.location());
        request.setSkills(row.skills());

        User created = userService.create(request);

        // The only automatic delivery of a generated password. It is published
        // rather than sent here so it waits for this row's own transaction to
        // commit - a row that rolls back must not have already mailed working
        // credentials for an account that no longer exists. If mail is off or
        // the send fails, the result workbook remains the way to relay it.
        events.publishEvent(new CandidateAccountCreated(
                created.getId(), created.getFullName(), created.getEmail(), password));

        return new CandidateResolution(created, true, password);
    }

    /** Rebuilds a raw row so the re-check runs through exactly the same code path. */
    private static RawRow toRawRow(ValidatedRow row) {
        return new RawRow(
                row.excelRowNumber(),
                row.interviewName(),
                row.candidateName(),
                row.candidateEmail(),
                row.scheduledAt() == null ? "" : row.scheduledAt().toLocalDate().toString(),
                row.scheduledAt() == null ? "" : row.scheduledAt().toLocalTime().toString(),
                row.domain(),
                row.candidateType() == null ? "" : row.candidateType().name(),
                row.experienceYears() == null ? "" : String.valueOf(row.experienceYears()),
                row.language() == null ? "" : row.language().name(),
                row.interviewType() == null ? "" : row.interviewType().name(),
                String.valueOf(row.questionCount()),
                String.valueOf(row.durationMinutes()),
                row.collegeName() == null ? "" : row.collegeName(),
                row.location() == null ? "" : row.location(),
                row.skills() == null ? "" : row.skills());
    }

    /** Readable but not guessable; the recruiter relays it manually. */
    private static String generatePassword() {
        byte[] bytes = new byte[9];
        RANDOM.nextBytes(bytes);
        return "Iv-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
