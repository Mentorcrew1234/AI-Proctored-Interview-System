package com.project.proctorinterview.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import com.project.proctorinterview.common.Enums.QuestionMode;

/**
 * Whether questions are generated as a complete fixed set up front, or one at
 * a time, adapted from the previous answer's performance
 * ({@code app.interview.question-mode}, environment variable
 * {@code INTERVIEW_QUESTION_MODE}).
 *
 * <ul>
 *   <li>{@code FIXED} - the existing, fully verified behaviour: all of an
 *       interview's questions are generated together before the candidate sees
 *       the first one. {@link com.project.proctorinterview.question.QuestionService#ensureQuestions}
 *       is unchanged and this is what it always does, regardless of this
 *       property.</li>
 *   <li>{@code ADAPTIVE} - the next question is generated only once the
 *       previous one has been answered and evaluated, using its difficulty and
 *       Java-computed overall score as context.</li>
 * </ul>
 *
 * <h2>Why this binds a String rather than the enum</h2>
 *
 * <p>Same reasoning as {@link InterviewModeProperties}: binding the enum
 * directly would make Spring's binder fail fast on an unconvertible value,
 * turning {@code INTERVIEW_QUESTION_MODE=INVALID} into a refusal to start.
 * {@code FIXED} is the only behaviour verified end to end against real Gemini
 * (Phase 5.4); the dangerous mistake here is silently running a candidate
 * through the new, unwired adaptive path, not refusing to boot. Binding the
 * raw text and resolving it ourselves keeps an unrecognised value on the safe,
 * already-proven default, with a logged warning rather than a guess.
 */
@ConfigurationProperties(prefix = "app.interview")
public record QuestionModeProperties(String questionMode) {

    private static final Logger log = LoggerFactory.getLogger(QuestionModeProperties.class);

    /**
     * The safe default, applied when the property is absent, blank or not one
     * of the two supported values. Adaptive questioning must be opted into
     * explicitly.
     */
    public static final QuestionMode DEFAULT = QuestionMode.FIXED;

    /**
     * The configured mode, or {@link #DEFAULT} if the value is missing or not
     * recognised. Case and surrounding whitespace are forgiven, matching
     * {@link InterviewModeProperties#resolved()}.
     */
    public QuestionMode resolved() {
        if (questionMode == null || questionMode.isBlank()) {
            return DEFAULT;
        }
        try {
            return QuestionMode.valueOf(questionMode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognised app.interview.question-mode '{}' - falling back to {}. "
                    + "Supported values are FIXED and ADAPTIVE.", questionMode, DEFAULT);
            return DEFAULT;
        }
    }

    /**
     * The mode that actually applies to one interview.
     *
     * <p>An interview that names a mode wins; one that does not falls back to
     * this server-wide setting. That is what makes the column nullable
     * meaningful: {@code null} is "inherit", not "unknown-and-therefore-broken",
     * and every interview scheduled before the column existed keeps behaving
     * exactly as it did.
     */
    public QuestionMode effectiveFor(QuestionMode perInterview) {
        return perInterview != null ? perInterview : resolved();
    }

    /** True only when ADAPTIVE was explicitly and validly configured. */
    public boolean isAdaptive() {
        return resolved() == QuestionMode.ADAPTIVE;
    }
}
