package com.project.proctorinterview.question;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewType;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    List<Question> findBySessionIdOrderBySequenceNoAsc(Long sessionId);

    Optional<Question> findBySessionIdAndSequenceNo(Long sessionId, int sequenceNo);

    long countBySessionId(Long sessionId);

    /**
     * Recent question texts from other interviews with the same domain,
     * interview type, candidate type and language, most recent first, capped
     * by {@code pageable} - never the whole table. Used to give Gemini a
     * short "avoid repeating these" list so two interviews scheduled with
     * identical parameters do not read as the same archetype; see
     * {@code QuestionService.ensureQuestions} and
     * {@code GeminiLlmClient.questionPrompt}.
     *
     * <p>{@code pageable} without a {@code Page}/{@code Slice} return type
     * applies its limit directly as SQL {@code LIMIT}, with no separate
     * count query.
     */
    @Query("""
            SELECT q.text FROM Question q
            JOIN q.session s
            JOIN s.interview i
            WHERE i.domain = :domain
              AND i.interviewType = :interviewType
              AND i.candidateType = :candidateType
              AND i.language = :language
            ORDER BY q.createdAt DESC
            """)
    List<String> findRecentTextsByContext(@Param("domain") String domain,
            @Param("interviewType") InterviewType interviewType,
            @Param("candidateType") CandidateType candidateType,
            @Param("language") InterviewLanguage language,
            Pageable pageable);
}
