package com.project.proctorinterview.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.project.proctorinterview.answer.TranscriptCleaner.CleanedTranscript;
import com.project.proctorinterview.config.TranscriptProperties;

/**
 * Filler removal, tested without Spring.
 *
 * <p>The rule that matters most: the raw transcript is evidence of what the
 * candidate actually said, so cleaning must never damage it, and must never
 * remove real words that merely contain a filler as a substring.
 */
class TranscriptCleanerTest {

    private TranscriptCleaner cleaner;

    @BeforeEach
    void setUp() {
        // Mirrors the shipped application.yml rather than inventing a list, so
        // these tests describe what actually runs. Note what is NOT here:
        // "like", "right", "okay" and "actually" were removed because each has
        // a common technical meaning and this cleaner strips a word anywhere it
        // appears - see ShippedTranscriptConfigTest.
        cleaner = new TranscriptCleaner(new TranscriptProperties(
                List.of("you know", "sort of", "kind of", "i mean", "you see",
                        ", right", ", okay"),
                List.of("um", "uh", "hmm", "er", "ah",
                        "basically", "literally", "obviously", "essentially")));
    }

    @Test
    void removesSingleWordFillers() {
        CleanedTranscript result = cleaner.clean("um I would basically use uh an interface");

        assertThat(result.cleanText()).isEqualTo("I would use an interface");
        assertThat(result.fillerCount()).isEqualTo(3);
    }

    @Test
    void removesMultiWordFillersBeforeSingleWords() {
        // "sort of" must go as a phrase; handling "of" and "sort" separately
        // would leave debris behind.
        CleanedTranscript result = cleaner.clean("It is sort of a queue, you know, for tasks");

        assertThat(result.cleanText()).doesNotContain("sort of").doesNotContain("you know");
        assertThat(result.cleanText()).contains("queue").contains("tasks");
    }

    @Test
    void doesNotTouchRealWordsThatContainAFiller() {
        // "likely", "unlike", "actual", "rightly" all embed filler words.
        CleanedTranscript result =
                cleaner.clean("That is likely the actual cause, unlike the other theory");

        assertThat(result.cleanText())
                .contains("likely")
                .contains("actual")
                .contains("unlike");
        assertThat(result.fillerCount()).isZero();
    }

    @Test
    void isCaseInsensitiveButPreservesTheRestOfTheCasing() {
        CleanedTranscript result = cleaner.clean("Um, Basically the API returns JSON");

        assertThat(result.cleanText()).isEqualTo("the API returns JSON");
    }

    @Test
    void collapsesStutteredRepeats() {
        CleanedTranscript result = cleaner.clean("I would would use the the database");

        assertThat(result.cleanText()).isEqualTo("I would use the database");
    }

    @Test
    void tidiesPunctuationAndSpacingLeftBehind() {
        CleanedTranscript result = cleaner.clean("So , um ,  I  would   use a  cache .");

        assertThat(result.cleanText()).doesNotContain("  ");
        assertThat(result.cleanText()).doesNotContain(" ,");
        assertThat(result.cleanText()).contains("I would use a cache");
    }

    @Test
    void countsWordsInTheCleanedTextOnly() {
        CleanedTranscript result = cleaner.clean("um uh I would use a cache");

        assertThat(result.wordCount()).isEqualTo(5);
        assertThat(result.fillerCount()).isEqualTo(2);
    }

    @Test
    void handlesAnAnswerThatIsEntirelyFiller() {
        CleanedTranscript result = cleaner.clean("um uh hmm basically er");

        assertThat(result.cleanText()).isEmpty();
        assertThat(result.wordCount()).isZero();
        assertThat(result.fillerCount()).isEqualTo(5);
    }

    @Test
    void handlesEmptyAndNullInput() {
        assertThat(cleaner.clean(null).cleanText()).isEmpty();
        assertThat(cleaner.clean("").cleanText()).isEmpty();
        assertThat(cleaner.clean("   ").cleanText()).isEmpty();
        assertThat(cleaner.clean(null).wordCount()).isZero();
    }

    @Test
    void leavesACleanAnswerUnchanged() {
        String answer = "I would add an index on the user_id column and measure the query plan first.";

        CleanedTranscript result = cleaner.clean(answer);

        assertThat(result.cleanText()).isEqualTo(answer);
        assertThat(result.fillerCount()).isZero();
    }

    @Test
    void preservesTechnicalTermsAndSymbols() {
        String answer = "Use O(n log n) sorting, then a HashMap<String, List<Integer>> for lookups.";

        assertThat(cleaner.clean(answer).cleanText()).isEqualTo(answer);
    }

