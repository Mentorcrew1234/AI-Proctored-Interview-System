package com.project.proctorinterview.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.project.proctorinterview.common.Enums.MonitoringCoverage;
import com.project.proctorinterview.common.Enums.ProctorEventType;
import com.project.proctorinterview.common.Enums.Recommendation;
import com.project.proctorinterview.config.ProctoringPolicyProperties;
import com.project.proctorinterview.config.ScoringProperties;
import com.project.proctorinterview.report.ScoreAggregator.AggregateScores;

/**
 * Turns scores plus proctoring observations into a recommendation and a
 * plain-language justification.
 *
 * <p>Entirely deterministic. The AI supplies per-answer sub-scores; the decision
 * itself is arithmetic against configured thresholds, so it can be recomputed by
 * hand and defended in a viva.
 *
 * <p>The proctoring rule is one-directional: it can move RECOMMENDED down to
 * FURTHER_REVIEW so a human takes a look, and nothing else. It cannot lower a
 * score and cannot produce NOT_RECOMMENDED.
 */
@Component
public class RecommendationEngine {

    private final ScoringProperties scoring;
    private final ProctoringPolicyProperties policy;

    public RecommendationEngine(ScoringProperties scoring, ProctoringPolicyProperties policy) {
        this.scoring = scoring;
        this.policy = policy;
    }

    /**
     * @param recommendation  the final outcome
     * @param integrityFlag   true when observations warrant a human look
     * @param cappedByProctoring true when those observations actually changed the
     *                           outcome, which the explanation must disclose
     */
    public record Outcome(
            Recommendation recommendation,
            boolean integrityFlag,
            boolean cappedByProctoring,
            String explanation) {
    }

    /**
     * @param coverage how much of the interview was actually observed. It shapes
     *                 the <b>wording</b> of the explanation and nothing else.
     *                 Incomplete monitoring is a failure of the observer, not of
     *                 the candidate, so it must never move a recommendation -
     *                 the alternative would be penalising someone for a GPU
     *                 fault or a dropped connection. Observations still only
     *                 ever cap at FURTHER_REVIEW.
     */
    public Outcome decide(AggregateScores scores, Map<String, Object> proctorCounts,
            MonitoringCoverage coverage) {
        Recommendation fromScore = scoring.recommendationFor(scores.overall());

        List<String> observed = triggeredObservations(proctorCounts);
        boolean integrityFlag = !observed.isEmpty();

        Recommendation finalRecommendation = fromScore;
        boolean capped = false;
        if (policy.forceReviewEnabled() && integrityFlag && fromScore == Recommendation.RECOMMENDED) {
            finalRecommendation = Recommendation.FURTHER_REVIEW;
            capped = true;
        }

        return new Outcome(finalRecommendation, integrityFlag, capped,
                explain(scores, fromScore, finalRecommendation, capped, observed, coverage));
    }

    /** Trigger event types that actually occurred, as "TYPE (n)" strings. */
    private List<String> triggeredObservations(Map<String, Object> counts) {
        List<String> observed = new ArrayList<>();
        for (ProctorEventType type : policy.reviewTriggerTypes()) {
            long count = asLong(counts.get(type.name()));
            if (count > 0) {
                observed.add(type.name() + " (" + count + ")");
            }
        }
        return observed;
    }

    private String explain(AggregateScores s, Recommendation fromScore,
            Recommendation finalRecommendation, boolean capped, List<String> observed,
            MonitoringCoverage coverage) {

        StringBuilder sb = new StringBuilder();

        sb.append("Overall score %d/100, from technical %d, problem solving %d, communication %d and relevance %d, "
                .formatted(s.overall(), s.technical(), s.problemSolving(), s.communication(), s.relevance()))
                .append("weighted %.0f/%.0f/%.0f/%.0f respectively. "
                        .formatted(scoring.technicalWeight() * 100, scoring.problemSolvingWeight() * 100,
                                scoring.communicationWeight() * 100, scoring.relevanceWeight() * 100));

        if (s.answeredCount() < s.totalQuestions()) {
            sb.append("The candidate answered %d of %d questions; unanswered questions score zero. "
                    .formatted(s.answeredCount(), s.totalQuestions()));
        }

        sb.append(switch (fromScore) {
            case RECOMMENDED -> "This is at or above the threshold of %d for a recommendation. "
                    .formatted(scoring.recommendedThreshold());
            case FURTHER_REVIEW -> "This falls in the %d to %d band, which calls for a human review rather than a decision either way. "
                    .formatted(scoring.furtherReviewThreshold(), scoring.recommendedThreshold() - 1);
            case NOT_RECOMMENDED -> "This is below the threshold of %d. "
                    .formatted(scoring.furtherReviewThreshold());
        });

        if (!observed.isEmpty()) {
            sb.append("Proctoring observations were recorded: ")
                    .append(String.join(", ", observed))
                    .append(". These are observations from automated browser-based detection, not evidence of misconduct, "
                            + "and they may be false positives. ");
            if (capped) {
                sb.append("Because of them the result has been held at FURTHER_REVIEW for a human to check, "
                        + "rather than being recommended automatically. The scores themselves were not reduced. ");
            }
        } else if (coverage == MonitoringCoverage.NONE_OBSERVED) {
            sb.append("No proctoring observations of concern were recorded. ");
        }

        // The coverage caveat is deliberately appended even when observations
        // exist. Five recorded observations with three dropped is still an
        // incomplete record, and a reader who is not told that will read the
        // list as the whole story.
        //
        // Note what this does NOT do: it does not move the recommendation. A
        // monitoring failure is the observer's, not the candidate's.
        if (coverage.isUnreliable()) {
            sb.append(switch (coverage) {
                case INCOMPLETE -> "Monitoring did not cover the whole interview - the detection models "
                        + "did not start, or observations were lost before they reached the server. "
                        + "Whatever is listed above is therefore an incomplete record, and the absence "
                        + "of an observation here is not evidence that nothing occurred. This has not "
                        + "affected the scores or the recommendation, because it is a failure of the "
                        + "monitoring and not of the candidate. ";
                case NOT_RECORDED -> "Whether monitoring ran for the whole interview was not recorded for "
                        + "this session, so the observations above cannot be confirmed as a complete "
                        + "record. This has not affected the scores or the recommendation. ";
                default -> "";
            });
        }

        sb.append("This recommendation (")
                .append(finalRecommendation.name())
                .append(") is produced by fixed configured thresholds, not by the AI. ")
                .append("It is advisory input for a human decision, not a hiring decision.");

        return sb.toString();
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
