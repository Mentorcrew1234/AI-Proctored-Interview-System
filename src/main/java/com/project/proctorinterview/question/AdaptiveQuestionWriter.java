package com.project.proctorinterview.question;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attempts one adaptively-generated question's insert in its own transaction.
 *
 * <p>A separate bean for the same reason as {@code SessionExpiryService} and
 * {@code BulkRowScheduler}: {@code @Transactional} is proxy-based, so a
 * self-invoked private method on {@link QuestionService} would silently run in
 * the caller's transaction instead of a new one. That matters here because two
 * concurrent requests can legitimately both attempt to insert the same
 * {@code (session_id, sequence_no)} - the unique constraint from
 * {@code V1__baseline.sql} lets exactly one win - and the loser's caller needs
 * to read back the winner afterwards. If the failing insert shared the
 * caller's transaction, that read would run in a transaction already marked
 * for rollback by the constraint violation. {@code REQUIRES_NEW} keeps the
 * failure contained to this one insert attempt, so
 * {@link QuestionService#ensureNextAdaptiveQuestion} can cleanly re-read the
 * winner in its own, still-healthy transaction.
 */
@Component
public class AdaptiveQuestionWriter {

    private final QuestionRepository questions;

    public AdaptiveQuestionWriter(QuestionRepository questions) {
        this.questions = questions;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Question insert(Question question) {
        return questions.saveAndFlush(question);
    }
}
