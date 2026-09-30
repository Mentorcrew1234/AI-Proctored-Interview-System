package com.project.proctorinterview.interview;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.interview.dto.ExamDtos.ExamInfo;
import com.project.proctorinterview.interview.dto.ExamDtos.StartExamRequest;
import com.project.proctorinterview.interview.dto.ExamDtos.StartExamResponse;

import jakarta.validation.Valid;

/** Candidate-facing exam endpoints, called by the React exam screen. */
@RestController
@RequestMapping("/api/exam")
public class ExamController {

    private final ExamService examService;

    public ExamController(ExamService examService) {
        this.examService = examService;
    }

    /** Instructions and configuration for the invite link. */
    @GetMapping("/{token}")
    public ExamInfo info(@PathVariable String token, @AuthenticationPrincipal AppUserDetails me) {
        return examService.describe(token, me.getId());
    }

    /** Creates the session, or resumes the existing one after a page reload. */
    @PostMapping("/{token}/start")
    public StartExamResponse start(@PathVariable String token,
            @Valid @RequestBody StartExamRequest request,
            @AuthenticationPrincipal AppUserDetails me) {
        return examService.start(token, me.getId(), request);
    }
}
