package com.project.proctorinterview.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.project.proctorinterview.answer.AnswerService;
import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.BulkInterviewMutationService;
import com.project.proctorinterview.interview.CandidateViewService;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewQueryService;
import com.project.proctorinterview.interview.InterviewService;
import com.project.proctorinterview.interview.InterviewSessionRepository;
import com.project.proctorinterview.interview.MultiScheduleService;
import com.project.proctorinterview.interview.dto.MultiScheduleDtos.MultiScheduleRequest;
import com.project.proctorinterview.interview.dto.MultiScheduleDtos.MultiScheduleResult;
import com.project.proctorinterview.interview.dto.BulkEditDtos.BulkEditFields;
import com.project.proctorinterview.interview.dto.BulkEditDtos.BulkResult;
import com.project.proctorinterview.interview.dto.BulkEditDtos.BulkSelection;
import com.project.proctorinterview.interview.InterviewExportExcelService;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.interview.dto.InterviewDtos.InterviewSummary;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.AttentionItem;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.InterviewInsights;
import com.project.proctorinterview.interview.dto.InterviewExportDtos.ExportPreflight;
import com.project.proctorinterview.interview.dto.InterviewExportDtos.ExportRow;
import com.project.proctorinterview.interview.dto.CandidateQueryDtos.CandidateFilter;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.InterviewFilter;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.InterviewTab;
import com.project.proctorinterview.proctor.ProctorEventService;
import com.project.proctorinterview.config.QuestionModeProperties;
import com.project.proctorinterview.report.ReportRepository;
import com.project.proctorinterview.report.dto.ReportQueryDtos.ReportPageResult;
import com.project.proctorinterview.report.dto.ReportQueryDtos.ReportRow;
import com.project.proctorinterview.report.ReportReviewService;
import com.project.proctorinterview.user.UserService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * Shared rendering for the interview management page and the interview detail
 * view.
 *
 * <p>Both the admin and recruiter list routes render the same template through
 * {@link #renderManagement}, so there is one implementation of search,
 * filtering, paging and insights rather than two that can drift apart. The only
 * difference between the two is the authorization scope, and that is decided in
 * {@link InterviewQueryService} from the authenticated principal - never from
 * anything submitted with the request.
 */
@Controller
public class InterviewManagementController {

    private final InterviewQueryService queryService;
    private final InterviewService interviewService;
    private final BulkInterviewMutationService bulkMutationService;
    private final UserService userService;
    /** Only for the "decided" badge on the list - see renderReports. */
    private final ReportReviewService reviewService;
    /** Only to name the fallback on the scheduling forms. */
    private final QuestionModeProperties questionModeProperties;
    private final CandidateViewService candidateView;
    private final InterviewSessionRepository sessions;
    private final ReportRepository reports;
    private final AnswerService answerService;
    private final ProctorEventService proctorEvents;
    private final InterviewExportExcelService exportExcelService;
    private final MultiScheduleService multiScheduleService;

    public InterviewManagementController(InterviewQueryService queryService,
            InterviewService interviewService, BulkInterviewMutationService bulkMutationService,
            UserService userService, ReportReviewService reviewService,
            QuestionModeProperties questionModeProperties, CandidateViewService candidateView,
            InterviewSessionRepository sessions, ReportRepository reports,
            AnswerService answerService, ProctorEventService proctorEvents,
            InterviewExportExcelService exportExcelService,
            MultiScheduleService multiScheduleService) {
        this.queryService = queryService;
        this.interviewService = interviewService;
        this.bulkMutationService = bulkMutationService;
        this.reviewService = reviewService;
        this.questionModeProperties = questionModeProperties;
        this.candidateView = candidateView;
        this.userService = userService;
        this.sessions = sessions;
        this.reports = reports;
        this.answerService = answerService;
        this.proctorEvents = proctorEvents;
        this.exportExcelService = exportExcelService;
        this.multiScheduleService = multiScheduleService;
    }

    /**
     * Populates the model for the management page. Called by both list routes.
     *
     * @param basePath where the page's own links should point back to
     */
    public String renderManagement(InterviewFilter filter, AppUserDetails me, Model model,
            String basePath) {
        try {
            model.addAttribute("result", queryService.search(filter, me.getId(), me.getRole()));
        } catch (ApiException e) {
            // A bad filter combination should explain itself, not 500.
            model.addAttribute("error", e.getMessage());
            filter.setFromDate(null);
            filter.setToDate(null);
            model.addAttribute("result", queryService.search(filter, me.getId(), me.getRole()));
        }

        model.addAttribute("filter", filter);
        model.addAttribute("basePath", basePath);
        model.addAttribute("insights", queryService.insights(me.getId(), me.getRole()));
        model.addAttribute("attention", queryService.needsAttention(me.getId(), me.getRole()));
        model.addAttribute("tabs", InterviewTab.values());
        model.addAttribute("activeTab", filter.resolvedTab());
        model.addAttribute("domains", queryService.domainOptions());
        model.addAttribute("statuses", InterviewStatus.values());
        model.addAttribute("experienceTypes", CandidateType.values());
        model.addAttribute("languages", InterviewLanguage.values());
        model.addAttribute("interviewTypes", InterviewType.values());
        model.addAttribute("pageSizes", java.util.List.of(20, 50, 100));
        model.addAttribute("activeFilters", filter.activeFilters());
        // Above this the export asks for confirmation rather than silently
        // building a very large workbook.
        model.addAttribute("exportSoftLimit", InterviewQueryService.EXPORT_SOFT_LIMIT);

        // Only an admin can meaningfully filter by recruiter; a recruiter is
        // already scoped to themselves.
        model.addAttribute("isAdmin", me.getRole() == Role.ADMIN);
        if (me.getRole() == Role.ADMIN) {
            model.addAttribute("recruiters", userService.listByRole(Role.RECRUITER));
        }

        return "interview/manage";
    }

    /**
     * Populates the model for the Reports page. Called by both report routes.
     *
     * <p>Reports are a view of finished interviews, so this is deliberately the
     * same filter object, the same query service and the same authorization
     * scope as the interview list - only the presentation differs. The tab is
     * pinned to {@code COMPLETED} here rather than offered as a control,
     * because a Reports page that could show scheduled interviews would not
     * mean anything.
     *
     * @param basePath       where this page's own links point back to
     * @param exportBasePath where the filtered Excel export lives for this role
     */
    public String renderReports(InterviewFilter filter, AppUserDetails me, Model model,
            String basePath, String exportBasePath) {

        filter.setTab(InterviewTab.COMPLETED.name());
        filter.setStatus(InterviewStatus.COMPLETED);

        ReportPageResult result;
        try {
            result = queryService.reportPage(filter, me.getId(), me.getRole());
        } catch (ApiException e) {
            model.addAttribute("error", e.getMessage());
            filter.setFromDate(null);
            filter.setToDate(null);
            result = queryService.reportPage(filter, me.getId(), me.getRole());
        }
        model.addAttribute("result", result);

        model.addAttribute("filter", filter);
        model.addAttribute("basePath", basePath);
        model.addAttribute("exportBasePath", exportBasePath);
        model.addAttribute("insights", queryService.insights(me.getId(), me.getRole()));
        model.addAttribute("domains", queryService.domainOptions());
        model.addAttribute("interviewTypes", InterviewType.values());
        model.addAttribute("experienceTypes", CandidateType.values());
        model.addAttribute("pageSizes", java.util.List.of(20, 50, 100));
        model.addAttribute("activeFilters", filter.activeFilters());
        model.addAttribute("exportSoftLimit", InterviewQueryService.EXPORT_SOFT_LIMIT);

        model.addAttribute("isAdmin", me.getRole() == Role.ADMIN);
        if (me.getRole() == Role.ADMIN) {
            model.addAttribute("recruiters", userService.listByRole(Role.RECRUITER));
        }

        // Which of these already have a human decision. One query for the page
        // rather than one per row, and computed here rather than pushed into
        // InterviewQueryService - a review is not part of what makes an
        // interview findable, and that service's job is finding.
        model.addAttribute("decidedSessions", reviewService.decidedAmong(
                result.rows().stream()
                        .map(ReportRow::sessionId)
                        .filter(java.util.Objects::nonNull)
                        .toList()));

        return "report/list";
    }

    /**
     * Detail view for one interview. Reads existing data only - it does not
     * regenerate reports or touch the interview.
     */
    @GetMapping("/interviews/{id}")
    public String details(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me,
            Model model) {
        try {
            // Assembled inside the service's transaction: open-in-view is off,
            // so lazy associations must be resolved before rendering starts.
            model.addAttribute("detail", queryService.detail(id, me.getId(), me.getRole()));
            String basePath = me.getRole() == Role.ADMIN ? "/admin/interviews" : "/recruiter/interviews";
            model.addAttribute("editHref", basePath + "/" + id + "/edit");
            model.addAttribute("deleteHref", basePath + "/" + id + "/delete");
            return "interview/details";
        } catch (ApiException e) {
            model.addAttribute("message", e.getMessage());
            return "interview/unavailable";
        }
    }

    /** Shared binding target so both list routes accept the same query string. */
    @ModelAttribute("filterBinding")
    public InterviewFilter filterBinding() {
        return new InterviewFilter();
    }

    // ---- dashboard pieces (shared by the admin and recruiter dashboards) ----
    //
    // Both dashboards answer the same shape of question about a different
    // scope, so they read from the same three methods rather than each
    // assembling their own counts. Scope comes from the principal in
    // InterviewQueryService, exactly as it does for the list page.

    /** Status counts under the caller's own scope. Count queries, not a fetch. */
    public InterviewInsights insights(AppUserDetails me) {
        return queryService.insights(me.getId(), me.getRole());
    }

    /**
     * Interviews a person should look at, derived from data the system already
     * records - nothing was invented and no status was added for it.
     */
    public List<AttentionItem> attention(AppUserDetails me) {
        return queryService.needsAttention(me.getId(), me.getRole());
    }

    /**
     * The most recent interviews under the caller's scope.
     *
     * <p>A paged query with the page size the dashboard actually shows, rather
     * than fetching every interview and keeping the first few.
     */
    public List<InterviewSummary> recentInterviews(AppUserDetails me, int limit) {
        InterviewFilter recent = new InterviewFilter();
        recent.setSize(20);
        recent.setPage(0);
        return queryService.search(recent, me.getId(), me.getRole()).rows().stream()
                .limit(limit)
                .toList();
    }

    // ---- filtered results export (shared by the admin and recruiter routes) ---

    /**
     * Exports the current filter's <b>entire</b> matching set as a workbook -
     * every page, not the one being viewed.
     *
     * <p>The browser sends only the filter, exactly as the bulk actions do; the
     * server re-runs it under the caller's own authorization scope. A recruiter
     * cannot widen the export by editing the query string, and the row count is
     * recomputed here rather than trusted from the page that offered the link.
     *
     * <p>A set larger than the soft limit needs {@code confirmLarge=true}. The
     * page asks first, but this check is the one that counts - the parameter is
     * a deliberate acknowledgement, not a formality.
     */
    public ResponseEntity<byte[]> exportExcel(InterviewFilter filter, AppUserDetails me,
            boolean confirmLarge) {

        ExportPreflight preflight;
        try {
            preflight = queryService.exportPreflight(filter, me.getId(), me.getRole());
        } catch (ApiException e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getMessage().getBytes(StandardCharsets.UTF_8));
        }

        if (preflight.needsConfirmation() && !confirmLarge) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("This filter matches %d interviews, which is more than the %d-row quick export. "
                            + "Go back and use the \"Export all %d\" option to confirm, or narrow the filters.")
                            .formatted(preflight.matching(), preflight.softLimit(), preflight.matching())
                            .getBytes(StandardCharsets.UTF_8));
        }

        List<ExportRow> rows = queryService.exportRows(filter, me.getId(), me.getRole());
        byte[] workbook = exportExcelService.build(rows, filter.activeFilters(), me.getFullName());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + exportFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(workbook);
    }

    /** Dated, so successive exports do not overwrite each other in Downloads. */
    private static String exportFileName() {
        return "interview-results-"
                + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_DATE)
                + ".xlsx";
    }

    // ---- scheduling (shared by the admin and recruiter routes) --------------
    //
    // Both roles schedule through these four methods, so the admin and
    // recruiter routes cannot drift apart - the same reasoning as edit/delete
    // below. The only difference is which URLs they post back to.

    /**
     * The single-interview form.
     *
     * @param actionUrl  where this form posts
     * @param cancelHref where Cancel returns to
     */
    public String scheduleForm(Model model, String actionUrl, String cancelHref) {
        if (!model.containsAttribute("form")) {
            CreateInterviewRequest form = new CreateInterviewRequest();
            // The next round hour tomorrow - a sensible starting point.
            form.setScheduledAt(LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.HOURS));
            model.addAttribute("form", form);
        }
        populateScheduleOptions(model, actionUrl, cancelHref);
        return "interview/schedule-form";
    }

    /**
     * Creates one interview.
     *
     * <p>Validation failures - both bean-validation errors and business-rule
     * errors from {@link InterviewService#create} - redisplay the form with the
     * submitted values rather than redirecting, so nothing typed is lost.
     *
     * <p>Whoever schedules becomes the interview's recruiter of record. For an
     * admin that is the admin, which is the honest record of who created it.
     */
    public String create(AppUserDetails me, CreateInterviewRequest form, BindingResult binding,
            Model model, RedirectAttributes redirect,
            String actionUrl, String cancelHref, String redirectPath) {

        if (!binding.hasErrors()) {
            try {
                Interview created = interviewService.create(me.getId(), form);
                redirect.addFlashAttribute("createdInterviewId", created.getId());
                redirect.addFlashAttribute("message",
                        "Interview created. Share the invite link with the candidate.");
                return "redirect:" + redirectPath;
            } catch (ApiException e) {
                // Business-rule failures (e.g. a disabled candidate) belong on
                // the form, not on a generic error page.
                model.addAttribute("error", e.getMessage());
            }
        }
        populateScheduleOptions(model, actionUrl, cancelHref);
        return "interview/schedule-form";
    }

    /** The multi-candidate form: the same fields, with a candidate multi-select. */
    public String multiScheduleForm(Model model, String actionUrl, String cancelHref) {
        if (!model.containsAttribute("form")) {
            MultiScheduleRequest form = new MultiScheduleRequest();
            form.setScheduledAt(LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.HOURS));
            model.addAttribute("form", form);
        }
        populateScheduleOptions(model, actionUrl, cancelHref);
        model.addAttribute("maxCandidates", MultiScheduleService.MAX_CANDIDATES);
        return "interview/multi-schedule-form";
    }

    /**
     * Creates one interview per selected candidate.
     *
     * <p>Each is committed independently, so a candidate whose account was
     * disabled between loading the form and submitting it is reported and
     * skipped rather than discarding the interviews already created for
     * everyone else. Partial success is stated plainly, never hidden.
     *
     * <p>On success the caller lands on the interview list filtered to this
     * interview name, which is where the new records and their invite links
     * already are - rather than a result page duplicating the list.
     */
    public String createMany(AppUserDetails me, MultiScheduleRequest form, BindingResult binding,
            Model model, RedirectAttributes redirect,
            String actionUrl, String cancelHref, String redirectPath) {

        if (!binding.hasErrors()) {
            try {
                MultiScheduleResult result = multiScheduleService.schedule(me.getId(), form);
                flashMultiResult(redirect, result);
                return "redirect:" + redirectPath + "?search="
                        + URLEncoder.encode(form.getInterviewName().trim(), StandardCharsets.UTF_8);
            } catch (ApiException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        populateScheduleOptions(model, actionUrl, cancelHref);
        model.addAttribute("maxCandidates", MultiScheduleService.MAX_CANDIDATES);
        return "interview/multi-schedule-form";
    }

    /** Options both scheduling forms need. */
    // ---- candidates ---------------------------------------------------------
    //
    // Rendered for both staff roles from one place, exactly as the interview
    // list is. The scope difference lives in CandidateViewService, not here:
    // a recruiter sees candidates they scheduled, an admin sees all.

    public String renderCandidates(CandidateFilter filter, AppUserDetails me, Model model,
            String basePath) {
        model.addAttribute("candidates", candidateView.list(me.getId(), me.getRole(), filter));
        // Options come from the caller's UNFILTERED rows, so narrowing the list
        // never removes the dropdown entry needed to widen it again.
        model.addAttribute("filterOptions", candidateView.filterOptions(me.getId(), me.getRole()));
        model.addAttribute("activeFilters", filter.activeFilters());
        model.addAttribute("basePath", basePath);
        model.addAttribute("isAdmin", me.getRole() == Role.ADMIN);
        return "interview/candidates";
    }

    public String renderCandidateDetail(Long candidateId, AppUserDetails me, Model model,
            String basePath) {
        try {
            model.addAttribute("candidate",
                    candidateView.detail(candidateId, me.getId(), me.getRole()));
            model.addAttribute("basePath", basePath);
            model.addAttribute("isAdmin", me.getRole() == Role.ADMIN);
            return "interview/candidate-detail";
        } catch (ApiException e) {
            // Page errors stay HTML. A recruiter who has never interviewed this
            // person is told the same as for a candidate who does not exist.
            model.addAttribute("message", e.getMessage());
            return "interview/unavailable";
        }
    }

    private void populateScheduleOptions(Model model, String actionUrl, String cancelHref) {
        model.addAttribute("candidates", userService.selectableCandidates());
        // Feeds the pool filter above the picker. Built from the pool itself,
        // so it never offers a college nobody in the list actually has.
        model.addAttribute("poolOptions", userService.selectablePoolOptions());
        model.addAttribute("domains", InterviewService.availableDomains());
        model.addAttribute("actionUrl", actionUrl);
        model.addAttribute("cancelHref", cancelHref);
        addServerQuestionMode(model);
    }

    /**
     * What "use the server default" currently resolves to, so the form can name
     * it rather than making the recruiter go and read a config file.
     */
    private void addServerQuestionMode(Model model) {
        model.addAttribute("serverQuestionMode", questionModeProperties.resolved());
        // So the form can say how many domains the offline bank actually covers,
        // rather than implying every typed domain has curated questions.
        model.addAttribute("bankedDomains", InterviewService.bankedDomains());
    }

    private void flashMultiResult(RedirectAttributes redirect, MultiScheduleResult result) {
        if (result.scheduled() > 0) {
            redirect.addFlashAttribute("message", result.scheduled() + " interview(s) scheduled.");
        }
        if (!result.allSucceeded()) {
            redirect.addFlashAttribute("error",
                    result.failures().size() + " of " + result.requested()
                            + " could not be scheduled: " + summarizeMultiFailures(result));
        }
    }

    private static String summarizeMultiFailures(MultiScheduleResult result) {
        return result.failures().stream()
                .limit(5)
                .map(o -> o.candidateName() + " (" + o.reason() + ")")
                .collect(java.util.stream.Collectors.joining("; "));
    }

    // ---- edit / delete (shared by the admin and recruiter routes) -----------

    /**
     * Renders the edit form for a scheduled interview. Editing is only offered
     * while the interview is still {@code SCHEDULED} - the candidate section is
     * shown read-only in the template since the candidate cannot be reassigned
     * through this form.
     */
    public String editForm(Long id, AppUserDetails me, Model model, String actionUrl, String cancelHref) {
        Interview interview;
        try {
            interview = interviewService.getForActorWithCandidate(id, me.getId(), me.getRole());
        } catch (ApiException e) {
            model.addAttribute("message", e.getMessage());
            return "interview/unavailable";
        }
        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            model.addAttribute("message", "Only a scheduled interview can be edited.");
            return "interview/unavailable";
        }

        if (!model.containsAttribute("form")) {
            model.addAttribute("form", toEditForm(interview));
        }
        model.addAttribute("interviewId", id);
        model.addAttribute("candidateName", interview.getCandidate().getFullName());
        model.addAttribute("candidateEmail", interview.getCandidate().getEmail());
        model.addAttribute("domains", InterviewService.availableDomains());
        model.addAttribute("actionUrl", actionUrl);
        model.addAttribute("cancelHref", cancelHref);
        addServerQuestionMode(model);
        return "interview/edit-form";
    }

    /**
     * Applies an edit. Validation failures - both bean-validation errors and
     * business-rule errors from {@link InterviewService#update} - redisplay the
     * form with the submitted values rather than redirecting, so nothing typed
     * is lost.
     */
    public String update(Long id, AppUserDetails me, CreateInterviewRequest form, BindingResult binding,
            Model model, RedirectAttributes redirect, String actionUrl, String cancelHref, String redirectPath) {

        if (!binding.hasErrors()) {
            try {
                interviewService.update(id, me.getId(), me.getRole(), form);
                redirect.addFlashAttribute("message", "Interview updated.");
                return "redirect:" + redirectPath;
            } catch (ApiException e) {
                model.addAttribute("error", e.getMessage());
            }
        }

        model.addAttribute("interviewId", id);
        model.addAttribute("domains", InterviewService.availableDomains());
        model.addAttribute("actionUrl", actionUrl);
        model.addAttribute("cancelHref", cancelHref);
        addServerQuestionMode(model);
        populateCandidateDisplay(model, id, me);
        return "interview/edit-form";
    }

    /**
     * Deletes a scheduled interview. {@link InterviewService#delete} is the
     * actual authority here - it re-checks ownership and status independently of
     * whatever the browser sent, so hiding the button for an ineligible row is
     * only ever a convenience, never the enforcement.
     */
    public String delete(Long id, AppUserDetails me, RedirectAttributes redirect, String redirectPath) {
        try {
            interviewService.delete(id, me.getId(), me.getRole());
            redirect.addFlashAttribute("message", "Interview deleted.");
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + redirectPath;
    }

    private void populateCandidateDisplay(Model model, Long id, AppUserDetails me) {
        try {
            Interview interview = interviewService.getForActorWithCandidate(id, me.getId(), me.getRole());
            model.addAttribute("candidateName", interview.getCandidate().getFullName());
            model.addAttribute("candidateEmail", interview.getCandidate().getEmail());
        } catch (ApiException ignored) {
            // The same failure will surface again on the next submit attempt.
        }
    }

    // ---- bulk edit / delete (shared by the admin and recruiter routes) ------

    /**
     * Deletes every targeted interview independently via
     * {@link BulkInterviewMutationService}, then reports how many succeeded and
     * why any were skipped. Each row is re-validated exactly as a single delete
     * would be - a bulk action can never do anything a single action could not.
     */
    public String bulkDelete(InterviewFilter filter, BulkSelection selection, AppUserDetails me,
            RedirectAttributes redirect, String redirectPath) {
        List<Long> targetIds = resolveTargets(filter, selection, me);
        if (targetIds.isEmpty()) {
            redirect.addFlashAttribute("error", "No interviews were selected.");
            return "redirect:" + redirectPath;
        }
        BulkResult result = bulkMutationService.bulkDelete(targetIds, me.getId(), me.getRole());
        flashBulkResult(redirect, result, "deleted");
        return "redirect:" + redirectPath;
    }

    /** As {@link #bulkDelete}, applying only the fields flagged in {@code fields}. */
    public String bulkEdit(InterviewFilter filter, BulkSelection selection, BulkEditFields fields,
            AppUserDetails me, RedirectAttributes redirect, String redirectPath) {
        if (!fields.appliesAnything()) {
            redirect.addFlashAttribute("error", "Choose at least one field to bulk-edit.");
            return "redirect:" + redirectPath;
        }
        List<Long> targetIds = resolveTargets(filter, selection, me);
        if (targetIds.isEmpty()) {
            redirect.addFlashAttribute("error", "No interviews were selected.");
            return "redirect:" + redirectPath;
        }
        BulkResult result = bulkMutationService.bulkUpdate(targetIds, me.getId(), me.getRole(), fields);
        flashBulkResult(redirect, result, "updated");
        return "redirect:" + redirectPath;
    }

    /**
     * "Select all matching filter" is recomputed here from the filter and the
     * caller's own authorization scope - never trusted from a submitted id
     * list. A manual selection uses exactly the ids the browser checked.
     */
    private List<Long> resolveTargets(InterviewFilter filter, BulkSelection selection, AppUserDetails me) {
        if (selection.isSelectAllMatching()) {
            return queryService.idsMatching(filter, me.getId(), me.getRole());
        }
        return selection.getIds() == null ? List.of() : selection.getIds();
    }

    private void flashBulkResult(RedirectAttributes redirect, BulkResult result, String verb) {
        if (result.allSucceeded()) {
            redirect.addFlashAttribute("message", result.succeeded() + " interview(s) " + verb + ".");
            return;
        }
        if (result.succeeded() > 0) {
            redirect.addFlashAttribute("message", result.succeeded() + " interview(s) " + verb + ".");
        }
        redirect.addFlashAttribute("error",
                result.failedOutcomes().size() + " of " + result.requested()
                        + " could not be " + verb + ": " + summarizeFailures(result));
    }

    private static String summarizeFailures(BulkResult result) {
        return result.failedOutcomes().stream()
                .limit(5)
                .map(o -> o.displayName() + " (" + o.reason() + ")")
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static CreateInterviewRequest toEditForm(Interview interview) {
        CreateInterviewRequest form = new CreateInterviewRequest();
        form.setInterviewName(interview.getInterviewName());
        form.setCandidateId(interview.getCandidate().getId());
        form.setScheduledAt(LocalDateTime.ofInstant(interview.getScheduledAt(), ZoneId.systemDefault()));
        form.setCandidateType(interview.getCandidateType());
        form.setExperienceYears(interview.getExperienceYears());
        form.setDomain(interview.getDomain());
        form.setInterviewType(interview.getInterviewType());
        form.setQuestionCount(interview.getQuestionCount());
        // Carried through, including null: an interview inheriting the server
        // default must not be silently pinned to it by opening the edit form.
        form.setQuestionMode(interview.getQuestionMode());
        form.setResultVisibleToCandidate(interview.isResultVisibleToCandidate());
        return form;
    }
}
