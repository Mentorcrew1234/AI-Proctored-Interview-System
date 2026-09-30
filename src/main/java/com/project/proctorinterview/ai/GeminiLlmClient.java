package com.project.proctorinterview.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.project.proctorinterview.ai.dto.AiDtos.EvaluationRequest;
import com.project.proctorinterview.ai.dto.AiDtos.EvaluationResult;
import com.project.proctorinterview.ai.dto.AiDtos.GeneratedQuestion;
import com.project.proctorinterview.ai.dto.AiDtos.PriorAnswerContext;
import com.project.proctorinterview.ai.dto.AiDtos.QuestionRequest;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.config.GeminiProperties;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Google Gemini via its REST API.
 *
 * <p>Called with a plain {@link RestClient} rather than an SDK: one less
 * dependency to keep compatible with Spring Boot 4, and the request body is
 * visible in the source, which matters for a project that has to be explained.
 *
 * <p>Both calls use {@code responseMimeType: application/json} together with a
 * {@code responseSchema}, so the model is constrained to the exact shape we
 * parse instead of us pattern-matching prose.
 *
 * <p>Free tier is roughly 10 requests/minute and 250/day. An interview costs
 * one call for questions plus one per answer, so about 6 for a 5-question
 * interview. Any failure here is not fatal - {@link AiService} falls back.
 */
@Component
public class GeminiLlmClient implements LlmClient {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final GeminiProperties props;
    private final RestClient restClient;

    // Explicit, because the test-only constructor below makes the choice ambiguous.
    @Autowired
    public GeminiLlmClient(GeminiProperties props) {
        this(props, defaultRestClient(props));
    }

    /** Lets tests supply a stubbed transport. */
    GeminiLlmClient(GeminiProperties props, RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
    }

    private static RestClient defaultRestClient(GeminiProperties props) {
        // Explicit timeouts matter here: without them a hung request would stall
        // the candidate's interview instead of falling back to offline scoring.
        Duration timeout = Duration.ofSeconds(props.timeoutSeconds());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);

        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public String modelName() {
        return props.model();
    }

    @Override
    public boolean isAvailable() {
        return props.configured();
    }

    // ---- question generation ------------------------------------------------

    @Override
    public List<GeneratedQuestion> generateQuestions(QuestionRequest request) {
        String prompt = questionPrompt(request);
        Map<String, Object> schema = Map.of(
                "type", "ARRAY",
                "items", Map.of(
                        "type", "OBJECT",
                        "properties", Map.of(
                                "text", Map.of("type", "STRING"),
                                "difficulty", Map.of("type", "STRING",
                                        "enum", List.of("EASY", "MEDIUM", "HARD")),
                                "expectedPoints", Map.of(
                                        "type", "ARRAY",
                                        "items", Map.of("type", "STRING"))),
                        "required", List.of("text", "difficulty", "expectedPoints")));

        String json = call(prompt, schema);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> raw = MAPPER.readValue(json, List.class);

        List<GeneratedQuestion> questions = new ArrayList<>();
        for (Map<String, Object> item : raw) {
            String text = str(item.get("text"));
            if (text == null || text.isBlank()) {
                continue;
            }
            questions.add(new GeneratedQuestion(
                    text.trim(),
                    parseDifficulty(str(item.get("difficulty"))),
                    stringList(item.get("expectedPoints"))));
        }
        if (questions.isEmpty()) {
            throw new IllegalStateException("Gemini returned no usable questions");
        }
        return questions;
    }

