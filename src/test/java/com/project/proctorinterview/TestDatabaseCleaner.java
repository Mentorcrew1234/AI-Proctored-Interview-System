package com.project.proctorinterview;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.answer.AnswerRepository;
import com.project.proctorinterview.interview.InterviewRepository;
import com.project.proctorinterview.interview.InterviewSessionRepository;
import com.project.proctorinterview.passwordreset.PasswordResetTokenRepository;
import com.project.proctorinterview.proctor.ProctorEventRepository;
import com.project.proctorinterview.question.QuestionRepository;
import com.project.proctorinterview.report.ReportRepository;
import com.project.proctorinterview.retention.DataDeletionRepository;
import com.project.proctorinterview.report.ReportReviewRepository;
import com.project.proctorinterview.user.CandidateProfileRepository;
import com.project.proctorinterview.user.RecruiterProfileRepository;
import com.project.proctorinterview.user.UserRepository;

/**
 * Wipes the test database in foreign-key order.
 *
 * <p>Every {@code @SpringBootTest} shares one H2 instance, so a class that
 * leaves rows behind breaks the next one. Doing the ordering once here removes
 * a whole class of confusing failures where tests pass alone but fail together
 * &mdash; and means adding a new table only requires editing this file.
 */
@Component
public class TestDatabaseCleaner {

    private final DataDeletionRepository dataDeletions;
    private final ReportReviewRepository reportReviews;
    private final ReportRepository reports;
    private final AnswerRepository answers;
    private final QuestionRepository questions;
    private final ProctorEventRepository events;
    private final InterviewSessionRepository sessions;
    private final InterviewRepository interviews;
    private final CandidateProfileRepository candidateProfiles;
    private final RecruiterProfileRepository recruiterProfiles;
    private final PasswordResetTokenRepository resetTokens;
    private final UserRepository users;

    public TestDatabaseCleaner(DataDeletionRepository dataDeletions,
            ReportReviewRepository reportReviews,
            ReportRepository reports, AnswerRepository answers,
            QuestionRepository questions, ProctorEventRepository events,
            InterviewSessionRepository sessions, InterviewRepository interviews,
            CandidateProfileRepository candidateProfiles,
            RecruiterProfileRepository recruiterProfiles,
            PasswordResetTokenRepository resetTokens, UserRepository users) {
        this.dataDeletions = dataDeletions;
        this.reportReviews = reportReviews;
        this.reports = reports;
        this.answers = answers;
        this.questions = questions;
        this.events = events;
        this.sessions = sessions;
        this.interviews = interviews;
        this.candidateProfiles = candidateProfiles;
        this.recruiterProfiles = recruiterProfiles;
        this.resetTokens = resetTokens;
        this.users = users;
    }

    /** Children before parents, all the way down to users. */
    @Transactional
    public void clean() {
        // Before users: the log names the administrator who performed it.
        dataDeletions.deleteAllInBatch();
        // Before reports: a review points at one.
        reportReviews.deleteAllInBatch();
        reports.deleteAllInBatch();
        answers.deleteAllInBatch();
        questions.deleteAllInBatch();
        events.deleteAllInBatch();
        sessions.deleteAllInBatch();
        interviews.deleteAllInBatch();
        candidateProfiles.deleteAllInBatch();
        recruiterProfiles.deleteAllInBatch();
        // Before users: the FK cascades in MySQL, but H2 with ddl-auto is
        // stricter and this class is the one place ordering is stated.
        resetTokens.deleteAllInBatch();
        users.deleteAllInBatch();
    }
}
