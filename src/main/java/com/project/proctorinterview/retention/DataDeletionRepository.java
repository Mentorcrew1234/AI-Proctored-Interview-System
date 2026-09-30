package com.project.proctorinterview.retention;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DataDeletionRepository extends JpaRepository<DataDeletion, Long> {

    /**
     * The deletion log, newest first, with the administrator fetched eagerly -
     * the page names them and {@code open-in-view} is off.
     */
    @Query("select d from DataDeletion d join fetch d.performedBy order by d.createdAt desc, d.id desc")
    List<DataDeletion> recentFirst();
}
