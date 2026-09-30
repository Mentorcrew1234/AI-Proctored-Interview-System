package com.project.proctorinterview.retention;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.answer.AnswerRepository;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.config.RetentionProperties;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewRepository;
import com.project.proctorinterview.interview.InterviewSession;
import com.project.proctorinterview.interview.InterviewSessionRepository;
import com.project.proctorinterview.passwordreset.PasswordResetTokenRepository;
import com.project.proctorinterview.proctor.ProctorEventRepository;
import com.project.proctorinterview.question.QuestionRepository;
import com.project.proctorinterview.report.ReportRepository;
import com.project.proctorinterview.report.ReportReviewRepository;
import com.project.proctorinterview.retention.DataDeletion.Scope;
import com.project.proctorinterview.user.CandidateProfileRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Erasing a candidate's data, and seeing how much of it is old.
 *
 * <p>The system records what a candidate said word for word, what was observed
 * of them through their own camera, and how they were scored. Until this existed
 * none of it could be removed - {@code docs/system/quality/LIMITATIONS.md} said so plainly. For
 * a system that records and scores people, "we cannot delete your data" is not a
 * small gap.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <b>It does not run on a schedule.</b> There is no cron that quietly destroys
 * old interviews. An automatic purge is the kind of feature that works perfectly
 * until the morning of a demonstration, and the failure is unrecoverable. What
 * exists instead is a <em>preview</em> of what is older than the retention
 * window, and a deletion that a named administrator performs deliberately.
 *
 * <b>It does not anonymise.</b> Erasure means the rows go. Blanking a name while
 * keeping the transcript, the observations and the scores would leave data that
 * is still about a person and still re-identifiable from the interview it sits
 * under - a weaker guarantee dressed as a stronger one.
 *
 * <h2>Ordering</h2>
 *
 * Children before parents, all the way down, exactly as {@code
 * TestDatabaseCleaner} does it for the same foreign keys. Getting this wrong
 * produces a constraint violation rather than partial destruction, because it
 * all runs in one transaction - but the order is stated once, here, so adding a
 * table means editing one method.
 */
