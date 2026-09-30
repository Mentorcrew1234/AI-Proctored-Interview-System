package com.project.proctorinterview.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.report.ReportReviewService;
import com.project.proctorinterview.report.ReportPdfService;
import com.project.proctorinterview.report.ReportService;

/** Report page and its PDF download, shared by admins and recruiters. */
@Controller
public class ReportPageController {

    private final ReportService reportService;
    private final ReportPdfService pdfService;
    private final ReportReviewService reviewService;

    public ReportPageController(ReportService reportService, ReportPdfService pdfService,
            ReportReviewService reviewService) {
        this.reportService = reportService;
        this.pdfService = pdfService;
        this.reviewService = reviewService;
    }

    @GetMapping("/reports/{sessionId}")
    public String report(@PathVariable Long sessionId,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        try {
            reportService.assertCanView(sessionId, me.getId(), me.getRole());
            model.addAttribute("report", reportService.view(sessionId));

            // Staff only, and there is deliberately no flag to change that. A
            // half-formed internal judgement delivered to the candidate it is
            // about, with no context, would be worse than no feedback - so a
            // candidate is not shown the section at all, and the POST refuses
            // them independently in ReportReviewService.
            boolean staff = me.getRole() != Role.CANDIDATE;
            model.addAttribute("canReview", staff);
            model.addAttribute("review", staff ? reviewService.summaryFor(sessionId) : null);
            return "report/view";
        } catch (ApiException e) {
            // Page errors stay HTML rather than turning into JSON.
            model.addAttribute("message", e.getMessage());
            return "report/unavailable";
        }
    }

    /**
     * The same report as a PDF.
     *
     * <p>Authorization is {@link ReportService#assertCanView}, exactly as for
     * the HTML page - the URL prefix is not the control. That matters here
     * because {@code /reports/**} is reachable by a candidate: the service is
     * what enforces that they only ever see their own report, and only when the
     * recruiter made the result visible.
     */
    @GetMapping("/reports/{sessionId}/export.pdf")
    public ResponseEntity<byte[]> reportPdf(@PathVariable Long sessionId,
            @AuthenticationPrincipal AppUserDetails me) {
        try {
            reportService.assertCanView(sessionId, me.getId(), me.getRole());
            byte[] pdf = pdfService.build(reportService.view(sessionId));

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"interview-report-" + sessionId + ".pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (ApiException e) {
            // Same shape the service chose: a non-owner gets "not found" rather
            // than confirmation that someone else's session exists.
            return ResponseEntity.status(e.getStatus()).build();
        }
    }
}
