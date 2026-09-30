package com.project.proctorinterview.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.ReviewDecision;
import com.project.proctorinterview.report.ReportReviewService;

/**
 * Recording a decision on a report.
 *
 * <p>On the <b>session + CSRF chain</b> (`/reports/**`, not `/api/**`), so the
 * form's POST carries a CSRF token like every other staff form. That matters
 * more than usual here: this endpoint writes a judgement about a person, and a
 * cross-site POST that recorded one would be exactly the wrong thing to allow.
 *
 * <p>The GET side lives in {@link ReportPageController} - a review is part of
 * the report page, not a page of its own.
 */
@Controller
public class ReportReviewPageController {

    private final ReportReviewService reviews;

    public ReportReviewPageController(ReportReviewService reviews) {
        this.reviews = reviews;
    }

    @PostMapping("/reports/{sessionId}/review")
    public String record(@PathVariable Long sessionId,
            @RequestParam(required = false) ReviewDecision decision,
            @RequestParam(required = false) String note,
            @AuthenticationPrincipal AppUserDetails me,
            RedirectAttributes flash) {

        try {
            reviews.record(sessionId, me.getId(), me.getRole(), decision, note);
            flash.addFlashAttribute("message", "Your decision has been recorded.");
        } catch (ApiException e) {
            // Page errors stay HTML. The message is the service's own, which is
            // already written for a person rather than for a log.
            flash.addFlashAttribute("error", e.getMessage());
        }

        // Back to the decision tab, so the person sees what they just recorded
        // rather than the top of a long report.
        return "redirect:/reports/" + sessionId + "#panel-decision";
    }
}
