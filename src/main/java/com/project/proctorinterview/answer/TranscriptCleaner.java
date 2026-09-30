package com.project.proctorinterview.answer;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.project.proctorinterview.config.TranscriptProperties;

/**
 * Removes filler words from a spoken answer so the evaluator sees the substance.
 *
 * <p>The raw transcript is never modified. Cleaning produces a separate string;
 * both are stored, so what the candidate actually said is always recoverable and
 * the cleaning can be audited or redone.
 *
 * <p>Order matters: multi-word phrases are stripped first, because removing
 * "like" on its own would leave "you know" intact but break "sort of like".
 *
 * <p>Pure and dependency-free apart from configuration, so it is exhaustively
 * unit tested without any Spring context.
 */
@Component
public class TranscriptCleaner {

    private final List<Pattern> phrasePatterns;
    private final List<Pattern> wordPatterns;

    public TranscriptCleaner(TranscriptProperties props) {
        this.phrasePatterns = props.fillerPhrases().stream()
                .map(TranscriptCleaner::phrasePattern)
                .toList();
        this.wordPatterns = props.fillerWords().stream()
                .map(TranscriptCleaner::wordPattern)
                .toList();
    }

    /**
     * @param cleanText   the answer with fillers removed and spacing normalised
     * @param fillerCount how many filler occurrences were removed
     * @param wordCount   words remaining in the cleaned text
     */
    public record CleanedTranscript(String cleanText, int fillerCount, int wordCount) {
    }

    public CleanedTranscript clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return new CleanedTranscript("", 0, 0);
        }

        String working = raw;
        int removed = 0;

        // 1. Multi-word fillers first ("you know", "sort of").
        for (Pattern pattern : phrasePatterns) {
            Matcher matcher = pattern.matcher(working);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            if (count > 0) {
                removed += count;
                working = pattern.matcher(working).replaceAll(" ");
            }
        }

        // 2. Single-word fillers ("um", "basically").
        for (Pattern pattern : wordPatterns) {
            Matcher matcher = pattern.matcher(working);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            if (count > 0) {
                removed += count;
                working = pattern.matcher(working).replaceAll(" ");
            }
        }

        // 3. Stutters and repeats: "the the API" -> "the API".
        working = collapseRepeats(working);

        // 4. Tidy the spacing left behind by the removals.
        working = working
                .replaceAll("\\s+([,.!?;:])", "$1")
                .replaceAll("([,.!?;:])\\1+", "$1")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("(?m)^[\\s,]+", "")
                .trim();

        return new CleanedTranscript(working, removed, countWords(working));
    }

    /** Collapses an immediately repeated word, case-insensitively. */
    private static String collapseRepeats(String text) {
        return Pattern.compile("\\b(\\w+)(\\s+\\1\\b)+", Pattern.CASE_INSENSITIVE)
                .matcher(text)
                .replaceAll("$1");
    }

    public static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    /**
     * Word-boundary matched and case-insensitive, so "Like" is removed but
     * "likely" and "unlike" are left alone.
     */
    private static Pattern wordPattern(String word) {
        return Pattern.compile("\\b" + Pattern.quote(word.toLowerCase(Locale.ROOT)) + "\\b",
                Pattern.CASE_INSENSITIVE);
    }

    /**
     * Allows any whitespace between the words of a phrase.
     *
     * <p>The word-boundary anchors are applied only where they mean something.
     * A phrase may begin or end with punctuation - {@code ", right"} exists to
     * catch a trailing "…, right?" without removing the word "right" wherever
     * it appears - and {@code \b} before a comma requires a word character
     * immediately before it. That holds in "hash map, right?" but not in
     * "O(n), right?", where the preceding character is ')'. Anchoring
     * unconditionally therefore made such a phrase match or not depending on
     * the punctuation that happened to precede it.
     */
    private static Pattern phrasePattern(String phrase) {
        String trimmed = phrase.trim();
        String joined = String.join("\\s+",
                java.util.Arrays.stream(trimmed.split("\\s+"))
                        .map(Pattern::quote)
                        .toArray(String[]::new));

        String prefix = isWordChar(trimmed.charAt(0)) ? "\\b" : "";
        String suffix = isWordChar(trimmed.charAt(trimmed.length() - 1)) ? "\\b" : "";
        return Pattern.compile(prefix + joined + suffix, Pattern.CASE_INSENSITIVE);
    }

    /** Matches the character class {@code \w} anchors are defined against. */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
