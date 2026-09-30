package com.project.proctorinterview.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.project.proctorinterview.common.Enums.Recommendation;

/**
 * The scoring maths is the part of the system that decides an outcome, so it is
 * plain arithmetic over configured weights rather than anything the model
 * returns. These tests pin the weights, the rounding and the threshold
 * boundaries.
 */
class ScoringPropertiesTest {

    /** The values shipped in application.yml. */
    private static final ScoringProperties DEFAULTS =
            new ScoringProperties(0.35, 0.25, 0.20, 0.20, 70, 50);

    @Test
    void weightsTheFourSubScores() {
        // 80*.35 + 60*.25 + 70*.20 + 90*.20 = 28 + 15 + 14 + 18 = 75
        assertThat(DEFAULTS.weightedOverall(80, 60, 70, 90)).isEqualTo(75);
    }

    @Test
    void identicalSubScoresGiveThatScore() {
        assertThat(DEFAULTS.weightedOverall(70, 70, 70, 70)).isEqualTo(70);
        assertThat(DEFAULTS.weightedOverall(0, 0, 0, 0)).isZero();
        assertThat(DEFAULTS.weightedOverall(100, 100, 100, 100)).isEqualTo(100);
    }

    @Test
    void technicalCarriesTheMostWeight() {
        int strongTechnical = DEFAULTS.weightedOverall(100, 50, 50, 50);
        int strongCommunication = DEFAULTS.weightedOverall(50, 50, 100, 50);

        assertThat(strongTechnical).isGreaterThan(strongCommunication);
    }

    @Test
    void recommendationBoundariesAreExact() {
        // The boundary is where a disagreement would actually matter.
        assertThat(DEFAULTS.recommendationFor(70)).isEqualTo(Recommendation.RECOMMENDED);
        assertThat(DEFAULTS.recommendationFor(69)).isEqualTo(Recommendation.FURTHER_REVIEW);
        assertThat(DEFAULTS.recommendationFor(50)).isEqualTo(Recommendation.FURTHER_REVIEW);
        assertThat(DEFAULTS.recommendationFor(49)).isEqualTo(Recommendation.NOT_RECOMMENDED);
    }

    @Test
    void extremesMapToTheExpectedRecommendation() {
        assertThat(DEFAULTS.recommendationFor(100)).isEqualTo(Recommendation.RECOMMENDED);
        assertThat(DEFAULTS.recommendationFor(0)).isEqualTo(Recommendation.NOT_RECOMMENDED);
    }

    @Test
    void thresholdsAreConfigurableWithoutCodeChanges() {
        ScoringProperties strict = new ScoringProperties(0.35, 0.25, 0.20, 0.20, 85, 65);

        assertThat(strict.recommendationFor(80)).isEqualTo(Recommendation.FURTHER_REVIEW);
        assertThat(strict.recommendationFor(85)).isEqualTo(Recommendation.RECOMMENDED);
        assertThat(strict.recommendationFor(64)).isEqualTo(Recommendation.NOT_RECOMMENDED);
    }

    @Test
    void weightsAreNormalisedSoTheyNeedNotSumToOne() {
        ScoringProperties doubled = new ScoringProperties(0.70, 0.50, 0.40, 0.40, 70, 50);

        assertThat(doubled.weightedOverall(80, 60, 70, 90))
                .isEqualTo(DEFAULTS.weightedOverall(80, 60, 70, 90));
    }

    @Test
    void zeroWeightsDoNotDivideByZero() {
        ScoringProperties broken = new ScoringProperties(0, 0, 0, 0, 70, 50);

        assertThat(broken.weightedOverall(80, 80, 80, 80)).isZero();
    }
}
