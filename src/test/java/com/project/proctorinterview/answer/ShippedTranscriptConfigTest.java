package com.project.proctorinterview.answer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.project.proctorinterview.answer.TranscriptCleaner.CleanedTranscript;
import com.project.proctorinterview.config.TranscriptProperties;

/**
 * The filler lists that actually ship, not a fixture.
 *
 * <p>{@link TranscriptCleanerTest} builds its own lists, which is right for
 * testing the cleaning <em>mechanism</em> but means it never saw the
 * configuration the application really runs with. That is exactly how "right",
 * "okay", "like" and "actually" sat on the shipped list: every unit test passed,
 * and "that's the right approach" still reached the evaluator as "that's the
 * approach".
 *
 * <p>These tests bind the real {@code application.yml} and assert the property
 * that matters: <b>cleaning must not change what an answer means</b>, because
 * the cleaned text is what gets scored.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShippedTranscriptConfigTest {

    @Autowired
    private TranscriptCleaner cleaner;
    @Autowired
    private TranscriptProperties properties;

    /**
     * Words with a common technical meaning, which must never be stripped
     * wherever they appear. Each sentence here is one a candidate could
     * plausibly say in a real answer.
     */
    @Test
    void theShippedListNeverStripsAWordThatCarriesMeaning() {
        assertThat(cleaner.clean("that is the right approach for this").cleanText())
                .contains("right approach");
        assertThat(cleaner.clean("the performance was okay under load").cleanText())
                .contains("was okay");
        assertThat(cleaner.clean("an interface works like a contract").cleanText())
                .contains("works like a contract");
        assertThat(cleaner.clean("it actually returns null here").cleanText())
                .contains("actually returns null");
        assertThat(cleaner.clean("just one thread writes to it").cleanText())
                .contains("just one thread");
        assertThat(cleaner.clean("so the query runs twice").cleanText())
                .contains("so the query runs twice");
        assertThat(cleaner.clean("it handles concurrency well").cleanText())
                .contains("handles concurrency well");
        assertThat(cleaner.clean("that lookup is really slow").cleanText())
                .contains("really slow");
    }

    /** The same words, in the one position where they can only be hesitation. */
    @Test
    void theShippedListStillCatchesTrailingHesitation() {
        assertThat(cleaner.clean("it is O(n), right?").cleanText())
                .isEqualTo("it is O(n)?");
        assertThat(cleaner.clean("we index the column, okay?").cleanText())
                .isEqualTo("we index the column?");
    }

    @Test
    void theShippedListRemovesUnambiguousHesitation() {
        CleanedTranscript result = cleaner.clean(
                "um so uh I would basically hmm look at the index, you know");

        assertThat(result.cleanText().toLowerCase())
                .doesNotContain("um ")
                .doesNotContain("uh ")
                .doesNotContain("hmm")
                .doesNotContain("basically")
                .doesNotContain("you know");
        assertThat(result.cleanText()).contains("look at the index");
        assertThat(result.fillerCount()).isGreaterThanOrEqualTo(5);
    }

    /**
     * A guard rather than a behaviour test: if one of these is ever added back
     * to the word list, this fails and points at the reason.
     */
    @Test
    void theWordListExcludesEveryAmbiguousWord() {
        assertThat(properties.fillerWords())
                .as("a word removed anywhere must have no common technical meaning; "
                        + "put position-sensitive ones in fillerPhrases instead")
                .doesNotContain("like", "right", "okay", "actually", "just", "so",
                        "well", "really", "think", "mean", "sure", "fine");
    }

    @Test
    void theShippedListIsSubstantialEnoughToBeWorthHaving() {
        // Not a quality measure - only a check that the config was not lost or
        // emptied by a bad merge, which would silently disable cleaning.
        assertThat(properties.fillerWords()).hasSizeGreaterThan(8);
        assertThat(properties.fillerPhrases()).hasSizeGreaterThan(8);
    }

    @Test
    void cleaningAnAnswerWithNoFillersChangesNothing() {
        String precise = "I would add a composite index on user_id and created_at, "
                + "then measure the query plan again.";

        assertThat(cleaner.clean(precise).cleanText()).isEqualTo(precise);
        assertThat(cleaner.clean(precise).fillerCount()).isZero();
    }
}
