package com.project.proctorinterview.interview;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;

/**
 * Creates one interview for one candidate, in its own transaction.
 *
 * <p>A separate bean for the same reason as {@code BulkRowScheduler} and
 * {@code BulkInterviewRowMutator}: Spring applies {@code @Transactional}
 * through a proxy, so a self-invoked method would quietly run in the caller's
 * transaction instead of its own. Without {@code REQUIRES_NEW} here, one
 * candidate whose account had just been disabled would roll back every
 * interview already created for the others.
 */
@Service
public class MultiScheduleRowScheduler {

    private final InterviewService interviewService;

    public MultiScheduleRowScheduler(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    /**
     * Schedules for one candidate through the ordinary scheduling service, so
     * the same validation, the same invite token and the same SCHEDULED status
     * apply. Rolls back only this candidate on failure.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Interview scheduleOne(Long actorUserId, CreateInterviewRequest request) {
        return interviewService.create(actorUserId, request);
    }
}
