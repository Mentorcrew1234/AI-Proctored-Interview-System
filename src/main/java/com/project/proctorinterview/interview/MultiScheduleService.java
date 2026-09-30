package com.project.proctorinterview.interview;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.interview.dto.MultiScheduleDtos.MultiScheduleOutcome;
import com.project.proctorinterview.interview.dto.MultiScheduleDtos.MultiScheduleRequest;
import com.project.proctorinterview.interview.dto.MultiScheduleDtos.MultiScheduleResult;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Schedules the same interview for several candidates at once.
 *
 * <p>Extends scheduling; it does not replace it. Every candidate goes through
 * {@link InterviewService#create} - the same method the single form uses - so a
 * multi-scheduled interview is an ordinary interview, and this path can never
 * produce a record the single form would have rejected.
 *
 * <p>Not transactional itself, on purpose: each candidate is committed
 * independently by {@link MultiScheduleRowScheduler} so one failure cannot
 * discard the rest. A transaction here would defeat that.
 */
@Service
public class MultiScheduleService {

    /**
     * A ceiling on one submission. Not a policy - the select is populated from
     * the candidate list, so this only stops a hand-crafted request asking for
     * an unbounded number of interviews in one transaction-per-row loop.
     */
    public static final int MAX_CANDIDATES = 100;

    private final MultiScheduleRowScheduler rowScheduler;
    private final UserRepository users;

    public MultiScheduleService(MultiScheduleRowScheduler rowScheduler, UserRepository users) {
        this.rowScheduler = rowScheduler;
        this.users = users;
    }

    /**
     * One interview per selected candidate.
     *
     * @param actorUserId whoever is scheduling - they become the interview's
     *                    recruiter of record, exactly as with the single form
     */
    public MultiScheduleResult schedule(Long actorUserId, MultiScheduleRequest form) {
        // A candidate selected twice is one interview, not two. Ordered so the
        // result reads in the order they were picked.
        List<Long> candidateIds = new ArrayList<>(new LinkedHashSet<>(form.getCandidateIds()));

        if (candidateIds.isEmpty()) {
            throw ApiException.badRequest("Select at least one candidate");
        }
        if (candidateIds.size() > MAX_CANDIDATES) {
            throw ApiException.badRequest(
                    "Select at most " + MAX_CANDIDATES + " candidates at a time. "
                            + "Use bulk scheduling from a spreadsheet for larger drives.");
        }

        List<MultiScheduleOutcome> outcomes = new ArrayList<>();
        int scheduled = 0;

        for (Long candidateId : candidateIds) {
            String name = displayName(candidateId);
            try {
                Interview created = rowScheduler.scheduleOne(actorUserId, form.toCreateRequest(candidateId));
                outcomes.add(new MultiScheduleOutcome(candidateId, name, true, null, created.getId()));
                scheduled++;
            } catch (ApiException e) {
                // Expected refusals - disabled account, not a candidate, missing
                // years of experience. Reported per candidate, never swallowed.
                outcomes.add(new MultiScheduleOutcome(candidateId, name, false, e.getMessage(), null));
            } catch (RuntimeException e) {
                outcomes.add(new MultiScheduleOutcome(candidateId, name, false,
                        "Could not be scheduled", null));
            }
        }

        return new MultiScheduleResult(candidateIds.size(), scheduled, outcomes);
    }

    /**
     * A name for the result message.
     *
     * <p>No {@code @Transactional} here on purpose: this class is proxied, so
     * the annotation would do nothing on a private method - the repository call
     * runs in its own transaction, which is all this read needs. A candidate
     * that has since vanished is reported by id rather than failing the whole
     * operation.
     */
    private String displayName(Long candidateId) {
        return users.findById(candidateId)
                .map(User::getFullName)
                .orElse("Candidate #" + candidateId);
    }
}
