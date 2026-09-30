package com.project.proctorinterview.web;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.interview.dto.CandidateQueryDtos.CandidateFilter;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.InterviewFilter;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.dto.BulkEditDtos.BulkEditFields;
import com.project.proctorinterview.interview.dto.BulkEditDtos.BulkSelection;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.interview.dto.MultiScheduleDtos.MultiScheduleRequest;
import com.project.proctorinterview.retention.DataDeletion;
import com.project.proctorinterview.retention.DataRetentionService;
import com.project.proctorinterview.user.UserRepository;
import com.project.proctorinterview.user.UserService;
import com.project.proctorinterview.user.dto.UserDtos.CreateUserRequest;
import com.project.proctorinterview.user.dto.UserDtos.UpdateUserRequest;

import jakarta.validation.Valid;

/** Server-rendered screens for the administrator role. */
@Controller
@RequestMapping("/admin")
public class AdminPageController {

    private final UserService userService;
    private final InterviewManagementController management;
    /** Read-only: the health view is assembled by AiService. */
    private final com.project.proctorinterview.ai.AiService aiService;
    private final DataRetentionService retention;
    private final UserRepository users;

    public AdminPageController(UserService userService, InterviewManagementController management,
            com.project.proctorinterview.ai.AiService aiService,
            DataRetentionService retention, UserRepository users) {
        this.userService = userService;
        this.management = management;
        this.aiService = aiService;
        this.retention = retention;
        this.users = users;
    }

    // ---- data retention and erasure ----------------------------------------
    //
    // Admin only, through the existing /admin/** rule. Deliberately not offered
    // to recruiters: erasing a person's record is not a routine scheduling
    // action, and it cannot be undone from a backup this prototype does not
    // take.

    /**
     * What is older than the retention window, plus the log of what has been
     * erased.
     *
     * <p>A report, never an action - see {@link DataRetentionService}. Nothing
     * on this page deletes anything on its own.
     */
    @GetMapping("/data-retention")
    public String dataRetention(Model model) {
        model.addAttribute("overview", retention.retentionOverview());
        model.addAttribute("log", retention.deletionLog());
        return "admin/data-retention";
    }

    /**
     * Exactly what would be erased for one candidate, before anything goes.
     *
     * <p>Shown first on purpose: "delete this person's data" is not a request
     * anyone should approve without seeing its size.
     */
    @GetMapping("/users/{id}/data")
    public String candidateData(@PathVariable Long id, Model model,
            RedirectAttributes redirect) {
        try {
            model.addAttribute("preview", retention.preview(id));
            return "admin/user-data";
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/users";
        }
    }

    @PostMapping("/users/{id}/data/delete")
    public String deleteCandidateData(@PathVariable Long id,
            @RequestParam String scope,
            @RequestParam(required = false) String confirmEmail,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal AppUserDetails me,
            RedirectAttributes redirect) {

        try {
            var actor = users.findById(me.getId())
                    .orElseThrow(() -> ApiException.notFound("User"));
            var record = retention.deleteCandidateData(id, actor,
                    DataDeletion.Scope.valueOf(scope), confirmEmail, reason);

            redirect.addFlashAttribute("message",
                    "Erased %d record(s). %s".formatted(
                            record.getInterviewsDeleted() + record.getSessionsDeleted()
                                    + record.getQuestionsDeleted() + record.getAnswersDeleted()
                                    + record.getProctorEventsDeleted() + record.getReportsDeleted()
                                    + record.getReviewsDeleted(),
                            record.getScope().label()));
            // Back to the list, not the preview: for an account deletion the
            // preview's subject no longer exists.
            return "redirect:/admin/users";
        } catch (IllegalArgumentException e) {
            // An unrecognised scope is only reachable by hand-editing the form.
            redirect.addFlashAttribute("error", "Choose what to erase.");
            return "redirect:/admin/users/" + id + "/data";
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/users/" + id + "/data";
        }
    }

