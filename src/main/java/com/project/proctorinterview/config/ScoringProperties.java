package com.project.proctorinterview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.project.proctorinterview.common.Enums.Recommendation;

/**
 * Deterministic scoring rules ({@code app.report.scoring}).
 *
 * <p>This is where the outcome is actually decided. The AI contributes
 * sub-scores; the weighting and the thresholds live here, in configuration, and
 * are applied in plain arithmetic. That means a result can be recomputed by hand
 * and defended, and the thresholds can be changed without touching code.
 */
@ConfigurationProperties(prefix = "app.report.scoring")
public record ScoringProperties(
        double technicalWeight,
        double problemSolvingWeight,
        double communicationWeight,
        double relevanceWeight,
        int recommendedThreshold,
        int furtherReviewThreshold) {

    /** Weighted composite of the four sub-scores, rounded to a whole number. */
    public int weightedOverall(int technical, int problemSolving, int communication, int relevance) {
        double total = technicalWeight + problemSolvingWeight + communicationWeight + relevanceWeight;
        if (total <= 0) {
            return 0;
        }
        double weighted = technical * technicalWeight
                + problemSolving * problemSolvingWeight
                + communication * communicationWeight
                + relevance * relevanceWeight;
        return (int) Math.round(weighted / total);
    }

    public Recommendation recommendationFor(int overallScore) {
        if (overallScore >= recommendedThreshold) {
            return Recommendation.RECOMMENDED;
        }
        if (overallScore >= furtherReviewThreshold) {
            return Recommendation.FURTHER_REVIEW;
        }
        return Recommendation.NOT_RECOMMENDED;
    }
}
