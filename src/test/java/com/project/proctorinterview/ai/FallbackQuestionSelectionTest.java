package com.project.proctorinterview.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.project.proctorinterview.ai.dto.AiDtos.GeneratedQuestion;
import com.project.proctorinterview.ai.dto.AiDtos.QuestionRequest;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewType;

/**
 * How the offline bank picks a candidate's questions.
 *
 * <p>The defect these tests were written for: selection used to be
 * {@code pool.get(i % pool.size())}, so asking for more questions than the pool
 * held silently re-asked question 1 as question 6. A duplicated question is
 * worse than a shorter interview - it is asked twice, graded twice, and counted
 * twice in the divisor, which misrepresents the candidate in both directions.
 *
 * <p>Selection is now without replacement, ordered easiest first, and drawn from
 * a difficulty band chosen by the candidate's experience.
 */
class FallbackQuestionSelectionTest {

    private FallbackLlmClient fallback;

    @BeforeEach
    void setUp() {
        fallback = new FallbackLlmClient();
        fallback.loadBank();
    }

    private static QuestionRequest request(CandidateType type, Integer years, int count) {
        return new QuestionRequest("Java", InterviewType.TECHNICAL, type, years, count, InterviewLanguage.ENGLISH);
    }

    private static QuestionRequest requestForDomain(String domain, int count) {
        return new QuestionRequest(domain, InterviewType.TECHNICAL, CandidateType.FRESHER,
                null, count, InterviewLanguage.ENGLISH);
    }

    private static List<String> textsOf(List<GeneratedQuestion> questions) {
        return questions.stream().map(GeneratedQuestion::text).toList();
    }

    // ---- an unknown domain --------------------------------------------------
    //
    // Domains became free text, so a recruiter may schedule "Rust and
    // WebAssembly" and the offline bank will have nothing curated for it. That
    // is now the normal case rather than an oddity.

    @Test
    void anUnknownDomainStillProducesAFullSetOfQuestions() {
        var questions = fallback.generateQuestions(requestForDomain("Rust and WebAssembly", 5));

        assertThat(questions).hasSize(5);
        assertThat(textsOf(questions)).doesNotHaveDuplicates();
    }

    /**
     * The general set is domain-neutral, and that is the point of it.
     *
     * <p>An unknown domain used to fall through to "Software Engineering",
     * which is a domain in its own right rather than a neutral one - so a Rust
     * candidate was quietly asked about estimation and code review as though
     * that had been the choice. The {@code _default} set names no language or
     * framework at all.
     */
    @Test
    void anUnknownDomainGetsTheGeneralSetRatherThanSomeOtherDomainsQuestions() {
        var unknown = textsOf(fallback.generateQuestions(requestForDomain("Rust and WebAssembly", 12)));
        var softwareEngineering =
                textsOf(fallback.generateQuestions(requestForDomain("Software Engineering", 12)));

        assertThat(unknown).isNotEqualTo(softwareEngineering);
        assertThat(unknown).noneSatisfy(text ->
                assertThat(text).containsIgnoringCase("java"));
    }

    @Test
    void aKnownDomainStillGetsItsOwnQuestions() {
        var java = textsOf(fallback.generateQuestions(requestForDomain("Java", 12)));
        var unknown = textsOf(fallback.generateQuestions(requestForDomain("Nothing We Cover", 12)));

        assertThat(java).isNotEqualTo(unknown);
    }