    private String questionPrompt(QuestionRequest r) {
        String experience = r.candidateType() == CandidateType.EXPERIENCED
                ? "an experienced candidate with about %d year(s) of professional experience"
                        .formatted(r.experienceYears() == null ? 1 : r.experienceYears())
                : "a fresher with no professional experience, only academic projects";

        String kind = r.interviewType() == InterviewType.TECHNICAL
                ? """
                  Focus on practical engineering judgement in %s: designing, debugging, \
                  trade-offs and handling real situations.""".formatted(r.domain())
                : """
                  Focus on workplace behaviour: collaboration, communication, handling \
                  pressure, conflict and ownership. Keep it grounded in situations a \
                  %s engineer would actually meet.""".formatted(r.domain());

        return """
               You are conducting a %s interview for %s.

               %s

               Write exactly %d interview questions in %s.

               Rules:
               - Every question must describe a concrete, realistic situation and ask \
               what the candidate would DO. Application over recall.
               - Never ask a definition question. Do not write "What is X?", \
               "Define X", "Explain the difference between X and Y", or \
               "List the features of X".
               - Each question is 1-3 sentences and self-contained. No sub-parts.
               - Vary difficulty across the set, ordered easiest first.
               - %s
               - For each question give 3-5 short expectedPoints: the specific ideas a \
               strong answer would mention. These are used to grade the answer, so make \
               them concrete and checkable, not vague.
               - Plain text only. No markdown, numbering or preamble.

               Good example: "A page on your team's site has started taking 8 seconds to \
               load, but only for some users. How would you find the cause?"
               Bad example: "What is caching?"
               %s
               %s
               """
                .formatted(
                        r.interviewType() == InterviewType.TECHNICAL ? "technical" : "HR",
                        experience,
                        kind,
                        r.count(),
                        // resolvedLanguage(), not language(): the prompt states the
                        // language it can actually write, so it never promises one
                        // the model was not asked for.
                        languageName(r.resolvedLanguage()),
                        diversityGuidance(r),
                        avoidSection(r.avoidQuestionTexts()),
                        priorAnswerGuidance(r.priorAnswer()));
    }

    /**
     * Spreads the generated set across different areas instead of letting
     * several questions cluster around the same underlying archetype -
     * variety this codebase's own tests found missing even though difficulty
     * already varied within a set (see docs/system/quality/LIMITATIONS.md, "Question
     * archetypes can repeat").
     *
     * <p>The category list is a guide, not a lookup table: which of them
     * actually apply is left to the model, because a fixed domain-to-category
     * mapping would need updating for every new domain a recruiter types in,
     * where the model already knows what is relevant to "Database" versus
     * "Web Development".
     */
    private static String diversityGuidance(QuestionRequest r) {
        return r.interviewType() == InterviewType.TECHNICAL
                ? """
                  Spread the questions across different areas rather than concentrating \
                  on one topic - draw from whichever of these are relevant to %s: \
                  debugging/troubleshooting, design/trade-offs, performance, \
                  concurrency/reliability, data/storage decisions, production incidents, \
                  and testing/maintainability. Do not write more than one question from \
                  the same underlying archetype.""".formatted(r.domain())
                : """
                  Spread the questions across different situations rather than \
                  concentrating on one - for example collaboration, communication, \
                  handling pressure, conflict and ownership. Do not write more than one \
                  question from the same underlying archetype.""";
    }

    /**
     * A bounded "do not repeat these" reminder, built from previous questions
     * for the same domain/type/candidate-type/language -
     * {@code QuestionRepository.findRecentTextsByContext} already limits how
     * many are ever passed in, so this only formats them; empty when there is
     * no history yet, which is the common case for a new domain.
     */
    private static String avoidSection(List<String> avoidQuestionTexts) {
        if (avoidQuestionTexts.isEmpty()) {
            return "";
        }
        String bullets = avoidQuestionTexts.stream()
                .map(text -> "- " + text)
                .collect(java.util.stream.Collectors.joining("\n"));
        return """

               These questions were already used for this kind of interview. Write \
               genuinely different scenarios - do not repeat or closely paraphrase any \
               of them:
               %s
               """.formatted(bullets);
    }

    /**
     * Adaptive generation's only signal (Phase 6.2): what the previous question
     * was rated and how the candidate actually did on it, as Java's scoring
     * already computed. Deliberately a judgement call for the model rather than
     * a fixed score-to-difficulty rule - a rigid table would need tuning nobody
     * has evidence for yet, whereas "harder if they did well, easier if they
     * struggled" is a direction, not a formula.
     *
     * <p>Empty whenever {@code priorAnswer} is null, which is every fixed-mode
     * call and the first question of any interview - so this is a no-op for
     * every caller that existed before Phase 6.2.
     */
    private static String priorAnswerGuidance(PriorAnswerContext priorAnswer) {
        if (priorAnswer == null) {
            return "";
        }
        return """

               The previous question was %s difficulty, and the candidate scored %d/100 \
               on it (already graded by this system, not by you). Use that as a signal for \
               how hard or deep this next question should be relative to the previous one - \
               strong performance can generally allow a harder or more in-depth question, \
               weak performance can generally allow an easier, more fundamental or \
               clarifying one. Use your own judgement rather than a fixed rule, and this \
               question still only counts toward the requested count and difficulty label \
               above.
               """.formatted(priorAnswer.previousDifficulty().name(), priorAnswer.previousOverallScore());
    }

