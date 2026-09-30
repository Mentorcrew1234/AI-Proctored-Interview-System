package com.project.proctorinterview.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.project.proctorinterview.config.MailProperties;
import com.project.proctorinterview.config.PasswordResetProperties;
import com.project.proctorinterview.mail.InterviewMailService;
import com.project.proctorinterview.mail.MailEvents.PasswordResetRequested;
import com.project.proctorinterview.passwordreset.PasswordResetService;
import com.project.proctorinterview.passwordreset.PasswordResetService.RedeemResult;
import com.project.proctorinterview.user.UserRepository;

/**
 * The forgot-password pages.
 *
 * <p>On the <b>session + CSRF chain</b>, not {@code /api/**} - these are
 * server-rendered forms, and CSRF protection on the POSTs is exactly what is
 * wanted. Both paths are reachable signed out, which means they must be added
 * to the permitted list in {@code SecurityConfig}; without that a user who
 * cannot log in would be redirected to the login page they cannot use.
 *
 * <p><b>Requesting a reset always reports the same thing</b>, whether or not the
 * address belongs to an account. Saying "no such account" would turn this page
 * into an account-enumeration tool, which the login and the invite link both
 * already refuse to be.
 */
@Controller
public class PasswordResetPageController {

    private final PasswordResetService resets;
    private final InterviewMailService mail;
    private final UserRepository users;
    private final MailProperties mailProperties;
    private final PasswordResetProperties properties;

    public PasswordResetPageController(PasswordResetService resets, InterviewMailService mail,
            UserRepository users, MailProperties mailProperties, PasswordResetProperties properties) {
        this.resets = resets;
        this.mail = mail;
        this.users = users;
        this.mailProperties = mailProperties;
        this.properties = properties;
    }

    @GetMapping("/forgot-password")
    public String forgotForm(Model model) {
        // Stated plainly rather than discovered by waiting for an email that
        // will never arrive. A prototype with mail switched off is the normal
        // case, not an error.
        model.addAttribute("mailEnabled", mailProperties.enabled());
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String requestReset(@RequestParam String email, Model model) {
        resets.requestReset(email).ifPresent(token -> users.findByEmailIgnoreCase(email.trim())
                .ifPresent(user -> mail.sendPasswordReset(new PasswordResetRequested(
                        user.getId(), user.getFullName(), user.getEmail(),
                        token, properties.expiryMinutes()))));

        // Identical for a real address, an unknown one and a disabled account.
        // Note this is returned even when mail is switched off - the page has
        // already said so, and branching here on delivery would leak the same
        // fact the wording is protecting.
        model.addAttribute("submitted", true);
        model.addAttribute("mailEnabled", mailProperties.enabled());
        return "auth/forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetForm(@RequestParam(required = false) String token, Model model) {
        model.addAttribute("token", token);
        model.addAttribute("tokenValid", resets.isRedeemable(token));
        return "auth/reset-password";
    }

    @PostMapping("/reset-password")
    public String submitReset(@RequestParam(required = false) String token,
            @RequestParam String password,
            @RequestParam String confirmPassword,
            Model model) {

        model.addAttribute("token", token);

        if (password == null || password.length() < 8) {
            return reject(model, "Choose a password of at least 8 characters.");
        }
        if (!password.equals(confirmPassword)) {
            return reject(model, "The two passwords do not match.");
        }

        // Re-checked here rather than trusted from the render: it can expire,
        // or be superseded by a newer request, in between.
        if (resets.redeem(token, password) == RedeemResult.INVALID) {
            model.addAttribute("tokenValid", false);
            return "auth/reset-password";
        }

        return "redirect:/login?reset";
    }

    private String reject(Model model, String message) {
        model.addAttribute("tokenValid", true);
        model.addAttribute("error", message);
        return "auth/reset-password";
    }
}
