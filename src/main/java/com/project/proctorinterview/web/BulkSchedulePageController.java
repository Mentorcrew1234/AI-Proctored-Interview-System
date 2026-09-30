package com.project.proctorinterview.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.bulk.BulkScheduleService;
import com.project.proctorinterview.bulk.BulkStagingStore;
import com.project.proctorinterview.bulk.BulkStagingStore.StagedBatch;
import com.project.proctorinterview.bulk.dto.BulkDtos.BulkScheduleResult;
import com.project.proctorinterview.bulk.dto.BulkDtos.ValidationSummary;
import com.project.proctorinterview.common.ApiException;

/**
 * Bulk interview scheduling, as a server-rendered flow.
 *
 * <p>Four distinct steps, and only the last one writes:
 *
 * <ol>
 *   <li>{@code GET  /scheduling/bulk} - upload form</li>
 *   <li>{@code POST /scheduling/bulk/validate} - parse and validate, create nothing</li>
 *   <li>{@code POST /scheduling/bulk/confirm} - the only step that creates interviews</li>
 *   <li>{@code GET  /scheduling/bulk/result/{batchId}} - what happened</li>
 * </ol>
 *
 * <p>These sit on the session + CSRF chain alongside the other staff screens
 * rather than under {@code /api/**}, which is the stateless JWT chain used by
 * the candidate's React app. Putting them there would force this page to obtain
 * a bearer token for no benefit.
 *
 * <p>The validated batch is held server-side and referenced by an opaque id, so
 * the confirm step never has to trust rows sent back by the browser.
 */
@Controller
@RequestMapping("/scheduling/bulk")
public class BulkSchedulePageController {

    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;

    private final BulkScheduleService bulkService;
    private final BulkStagingStore staging;

    public BulkSchedulePageController(BulkScheduleService bulkService, BulkStagingStore staging) {
        this.bulkService = bulkService;
        this.staging = staging;
    }

    @GetMapping
    public String uploadPage() {
        return "scheduling/bulk-upload";
    }

    /** The blank template, generated fresh so example dates are always in the future. */
    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate() {
        byte[] workbook = bulkService.templateWorkbook();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bulk-interview-template.xlsx\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(workbook);
    }

    /**
     * Step 2. Parses and validates the upload and shows the review page.
     * <b>Creates nothing.</b>
     */
    @PostMapping("/validate")
    public String validate(@RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserDetails me, Model model) {

        if (file == null || file.isEmpty()) {
            model.addAttribute("error", "Please choose a file to upload.");
            return "scheduling/bulk-upload";
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            model.addAttribute("error", "That file is larger than 5 MB. "
                    + "The template should be well under this even with hundreds of rows.");
            return "scheduling/bulk-upload";
        }
        String name = file.getOriginalFilename() == null ? "upload.xlsx" : file.getOriginalFilename();
        if (!name.toLowerCase().endsWith(".xlsx")) {
            model.addAttribute("error",
                    "Only .xlsx files are supported. Re-save the file as .xlsx and try again.");
            return "scheduling/bulk-upload";
        }

        ValidationSummary summary;
        try {
            summary = bulkService.validateUpload(file.getBytes(), name);
        } catch (ApiException e) {
            model.addAttribute("error", e.getMessage());
            return "scheduling/bulk-upload";
        } catch (Exception e) {
            model.addAttribute("error", "The file could not be read. "
                    + "Please upload the downloaded template, saved as .xlsx.");
            return "scheduling/bulk-upload";
        }

        StagedBatch batch = staging.stage(me.getId(), summary);
        model.addAttribute("batchId", batch.batchId());
        model.addAttribute("summary", summary);
        return "scheduling/bulk-review";
    }

    /** The invalid rows and their errors, so they can be fixed and re-uploaded. */
    @GetMapping("/errors/{batchId}")
    public ResponseEntity<byte[]> downloadErrorReport(@PathVariable String batchId,
            @AuthenticationPrincipal AppUserDetails me) {
        StagedBatch batch = staging.find(batchId, me.getId());
        if (batch == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bulk-interview-errors.xlsx\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bulkService.buildErrorReport(batch.summary()));
    }

    /**
     * Step 3. The only step that creates interviews, and only for a batch this
     * server validated and this user uploaded.
     *
     * <p>Claiming the batch is atomic, so a double-clicked button schedules once
     * and the second request simply shows the result of the first.
     */
    @PostMapping("/confirm")
    public String confirm(@RequestParam("batchId") String batchId,
            @AuthenticationPrincipal AppUserDetails me, Model model) {

        StagedBatch batch = staging.find(batchId, me.getId());
        if (batch == null) {
            model.addAttribute("error",
                    "That upload is no longer available. Uploads expire after 30 minutes — "
                            + "please upload the file again. Nothing was scheduled.");
            return "scheduling/bulk-upload";
        }

        if (!staging.claim(batch)) {
            // Already scheduled: show what happened rather than doing it twice.
            model.addAttribute("result", batch.result());
            model.addAttribute("alreadyScheduled", true);
            return "scheduling/bulk-result";
        }

        BulkScheduleResult result = bulkService.schedule(batch.summary(), me.getId(), batchId);
        staging.complete(batch, result);

        model.addAttribute("result", result);
        return "scheduling/bulk-result";
    }

    /** Re-displays a completed batch, e.g. after a refresh. */
    @GetMapping("/result/{batchId}")
    public String result(@PathVariable String batchId,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        StagedBatch batch = staging.find(batchId, me.getId());
        if (batch == null || !batch.isConsumed()) {
            return "redirect:/scheduling/bulk";
        }
        model.addAttribute("result", batch.result());
        model.addAttribute("alreadyScheduled", true);
        return "scheduling/bulk-result";
    }

    /** Workbook summary of a completed batch, for record keeping. */
    @GetMapping("/report/{batchId}")
    public ResponseEntity<byte[]> downloadResultReport(@PathVariable String batchId,
            @AuthenticationPrincipal AppUserDetails me) {
        StagedBatch batch = staging.find(batchId, me.getId());
        if (batch == null || !batch.isConsumed()) {
            return ResponseEntity.notFound().build();
        }
        byte[] workbook = bulkService.buildResultReport(batch.result());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bulk-scheduling-result.xlsx\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(workbook);
    }
}