    @Test
    void preservesNonAsciiText() {
        String answer = "The naive approach is O(n²) — I would use a café-style queue instead";

        assertThat(cleaner.clean(answer).cleanText()).contains("O(n²)").contains("café");
    }

    @Test
    void handlesALongRealisticSpokenAnswer() {
        String raw = """
                So um, basically what I would do is, you know, first I would actually \
                look at the the query plan, right, because like the index might be \
                missing. I mean, if the table has grown a lot then uh a full scan \
                would be slow.""";

        CleanedTranscript result = cleaner.clean(raw);

        assertThat(result.fillerCount()).isGreaterThan(4);
        assertThat(result.cleanText())
                .contains("query plan")
                .contains("index")
                .contains("full scan");
        // Every filler on the list is gone, as whole words.
        assertThat(result.cleanText().toLowerCase()).doesNotMatch(".*\\bum\\b.*");
        assertThat(result.cleanText().toLowerCase()).doesNotMatch(".*\\bbasically\\b.*");
        assertThat(result.cleanText().toLowerCase()).doesNotMatch(".*\\byou know\\b.*");
        // "right" trailing a clause is caught by the phrase list.
        assertThat(result.cleanText().toLowerCase()).doesNotMatch(".*,\\s*right\\b.*");
        // But "like" and "actually" survive, deliberately: removing them
        // wherever they appear would break "works like a contract" and
        // "actually returns null", and the cleaned text is what the evaluator
        // scores. See meaningfulWordsAreNeverRemoved below.
        assertThat(result.cleanText().toLowerCase()).contains("like");
        assertThat(result.cleanText().toLowerCase()).contains("actually");
    }

    // ---- the rule that decides what may be on the list ----------------------

    /**
     * The reason four words were taken off the filler list.
     *
     * <p>{@code clean()} strips a listed word <b>anywhere</b> it appears, and
     * the cleaned text is what the evaluator scores - so a word with any common
     * technical meaning must not be listed, however often it is also a filler.
     * Before this change "that's the right approach" reached the evaluator as
     * "that's the approach".
     */
    @Test
    void meaningfulWordsAreNeverRemoved() {
        assertThat(cleaner.clean("that is the right approach").cleanText())
                .isEqualTo("that is the right approach");
        assertThat(cleaner.clean("the performance was okay").cleanText())
                .isEqualTo("the performance was okay");
        assertThat(cleaner.clean("an interface works like a contract").cleanText())
                .isEqualTo("an interface works like a contract");
        assertThat(cleaner.clean("it actually returns null").cleanText())
                .isEqualTo("it actually returns null");
    }

    /**
     * The same words ARE removed where they can only be hesitation - trailing a
     * clause. This is why they live in the phrase list rather than nowhere.
     */
    @Test
    void theSameWordsAreRemovedWhereTheyCanOnlyBeFiller() {
        CleanedTranscript trailing = cleaner.clean("it is a hash map, right?");

        assertThat(trailing.cleanText()).isEqualTo("it is a hash map?");
        assertThat(trailing.fillerCount()).isEqualTo(1);
    }

    /**
     * Regression: a phrase beginning with punctuation used to match or not
     * depending on the character before it.
     *
     * <p>{@code phrasePattern} anchored every phrase with {@code \b}, and
     * {@code \b} before a comma needs a word character immediately before the
     * comma. "hash map, right?" has one; "O(n), right?" has ')' and did not, so
     * the identical hesitation survived after a bracket, a quote or a digit in
     * parentheses - which is most of how a technical answer is punctuated.
     */
    @Test
    void aTrailingFillerIsCaughtWhateverPrecedesIt() {
        assertThat(cleaner.clean("it is O(n), right?").cleanText()).isEqualTo("it is O(n)?");
        assertThat(cleaner.clean("it is a hash map, right?").cleanText())
                .isEqualTo("it is a hash map?");
        assertThat(cleaner.clean("we cache it 5 minutes, right?").cleanText())
                .isEqualTo("we cache it 5 minutes?");
    }

    @Test
    void aWordAndItsTrailingFormAreCountedSeparately() {
        // "right" as a real word is kept and not counted; the trailing one goes.
        CleanedTranscript mixed = cleaner.clean("that is the right call, right?");

        assertThat(mixed.cleanText()).isEqualTo("that is the right call?");
        assertThat(mixed.fillerCount()).isEqualTo(1);
    }
}
