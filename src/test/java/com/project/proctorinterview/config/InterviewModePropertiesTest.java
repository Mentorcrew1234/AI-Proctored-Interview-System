package com.project.proctorinterview.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.project.proctorinterview.config.InterviewModeProperties.Mode;

/**
 * The mode resolver.
 *
 * <p>Everything here exists to protect one rule: the application must never end
 * up in DEMO by accident. Showing a real candidate the proctoring monitor
 * because a value was misspelled, blank or missing would be the worst outcome,
 * so anything that is not an explicit, valid DEMO resolves to NORMAL.
 */
class InterviewModePropertiesTest {

    @Test
    void normalIsTheDefaultWhenNothingIsConfigured() {
        assertThat(new InterviewModeProperties(null).resolved()).isEqualTo(Mode.NORMAL);
        assertThat(new InterviewModeProperties(null).isDemo()).isFalse();
    }

    @Test
    void aBlankValueResolvesToNormal() {
        assertThat(new InterviewModeProperties("").resolved()).isEqualTo(Mode.NORMAL);
        assertThat(new InterviewModeProperties("   ").resolved()).isEqualTo(Mode.NORMAL);
    }

    /**
     * The requirement that made this a String property rather than an enum: an
     * unbindable enum value stops the application from starting, and the rule
     * is to fall back, not to fail.
     */
    @Test
    void anInvalidValueResolvesToNormalRatherThanFailing() {
        assertThat(new InterviewModeProperties("INVALID").resolved()).isEqualTo(Mode.NORMAL);
        assertThat(new InterviewModeProperties("INVALID").isDemo()).isFalse();
        assertThat(new InterviewModeProperties("demonstration").isDemo()).isFalse();
        assertThat(new InterviewModeProperties("TRUE").isDemo()).isFalse();
    }

    @Test
    void demoIsHonouredWhenSpelledCorrectly() {
        assertThat(new InterviewModeProperties("DEMO").resolved()).isEqualTo(Mode.DEMO);
        assertThat(new InterviewModeProperties("DEMO").isDemo()).isTrue();
    }

    /** Typed by hand into a shell or a YAML file, so case and spacing are forgiven. */
    @Test
    void caseAndSurroundingSpaceAreForgiven() {
        assertThat(new InterviewModeProperties("demo").isDemo()).isTrue();
        assertThat(new InterviewModeProperties("  Demo  ").isDemo()).isTrue();
        assertThat(new InterviewModeProperties(" normal ").resolved()).isEqualTo(Mode.NORMAL);
    }

    @Test
    void theNameHandedToTheBrowserIsAlwaysAValidMode() {
        assertThat(new InterviewModeProperties("INVALID").resolvedName()).isEqualTo("NORMAL");
        assertThat(new InterviewModeProperties(null).resolvedName()).isEqualTo("NORMAL");
        assertThat(new InterviewModeProperties("demo").resolvedName()).isEqualTo("DEMO");
    }
}
