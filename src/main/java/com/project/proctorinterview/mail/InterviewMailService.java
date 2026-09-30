package com.project.proctorinterview.mail;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.config.MailProperties;
import com.project.proctorinterview.mail.MailEvents.CandidateAccountCreated;
import com.project.proctorinterview.mail.MailEvents.PasswordResetRequested;
import com.project.proctorinterview.mail.MailEvents.InterviewScheduled;

import jakarta.mail.internet.MimeMessage;

/**
 * Builds and sends candidate email.
 *
 * <p><b>Sending can never break scheduling.</b> Every failure here is caught and
 * logged, never rethrown: an unreachable SMTP server, a rejected credential or a
 * malformed address must not undo an interview that is already committed and
 * visible in the recruiter's list. The recruiter can still read the invite link
 * from the interview's own Access tab, exactly as before email existed, so a
 * failed send degrades to the previous behaviour rather than losing anything.
 *
 * <p>The same reasoning applies to being switched off: with
 * {@code app.mail.enabled=false} this class is inert, and the surrounding flow
 * is bit-for-bit what it was before.
 */
@Service
public class InterviewMailService {

    private static final Logger log = LoggerFactory.getLogger(InterviewMailService.class);

    /** Shown to a human, in the server's zone - the same basis the UI uses. */
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm").withZone(ZoneId.systemDefault());

    private final JavaMailSender sender;
    private final SpringTemplateEngine templates;
    private final MailProperties props;

    public InterviewMailService(JavaMailSender sender, SpringTemplateEngine templates, MailProperties props) {
        this.sender = sender;
        this.templates = templates;
        this.props = props;
    }

    /** The invitation: what the interview is, when, and the link to sit it. */
    public void sendInterviewInvite(InterviewScheduled event) {
        if (notSendable("interview invitation", event.candidateEmail())) {
            return;
        }

        Context context = new Context();
        context.setVariable("candidateName", event.candidateName());
        context.setVariable("recruiterName", event.recruiterName());
        context.setVariable("interviewName", displayName(event));
        context.setVariable("domain", event.domain());
        context.setVariable("interviewKind",
                event.interviewType() == InterviewType.TECHNICAL ? "Technical" : "HR / General");
        context.setVariable("scheduledAt", WHEN.format(event.scheduledAt()));
        context.setVariable("durationMinutes", event.durationMinutes());
        context.setVariable("questionCount", event.questionCount());
        context.setVariable("inviteLink", props.inviteLink(event.inviteToken()));

        send(event.candidateEmail(),
                "Your interview is scheduled: " + displayName(event),
                "mail/interview-invite",
                context,
                "interview " + event.interviewId());
    }

    /**
     * Credentials for an account the bulk upload just created.
     *
     * <p>This is the one place the generated password is ever delivered
     * automatically. It is not stored anywhere readable, so if this send fails
     * the recruiter must still fall back to the result workbook - which is why
     * the failure is logged loudly enough to notice.
     */
    public void sendAccountCreated(CandidateAccountCreated event) {
        if (notSendable("account credentials", event.candidateEmail())) {
            return;
        }

        Context context = new Context();
        context.setVariable("candidateName", event.candidateName());
        context.setVariable("email", event.candidateEmail());
        context.setVariable("password", event.generatedPassword());
        context.setVariable("loginLink", props.dashboardLink());

        send(event.candidateEmail(),
                "Your interview account has been created",
                "mail/account-created",
                context,
                "new account " + event.userId());
    }

    /**
     * A password reset link.
     *
     * <p>Unlike the other two, this one is not fire-and-forget from the
     * caller's point of view: if it does not arrive, the user has no other way
     * in. It still cannot be allowed to throw - the token is already committed
     * - so the failure is logged and the caller reports success either way,
     * because telling the requester "we could not send that" would also tell an
     * attacker that the address exists.
     */
    public void sendPasswordReset(PasswordResetRequested event) {
        if (notSendable("password reset", event.email())) {
            return;
        }

        Context context = new Context();
        context.setVariable("userName", event.userName());
        context.setVariable("resetLink", props.baseUrl() + "/reset-password?token=" + event.token());
        context.setVariable("expiryMinutes", event.expiryMinutes());

        send(event.email(),
                "Reset your interview account password",
                "mail/password-reset",
                context,
                "password reset for user " + event.userId());
    }

    private boolean notSendable(String what, String recipient) {
        if (!props.enabled()) {
            return true;
        }
        if (props.from() == null) {
            log.warn("app.mail.enabled is true but app.mail.from is not set - not sending the {}. "
                    + "Set app.mail.from (and the spring.mail.* credentials) to enable email.", what);
            return true;
        }
        if (recipient == null || recipient.isBlank()) {
            log.warn("No recipient address available for the {} - skipping", what);
            return true;
        }
        return false;
    }

    private void send(String to, String subject, String template, Context context, String describes) {
        try {
            String html = templates.process(template, context);

            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(props.from(), props.fromName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);

            sender.send(message);
            log.info("Sent {} email for {} to {}", template, describes, redact(to));
        } catch (Exception e) {
            // Deliberately swallowed - see the class comment. The interview is
            // already committed and remains usable through the invite link on
            // its own page.
            log.warn("Could not send the {} email for {} to {}: {}",
                    template, describes, redact(to), e.getMessage());
        }
    }

    /**
     * Logs enough of an address to identify a delivery problem without writing
     * a full mailbox into the log for every interview scheduled.
     */
    private static String redact(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String displayName(InterviewScheduled event) {
        return event.interviewName() == null || event.interviewName().isBlank()
                ? "Interview #" + event.interviewId()
                : event.interviewName();
    }
}
