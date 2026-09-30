package com.project.proctorinterview.mail;

import java.time.Instant;

import com.project.proctorinterview.common.Enums.InterviewType;

/**
 * What the mail layer is told about, and nothing more.
 *
 * <p>Every event here is a <b>snapshot, not an entity reference</b>. They are built
 * while the scheduling transaction is still open and the entities are still
 * attached, because the listeners run <em>after</em> that transaction commits -
 * with {@code open-in-view} off, handing a listener an entity would mean a
 * {@code LazyInitializationException} the moment it read the candidate's name.
 * Copying the handful of fields the templates need is cheaper than re-reading
 * the row, and it makes the mail layer impossible to couple to the schema.
 */
public final class MailEvents {

    private MailEvents() {
    }

    /**
     * One interview was scheduled and committed.
     *
     * <p>Published by {@code InterviewService.create}, which every scheduling
     * path goes through - the single form, multi-candidate scheduling and the
     * Excel bulk upload - so no scenario can be added later that quietly skips
     * the invitation.
     */
    public record InterviewScheduled(
            Long interviewId,
            String candidateName,
            String candidateEmail,
            String recruiterName,
            String interviewName,
            String domain,
            InterviewType interviewType,
            Instant scheduledAt,
            int durationMinutes,
            int questionCount,
            String inviteToken) {
    }

    /**
     * A candidate account was created during a bulk upload, with a generated
     * password.
     *
     * <p>Separate from the invitation on purpose. The password is the only way
     * that person can sign in at all, it is never persisted in readable form,
     * and it is not part of "your interview is scheduled" - conflating the two
     * would mean re-sending credentials every time an existing candidate is
     * scheduled again.
     */
    public record CandidateAccountCreated(
            Long userId,
            String candidateName,
            String candidateEmail,
            String generatedPassword) {
    }

    /**
     * Someone asked to reset a password and a token was issued.
     *
     * <p>Unlike the other two this is not published as an application event -
     * it is handed straight to the mail service by the controller. The reason
     * is timing: the other two are deliberately decoupled so a mail failure
     * cannot affect scheduling, whereas here the email IS the feature. There is
     * nothing useful to do after issuing a token except send it.
     *
     * <p>Carries the raw token, which exists nowhere else - only its SHA-256 is
     * stored. That makes this record the one thing in the system that must
     * never be logged.
     */
    public record PasswordResetRequested(
            Long userId,
            String userName,
            String email,
            String token,
            int expiryMinutes) {
    }
}
