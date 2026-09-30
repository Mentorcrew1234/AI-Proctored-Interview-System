package com.project.proctorinterview.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.project.proctorinterview.answer.Answer;
import com.project.proctorinterview.common.Enums.MonitoringCoverage;
import com.project.proctorinterview.common.Enums.ProctorEventType;
import com.project.proctorinterview.common.Enums.Recommendation;
import com.project.proctorinterview.config.ProctoringPolicyProperties;
import com.project.proctorinterview.config.ScoringProperties;

/**
 * Aggregation and the recommendation rule, tested without Spring.
 *
 * <p>The rule under most scrutiny here is the proctoring policy: observations may
 * hold a result for human review, but must never lower a score and must never
 * produce NOT_RECOMMENDED.
 */
class ReportScoringTest {

    private static final ScoringProperties SCORING =
            new ScoringProperties(0.35, 0.25, 0.20, 0.20, 70, 50);
    private static final ProctoringPolicyProperties POLICY =
            new ProctoringPolicyProperties(true, List.of(
                    ProctorEventType.PHONE_DETECTED,
                    ProctorEventType.MULTIPLE_FACES,
                    ProctorEventType.MULTIPLE_PERSONS));

    private final ScoreAggregator aggregator = new ScoreAggregator(SCORING);
    private final RecommendationEngine engine = new RecommendationEngine(SCORING, POLICY);

    private static Answer answer(int technical, int relevance, int problemSolving, int communication) {
        Answer a = new Answer();
        a.setTechnicalScore(technical);
        a.setRelevanceScore(relevance);
        a.setProblemSolvingScore(problemSolving);
        a.setCommunicationScore(communication);
        return a;
    }

