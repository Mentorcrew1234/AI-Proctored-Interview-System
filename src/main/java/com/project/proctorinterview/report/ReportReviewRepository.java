package com.project.proctorinterview.report;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportReviewRepository extends JpaRepository<ReportReview, Long> {

    /**
     * Every decision on this report, newest first - the first element is the
     * current one.
     *
     * <p>Fetches the reviewer eagerly on purpose: the page names who decided,
     * {@code open-in-view} is off, and a lazy association read in a template
     * throws. Same reasoning as {@code InterviewDetailView}.
     */
    @Query("select r from ReportReview r join fetch r.reviewer "
            + "where r.report.id = :reportId order by r.createdAt desc, r.id desc")
    List<ReportReview> historyFor(@Param("reportId") Long reportId);

    /**
     * Which of these sessions have a decision recorded, for the list page.
     *
     * <p>Keyed by session rather than report because that is what the list rows
     * carry. One query for the whole page rather than one per row - the same
     * reasoning as {@code findClientEventIdsIn}. A report with several
     * decisions appears once.
     */
    @Query("select distinct r.report.session.id from ReportReview r "
            + "where r.report.session.id in :sessionIds")
    Set<Long> decidedSessionIdsIn(@Param("sessionIds") Collection<Long> sessionIds);
}
