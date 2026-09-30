package com.project.proctorinterview.interview;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.project.proctorinterview.common.Enums.SessionStatus;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {

    Optional<InterviewSession> findByInterviewId(Long interviewId);

    Optional<InterviewSession> findByInterviewInviteToken(String inviteToken);

    /** Batch lookup so the candidate dashboard is not one query per interview. */
    List<InterviewSession> findByInterviewIdIn(List<Long> interviewIds);

    /**
     * Sessions still running, with their interview already fetched.
     *
     * <p>For the expiry sweep. The join fetch matters: the sweep asks each
     * session for its deadline, which reads the interview's duration through
     * what would otherwise be a lazy association - one query per row, or a
     * {@code LazyInitializationException} outside a transaction.
     *
     * <p>Only in-flight interviews match, so this stays a short list however
     * many interviews the system has held over its lifetime.
     */
    @Query("select s from InterviewSession s join fetch s.interview where s.status = :status")
    List<InterviewSession> findRunningWithInterview(@Param("status") SessionStatus status);
}
