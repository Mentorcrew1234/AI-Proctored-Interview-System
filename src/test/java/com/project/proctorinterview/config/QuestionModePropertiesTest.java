package com.project.proctorinterview.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.project.proctorinterview.common.Enums.QuestionMode;

/**
 * The question-mode resolver.
 *
 * <p>Mirrors {@link InterviewModePropertiesTest}: the rule this protects is
 * that adaptive questioning - unverified against real Gemini, unwired into any
 * endpoint - must never turn on by accident. Anything that is not an explicit,
 * valid {@code ADAPTIVE} resolves to {@code FIXED}, the only behaviour Phase
 * 5.4 actually verified end to end.
 */
class QuestionModePropertiesTest {

    @Test
    void fixedIsTheDefaultWhenNothingIsConfigured() {
        assertThat(new QuestionModeProperties(null).resolved()).isEqualTo(QuestionMode.FIXED);
        assertThat(new QuestionModeProperties(null).isAdaptive()).isFalse();
    }

    @Test
    void aBlankValueResolvesToFixed() {
        assertThat(new QuestionModeProperties("").resolved()).isEqualTo(QuestionMode.FIXED);
        assertThat(new QuestionModeProperties("   ").resolved()).isEqualTo(QuestionMode.FIXED);
    }

    /**
     * The requirement that made this a String property rather than an enum: an
     * unbindable enum value stops the application from starting, and the rule
     * is to fall back, not to fail.
     */
    @Test
    void anInvalidValueResolvesToFixedRatherThanFailing() {
        assertThat(new QuestionModeProperties("INVALID").resolved()).isEqualTo(QuestionMode.FIXED);
        assertThat(new QuestionModeProperties("INVALID").isAdaptive()).isFalse();
        assertThat(new QuestionModeProperties("adaptivve").isAdaptive()).isFalse();
        assertThat(new QuestionModeProperties("TRUE").isAdaptive()).isFalse();
    }

    @Test
    void adaptiveIsHonouredWhenSpelledCorrectly() {
        assertThat(new QuestionModeProperties("ADAPTIVE").resolved()).isEqualTo(QuestionMode.ADAPTIVE);
        assertThat(new QuestionModeProperties("ADAPTIVE").isAdaptive()).isTrue();
    }

    /** Typed by hand into a shell or a YAML file, so case and spacing are forgiven. */
    @Test
    void caseAndSurroundingSpaceAreForgiven() {
        assertThat(new QuestionModeProperties("adaptive").isAdaptive()).isTrue();
        assertThat(new QuestionModeProperties("  Adaptive  ").isAdaptive()).isTrue();
        assertThat(new QuestionModeProperties(" fixed ").resolved()).isEqualTo(QuestionMode.FIXED);
    }
}
