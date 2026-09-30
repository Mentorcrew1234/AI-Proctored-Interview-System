package com.project.proctorinterview.report;

import java.util.List;

import org.springframework.stereotype.Component;

import com.project.proctorinterview.answer.Answer;
import com.project.proctorinterview.config.ScoringProperties;

/**
 * Combines per-answer scores into one set of session scores.
 *
 * <p>Two rules worth stating explicitly:
 *
 * <ol>
 *   <li>An unanswered question counts as zero, not as absent. Averaging only the
 *       answered questions would let a candidate who answered one question well
 *       and skipped four outscore one who attempted everything.</li>
 *   <li>The overall figure is recomputed from the aggregated dimensions using
 *       the configured weights, rather than averaging the per-answer overalls.
 *       Same result for linear weights, but it keeps one definition of "overall"
 *       in the system.</li>
 * </ol>
 */
@Component
public class ScoreAggregator {

    private final ScoringProperties scoring;

    public ScoreAggregator(ScoringProperties scoring) {
        this.scoring = scoring;
    }

    public record AggregateScores(
            int technical,
            int relevance,
            int problemSolving,
            int communication,
            int overall,
            int answeredCount,
            int totalQuestions) {
    }

    /**
     * @param answers        the answers actually submitted
     * @param totalQuestions how many questions the interview had; the divisor,
     *                       so skipped questions drag the average down
     */
    public AggregateScores aggregate(List<Answer> answers, int totalQuestions) {
        if (totalQuestions <= 0) {
            return new AggregateScores(0, 0, 0, 0, 0, 0, 0);
        }

        int technicalSum = 0;
        int relevanceSum = 0;
        int problemSolvingSum = 0;
        int communicationSum = 0;

        for (Answer answer : answers) {
            technicalSum += orZero(answer.getTechnicalScore());
            relevanceSum += orZero(answer.getRelevanceScore());
            problemSolvingSum += orZero(answer.getProblemSolvingScore());
            communicationSum += orZero(answer.getCommunicationScore());
        }

        int technical = technicalSum / totalQuestions;
        int relevance = relevanceSum / totalQuestions;
        int problemSolving = problemSolvingSum / totalQuestions;
        int communication = communicationSum / totalQuestions;

        int overall = scoring.weightedOverall(technical, problemSolving, communication, relevance);

        return new AggregateScores(technical, relevance, problemSolving, communication,
                overall, answers.size(), totalQuestions);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
