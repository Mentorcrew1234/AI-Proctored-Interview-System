package com.project.proctorinterview.report;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.ReviewDecision;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewSession;
import com.project.proctorinterview.interview.InterviewSessionRepository;
import com.project.proctorinterview.report.dto.ReportDtos.ReviewEntry;
import com.project.proctorinterview.report.dto.ReportDtos.ReviewSummary;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Records what a person decided after reading a report.
 *
 * <p>Every report already ends with "advisory input for a human decision, not a
 * hiring decision" - {@code RecommendationEngine} says it, the page says it,
 * {@code docs/system/quality/LIMITATIONS.md} says it. Until this existed there was nowhere to
 * put the decision, so the system deferred to a human and then discarded their
 * answer.
 *
 * <h2>Rules</h2>
 *
 * <b>Staff only, and never the candidate.</b> Unlike scores, which a recruiter
 * can choose to show through {@code resultVisibleToCandidate}, a review is never
 * visible to the person it is about. A half-formed internal judgement delivered
 * to a candidate with no context would be worse than no feedback, and there is
 * no flag to turn this on - the absence of the option is the design.
 *
 * <b>Scope is the same predicate as viewing.</b> A recruiter may decide only on
 * their own interviews; an admin on any. Reusing {@link
 * ReportService#assertCanView} rather than writing a second rule means the two
 * cannot drift apart - and a candidate is rejected before that check even runs,
 * because for them the answer is never "yes".
 *
 * <b>Nothing here touches the report.</b> Scores, recommendation and explanation
 * are what the system computed and stay exactly as computed. A review sits
 * beside them, which is what makes "the model said X, the recruiter decided Y"
 * a statement the data can actually support.
 */
@Service
public class ReportReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReportReviewService.class);
    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());
    private static final int MAX_NOTE = 2000;

    private final ReportRepository reports;
    private final ReportReviewRepository reviews;
    private final InterviewSessionRepository sessions;
    private final UserRepository users;
    private final ReportService reportService;

    public ReportReviewService(ReportRepository reports, ReportReviewRepository reviews,
            InterviewSessionRepository sessions, UserRepository users, ReportService reportService) {
        this.reports = reports;
        this.reviews = reviews;
        this.sessions = sessions;
        this.users = users;
        this.reportService = reportService;
    }

    /**
     * Records a decision against the report for this session.
     *
     * @throws ApiException if the actor may not decide on this report
     */
    @Transactional
    public ReportReview record(Long sessionId, Long actorId, Role actorRole,
            ReviewDecision decision, String note) {

        assertCanReview(sessionId, actorId, actorRole);

        if (decision == null) {
            throw ApiException.badRequest("Choose a decision");
        }
        String trimmed = note == null ? null : note.trim();
        if (trimmed != null && trimmed.length() > MAX_NOTE) {
            throw ApiException.badRequest("The note is too long (limit " + MAX_NOTE + " characters)");
        }

        Report report = reports.findBySessionId(sessionId)
                .orElseThrow(() -> ApiException.notFound("Report"));
        User reviewer = users.findById(actorId)
                .orElseThrow(() -> ApiException.notFound("User"));

        ReportReview review = new ReportReview();
        review.setReport(report);
        review.setReviewer(reviewer);
        review.setDecision(decision);
        review.setNote(trimmed == null || trimmed.isEmpty() ? null : trimmed);
        reviews.save(review);

        // Logged because it is a decision about a person, and because whether
        // humans agree with the model is the one thing worth counting here.
        log.info("Report {} (session {}) decided {} by user {} - model had said {} ({})",
                report.getId(), sessionId, decision, actorId, report.getRecommendation(),
                decision.agreesWith(report.getRecommendation()) ? "agrees" : "differs");

        return review;
    }

    /** The decision history for a session's report, newest first. */
    @Transactional(readOnly = true)
    public ReviewSummary summaryFor(Long sessionId) {
        Report report = reports.findBySessionId(sessionId).orElse(null);
        if (report == null) {
            return new ReviewSummary(null, List.of());
        }

        List<ReviewEntry> entries = reviews.historyFor(report.getId()).stream()
                .map(r -> new ReviewEntry(
                        r.getDecision(),
                        r.getDecision().label(),
                        r.getReviewer().getFullName(),
                        r.getNote(),
                        r.getCreatedAt(),
                        DISPLAY.format(r.getCreatedAt()),
                        r.getDecision().agreesWith(report.getRecommendation())))
                .toList();

        // The first entry is the current decision; the rest are what it replaced.
        return new ReviewSummary(entries.isEmpty() ? null : entries.getFirst(), entries);
    }

    /**
     * Which of these sessions already have a decision, for the list page.
     *
     * <p>A set rather than a per-row lookup: one query for the page, and the
     * template only ever asks "is this one in it".
     */
    @Transactional(readOnly = true)
    public java.util.Set<Long> decidedAmong(java.util.Collection<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return java.util.Set.of();
        }
        return reviews.decidedSessionIdsIn(sessionIds);
    }

    /**
     * Whether this actor may record a decision on this report.
     *
     * <p>A candidate never may, whatever the visibility flag says - checked
     * before the view rule, because for them the answer is not "it depends".
     */
    @Transactional(readOnly = true)
    public void assertCanReview(Long sessionId, Long actorId, Role actorRole) {
        if (actorRole == Role.CANDIDATE) {
            // Not "forbidden with an explanation": a candidate has no business
            // knowing this facility exists for their own report.
            throw ApiException.notFound("Report");
        }
        reportService.assertCanView(sessionId, actorId, actorRole);

        InterviewSession session = sessions.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Report"));
        Interview interview = session.getInterview();
        if (interview == null) {
            throw ApiException.notFound("Report");
        }
    }
}
