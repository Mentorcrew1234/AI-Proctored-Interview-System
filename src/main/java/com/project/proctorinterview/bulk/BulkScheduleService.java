package com.project.proctorinterview.bulk;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.project.proctorinterview.bulk.dto.BulkDtos.BulkScheduleResult;
import com.project.proctorinterview.bulk.dto.BulkDtos.FailedRow;
import com.project.proctorinterview.bulk.dto.BulkDtos.RawRow;
import com.project.proctorinterview.bulk.dto.BulkDtos.ScheduledRow;
import com.project.proctorinterview.bulk.dto.BulkDtos.ValidatedRow;
import com.project.proctorinterview.bulk.dto.BulkDtos.ValidationSummary;
import com.project.proctorinterview.common.ApiException;

/**
 * Orchestrates bulk scheduling: parse, validate, then create.
 *
 * <p>Validation and creation are deliberately separate operations. Uploading a
 * file never writes anything; only an explicit confirmation does.
 */
@Service
public class BulkScheduleService {

    private static final Logger log = LoggerFactory.getLogger(BulkScheduleService.class);

    private final BulkExcelService excelService;
    private final BulkValidationService validationService;
    private final BulkRowScheduler rowScheduler;

    public BulkScheduleService(BulkExcelService excelService, BulkValidationService validationService,
            BulkRowScheduler rowScheduler) {
        this.excelService = excelService;
        this.validationService = validationService;
        this.rowScheduler = rowScheduler;
    }

    /** Parses and validates an upload. Creates nothing. */
    public ValidationSummary validateUpload(byte[] fileBytes, String fileName) {
        List<RawRow> rawRows = excelService.parse(new ByteArrayInputStream(fileBytes));
        List<ValidatedRow> rows = validationService.validate(rawRows);

        int valid = (int) rows.stream().filter(ValidatedRow::valid).count();
        return new ValidationSummary(null, fileName, rows.size(), valid, rows.size() - valid, rows);
    }

    /**
     * Creates interviews for the valid rows of an already-validated batch.
     *
     * <p>Each row is written in its own transaction by {@link BulkRowScheduler},
     * so one failure cannot roll back rows that already succeeded. Failures are
     * collected and reported per row rather than swallowed.
     */
    public BulkScheduleResult schedule(ValidationSummary staged, Long actorUserId, String batchId) {
        List<ValidatedRow> rows = staged.validRowList();

        List<ScheduledRow> scheduled = new ArrayList<>();
        List<FailedRow> failures = new ArrayList<>();

        for (ValidatedRow row : rows) {
            try {
                scheduled.add(rowScheduler.scheduleOne(row, actorUserId));
            } catch (Exception e) {
                log.warn("Bulk row {} failed to schedule: {}", row.excelRowNumber(), e.getMessage());
                failures.add(new FailedRow(row.excelRowNumber(), row.candidateName(), friendlyReason(e)));
            }
        }

        log.info("Bulk batch {}: {} scheduled, {} failed, {} invalid",
                batchId, scheduled.size(), failures.size(), staged.invalidRows());

        return new BulkScheduleResult(batchId, scheduled.size(), failures.size(),
                staged.invalidRows(), scheduled, failures, Instant.now());
    }

    public byte[] templateWorkbook() {
        return excelService.buildTemplate();
    }

    public byte[] buildErrorReport(ValidationSummary summary) {
        return excelService.buildErrorReport(summary.invalidRowList());
    }

    /**
     * Workbook record of a completed batch. Includes any generated passwords,
     * since there is no email integration and the recruiter has to relay them.
     */
    public byte[] buildResultReport(BulkScheduleResult result) {
        return excelService.buildResultReport(result);
    }

    /** Keeps stack traces and database internals out of anything a user sees. */
    private static String friendlyReason(Exception e) {
        if (e instanceof ApiException || e instanceof IllegalStateException) {
            String message = e.getMessage();
            return message == null || message.isBlank() ? "Could not be scheduled." : message;
        }
        return "Could not be scheduled because of an unexpected error.";
    }
}
