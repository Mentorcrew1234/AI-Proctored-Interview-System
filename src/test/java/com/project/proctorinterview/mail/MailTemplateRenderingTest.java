package com.project.proctorinterview.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * The email bodies, rendered by the real template engine.
 *
 * <p>{@link InterviewMailServiceTest} mocks the engine, because what it is
 * testing is when a send happens rather than what it says. That leaves a gap
 * this closes: a typo in a Thymeleaf expression would sail through those tests
 * and only surface as a caught, logged failure at the moment a real candidate
 * should have been invited - the quietest possible way for it to break.
 *
 * <p>These assert the load-bearing content: the link, the details a candidate
 * needs to turn up, and the wording rules the project applies everywhere else -
 * a detection is an observation, and video never leaves the browser.
 */
@SpringBootTest
@ActiveProfiles("test")
class MailTemplateRenderingTest {

    @Autowired
    private SpringTemplateEngine templates;

    @Test
    void theInvitationRendersEveryDetailACandidateNeeds() {
        Context context = new Context();
        context.setVariable("candidateName", "Arun Kumar");
        context.setVariable("recruiterName", "Priya Raman");
        context.setVariable("interviewName", "Java Campus Drive");
        context.setVariable("domain", "Java");
        context.setVariable("interviewKind", "Technical");
        context.setVariable("scheduledAt", "Monday, 1 September 2026 at 10:00");
        context.setVariable("durationMinutes", 30);
        context.setVariable("questionCount", 5);
        context.setVariable("inviteLink", "https://test.example.in/exam/tok-123");

        String html = templates.process("mail/interview-invite", context);

        assertThat(html)
                .contains("Arun Kumar")
                .contains("Priya Raman")
                .contains("Java Campus Drive")
                .contains("Monday, 1 September 2026 at 10:00")
                .contains("30")
                .contains("Technical")
                // The link must appear as a real href AND as copyable text, because
                // some clients strip the button.
                .contains("href=\"https://test.example.in/exam/tok-123\"")
                .containsPattern(">\\s*https://test\\.example\\.in/exam/tok-123\\s*<");

        // No unresolved expressions left behind.
        assertThat(html).doesNotContain("th:text").doesNotContain("${");
    }

    /**
     * The wording rule that applies in the UI and the report applies here too:
     * a detection is an observation, never an accusation.
     */
    @Test
    void theInvitationStatesTheMonitoringPositionHonestly() {
        Context context = new Context();
        context.setVariable("candidateName", "Arun");
        context.setVariable("recruiterName", "Priya");
        context.setVariable("interviewName", "Drive");
        context.setVariable("domain", "Java");
        context.setVariable("interviewKind", "Technical");
        context.setVariable("scheduledAt", "today");
        context.setVariable("durationMinutes", 30);
        context.setVariable("questionCount", 5);
        context.setVariable("inviteLink", "https://x/exam/t");

        String html = templates.process("mail/interview-invite", context);

        assertThat(html)
                .contains("your video is never uploaded".toLowerCase())
                .contains("observations, not as accusations")
                .contains("The link alone is not access");
    }

    @Test
    void theAccountEmailRendersTheCredentialsAndTheSignInLink() {
        Context context = new Context();
        context.setVariable("candidateName", "Brand New");
        context.setVariable("email", "brand.new@example.com");
        context.setVariable("password", "Iv-AbCdEf123");
        context.setVariable("loginLink", "https://test.example.in/login");

        String html = templates.process("mail/account-created", context);

        assertThat(html)
                .contains("Brand New")
                .contains("brand.new@example.com")
                .contains("Iv-AbCdEf123")
                .contains("href=\"https://test.example.in/login\"")
                // The password cannot be looked up again, so the email has to say so.
                .contains("not stored anywhere it can be read back");

        assertThat(html).doesNotContain("th:text").doesNotContain("${");
    }
}
