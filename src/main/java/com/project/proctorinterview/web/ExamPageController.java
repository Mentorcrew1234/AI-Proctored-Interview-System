package com.project.proctorinterview.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves the React exam application for any invite link.
 *
 * <p>The bundle is static and public; the token is not resolved here. The
 * browser reads it from the URL and calls {@code /api/exam/{token}}, which is
 * where authentication and ownership are actually enforced.
 */
@Controller
public class ExamPageController {

    /**
     * The {@code [^.]+} pattern excludes anything containing a dot, so real
     * files such as {@code /exam/index.html} fall through to the static resource
     * handler. Without it this mapping would match its own forward target and
     * loop until the request blew up.
     */
    @GetMapping("/exam/{token:[^.]+}")
    public String exam(@PathVariable String token) {
        // Forward rather than redirect so the token stays in the address bar.
        return "forward:/exam/index.html";
    }
}
