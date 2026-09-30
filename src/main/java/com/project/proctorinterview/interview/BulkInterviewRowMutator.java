package com.project.proctorinterview.interview;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.dto.BulkEditDtos.BulkEditFields;

/**
 * Applies one bulk edit/delete to one interview, in its own transaction.
 *
 * <p>Separate bean for the same reason as {@code BulkRowScheduler}: Spring's
 * {@code @Transactional} is proxy-based, so a self-invoked method would run
 * in the caller's transaction instead of its own. Without REQUIRES_NEW here,
 * one failing row in a "select all matching filter" batch could roll back
 * every row that already succeeded.
 */
@Service
public class BulkInterviewRowMutator {

    private final InterviewService interviewService;

    public BulkInterviewRowMutator(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteOne(Long interviewId, Long actorId, Role actorRole) {
        interviewService.delete(interviewId, actorId, actorRole);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateOne(Long interviewId, Long actorId, Role actorRole, BulkEditFields fields) {
        interviewService.bulkUpdateFields(interviewId, actorId, actorRole, fields);
    }
}
