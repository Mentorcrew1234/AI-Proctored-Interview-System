package com.project.proctorinterview.answer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.project.proctorinterview.common.Enums.EvaluatorType;
import com.project.proctorinterview.common.StringListConverter;
import com.project.proctorinterview.question.Question;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A candidate's answer plus its evaluation. Evaluation is strictly 1:1 with the
 * answer, so it lives in the same row rather than a separate table.
 *
 * <p>{@code rawTranscript} is never modified after capture — cleaning produces
 * {@code cleanTranscript}, and only the cleaned text is sent for evaluation.
 */
@Entity
@Table(name = "answers")
@Getter
@Setter
public class Answer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false, unique = true)
    private Question question;

    /** Exactly what speech recognition produced. Preserved verbatim. */
    @Column(name = "raw_transcript", columnDefinition = "LONGTEXT")
    private String rawTranscript;

    /** Filler words removed; this is what the evaluator sees. */
    @Column(name = "clean_transcript", columnDefinition = "LONGTEXT")
    private String cleanTranscript;

    @Column(name = "filler_count", nullable = false)
    private int fillerCount;

    @Column(name = "word_count", nullable = false)
    private int wordCount;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;

    // ---- evaluation -------------------------------------------------------

    @Column(name = "technical_score")
    private Integer technicalScore;

    @Column(name = "relevance_score")
    private Integer relevanceScore;

    @Column(name = "problem_solving_score")
    private Integer problemSolvingScore;

    @Column(name = "communication_score")
    private Integer communicationScore;

    /** Weighted composite, always computed in Java — never taken from the model. */
    @Column(name = "overall_score")
    private Integer overallScore;

    @Column(columnDefinition = "TEXT")
    private String feedback;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> strengths = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> weaknesses = new ArrayList<>();

    /** Records whether the LLM or the offline heuristic produced these scores. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private EvaluatorType evaluator;

    @Column(name = "model_name", length = 80)
    private String modelName;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    @PrePersist
    void onCreate() {
        if (answeredAt == null) {
            answeredAt = Instant.now();
        }
    }
}
