package com.project.proctorinterview.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.interview.CandidateInterviewService;

/** Server-rendered entry points: the login page and the post-login landing page. */
@Controller
public class PageController {

    private final CandidateInterviewService candidateInterviews;

    public PageController(CandidateInterviewService candidateInterviews) {
        this.candidateInterviews = candidateInterviews;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /**
     * Sends each role to its own landing screen.
     *
     * <p>{@code tab} only decides which section of the candidate's own page is
     * open on arrival, so the "My Results" sidebar entry can land on the right
     * view. It selects nothing and filters nothing - the page always loads the
     * same single query for the signed-in candidate, and the sections are
     * switched in the browser.
     */
    @GetMapping("/")
    public String home(@AuthenticationPrincipal AppUserDetails principal,
            @RequestParam(required = false) String tab, Model model) {
        if (principal == null) {
            return "redirect:/login";
        }
        return switch (principal.getRole()) {
            case ADMIN -> "redirect:/admin/dashboard";
            case RECRUITER -> "redirect:/recruiter/dashboard";
            case CANDIDATE -> {
                model.addAttribute("user", principal);
                model.addAttribute("dashboard", candidateInterviews.dashboard(principal.getId()));
                model.addAttribute("navActive", "completed".equalsIgnoreCase(tab) ? "results" : "dashboard");
                yield "home";
            }
        };
    }
}
