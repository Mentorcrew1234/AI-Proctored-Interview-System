package com.project.proctorinterview.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.InterviewFilter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.interview.dto.CandidateQueryDtos.CandidateFilter;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.interview.InterviewService;
import com.project.proctorinterview.interview.dto.BulkEditDtos.BulkEditFields;
import com.project.proctorinterview.interview.dto.BulkEditDtos.BulkSelection;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.interview.dto.MultiScheduleDtos.MultiScheduleRequest;

import jakarta.validation.Valid;

/** Server-rendered screens for the recruiter role. */
@Controller
@RequestMapping("/recruiter")
public class RecruiterPageController {

    /** Only cancel still calls the service directly; everything else is shared. */
    private final InterviewService interviewService;
    private final InterviewManagementController management;

    public RecruiterPageController(InterviewService interviewService,
            InterviewManagementController management) {
        this.interviewService = interviewService;
        this.management = management;
    }

    /**
     * "What needs my attention?" - the same three reads the admin dashboard
     * uses, scoped by the query service to this recruiter's own interviews.
     *
     * <p>It used to fetch every interview this recruiter owns just to count
     * them and display five.
     */
    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal AppUserDetails me, Model model) {
        model.addAttribute("insights", management.insights(me));
        model.addAttribute("attention", management.attention(me));
        model.addAttribute("interviews", management.recentInterviews(me, RECENT_ROWS));
        return "recruiter/dashboard";
    }

    /** Enough recent rows to show activity without becoming a second table. */
    private static final int RECENT_ROWS = 6;

    /**
     * Interview management. Renders the shared page; the query service scopes
     * results to this recruiter's own interviews.
     */
    // ---- candidates ---------------------------------------------------------

    /**
     * Candidates this recruiter has actually interviewed.
     *
     * <p>The scope is enforced in the query, not here - see
     * {@code CandidateViewService}.
     */
    @GetMapping("/candidates")
    public String candidates(@ModelAttribute("filter") CandidateFilter filter,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        return management.renderCandidates(filter, me, model, "/recruiter");
    }

    @GetMapping("/candidates/{id}")
    public String candidate(@PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        return management.renderCandidateDetail(id, me, model, "/recruiter");
    }

    @GetMapping("/interviews")
    public String list(@ModelAttribute InterviewFilter filter,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        return management.renderManagement(filter, me, model, "/recruiter/interviews");
    }

    /**
     * Reports. Renders the shared page; the query service scopes results to
     * this recruiter's own interviews.
     */
    @GetMapping("/reports")
    public String reports(@ModelAttribute InterviewFilter filter,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        return management.renderReports(filter, me, model, "/recruiter/reports", "/recruiter/interviews");
    }

    /**
     * The whole filtered result set as a workbook - every page, not just the
     * one on screen. The query service scopes it to this recruiter's own
     * interviews, so no query string can widen it.
     */
    @GetMapping("/interviews/export.xlsx")
    public ResponseEntity<byte[]> exportInterviews(@ModelAttribute InterviewFilter filter,
            @RequestParam(defaultValue = "false") boolean confirmLarge,
            @AuthenticationPrincipal AppUserDetails me) {
        return management.exportExcel(filter, me, confirmLarge);
    }

    /**
     * Schedule one interview.
     *
     * <p>Delegates to the shared method so the admin and recruiter forms cannot
     * drift apart - the same arrangement as edit, delete and the bulk actions.
     */
    @GetMapping("/interviews/new")
    public String newForm(Model model) {
        return management.scheduleForm(model, "/recruiter/interviews", "/recruiter/interviews");
    }

    @PostMapping("/interviews")
    public String create(@AuthenticationPrincipal AppUserDetails me,
            @Valid @ModelAttribute("form") CreateInterviewRequest form,
            BindingResult binding, Model model, RedirectAttributes redirect) {
        return management.create(me, form, binding, model, redirect,
                "/recruiter/interviews", "/recruiter/interviews", "/recruiter/interviews");
    }

    /** Schedule the same interview for several candidates at once. */
    @GetMapping("/interviews/new-multi")
    public String newMultiForm(Model model) {
        return management.multiScheduleForm(model,
                "/recruiter/interviews/multi", "/recruiter/interviews");
    }

    @PostMapping("/interviews/multi")
    public String createMany(@AuthenticationPrincipal AppUserDetails me,
            @Valid @ModelAttribute("form") MultiScheduleRequest form,
            BindingResult binding, Model model, RedirectAttributes redirect) {
        return management.createMany(me, form, binding, model, redirect,
                "/recruiter/interviews/multi", "/recruiter/interviews", "/recruiter/interviews");
    }

    @PostMapping("/interviews/{id}/cancel")
    public String cancel(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id,
            RedirectAttributes redirect) {
        try {
            interviewService.cancel(id, me.getId(), me.getRole());
            redirect.addFlashAttribute("message", "Interview cancelled.");
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/recruiter/interviews";
    }

    @GetMapping("/interviews/{id}/edit")
    public String editInterview(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me, Model model) {
        return management.editForm(id, me, model,
                "/recruiter/interviews/" + id + "/edit", "/interviews/" + id);
    }

    @PostMapping("/interviews/{id}/edit")
    public String updateInterview(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me,
            @Valid @ModelAttribute("form") CreateInterviewRequest form, BindingResult binding,
            Model model, RedirectAttributes redirect) {
        return management.update(id, me, form, binding, model, redirect,
                "/recruiter/interviews/" + id + "/edit", "/interviews/" + id, "/recruiter/interviews");
    }

    @PostMapping("/interviews/{id}/delete")
    public String deleteInterview(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me,
            RedirectAttributes redirect) {
        return management.delete(id, me, redirect, "/recruiter/interviews");
    }

    @PostMapping("/interviews/bulk-delete")
    public String bulkDeleteInterviews(@AuthenticationPrincipal AppUserDetails me,
            @ModelAttribute InterviewFilter filter, @ModelAttribute BulkSelection selection,
            RedirectAttributes redirect) {
        return management.bulkDelete(filter, selection, me, redirect, "/recruiter/interviews");
    }

    @PostMapping("/interviews/bulk-edit")
    public String bulkEditInterviews(@AuthenticationPrincipal AppUserDetails me,
            @ModelAttribute InterviewFilter filter, @ModelAttribute BulkSelection selection,
            @ModelAttribute BulkEditFields fields, RedirectAttributes redirect) {
        return management.bulkEdit(filter, selection, fields, me, redirect, "/recruiter/interviews");
    }
}