    /** Prompt-facing name for a language the generator supports. */
    private static String languageName(InterviewLanguage language) {
        return language == InterviewLanguage.ENGLISH ? "English" : language.name();
    }

    // ---- answer evaluation --------------------------------------------------

    @Override
    public EvaluationResult evaluateAnswer(EvaluationRequest request) {
        Map<String, Object> intScore = Map.of("type", "INTEGER");
        Map<String, Object> schema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "technicalScore", intScore,
                        "relevanceScore", intScore,
                        "problemSolvingScore", intScore,
                        "communicationScore", intScore,
                        "feedback", Map.of("type", "STRING"),
                        "strengths", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "weaknesses", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("technicalScore", "relevanceScore", "problemSolvingScore",
                        "communicationScore", "feedback", "strengths", "weaknesses"));

        String json = call(evaluationPrompt(request), schema);
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = MAPPER.readValue(json, Map.class);

        return new EvaluationResult(
                intOf(raw.get("technicalScore")),
                intOf(raw.get("relevanceScore")),
                intOf(raw.get("problemSolvingScore")),
                intOf(raw.get("communicationScore")),
                str(raw.get("feedback")),
                stringList(raw.get("strengths")),
                stringList(raw.get("weaknesses")));
    }

    private String evaluationPrompt(EvaluationRequest r) {
        return """
               You are grading one answer from a %s interview in %s.

               QUESTION:
               %s

               POINTS A STRONG ANSWER WOULD COVER:
               %s

               CANDIDATE'S ANSWER (transcribed from speech, filler words removed):
               %s

               Score each dimension 0-100:
               - technicalScore: correctness and depth of the domain reasoning.
               - relevanceScore: did they answer THIS question, and cover the points above.
               - problemSolvingScore: quality of approach, structure, trade-offs considered.
               - communicationScore: clarity and organisation of the explanation.

               Then write:
               - feedback: 2-3 sentences addressed to the candidate, specific to what \
               they actually said.
               - strengths: 1-3 short phrases. Empty array if there are genuinely none.
               - weaknesses: 1-3 short phrases naming what was missing.

               Grading rules:
               - This is a spoken answer. Judge the substance, not the grammar, and do \
               not penalise transcription errors or informal phrasing.
               - An empty, off-topic or content-free answer scores below 20. Do not be \
               generous to fill the scale.
               - Be specific. Never write generic feedback that would fit any answer.
               """
                .formatted(
                        r.interviewType() == InterviewType.TECHNICAL ? "technical" : "HR",
                        r.domain(),
                        r.questionText(),
                        r.expectedPoints() == null || r.expectedPoints().isEmpty()
                                ? "(none supplied)"
                                : "- " + String.join("\n- ", r.expectedPoints()),
                        r.cleanTranscript() == null || r.cleanTranscript().isBlank()
                                ? "(the candidate did not answer)"
                                : r.cleanTranscript());
    }

    // ---- transport ----------------------------------------------------------

    /** One generateContent call, returning the raw JSON text the model produced. */
    private String call(String prompt, Map<String, Object> responseSchema) {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "temperature", 0.7,
                        "responseMimeType", "application/json",
                        "responseSchema", responseSchema));

        String response = restClient.post()
                .uri("/v1beta/models/{model}:generateContent?key={key}", props.model(), props.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        return extractText(response);
    }

    /** Digs the generated text out of Gemini's candidates/content/parts envelope. */
    @SuppressWarnings("unchecked")
    private static String extractText(String response) {
        Map<String, Object> root = MAPPER.readValue(response, Map.class);
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) root.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalStateException("Gemini response contained no candidates");
        }
        Map<String, Object> content = (Map<String, Object>) candidates.getFirst().get("content");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            throw new IllegalStateException("Gemini response contained no parts");
        }
        String text = str(parts.getFirst().get("text"));
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Gemini returned empty text");
        }
        return text;
    }

    // ---- lenient parsing ----------------------------------------------------

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intOf(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        return List.of();
    }

    private static Difficulty parseDifficulty(String value) {
        try {
            return Difficulty.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (RuntimeException e) {
            return Difficulty.MEDIUM;
        }
    }

}
