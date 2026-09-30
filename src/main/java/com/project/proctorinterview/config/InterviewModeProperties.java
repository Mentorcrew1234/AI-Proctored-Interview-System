package com.project.proctorinterview.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much of the proctoring machinery the candidate's screen shows
 * ({@code app.interview.mode}, environment variable {@code INTERVIEW_MODE}).
 *
 * <p>This is a <b>presentation</b> setting and nothing more. Both modes run the
 * same interview, the same questions, the same detection pipeline, the same
 * event recording and the same report. The only difference is what the browser
 * draws on screen:
 *
 * <ul>
 *   <li>{@code NORMAL} - the real candidate experience. A clean interview
 *       screen: question, answer, progress, finish. No detection state, no
 *       counters, no model status.</li>
 *   <li>{@code DEMO} - the same interview with a monitoring panel and detection
 *       overlay, so a viva audience can see what the prototype is actually
 *       detecting.</li>
 * </ul>
 *
 * <p><b>DEMO does not weaken proctoring and does not fabricate anything.</b> It
 * displays the values the candidate's own browser has already computed from
 * their own camera; nothing extra is requested from the server, and no
 * detection threshold changes.
 *
 * <h2>Why this binds a String rather than the enum</h2>
 *
 * <p>Spring's binder fails fast on a value it cannot convert, so declaring this
 * as {@code Mode} would mean {@code INTERVIEW_MODE=INVALID} <em>stops the
 * application from starting</em>. The requirement is the opposite: an
 * unrecognised or missing value must fall back to {@code NORMAL}, because the
 * dangerous failure here is silently running a real interview in DEMO. Binding
 * the raw text and resolving it ourselves keeps that guarantee, and says so in
 * the log rather than failing quietly.
 */
@ConfigurationProperties(prefix = "app.interview")
public record InterviewModeProperties(String mode) {

    private static final Logger log = LoggerFactory.getLogger(InterviewModeProperties.class);

    public enum Mode {
        /** The real candidate experience. Nothing internal is shown. */
        NORMAL,
        /** Demonstration and development: monitoring panel and detection overlay. */
        DEMO
    }

    /**
     * The safe default, applied when the property is absent, blank or not one
     * of the two supported values.
     */
    public static final Mode DEFAULT = Mode.NORMAL;

    /**
     * The configured mode, or {@link #DEFAULT} if the value is missing or not
     * recognised.
     *
     * <p>Case and surrounding whitespace are forgiven - {@code demo},
     * {@code DEMO} and {@code " Demo "} all mean the same thing - because this
     * is typed into a shell or a YAML file by hand. Anything else is refused
     * and logged, never guessed at.
     */
    public Mode resolved() {
        if (mode == null || mode.isBlank()) {
            return DEFAULT;
        }
        try {
            return Mode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognised app.interview.mode '{}' - falling back to {}. "
                    + "Supported values are NORMAL and DEMO.", mode, DEFAULT);
            return DEFAULT;
        }
    }

    /**
     * True only when DEMO was explicitly and validly configured.
     *
     * <p>The single place anything asks "are we demonstrating?", so the answer
     * cannot drift between callers.
     */
    public boolean isDemo() {
        return resolved() == Mode.DEMO;
    }

    /** The value handed to the browser. Always a valid mode name. */
    public String resolvedName() {
        return resolved().name();
    }
}