    private static int rank(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> 0;
            case MEDIUM -> 1;
            case HARD -> 2;
        };
    }

    // ---- the defect ---------------------------------------------------------

    /**
     * The regression test for the exact reported defect: 10 questions from a
     * bank that used to hold 5 produced Q6..Q10 as copies of Q1..Q5.
     */
    @Test
    void askingForTenQuestionsNeverRepeatsOne() {
        var questions = fallback.generateQuestions(request(CandidateType.FRESHER, null, 10));

        assertThat(questions).hasSize(10);
        assertThat(textsOf(questions)).doesNotHaveDuplicates();
    }

    /** The same guarantee for every candidate shape, at the maximum count. */
    @Test
    void noCandidateEverReceivesTheSameQuestionTwice() {
        List<QuestionRequest> shapes = List.of(
                request(CandidateType.FRESHER, null, 10),
                request(CandidateType.EXPERIENCED, 1, 10),
                request(CandidateType.EXPERIENCED, 3, 10),
                request(CandidateType.EXPERIENCED, 9, 10));

        for (QuestionRequest shape : shapes) {
            var questions = fallback.generateQuestions(shape);
            assertThat(textsOf(questions))
                    .as("candidate %s / %s years", shape.candidateType(), shape.experienceYears())
                    .doesNotHaveDuplicates();
        }
    }

    /**
     * The honest-shortfall rule, and the strongest statement of the fix.
     *
     * <p>Asked for more than the whole pool can supply, selection returns every
     * distinct question it has and stops. The old modulo would have padded to
     * the requested number by repeating. Fewer questions is safe because the
     * report divides by the questions that actually exist
     * ({@code ReportService} passes {@code questions.size()}), so a shorter
     * interview still scores out of what was asked.
     */
    @Test
    void askingForMoreThanTheWholePoolReturnsEveryDistinctQuestionAndNoPadding() {
        // Deliberately not a fixed size: the bank grows, and hard-coding its
        // current depth made this fail for the good reason that more questions
        // had been added. What must hold is that asking for more than exists
        // returns everything once, and never pads by repeating.
        int poolSize = fallback.generateQuestions(request(CandidateType.FRESHER, null, 500)).size();
        var questions = fallback.generateQuestions(request(CandidateType.FRESHER, null, 500));

        assertThat(questions).hasSize(poolSize);
        assertThat(poolSize)
                .as("the offline bank should hold a usable number of questions per domain")
                .isGreaterThanOrEqualTo(10);
        assertThat(textsOf(questions)).doesNotHaveDuplicates();
    }

    @Test
    void askingForFewerQuestionsThanThePoolHoldsReturnsDistinctOnes() {
        var questions = fallback.generateQuestions(request(CandidateType.FRESHER, null, 5));

        assertThat(questions).hasSize(5);
        assertThat(textsOf(questions)).doesNotHaveDuplicates();
    }

    // ---- experience-aware selection -----------------------------------------

    /** A fresher is asked beginner and intermediate questions, never only hard ones. */
    @Test
    void aFresherIsAskedBeginnerAndIntermediateQuestions() {
        var questions = fallback.generateQuestions(request(CandidateType.FRESHER, null, 5));

        assertThat(questions).extracting(GeneratedQuestion::difficulty)
                .containsOnly(Difficulty.EASY, Difficulty.MEDIUM);
        assertThat(questions).extracting(GeneratedQuestion::difficulty)
                .contains(Difficulty.EASY);
    }

    /** An early-career professional starts from the intermediate band. */
    @Test
    void anEarlyCareerCandidateIsAskedIntermediateQuestionsFirst() {
        var questions = fallback.generateQuestions(request(CandidateType.EXPERIENCED, 2, 4));

        assertThat(questions).extracting(GeneratedQuestion::difficulty)
                .containsOnly(Difficulty.MEDIUM);
    }

    /** A senior candidate gets the intermediate and advanced band. */
    @Test
    void aSeniorCandidateIsAskedTheHarderBand() {
        var questions = fallback.generateQuestions(request(CandidateType.EXPERIENCED, 8, 6));

        assertThat(questions).extracting(GeneratedQuestion::difficulty)
                .containsOnly(Difficulty.MEDIUM, Difficulty.HARD);
        assertThat(questions).extracting(GeneratedQuestion::difficulty)
                .contains(Difficulty.HARD);
    }

    /** The point of the whole feature: experience actually changes the set. */
    @Test
    void differentExperienceLevelsProduceDifferentQuestions() {
        var fresher = textsOf(fallback.generateQuestions(request(CandidateType.FRESHER, null, 5)));
        var senior = textsOf(fallback.generateQuestions(request(CandidateType.EXPERIENCED, 8, 5)));

        assertThat(fresher).isNotEqualTo(senior);
    }

    /** A missing year count must not throw; it is treated as early career. */
    @Test
    void anExperiencedCandidateWithNoYearsRecordedStillGetsQuestions() {
        var questions = fallback.generateQuestions(request(CandidateType.EXPERIENCED, null, 5));

        assertThat(questions).hasSize(5);
        assertThat(textsOf(questions)).doesNotHaveDuplicates();
    }

    // ---- progression --------------------------------------------------------

    /**
     * Easiest first, including when the band had to be widened to fill the
     * interview - a widened set must still climb rather than drop back down.
     */
    @Test
    void questionsAreOrderedEasiestFirst() {
        for (int count : new int[] { 5, 8, 10 }) {
            var questions = fallback.generateQuestions(request(CandidateType.FRESHER, null, count));

            List<Integer> ranks = questions.stream().map(q -> rank(q.difficulty())).toList();
            assertThat(ranks).as("count %d", count).isSorted();
        }
    }

    @Test
    void aWidenedSeniorSelectionStillClimbs() {
        var questions = fallback.generateQuestions(request(CandidateType.EXPERIENCED, 8, 10));

        List<Integer> ranks = questions.stream().map(q -> rank(q.difficulty())).toList();
        assertThat(ranks).isSorted();
        assertThat(textsOf(questions)).doesNotHaveDuplicates();
    }

    // ---- existing behaviour that must not regress ---------------------------

    @Test
    void hrInterviewsStillUseTheDomainIndependentSet() {
        var questions = fallback.generateQuestions(new QuestionRequest(
                "Java", InterviewType.HR_GENERAL, CandidateType.EXPERIENCED, 3, 5, InterviewLanguage.ENGLISH));

        assertThat(questions).hasSize(5);
        assertThat(textsOf(questions)).doesNotHaveDuplicates();
        // Behavioural, not technical: the HR pool is domain independent.
        assertThat(questions.getFirst().text()).isNotBlank();
    }

    @Test
    void anUnknownDomainStillFallsBackToAGeneralSet() {
        var questions = fallback.generateQuestions(new QuestionRequest(
                "Quantum Basket Weaving", InterviewType.TECHNICAL, CandidateType.FRESHER, null, 5, InterviewLanguage.ENGLISH));

        assertThat(questions).hasSize(5);
        assertThat(textsOf(questions)).doesNotHaveDuplicates();
    }

    @Test
    void everyKnownDomainCanFillTheLargestAllowedInterview() {
        List<String> domains = List.of("Java", "Python", "Web Development", "Database",
                "Software Engineering", "Data Structures & Algorithms");

        for (String domain : domains) {
            var questions = fallback.generateQuestions(new QuestionRequest(
                    domain, InterviewType.TECHNICAL, CandidateType.FRESHER, null, 10, InterviewLanguage.ENGLISH));

            assertThat(questions).as("domain %s", domain).hasSize(10);
            assertThat(textsOf(questions)).as("domain %s", domain).doesNotHaveDuplicates();
        }
    }

    /** Bank entries are mapped whole: text, difficulty and the grading rubric. */
    @Test
    void bankEntriesAreMappedWithTheirExpectedPoints() {
        var questions = fallback.generateQuestions(request(CandidateType.FRESHER, null, 5));

        assertThat(questions).allSatisfy(q -> {
            assertThat(q.text()).isNotBlank();
            assertThat(q.difficulty()).isNotNull();
            // expectedPoints double as the fallback scorer's rubric, so an entry
            // without them would silently make that answer ungradeable.
            assertThat(q.expectedPoints()).isNotEmpty();
        });
    }

    @Test
    void theBankStillNeverAsksDefinitionQuestions() {
        for (int count : new int[] { 5, 10 }) {
            var questions = fallback.generateQuestions(request(CandidateType.EXPERIENCED, 6, count));

            assertThat(questions).allSatisfy(q -> {
                String text = q.text().toLowerCase();
                assertThat(text).doesNotStartWith("what is");
                assertThat(text).doesNotStartWith("define");
                assertThat(text).doesNotContain("explain the difference between");
            });
        }
    }

    /** An interview type with no pool at all is still a hard failure, as before. */
    @Test
    void aCompletelyEmptyBankStillFailsLoudly() {
        FallbackLlmClient empty = new FallbackLlmClient();
        // Deliberately not calling loadBank(): no pool exists for anything.

        assertThatThrownBy(() -> empty.generateQuestions(request(CandidateType.FRESHER, null, 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No fallback questions available");
    }

    // ---- provenance and evaluation are untouched ----------------------------

    @Test
    void theFallbackStillIdentifiesItselfAsTheOfflineBank() {
        assertThat(fallback.modelName()).isEqualTo("offline-fallback");
        assertThat(fallback.isAvailable()).isTrue();
    }
}
