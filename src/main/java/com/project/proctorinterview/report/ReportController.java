package com.project.proctorinterview.report;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.report.dto.ReportDtos.ReportView;

/** Report JSON. Access rules live in the service, shared with the page controller. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/{sessionId}")
    public ReportView report(@PathVariable Long sessionId,
            @AuthenticationPrincipal AppUserDetails me) {
        reportService.assertCanView(sessionId, me.getId(), me.getRole());
        return reportService.view(sessionId);
    }
}
