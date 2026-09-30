package com.project.proctorinterview.mail;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.project.proctorinterview.mail.MailEvents.CandidateAccountCreated;
import com.project.proctorinterview.mail.MailEvents.InterviewScheduled;

/**
 * Sends candidate email once the scheduling transaction has actually committed.
 *
 * <p>{@code AFTER_COMMIT} is the whole point of this class. Sending from inside
 * the transaction would mean a row that later rolled back - a failing bulk row,
 * a constraint violation - had already told the candidate their interview
 * exists, and email cannot be recalled. Waiting for the commit makes the message
 * a statement about something that is genuinely true.
 *
 * <p>It also matters for the bulk path specifically: each row commits in its own
 * {@code REQUIRES_NEW} transaction, so one candidate's invitation goes out as
 * soon as their row succeeds, and a later row failing neither suppresses it nor
 * re-sends it.
 *
 * <p>{@code @Async} is here for the bulk upload. An SMTP round trip is roughly a
 * second, and the listener would otherwise run on the request thread - so
 * confirming a fifty-row batch would hold the recruiter's page open for the
 * time it took to talk to Gmail fifty times, for work that is already committed
 * and already reported on screen. Handing each send to the task executor keeps
 * the response as quick as it was before email existed. Nothing downstream
 * depends on the result, so there is no ordering or return value to lose.
 *
 * <p>Nothing here retries. A send that fails is logged by
 * {@link InterviewMailService} and the recruiter still has the invite link on
 * the interview page; a retry queue would be real infrastructure for a
 * prototype that already has a working manual fallback.
 */
@Component
public class SchedulingMailListener {

    private final InterviewMailService mailService;

    public SchedulingMailListener(InterviewMailService mailService) {
        this.mailService = mailService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInterviewScheduled(InterviewScheduled event) {
        mailService.sendInterviewInvite(event);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCandidateAccountCreated(CandidateAccountCreated event) {
        mailService.sendAccountCreated(event);
    }
}