    /**
     * "What is happening across the platform?" - platform-wide counts, the
     * things a person should look at, and the most recent activity.
     *
     * <p>Built from count queries and one paged search. It used to fetch every
     * user and every interview (and, through {@code toSummary}, a session and a
     * report lookup <em>per interview</em>) only to count them and show five.
     */
    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal AppUserDetails me, Model model) {
        var recruiters = userService.countByRole(Role.RECRUITER);
        var candidates = userService.countByRole(Role.CANDIDATE);

        model.addAttribute("recruiterCount", recruiters.total());
        model.addAttribute("candidateCount", candidates.total());
        model.addAttribute("disabledCount", recruiters.disabled() + candidates.disabled());
        model.addAttribute("insights", management.insights(me));
        model.addAttribute("attention", management.attention(me));
        model.addAttribute("recentInterviews", management.recentInterviews(me, RECENT_ROWS));
        return "admin/dashboard";
    }

    /** Enough recent rows to show activity without becoming a second table. */
    private static final int RECENT_ROWS = 6;

    /**
     * The account list: a role tab plus a free-text search and a status
     * filter, any combination of the three.
     *
     * <p>An unrecognised {@code role} or {@code status} - hand-edited into the
     * URL, or a stale bookmark from a value that no longer exists - is treated
     * as "no filter" rather than a 500. The tabs and the select only ever send
     * a value from their own options, so this only ever matters for a URL
     * someone typed by hand.
     */
    @GetMapping("/users")
    public String users(@RequestParam(required = false) String role,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status, Model model) {

        Role roleFilter = parseRole(role);
        Boolean enabledFilter = parseStatus(status);

        model.addAttribute("users", userService.search(roleFilter, search, enabledFilter));
        model.addAttribute("selectedRole", role);
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("hasFilters",
                (search != null && !search.isBlank()) || (status != null && !status.isBlank()));
        return "admin/users";
    }

    private static Role parseRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        try {
            return Role.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Boolean parseStatus(String status) {
        if ("ENABLED".equalsIgnoreCase(status)) {
            return Boolean.TRUE;
        }
        if ("DISABLED".equalsIgnoreCase(status)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Runtime AI health.
     *
     * <p>Admin-only by virtue of the {@code /admin/**} rule already in
     * SecurityConfig - no second mechanism, and nothing here is candidate- or
     * recruiter-facing. The view carries no API key: see AiHealthRegistry.
     */
    @GetMapping("/ai-health")
    public String aiHealth(Model model) {
        model.addAttribute("health", aiService.healthView());
        return "admin/ai-health";
    }

    @GetMapping("/users/new")
    public String newUserForm(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CreateUserRequest());
        }
        return "admin/user-form";
    }

    @PostMapping("/users")
    public String createUser(@Valid @ModelAttribute("form") CreateUserRequest form,
            BindingResult binding, Model model, RedirectAttributes redirect) {

        if (binding.hasErrors()) {
            return "admin/user-form";
        }
        try {
            userService.create(form);
            redirect.addFlashAttribute("message", "Account created for " + form.getEmail());
            return "redirect:/admin/users";
        } catch (ApiException e) {
            model.addAttribute("error", e.getMessage());
            return "admin/user-form";
        }
    }

    /**
     * Correct the details on an existing account.
     *
     * <p>Email, role, password and enabled are shown but not editable - each has
     * its own operation, and {@link UpdateUserRequest} has no field for any of
     * them, so the restriction is in the type rather than in this template.
     */
    @GetMapping("/users/{id}/edit")
    public String editUserForm(@PathVariable Long id, Model model, RedirectAttributes redirect) {
        try {
            if (!model.containsAttribute("form")) {
                model.addAttribute("form", userService.editForm(id));
            }
            model.addAttribute("account", userService.row(id));
            return "admin/user-edit";
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/users";
        }
    }

    @PostMapping("/users/{id}")
    public String updateUser(@PathVariable Long id,
            @Valid @ModelAttribute("form") UpdateUserRequest form,
            BindingResult binding, Model model, RedirectAttributes redirect) {

        // Re-rendering needs the header again: the form bean carries what the
        // user typed, never the identity of the account it belongs to.
        if (binding.hasErrors()) {
            model.addAttribute("account", userService.row(id));
            return "admin/user-edit";
        }
        try {
            userService.update(id, form);
            redirect.addFlashAttribute("message", "Details updated for " + form.getFullName() + ".");
            return "redirect:/admin/users";
        } catch (ApiException e) {
            model.addAttribute("account", userService.row(id));
            model.addAttribute("error", e.getMessage());
            return "admin/user-edit";
        }
    }

    @PostMapping("/users/{id}/toggle")
    public String toggleUser(@PathVariable Long id, @RequestParam boolean enabled,
            RedirectAttributes redirect) {
        try {
            userService.setEnabled(id, enabled);
            redirect.addFlashAttribute("message", enabled ? "Account enabled." : "Account disabled.");
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    /**
     * Interview management. Same page as the recruiter sees; the query service
     * gives an admin an unrestricted scope.
     */
    // ---- candidates ---------------------------------------------------------

    /** Every candidate with an interview. Admin scope is unrestricted. */
    @GetMapping("/candidates")
    public String candidates(@ModelAttribute("filter") CandidateFilter filter,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        return management.renderCandidates(filter, me, model, "/admin");
    }

    @GetMapping("/candidates/{id}")
    public String candidate(@PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        return management.renderCandidateDetail(id, me, model, "/admin");
    }

    @GetMapping("/interviews")
    public String interviews(@ModelAttribute InterviewFilter filter,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        return management.renderManagement(filter, me, model, "/admin/interviews");
    }

    /**
     * Schedule one interview.
     *
     * <p>The admin equivalent of the recruiter form, rendering the same shared
     * template through the same controller method. The admin becomes the
     * interview's recruiter of record, which is the honest record of who
     * created it.
     */
    @GetMapping("/interviews/new")
    public String newInterviewForm(Model model) {
        return management.scheduleForm(model, "/admin/interviews", "/admin/interviews");
    }

    @PostMapping("/interviews")
    public String createInterview(@AuthenticationPrincipal AppUserDetails me,
            @Valid @ModelAttribute("form") CreateInterviewRequest form, BindingResult binding,
            Model model, RedirectAttributes redirect) {
        return management.create(me, form, binding, model, redirect,
                "/admin/interviews", "/admin/interviews", "/admin/interviews");
    }

    /** Schedule the same interview for several candidates at once. */
    @GetMapping("/interviews/new-multi")
    public String newMultiInterviewForm(Model model) {
        return management.multiScheduleForm(model, "/admin/interviews/multi", "/admin/interviews");
    }

    @PostMapping("/interviews/multi")
    public String createInterviews(@AuthenticationPrincipal AppUserDetails me,
            @Valid @ModelAttribute("form") MultiScheduleRequest form, BindingResult binding,
            Model model, RedirectAttributes redirect) {
        return management.createMany(me, form, binding, model, redirect,
                "/admin/interviews/multi", "/admin/interviews", "/admin/interviews");
    }

    /**
     * Reports. The same page a recruiter sees; the query service gives an admin
     * an unrestricted scope.
     */
    @GetMapping("/reports")
    public String reports(@ModelAttribute InterviewFilter filter,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        return management.renderReports(filter, me, model, "/admin/reports", "/admin/interviews");
    }

    /**
     * The whole filtered result set as a workbook - every page, not just the
     * one on screen. Scope is applied server-side, so this is an admin's full
     * set only because an admin's scope is unrestricted.
     */
    @GetMapping("/interviews/export.xlsx")
    public ResponseEntity<byte[]> exportInterviews(@ModelAttribute InterviewFilter filter,
            @RequestParam(defaultValue = "false") boolean confirmLarge,
            @AuthenticationPrincipal AppUserDetails me) {
        return management.exportExcel(filter, me, confirmLarge);
    }

    @GetMapping("/interviews/{id}/edit")
    public String editInterview(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me, Model model) {
        return management.editForm(id, me, model,
                "/admin/interviews/" + id + "/edit", "/interviews/" + id);
    }

    @PostMapping("/interviews/{id}/edit")
    public String updateInterview(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me,
            @Valid @ModelAttribute("form") CreateInterviewRequest form, BindingResult binding,
            Model model, RedirectAttributes redirect) {
        return management.update(id, me, form, binding, model, redirect,
                "/admin/interviews/" + id + "/edit", "/interviews/" + id, "/admin/interviews");
    }

    @PostMapping("/interviews/{id}/delete")
    public String deleteInterview(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me,
            RedirectAttributes redirect) {
        return management.delete(id, me, redirect, "/admin/interviews");
    }

    @PostMapping("/interviews/bulk-delete")
    public String bulkDeleteInterviews(@AuthenticationPrincipal AppUserDetails me,
            @ModelAttribute InterviewFilter filter, @ModelAttribute BulkSelection selection,
            RedirectAttributes redirect) {
        return management.bulkDelete(filter, selection, me, redirect, "/admin/interviews");
    }

    @PostMapping("/interviews/bulk-edit")
    public String bulkEditInterviews(@AuthenticationPrincipal AppUserDetails me,
            @ModelAttribute InterviewFilter filter, @ModelAttribute BulkSelection selection,
            @ModelAttribute BulkEditFields fields, RedirectAttributes redirect) {
        return management.bulkEdit(filter, selection, fields, me, redirect, "/admin/interviews");
    }

}
