package com.project.proctorinterview.proctor;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.project.proctorinterview.common.Enums.ProctorEventType;

public interface ProctorEventRepository extends JpaRepository<ProctorEvent, Long> {

    List<ProctorEvent> findBySessionIdOrderByStartTimeAsc(Long sessionId);

    /** Guards against duplicate uploads when the client retries a batch. */
    boolean existsByClientEventId(String clientEventId);

    /**
     * Which of these ids are already stored - one query for a whole batch
     * rather than one per event. A batch can carry 200 events, so this is the
     * difference between one round trip and two hundred.
     */
    @Query("select e.clientEventId from ProctorEvent e where e.clientEventId in :ids")
    Set<String> findClientEventIdsIn(@Param("ids") Collection<String> ids);

    long countBySessionIdAndEventType(Long sessionId, ProctorEventType eventType);

    /**
     * How many observations a session has, for the coverage verdict. A count
     * rather than a load: the verdict only needs to know whether there are any.
     */
    long countBySessionId(Long sessionId);
}