    private static List<Answer> answers(int count, int score) {
        List<Answer> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(answer(score, score, score, score));
        }
        return list;
    }

    private static Map<String, Object> counts(Object... typeCountPairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < typeCountPairs.length; i += 2) {
            map.put(String.valueOf(typeCountPairs[i]), typeCountPairs[i + 1]);
        }
        return map;
    }

    // ---- aggregation --------------------------------------------------------

    @Test
    void averagesAcrossAllAnswers() {
        var scores = aggregator.aggregate(
                List.of(answer(80, 80, 80, 80), answer(60, 60, 60, 60)), 2);

        assertThat(scores.technical()).isEqualTo(70);
        assertThat(scores.overall()).isEqualTo(70);
        assertThat(scores.answeredCount()).isEqualTo(2);
    }

    @Test
    void anUnansweredQuestionCountsAsZeroNotAsAbsent() {
        // Answering one question perfectly and skipping four must not produce a
        // perfect score.
        var scores = aggregator.aggregate(answers(1, 100), 5);

        assertThat(scores.overall()).isEqualTo(20);
        assertThat(scores.answeredCount()).isEqualTo(1);
        assertThat(scores.totalQuestions()).isEqualTo(5);
    }

    @Test
    void answeringEverythingWellBeatsAnsweringOneThingWell() {
        int thorough = aggregator.aggregate(answers(5, 70), 5).overall();
        int selective = aggregator.aggregate(answers(1, 100), 5).overall();

        assertThat(thorough).isGreaterThan(selective);
    }

    @Test
    void handlesNoAnswersAndNoQuestions() {
        assertThat(aggregator.aggregate(List.of(), 5).overall()).isZero();
        assertThat(aggregator.aggregate(List.of(), 0).overall()).isZero();
        assertThat(aggregator.aggregate(List.of(), 0).totalQuestions()).isZero();
    }

    @Test
    void nullSubScoresAreTreatedAsZero() {
        Answer partial = new Answer(); // evaluation never ran
        var scores = aggregator.aggregate(List.of(partial), 1);

        assertThat(scores.overall()).isZero();
    }

    @Test
    void appliesTheConfiguredWeightsToTheAggregate() {
        // technical 80, relevance 90, problem solving 60, communication 70
        var scores = aggregator.aggregate(List.of(answer(80, 90, 60, 70)), 1);

        // 80*.35 + 60*.25 + 70*.20 + 90*.20 = 75
        assertThat(scores.overall()).isEqualTo(75);
    }

    // ---- recommendation -----------------------------------------------------

    @Test
    void aCleanHighScoreIsRecommended() {
        var outcome = engine.decide(
                aggregator.aggregate(answers(5, 85), 5), counts(), MonitoringCoverage.NONE_OBSERVED);

        assertThat(outcome.recommendation()).isEqualTo(Recommendation.RECOMMENDED);
        assertThat(outcome.integrityFlag()).isFalse();
        assertThat(outcome.cappedByProctoring()).isFalse();
    }

    @Test
    void observationsHoldAHighScoreAtFurtherReview() {
        var scores = aggregator.aggregate(answers(5, 85), 5);

        var outcome = engine.decide(scores, counts("PHONE_DETECTED", 1), MonitoringCoverage.OBSERVATIONS_RECORDED);

        assertThat(outcome.recommendation()).isEqualTo(Recommendation.FURTHER_REVIEW);
        assertThat(outcome.integrityFlag()).isTrue();
        assertThat(outcome.cappedByProctoring()).isTrue();
        // The scores themselves are untouched.
        assertThat(scores.overall()).isEqualTo(85);
    }

    @Test
    void proctoringCanNeverProduceNotRecommended() {
        // A borderline-review score with heavy observations still cannot be
        // pushed into a rejection.
        var outcome = engine.decide(
                aggregator.aggregate(answers(5, 55), 5),
                counts("PHONE_DETECTED", 9, "MULTIPLE_FACES", 7, "MULTIPLE_PERSONS", 5),
                MonitoringCoverage.OBSERVATIONS_RECORDED);

        assertThat(outcome.recommendation()).isEqualTo(Recommendation.FURTHER_REVIEW);
        assertThat(outcome.recommendation()).isNotEqualTo(Recommendation.NOT_RECOMMENDED);
    }

    @Test
    void proctoringDoesNotRescueALowScore() {
        // The cap only ever moves RECOMMENDED down; it never moves anything up.
        var outcome = engine.decide(
                aggregator.aggregate(answers(5, 20), 5), counts(), MonitoringCoverage.NONE_OBSERVED);

        assertThat(outcome.recommendation()).isEqualTo(Recommendation.NOT_RECOMMENDED);
    }

    @Test
    void nonTriggerObservationsDoNotHoldTheResult() {
        // Looking away or switching tabs is recorded, but is not on its own a
        // reason to withhold a recommendation.
        var outcome = engine.decide(
                aggregator.aggregate(answers(5, 85), 5),
                counts("HEAD_TURN", 4, "TAB_SWITCH", 2, "NO_FACE", 1), MonitoringCoverage.OBSERVATIONS_RECORDED);

        assertThat(outcome.recommendation()).isEqualTo(Recommendation.RECOMMENDED);
        assertThat(outcome.integrityFlag()).isFalse();
    }

    @Test
    void theCapCanBeTurnedOffInConfiguration() {
        var lenient = new RecommendationEngine(SCORING,
                new ProctoringPolicyProperties(false, List.of(ProctorEventType.PHONE_DETECTED)));

        var outcome = lenient.decide(
                aggregator.aggregate(answers(5, 85), 5), counts("PHONE_DETECTED", 3),
                MonitoringCoverage.OBSERVATIONS_RECORDED);

        assertThat(outcome.recommendation()).isEqualTo(Recommendation.RECOMMENDED);
        // Still flagged for a human to notice, just not enforced.
        assertThat(outcome.integrityFlag()).isTrue();
        assertThat(outcome.cappedByProctoring()).isFalse();
    }

    // ---- explanation --------------------------------------------------------

    @Test
    void theExplanationStatesTheNumbersAndTheProctoringCaveat() {
        var outcome = engine.decide(
                aggregator.aggregate(answers(5, 85), 5), counts("PHONE_DETECTED", 1),
                MonitoringCoverage.OBSERVATIONS_RECORDED);

        assertThat(outcome.explanation())
                .contains("85")
                .contains("PHONE_DETECTED (1)")
                .contains("not evidence of misconduct")
                .contains("scores themselves were not reduced")
                .contains("not by the AI");
    }

    @Test
    void theExplanationDisclosesUnansweredQuestions() {
        var outcome = engine.decide(
                aggregator.aggregate(answers(2, 90), 5), counts(), MonitoringCoverage.NONE_OBSERVED);

        assertThat(outcome.explanation()).contains("answered 2 of 5");
    }

    @Test
    void theExplanationNeverCallsAnObservationCheating() {
        var outcome = engine.decide(
                aggregator.aggregate(answers(5, 85), 5),
                counts("PHONE_DETECTED", 2, "MULTIPLE_FACES", 1), MonitoringCoverage.OBSERVATIONS_RECORDED);

        assertThat(outcome.explanation().toLowerCase())
                .doesNotContain("cheat")
                .doesNotContain("dishonest")
                .doesNotContain("malpractice");
    }

    // ---- monitoring coverage ------------------------------------------------
    //
    // The distinction these lock in: "nothing was observed" and "nothing was
    // watching" are different statements, and a report that says the first when
    // the second is true is the one genuinely misleading output this system
    // could produce.

    @Test
    void anUnwatchedInterviewIsNotDescribedAsClean() {
        var outcome = engine.decide(
                aggregator.aggregate(answers(5, 85), 5), counts(), MonitoringCoverage.INCOMPLETE);

        assertThat(outcome.explanation())
                .doesNotContain("No proctoring observations of concern were recorded")
                .contains("Monitoring did not cover the whole interview")
                .contains("not evidence that nothing occurred");
    }

    @Test
    void incompleteMonitoringNeverChangesTheRecommendation() {
        // The whole point: a monitoring failure is the observer's, not the
        // candidate's, so it must cost them nothing.
        var clean = engine.decide(
                aggregator.aggregate(answers(5, 85), 5), counts(), MonitoringCoverage.NONE_OBSERVED);
        var unwatched = engine.decide(
                aggregator.aggregate(answers(5, 85), 5), counts(), MonitoringCoverage.INCOMPLETE);

        assertThat(unwatched.recommendation()).isEqualTo(clean.recommendation());
        assertThat(unwatched.recommendation()).isEqualTo(Recommendation.RECOMMENDED);
        assertThat(unwatched.integrityFlag()).isFalse();
        assertThat(unwatched.cappedByProctoring()).isFalse();
    }

    @Test
    void aPartialRecordIsCaveatedEvenWhenObservationsExist() {
        // Five recorded observations with some dropped is still an incomplete
        // record, and a reader not told that will read the list as the whole
        // story.
        var outcome = engine.decide(
                aggregator.aggregate(answers(5, 85), 5),
                counts("PHONE_DETECTED", 1), MonitoringCoverage.INCOMPLETE);

        assertThat(outcome.explanation())
                .contains("PHONE_DETECTED (1)")
                .contains("incomplete record");
    }

    @Test
    void anOlderSessionIsReportedAsUnknownRatherThanFailed() {
        var outcome = engine.decide(
                aggregator.aggregate(answers(5, 85), 5), counts(), MonitoringCoverage.NOT_RECORDED);

        assertThat(outcome.explanation())
                .contains("was not recorded for")
                .doesNotContain("Monitoring did not cover the whole interview");
        assertThat(outcome.recommendation()).isEqualTo(Recommendation.RECOMMENDED);
    }

    @Test
    void aFullyMonitoredQuietInterviewStillSaysSoPlainly() {
        var outcome = engine.decide(
                aggregator.aggregate(answers(5, 85), 5), counts(), MonitoringCoverage.NONE_OBSERVED);

        assertThat(outcome.explanation())
                .contains("No proctoring observations of concern were recorded")
                .doesNotContain("incomplete");
    }
}
