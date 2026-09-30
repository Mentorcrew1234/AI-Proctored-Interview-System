package com.project.proctorinterview.mail;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.spring6.SpringTemplateEngine;

import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.config.MailProperties;
import com.project.proctorinterview.mail.MailEvents.CandidateAccountCreated;
import com.project.proctorinterview.mail.MailEvents.InterviewScheduled;

import jakarta.mail.internet.MimeMessage;

/**
 * What the mail service must guarantee to the rest of the system.
 *
 * <p>The load-bearing test here is {@link #aFailedSendNeverPropagates()}. The
 * interview is already committed by the time this class runs, and it is fully
 * usable through the invite link on its own page - so an unreachable SMTP
 * server must degrade to the pre-email behaviour, never undo a recruiter's
 * scheduling or surface as an error on a page that already succeeded.
 */
class InterviewMailServiceTest {

    private static final InterviewScheduled INVITE = new InterviewScheduled(
            7L, "Arun Kumar", "arun@example.com", "Priya Raman",
            "Java Campus Drive", "Java", InterviewType.TECHNICAL,
            Instant.parse("2026-09-01T04:30:00Z"), 30, 5, "tok-123");

    private static final CandidateAccountCreated ACCOUNT = new CandidateAccountCreated(
            9L, "Arun Kumar", "arun@example.com", "Iv-secret");

    private static MailProperties enabled() {
        return new MailProperties(true, "sender@example.com", null, "https://test.example.in");
    }

    private static InterviewMailService service(MailProperties props, JavaMailSender sender) {
        SpringTemplateEngine templates = mock(SpringTemplateEngine.class);
        when(templates.process(any(String.class), any())).thenReturn("<html>body</html>");
        return new InterviewMailService(sender, templates, props);
    }

    @Test
    void nothingIsSentWhenMailIsDisabled() {
        JavaMailSender sender = mock(JavaMailSender.class);
        service(new MailProperties(false, "sender@example.com", null, null), sender)
                .sendInterviewInvite(INVITE);

        // Not even a message is constructed - being off must cost nothing.
        verify(sender, never()).createMimeMessage();
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void nothingIsSentWhenEnabledButNoSenderAddressIsConfigured() {
        JavaMailSender sender = mock(JavaMailSender.class);
        service(new MailProperties(true, null, null, null), sender).sendInterviewInvite(INVITE);

        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void nothingIsSentWhenTheCandidateHasNoAddress() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        InterviewScheduled noAddress = new InterviewScheduled(
                7L, "Arun", null, "Priya", "Drive", "Java", InterviewType.TECHNICAL,
                Instant.now(), 30, 5, "tok");
        service(enabled(), sender).sendInterviewInvite(noAddress);

        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void anInvitationIsSentWhenEnabledAndConfigured() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(newMimeMessage());

        service(enabled(), sender).sendInterviewInvite(INVITE);

        verify(sender).send(any(MimeMessage.class));
    }

    @Test
    void accountCredentialsAreSentWhenEnabledAndConfigured() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(newMimeMessage());

        service(enabled(), sender).sendAccountCreated(ACCOUNT);

        verify(sender).send(any(MimeMessage.class));
    }

    /**
     * The whole reason scheduling is allowed to depend on this class at all.
     */
    @Test
    void aFailedSendNeverPropagates() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(newMimeMessage());
        org.mockito.Mockito.doThrow(new MailSendException("SMTP is down"))
                .when(sender).send(any(MimeMessage.class));

        InterviewMailService service = service(enabled(), sender);

        assertThatCode(() -> service.sendInterviewInvite(INVITE)).doesNotThrowAnyException();
        assertThatCode(() -> service.sendAccountCreated(ACCOUNT)).doesNotThrowAnyException();
    }

    /** A real MimeMessage, since MimeMessageHelper writes into it. */
    private static MimeMessage newMimeMessage() {
        return new jakarta.mail.internet.MimeMessage(
                jakarta.mail.Session.getInstance(new java.util.Properties()));
    }
}
