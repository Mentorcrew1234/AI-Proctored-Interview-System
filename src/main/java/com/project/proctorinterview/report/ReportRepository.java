package com.project.proctorinterview.report;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {

    Optional<Report> findBySessionId(Long sessionId);

    boolean existsBySessionId(Long sessionId);

    /**
     * Batch lookup for the filtered export. Fetching reports one session at a
     * time would make the export cost a query per row.
     */
    List<Report> findBySessionIdIn(List<Long> sessionIds);
}
