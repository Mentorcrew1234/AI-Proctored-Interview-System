package com.project.proctorinterview.interview;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.dto.BulkEditDtos.BulkEditFields;
import com.project.proctorinterview.interview.dto.BulkEditDtos.BulkOutcome;
import com.project.proctorinterview.interview.dto.BulkEditDtos.BulkResult;

/**
 * Orchestrates bulk edit/delete from the interview management page. Each row
 * is applied independently by {@link BulkInterviewRowMutator} - a failure on
 * one interview never rolls back the others, and is reported instead of
 * being swallowed. Not itself transactional, so this loop never becomes one
 * shared transaction that a single bad row could roll back.
 */
@Service
public class BulkInterviewMutationService {

    private static final Logger log = LoggerFactory.getLogger(BulkInterviewMutationService.class);

    private final BulkInterviewRowMutator rowMutator;
    private final InterviewRepository interviews;

    public BulkInterviewMutationService(BulkInterviewRowMutator rowMutator, InterviewRepository interviews) {
        this.rowMutator = rowMutator;
        this.interviews = interviews;
    }

    public BulkResult bulkDelete(List<Long> ids, Long actorId, Role actorRole) {
        List<BulkOutcome> outcomes = new ArrayList<>();
        int succeeded = 0;
        for (Long id : ids) {
            String name = displayNameOf(id);
            try {
                rowMutator.deleteOne(id, actorId, actorRole);
                succeeded++;
                outcomes.add(new BulkOutcome(id, name, true, null));
            } catch (Exception e) {
                log.warn("Bulk delete of interview {} failed: {}", id, e.getMessage());
                outcomes.add(new BulkOutcome(id, name, false, friendlyReason(e)));
            }
        }
        return new BulkResult(ids.size(), succeeded, outcomes);
    }

    public BulkResult bulkUpdate(List<Long> ids, Long actorId, Role actorRole, BulkEditFields fields) {
        List<BulkOutcome> outcomes = new ArrayList<>();
        int succeeded = 0;
        for (Long id : ids) {
            String name = displayNameOf(id);
            try {
                rowMutator.updateOne(id, actorId, actorRole, fields);
                succeeded++;
                outcomes.add(new BulkOutcome(id, name, true, null));
            } catch (Exception e) {
                log.warn("Bulk update of interview {} failed: {}", id, e.getMessage());
                outcomes.add(new BulkOutcome(id, name, false, friendlyReason(e)));
            }
        }
        return new BulkResult(ids.size(), succeeded, outcomes);
    }

    /** Resolved before the row operation runs, so a deleted row still reports its real name. */
    private String displayNameOf(Long id) {
        return interviews.findById(id).map(Interview::getDisplayName).orElse("Interview #" + id);
    }

    /** Keeps stack traces and database internals out of anything a user sees. */
    private static String friendlyReason(Exception e) {
        if (e instanceof ApiException) {
            String message = e.getMessage();
            return message == null || message.isBlank() ? "Could not be changed." : message;
        }
        return "Could not be changed because of an unexpected error.";
    }
}
