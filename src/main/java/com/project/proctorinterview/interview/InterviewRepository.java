package com.project.proctorinterview.interview;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * {@code JpaSpecificationExecutor} backs the management page's dynamic
 * filtering. With eight independent, optional filters, a finder method per
 * combination would be unmaintainable; the predicates are built in
 * {@link InterviewSpecifications} instead.
 */
public interface InterviewRepository
        extends JpaRepository<Interview, Long>, JpaSpecificationExecutor<Interview> {

    /** Entry point for the candidate's invite link. */
    Optional<Interview> findByInviteToken(String inviteToken);

    List<Interview> findByRecruiterIdOrderByScheduledAtDesc(Long recruiterId);

    List<Interview> findByCandidateIdOrderByScheduledAtDesc(Long candidateId);

    List<Interview> findAllByOrderByScheduledAtDesc();

    /**
     * Every domain in use, sorted. Feeds the filter dropdown, which since
     * domains became free text can no longer be built from a fixed list.
     */
    @Query("select distinct i.domain from Interview i where i.domain is not null order by i.domain")
    List<String> findDistinctDomains();

    // ---- candidates a recruiter has actually interviewed --------------------
    //
    // The scope is in the query rather than filtered afterwards. A recruiter
    // must only ever see candidates they scheduled, and doing that in Java over
    // an unrestricted result would mean the unrestricted result existed at all -
    // one forgotten filter away from a leak.

    /**
     * One row per candidate this recruiter has scheduled, with their counts.
     *
     * <p>Aggregated in the database rather than by loading every interview,
     * because the page shows totals and would otherwise read a recruiter's
     * whole history to count it.
     */
    @Query("""
            select i.candidate.id, i.candidate.fullName, i.candidate.email,
                   count(i), max(i.scheduledAt),
                   sum(case when i.status = com.project.proctorinterview.common.Enums.InterviewStatus.COMPLETED then 1 else 0 end),
                   cp.collegeName, cp.location, cp.skills, cp.primaryDomain,
                   cp.candidateType, cp.experienceYears
            from Interview i
            left join CandidateProfile cp on cp.user.id = i.candidate.id
            where i.recruiter.id = :recruiterId
            group by i.candidate.id, i.candidate.fullName, i.candidate.email,
                     cp.collegeName, cp.location, cp.skills, cp.primaryDomain,
                     cp.candidateType, cp.experienceYears
            order by max(i.scheduledAt) desc
            """)
    List<Object[]> findCandidateSummariesForRecruiter(@Param("recruiterId") Long recruiterId);

    /** The same, unrestricted, for an administrator. */
    @Query("""
            select i.candidate.id, i.candidate.fullName, i.candidate.email,
                   count(i), max(i.scheduledAt),
                   sum(case when i.status = com.project.proctorinterview.common.Enums.InterviewStatus.COMPLETED then 1 else 0 end),
                   cp.collegeName, cp.location, cp.skills, cp.primaryDomain,
                   cp.candidateType, cp.experienceYears
            from Interview i
            left join CandidateProfile cp on cp.user.id = i.candidate.id
            group by i.candidate.id, i.candidate.fullName, i.candidate.email,
                     cp.collegeName, cp.location, cp.skills, cp.primaryDomain,
                     cp.candidateType, cp.experienceYears
            order by max(i.scheduledAt) desc
            """)
    List<Object[]> findCandidateSummariesForAll();

    /** One candidate's interviews with this recruiter, newest first. */
    List<Interview> findByCandidateIdAndRecruiterIdOrderByScheduledAtDesc(
            Long candidateId, Long recruiterId);
}