@Service
public class DataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);
    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    private final UserRepository users;
    private final CandidateProfileRepository candidateProfiles;
    private final InterviewRepository interviews;
    private final InterviewSessionRepository sessions;
    private final QuestionRepository questions;
    private final AnswerRepository answers;
    private final ProctorEventRepository proctorEvents;
    private final ReportRepository reports;
    private final ReportReviewRepository reviews;
    private final PasswordResetTokenRepository resetTokens;
    private final DataDeletionRepository deletions;
    private final RetentionProperties properties;

    public DataRetentionService(UserRepository users, CandidateProfileRepository candidateProfiles,
            InterviewRepository interviews, InterviewSessionRepository sessions,
            QuestionRepository questions, AnswerRepository answers,
            ProctorEventRepository proctorEvents, ReportRepository reports,
            ReportReviewRepository reviews, PasswordResetTokenRepository resetTokens,
            DataDeletionRepository deletions, RetentionProperties properties) {
        this.users = users;
        this.candidateProfiles = candidateProfiles;
        this.interviews = interviews;
        this.sessions = sessions;
        this.questions = questions;
        this.answers = answers;
        this.proctorEvents = proctorEvents;
        this.reports = reports;
        this.reviews = reviews;
        this.resetTokens = resetTokens;
        this.deletions = deletions;
        this.properties = properties;
    }

    /**
     * What exists for one candidate, and would go.
     *
     * @param oldestScheduledAt null when there is nothing to delete
     */
    public record Preview(
            Long userId,
            String candidateName,
            String candidateEmail,
            int interviews,
            int sessions,
            int questions,
            int answers,
            int proctorEvents,
            int reports,
            int reviews,
            Instant oldestScheduledAt,
            Instant newestScheduledAt) {

        public boolean isEmpty() {
            return interviews == 0;
        }

        /** Total rows, for a single honest figure on the confirmation page. */
        public int totalRows() {
            return interviews + sessions + questions + answers + proctorEvents + reports + reviews;
        }
    }

    /**
     * Exactly what would be removed for this candidate.
     *
     * <p>Shown before anything is destroyed, because "delete this person's data"
     * is not a request anyone should approve without seeing its size.
     */
    @Transactional(readOnly = true)
    public Preview preview(Long userId) {
        User user = requireCandidate(userId);

        List<Interview> owned = interviews.findByCandidateIdOrderByScheduledAtDesc(userId);
        List<Long> interviewIds = owned.stream().map(Interview::getId).toList();

        List<InterviewSession> ownedSessions = interviewIds.isEmpty()
                ? List.of()
                : sessions.findByInterviewIdIn(interviewIds);
        List<Long> sessionIds = ownedSessions.stream().map(InterviewSession::getId).toList();

        int questionCount = 0;
        int answerCount = 0;
        int eventCount = 0;
        int reportCount = 0;
        int reviewCount = 0;

        for (Long sessionId : sessionIds) {
            var sessionQuestions = questions.findBySessionIdOrderBySequenceNoAsc(sessionId);
            questionCount += sessionQuestions.size();
            answerCount += answers.findByQuestionSessionIdOrderByQuestionSequenceNoAsc(sessionId).size();
            eventCount += (int) proctorEvents.countBySessionId(sessionId);

            var report = reports.findBySessionId(sessionId).orElse(null);
            if (report != null) {
                reportCount++;
                reviewCount += reviews.historyFor(report.getId()).size();
            }
        }

        Instant oldest = owned.stream().map(Interview::getScheduledAt)
                .filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
        Instant newest = owned.stream().map(Interview::getScheduledAt)
                .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);

        return new Preview(userId, user.getFullName(), user.getEmail(),
                owned.size(), ownedSessions.size(), questionCount, answerCount,
                eventCount, reportCount, reviewCount, oldest, newest);
    }

    /**
     * Erases this candidate's interview data, and optionally the account.
     *
     * <p>One transaction: it either all goes or none of it does. A partially
     * erased candidate - transcripts gone, observations left behind - would be
     * worse than either outcome.
     *
     * @param confirmationEmail must match the account's address exactly. The
     *                          action is irreversible and cannot be undone from
     *                          a backup the prototype does not take, so it asks
     *                          the administrator to name what they are erasing
     *                          rather than to click twice.
     */
    @Transactional
    public DataDeletion deleteCandidateData(Long userId, User actor, Scope scope,
            String confirmationEmail, String reason) {

        User user = requireCandidate(userId);

        if (confirmationEmail == null
                || !confirmationEmail.trim().equalsIgnoreCase(user.getEmail())) {
            throw ApiException.badRequest(
                    "Type the candidate's email address exactly to confirm this deletion");
        }

        Preview counts = preview(userId);

        // Children before parents. Same ordering as TestDatabaseCleaner, for
        // the same foreign keys.
        List<Interview> owned = interviews.findByCandidateIdOrderByScheduledAtDesc(userId);
        List<Long> interviewIds = owned.stream().map(Interview::getId).toList();
        List<InterviewSession> ownedSessions = interviewIds.isEmpty()
                ? List.of()
                : sessions.findByInterviewIdIn(interviewIds);

        for (InterviewSession session : ownedSessions) {
            Long sessionId = session.getId();

            reports.findBySessionId(sessionId).ifPresent(report -> {
                reviews.deleteAll(reviews.historyFor(report.getId()));
                reports.delete(report);
            });
            answers.deleteAll(answers.findByQuestionSessionIdOrderByQuestionSequenceNoAsc(sessionId));
            questions.deleteAll(questions.findBySessionIdOrderBySequenceNoAsc(sessionId));
            proctorEvents.deleteAll(proctorEvents.findBySessionIdOrderByStartTimeAsc(sessionId));
        }
        sessions.deleteAll(ownedSessions);
        interviews.deleteAll(owned);

        if (scope == Scope.INTERVIEW_DATA_AND_ACCOUNT) {
            resetTokens.invalidateAllFor(userId, Instant.now());
            candidateProfiles.findByUserId(userId).ifPresent(candidateProfiles::delete);
            users.delete(user);
        }

        DataDeletion record = new DataDeletion();
        record.setSubjectUserId(userId);
        record.setScope(scope);
        record.setPerformedBy(actor);
        record.setReason(reason == null || reason.isBlank() ? null : reason.trim());
        record.setInterviewsDeleted(counts.interviews());
        record.setSessionsDeleted(counts.sessions());
        record.setQuestionsDeleted(counts.questions());
        record.setAnswersDeleted(counts.answers());
        record.setProctorEventsDeleted(counts.proctorEvents());
        record.setReportsDeleted(counts.reports());
        record.setReviewsDeleted(counts.reviews());
        deletions.save(record);

        // Logged without the address: the point of the operation is that the
        // address stops being stored, and a log line would undo that.
        log.warn("Data deletion by user {}: subject {}, scope {}, {} rows removed",
                actor.getId(), userId, scope, counts.totalRows());

        return record;
    }

    /** How much interview data is older than the configured retention window. */
    public record RetentionOverview(
            int months,
            Instant cutoff,
            long interviewsOlder,
            long interviewsTotal) {

        public boolean anythingOld() {
            return interviewsOlder > 0;
        }
    }

    /**
     * What is past the retention window - a report, never an action.
     *
     * <p>Nothing acts on this automatically. It exists so somebody can see that
     * data has outlived its purpose and decide what to do, which is a different
     * thing from a scheduled job quietly destroying it.
     */
    @Transactional(readOnly = true)
    public RetentionOverview retentionOverview() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30L * properties.months()));

        List<Interview> all = interviews.findAllByOrderByScheduledAtDesc();
        long older = all.stream()
                .filter(i -> i.getScheduledAt() != null && i.getScheduledAt().isBefore(cutoff))
                .count();

        return new RetentionOverview(properties.months(), cutoff, older, all.size());
    }

    /**
     * One row of the erasure log, ready to render.
     *
     * <p>Deliberately carries no name or email of the subject - only the id of
     * an account that by then usually does not exist. That is the whole design
     * of the table it comes from: an erasure request must not leave the
     * identity behind in the record of the erasure.
     */
    public record DeletionRecord(
            Long subjectUserId,
            String scopeLabel,
            String performedByName,
            String reason,
            int totalRows,
            Instant deletedAt,
            String deletedAtText) {
    }

    /**
     * The erasure log, newest first.
     *
     * <p>Mapped to records <b>inside</b> this transaction rather than handing
     * entities to the template: {@code open-in-view} is off, so a lazy
     * association read during rendering throws - and it throws after the
     * response has been committed, which produces a half-written page rather
     * than an error page. Same reasoning as {@code InterviewDetailView}.
     */
    @Transactional(readOnly = true)
    public List<DeletionRecord> deletionLog() {
        return deletions.recentFirst().stream()
                .map(d -> new DeletionRecord(
                        d.getSubjectUserId(),
                        d.getScope().label(),
                        d.getPerformedBy().getFullName(),
                        d.getReason(),
                        d.getInterviewsDeleted() + d.getSessionsDeleted() + d.getQuestionsDeleted()
                                + d.getAnswersDeleted() + d.getProctorEventsDeleted()
                                + d.getReportsDeleted() + d.getReviewsDeleted(),
                        d.getCreatedAt(),
                        DISPLAY.format(d.getCreatedAt())))
                .toList();
    }

    /**
     * Only a candidate's data can be erased here.
     *
     * <p>A recruiter or admin account owns interviews rather than being their
     * subject, so removing one would orphan other people's records. Deleting
     * staff is a different operation with different consequences and is
     * deliberately not offered.
     */
    private User requireCandidate(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User"));
        if (user.getRole() != Role.CANDIDATE) {
            throw ApiException.badRequest(
                    "Only a candidate's data can be erased here. A recruiter or administrator "
                            + "owns interviews rather than being their subject.");
        }
        return user;
    }
}
