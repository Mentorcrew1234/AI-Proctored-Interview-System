package com.project.proctorinterview.bulk;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.project.proctorinterview.bulk.dto.BulkDtos.BulkScheduleResult;
import com.project.proctorinterview.bulk.dto.BulkDtos.ValidationSummary;

/**
 * Holds validated batches between the review page and the confirmation.
 *
 * <p>The alternative would be posting the rows back from the browser, which
 * would mean trusting the client's idea of what passed validation. Keeping the
 * batch server-side means the confirm step only carries an opaque id, and the
 * data it schedules is data this server itself validated.
 *
 * <p>In memory with a short expiry, which suits a single-instance prototype. A
 * clustered deployment would need shared storage; that is noted as a limitation
 * rather than solved with infrastructure this project does not need.
 */
@Component
public class BulkStagingStore {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_BATCHES = 200;

    /** A staged batch, owned by the user who uploaded it. */
    public static final class StagedBatch {
        private final String batchId;
        private final Long ownerUserId;
        private final ValidationSummary summary;
        private final Instant createdAt;
        /** Set once scheduling has run, which also makes a second confirm a no-op. */
        private volatile BulkScheduleResult result;

        StagedBatch(String batchId, Long ownerUserId, ValidationSummary summary) {
            this.batchId = batchId;
            this.ownerUserId = ownerUserId;
            this.summary = summary;
            this.createdAt = Instant.now();
        }

        public String batchId() {
            return batchId;
        }

        public Long ownerUserId() {
            return ownerUserId;
        }

        public ValidationSummary summary() {
            return summary;
        }

        public BulkScheduleResult result() {
            return result;
        }

        public boolean isConsumed() {
            return result != null;
        }

        void markScheduled(BulkScheduleResult result) {
            this.result = result;
        }

        boolean isExpired() {
            return createdAt.plus(TTL).isBefore(Instant.now());
        }
    }

    private final Map<String, StagedBatch> batches = new ConcurrentHashMap<>();

    public StagedBatch stage(Long ownerUserId, ValidationSummary summary) {
        evictExpired();
        String batchId = UUID.randomUUID().toString();
        StagedBatch batch = new StagedBatch(batchId, ownerUserId, summary);
        batches.put(batchId, batch);
        return batch;
    }

    /**
     * @return the batch, or null when it is unknown, expired, or belongs to
     *         someone else. All three are indistinguishable to the caller on
     *         purpose.
     */
    public StagedBatch find(String batchId, Long requestingUserId) {
        if (batchId == null) {
            return null;
        }
        StagedBatch batch = batches.get(batchId);
        if (batch == null || batch.isExpired() || !batch.ownerUserId().equals(requestingUserId)) {
            return null;
        }
        return batch;
    }

    /**
     * Claims a batch for scheduling. Atomic, so two rapid confirms cannot both
     * proceed - the second sees the batch already consumed.
     */
    public synchronized boolean claim(StagedBatch batch) {
        if (batch.isConsumed()) {
            return false;
        }
        // Reserved by writing an empty result; replaced by the real one after.
        batch.markScheduled(new BulkScheduleResult(batch.batchId(), 0, 0, 0,
                java.util.List.of(), java.util.List.of(), Instant.now()));
        return true;
    }

    public void complete(StagedBatch batch, BulkScheduleResult result) {
        batch.markScheduled(result);
    }

    private void evictExpired() {
        batches.values().removeIf(StagedBatch::isExpired);
        if (batches.size() > MAX_BATCHES) {
            batches.entrySet().stream()
                    .sorted((a, b) -> a.getValue().createdAt.compareTo(b.getValue().createdAt))
                    .limit(batches.size() - MAX_BATCHES)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(batches::remove);
        }
    }
}
