package com.project.proctorinterview.answer;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnswerRepository extends JpaRepository<Answer, Long> {

    Optional<Answer> findByQuestionId(Long questionId);

    boolean existsByQuestionId(Long questionId);

    /** All answers for a session, in question order — drives report aggregation. */
    List<Answer> findByQuestionSessionIdOrderByQuestionSequenceNoAsc(Long sessionId);

    /**
     * Answer counts for many sessions at once, as {@code [sessionId, count]}.
     *
     * <p>For the filtered export: counting per session would cost one query per
     * exported row, which is exactly what the export's batch assembly exists to
     * avoid. Sessions with no answers are simply absent from the result.
     */
    @Query("""
            select a.question.session.id, count(a)
            from Answer a
            where a.question.session.id in :sessionIds
            group by a.question.session.id""")
    List<Object[]> countBySessionIdIn(@Param("sessionIds") List<Long> sessionIds);
}
