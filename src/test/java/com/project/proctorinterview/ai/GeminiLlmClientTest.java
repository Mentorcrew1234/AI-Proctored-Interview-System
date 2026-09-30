package com.project.proctorinterview.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestClient;

import com.project.proctorinterview.ai.dto.AiDtos.EvaluationRequest;
import com.project.proctorinterview.ai.dto.AiDtos.PriorAnswerContext;
import com.project.proctorinterview.ai.dto.AiDtos.QuestionRequest;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.config.GeminiProperties;

/**
 * The Gemini client against a stubbed transport.
 *
 * <p>What matters is that every malformed or failing response raises rather than
 * returning something plausible-looking. Silently producing a zero score, or an
 * empty question, would be worse than failing: {@link AiService} can only fall
 * back to the offline path if this layer is honest about failing.
 */
class GeminiLlmClientTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private GeminiLlmClient client;

    private static final GeminiProperties PROPS =
            new GeminiProperties("test-key", "gemini-2.5-flash", "https://example.invalid", 5);

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GeminiLlmClient(PROPS, builder.build());
    }

    private static QuestionRequest questionRequest() {
        return new QuestionRequest("Java", InterviewType.TECHNICAL, CandidateType.FRESHER, null, 3, InterviewLanguage.ENGLISH);
    }

    private static EvaluationRequest evaluationRequest() {
        return new EvaluationRequest("How would you debug a slow query?",
                List.of("EXPLAIN plan", "indexes"), "I would check the query plan",
                "Database", InterviewType.TECHNICAL, Difficulty.MEDIUM);
    }

    /** Wraps model output in Gemini's candidates/content/parts envelope. */
    private static String envelope(String modelJson) {
        return """
               {"candidates":[{"content":{"parts":[{"text":%s}]}}]}"""
                .formatted(tools.jackson.databind.json.JsonMapper.builder().build()
                        .writeValueAsString(modelJson));
    }

    // ---- availability -------------------------------------------------------

    @Test
    void isUnavailableWithoutAnApiKey() {
        assertThat(new GeminiLlmClient(
                new GeminiProperties("", "gemini-2.5-flash", "https://example.invalid", 5),
                builder.build()).isAvailable()).isFalse();

        assertThat(new GeminiLlmClient(
                new GeminiProperties(null, "gemini-2.5-flash", "https://example.invalid", 5),
                builder.build()).isAvailable()).isFalse();

        assertThat(client.isAvailable()).isTrue();
    }

    // ---- happy path ---------------------------------------------------------

    @Test
    void parsesGeneratedQuestions() {
        server.expect(requestTo(Matchers.containsString("/v1beta/models/gemini-2.5-flash:generateContent")))
                .andRespond(withSuccess(envelope("""
                        [{"text":"Your API is slow. What do you do?","difficulty":"MEDIUM",
                          "expectedPoints":["measure first","check indexes"]}]"""),
                        MediaType.APPLICATION_JSON));

        var questions = client.generateQuestions(questionRequest());

        assertThat(questions).hasSize(1);
        assertThat(questions.getFirst().text()).isEqualTo("Your API is slow. What do you do?");
        assertThat(questions.getFirst().difficulty()).isEqualTo(Difficulty.MEDIUM);
        assertThat(questions.getFirst().expectedPoints()).containsExactly("measure first", "check indexes");
        server.verify();
    }

    @Test
    void sendsTheApiKeyAndAConstrainedJsonSchema() {
        server.expect(requestTo(Matchers.containsString("key=test-key")))
                .andExpect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("\"responseMimeType\":\"application/json\"")))
                .andExpect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("responseSchema")))
                .andRespond(withSuccess(envelope("""
                        [{"text":"A question","difficulty":"EASY","expectedPoints":["a"]}]"""),
                        MediaType.APPLICATION_JSON));

        client.generateQuestions(questionRequest());
        server.verify();
    }

    @Test
    void parsesAnEvaluation() {
        server.expect(requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess(envelope("""
                        {"technicalScore":82,"relevanceScore":86,"problemSolvingScore":78,
                         "communicationScore":80,"feedback":"Good approach.",
                         "strengths":["measured first"],"weaknesses":["no index detail"]}"""),
                        MediaType.APPLICATION_JSON));

        var result = client.evaluateAnswer(evaluationRequest());

        assertThat(result.technicalScore()).isEqualTo(82);
        assertThat(result.communicationScore()).isEqualTo(80);
        assertThat(result.feedback()).isEqualTo("Good approach.");
        assertThat(result.strengths()).containsExactly("measured first");
    }

    @Test
    void forcesOutOfRangeScoresBackIntoBounds() {
        // A model can return anything; the record clamps on construction.
        server.expect(requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess(envelope("""
                        {"technicalScore":150,"relevanceScore":-20,"problemSolvingScore":0,
                         "communicationScore":100,"feedback":"x","strengths":[],"weaknesses":[]}"""),
                        MediaType.APPLICATION_JSON));

        var result = client.evaluateAnswer(evaluationRequest());

        assertThat(result.technicalScore()).isEqualTo(100);
        assertThat(result.relevanceScore()).isZero();
    }

    // ---- failure paths ------------------------------------------------------

    @Test
    void rateLimitingRaises() {
        // The free tier is ~10 requests/minute, so this is the most likely
        // failure during a real interview.
        server.expect(requestTo(Matchers.any(String.class)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.generateQuestions(questionRequest()))
                .isInstanceOf(Exception.class);
    }

    @Test
    void serverErrorRaises() {
        server.expect(requestTo(Matchers.any(String.class))).andRespond(withServerError());

        assertThatThrownBy(() -> client.evaluateAnswer(evaluationRequest()))
                .isInstanceOf(Exception.class);
    }

    @Test
    void malformedJsonRaisesRatherThanReturningEmptyScores() {
        server.expect(requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess("this is not json at all", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.evaluateAnswer(evaluationRequest()))
                .isInstanceOf(Exception.class);
    }

    @Test
    void anEnvelopeWithNoCandidatesRaises() {
        // Happens when the model refuses or the response is filtered.
        server.expect(requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generateQuestions(questionRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no candidates");
    }

    @Test
    void anEmptyQuestionListRaisesRatherThanStartingAnEmptyInterview() {
        server.expect(requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess(envelope("[]"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generateQuestions(questionRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no usable questions");
    }

    @Test
    void questionsWithBlankTextAreDiscarded() {
        server.expect(requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess(envelope("""
                        [{"text":"","difficulty":"EASY","expectedPoints":[]},
                         {"text":"A real question?","difficulty":"HARD","expectedPoints":["x"]}]"""),
                        MediaType.APPLICATION_JSON));

        var questions = client.generateQuestions(questionRequest());

        assertThat(questions).hasSize(1);
        assertThat(questions.getFirst().text()).isEqualTo("A real question?");
    }

    @Test
    void anUnknownDifficultyFallsBackToMediumInsteadOfFailing() {
        server.expect(requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess(envelope("""
                        [{"text":"A question?","difficulty":"IMPOSSIBLE","expectedPoints":[]}]"""),
                        MediaType.APPLICATION_JSON));

        assertThat(client.generateQuestions(questionRequest()).getFirst().difficulty())
                .isEqualTo(Difficulty.MEDIUM);
    }

    // ---- language ------------------------------------------------------------

    private static final String ONE_QUESTION =
            """
            [{"text":"A question","difficulty":"EASY","expectedPoints":["a"]}]""";

    @Test
    void thePromptNamesTheRequestedLanguage() {
        server.expect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("in English")))
                .andRespond(withSuccess(envelope(ONE_QUESTION), MediaType.APPLICATION_JSON));

        client.generateQuestions(new QuestionRequest("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, null, 3, InterviewLanguage.ENGLISH));

        server.verify();
    }

    /**
     * A null language must not reach the prompt as "null" - it resolves to the
     * one language the generator can actually write.
     */
    @Test
    void aMissingLanguageStillProducesAnEnglishPrompt() {
        server.expect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("in English")))
                .andRespond(withSuccess(envelope(ONE_QUESTION), MediaType.APPLICATION_JSON));

        client.generateQuestions(new QuestionRequest("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, null, 3, null));

        server.verify();
    }

    // ---- cross-interview avoid context (Phase 5.2) ---------------------------

    @Test
    void previousQuestionTextsReachThePromptWhenProvided() {
        server.expect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("A page on your team's site")))
                .andExpect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("do not repeat")))
                .andRespond(withSuccess(envelope(ONE_QUESTION), MediaType.APPLICATION_JSON));

        client.generateQuestions(new QuestionRequest("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, null, 3, InterviewLanguage.ENGLISH,
                List.of("A page on your team's site takes 8 seconds to load. How would you find the cause?")));

        server.verify();
    }

    /**
     * The common case - a new domain, or the first interview in one - must
     * produce exactly the same prompt as before this feature existed, except
     * for the diversity guidance added in Phase 5.3, which is unconditional.
     */
    @Test
    void noAvoidSectionAppearsWhenThereIsNoHistory() {
        server.expect(MockRestRequestMatchers.content()
                        .string(Matchers.not(Matchers.containsString("do not repeat"))))
                .andExpect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("Spread the questions across different areas")))
                .andRespond(withSuccess(envelope(ONE_QUESTION), MediaType.APPLICATION_JSON));

        // The pre-Phase-5.2 six-argument shape - still the common call site.
        client.generateQuestions(questionRequest());

        server.verify();
    }

    // ---- within-set topic diversity (Phase 5.3) -------------------------------

    /**
     * Everything the prompt already guaranteed before this phase must still be
     * there, alongside the new diversity guidance - this phase only adds a
     * rule, it does not replace any existing one.
     */
    @Test
    void theOriginalScenarioAndDifficultyRulesSurviveAlongsideDiversityGuidance() {
        server.expect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("concrete, realistic situation")))
                .andExpect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("Vary difficulty across the set, ordered easiest first")))
                .andExpect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("3-5 short expectedPoints")))
                .andExpect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("Spread the questions across different areas")))
                .andExpect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("debugging/troubleshooting")))
                .andRespond(withSuccess(envelope(ONE_QUESTION), MediaType.APPLICATION_JSON));

        client.generateQuestions(questionRequest()); // TECHNICAL, domain "Java"

        server.verify();
    }

    /** The category list adapts to whatever domain was actually requested. */
    @Test
    void theDiversityCategoriesNameTheRequestedDomain() {
        server.expect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("relevant to Database")))
                .andRespond(withSuccess(envelope(ONE_QUESTION), MediaType.APPLICATION_JSON));

        client.generateQuestions(new QuestionRequest("Database", InterviewType.TECHNICAL,
                CandidateType.FRESHER, null, 3, InterviewLanguage.ENGLISH));

        server.verify();
    }

    /** HR interviews get behavioural spread guidance, not technical categories. */
    @Test
    void hrInterviewsGetBehaviouralDiversityGuidanceInsteadOfTechnicalCategories() {
        server.expect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("Spread the questions across different situations")))
                .andExpect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("collaboration, communication")))
                .andExpect(MockRestRequestMatchers.content()
                        .string(Matchers.not(Matchers.containsString("debugging/troubleshooting"))))
                .andRespond(withSuccess(envelope(ONE_QUESTION), MediaType.APPLICATION_JSON));

        client.generateQuestions(new QuestionRequest("Java", InterviewType.HR_GENERAL,
                CandidateType.EXPERIENCED, 3, 5, InterviewLanguage.ENGLISH));

        server.verify();
    }

    /**
     * Phase 5.2 (avoid repeating past interviews' questions) and Phase 5.3
     * (spread within this interview's own set) answer different questions and
     * must both reach the model at once, not one replacing the other.
     */
    @Test
    void diversityGuidanceAndAvoidHistoryBothAppearWhenBothApply() {
        server.expect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("Spread the questions across different areas")))
                .andExpect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("do not repeat")))
                .andRespond(withSuccess(envelope(ONE_QUESTION), MediaType.APPLICATION_JSON));

        client.generateQuestions(new QuestionRequest("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, null, 3, InterviewLanguage.ENGLISH,
                List.of("A previously used question about caching")));

        server.verify();
    }

    /** The schema and requested count are untouched by the new guidance. */
    @Test
    void countAndSchemaAreUnchangedWhenDiversityGuidanceIsPresent() {
        server.expect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("Write exactly 3 interview questions")))
                .andExpect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("\"responseMimeType\":\"application/json\"")))
                .andExpect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("responseSchema")))
                .andRespond(withSuccess(envelope(ONE_QUESTION), MediaType.APPLICATION_JSON));

        client.generateQuestions(questionRequest());

        server.verify();
    }

    // ---- adaptive prior-answer context (Phase 6.2) ----------------------------

    @Test
    void priorAnswerContextReachesThePromptWhenProvided() {
        server.expect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("HARD difficulty")))
                .andExpect(MockRestRequestMatchers.content()
                        .string(Matchers.containsString("scored 42/100")))
                .andRespond(withSuccess(envelope(ONE_QUESTION), MediaType.APPLICATION_JSON));

        client.generateQuestions(new QuestionRequest("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, null, 1, InterviewLanguage.ENGLISH, List.of(),
                new PriorAnswerContext(Difficulty.HARD, 42)));

        server.verify();
    }

    /** No prior answer, no guidance paragraph - every pre-Phase-6.2 call site. */
    @Test
    void noPriorAnswerGuidanceAppearsWithoutPriorAnswerContext() {
        server.expect(MockRestRequestMatchers.content()
                        .string(Matchers.not(Matchers.containsString("scored"))))
                .andRespond(withSuccess(envelope(ONE_QUESTION), MediaType.APPLICATION_JSON));

        client.generateQuestions(questionRequest());

        server.verify();
    }
}
