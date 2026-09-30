package com.project.proctorinterview.ai;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.project.proctorinterview.ai.dto.AiDtos.EvaluationRequest;
import com.project.proctorinterview.ai.dto.AiDtos.EvaluationResult;
import com.project.proctorinterview.ai.dto.AiDtos.GeneratedQuestion;
import com.project.proctorinterview.ai.dto.AiDtos.QuestionRequest;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.InterviewType;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offline provider: curated questions plus a transparent heuristic scorer.
 *
 * <p>This exists so an interview can always be completed - no API key, exhausted
 * free-tier quota, or a dropped connection mid-session should ever leave a
 * candidate stuck. It is used automatically whenever Gemini fails.
 *
 * <p>The scoring here makes no claim to understand the answer. It measures four
 * observable proxies: coverage of the question's expected points, whether the
 * answer is substantial enough, how disfluent the speech was, and whether it is
 * structured into sentences. Every evaluation it produces is stored with
 * {@code evaluator = FALLBACK} so the report can say honestly how it was graded.
 */
@Component
public class FallbackLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackLlmClient.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String DEFAULT_KEY = "_default";

    /** interviewType -> domain -> questions */
    private Map<String, Map<String, List<Map<String, Object>>>> bank = Map.of();

    @PostConstruct
    @SuppressWarnings("unchecked")
    void loadBank() {
        try (InputStream in = new ClassPathResource("question-bank.json").getInputStream()) {
            Map<String, Object> root = MAPPER.readValue(in, Map.class);
            Map<String, Map<String, List<Map<String, Object>>>> loaded = new java.util.LinkedHashMap<>();
            root.forEach((key, value) -> {
                if (!key.startsWith("_") && value instanceof Map<?, ?> domains) {
                    loaded.put(key, (Map<String, List<Map<String, Object>>>) domains);
                }
            });
            bank = loaded;
            log.info("Loaded offline question bank: {} interview type(s)", bank.size());
        } catch (Exception e) {
            log.error("Could not load question-bank.json - fallback questions unavailable", e);
        }
    }

    @Override
    public String modelName() {
        return "offline-fallback";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    // ---- questions ----------------------------------------------------------

    /**
     * Years of experience at which an EXPERIENCED candidate is offered the
     * harder half of the bank.
     *
     * <p>Deliberately one constant rather than a curve. This is the offline path
     * of a prototype: a threshold that can be stated in a sentence and defended
     * in a viva is worth more than a model nobody can explain.
     */
    private static final int SENIOR_YEARS = 4;

    @Override
    public List<GeneratedQuestion> generateQuestions(QuestionRequest request) {
        List<Map<String, Object>> pool = poolFor(request);
        if (pool.isEmpty()) {
            throw new IllegalStateException("No fallback questions available for " + request.domain());
        }

        List<GeneratedQuestion> selected = select(uniqueByText(pool), request);

        if (selected.size() < request.count()) {
            // A shorter interview, not a padded one. The report divides by the
            // questions that actually exist, so asking fewer scores correctly -
            // whereas asking the same question twice would count it twice and
            // quietly misrepresent both the candidate and the bank's coverage.
            log.warn("Offline bank holds only {} suitable question(s) for {}/{}; {} were requested",
                    selected.size(), request.interviewType(), request.domain(), request.count());
        }
        return selected;
    }

    /**
     * Chooses distinct questions for this candidate, easiest first.
     *
     * <p>Difficulties are consumed in the order {@link #tiersFor} gives: the band
     * that suits the candidate first, then the tiers to widen into if the bank
     * cannot fill the interview. <b>Nothing is ever taken twice.</b> The modulo
     * wrap this replaced silently re-asked question 1 as question 6 once the
     * requested count passed the pool size.
     */
    private static List<GeneratedQuestion> select(List<GeneratedQuestion> available,
            QuestionRequest request) {

        List<GeneratedQuestion> chosen = new ArrayList<>();
        for (Difficulty tier : tiersFor(request)) {
            for (GeneratedQuestion question : available) {
                if (chosen.size() >= request.count()) {
                    break;
                }
                if (question.difficulty() == tier && !chosen.contains(question)) {
                    chosen.add(question);
                }
            }
        }

        // Easiest first, keeping the bank's own order within one difficulty.
        // Collections.sort is stable, so a widened selection still climbs rather
        // than jumping from HARD back down to EASY halfway through.
        return chosen.stream()
                .sorted(Comparator.comparingInt((GeneratedQuestion q) -> rank(q.difficulty())))
                .toList();
    }

    /**
     * Difficulty preference order for a candidate: their band first, then the
     * tiers to widen into only if the bank is too small to fill the interview.
     *
     * <p>Experience is the only input, and the mapping is deliberately blunt:
     * a fresher is asked beginner and intermediate questions, an early-career
     * professional intermediate ones, and a senior one intermediate and
     * advanced. Widening prefers the adjacent tier that keeps the interview
     * fair - down for the early-career candidate, down for the senior only
     * after the advanced questions are exhausted.
     */
    static List<Difficulty> tiersFor(QuestionRequest request) {
        if (request.candidateType() != CandidateType.EXPERIENCED) {
            return List.of(Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD);
        }
        int years = request.experienceYears() == null ? 1 : request.experienceYears();
        return years >= SENIOR_YEARS
                ? List.of(Difficulty.MEDIUM, Difficulty.HARD, Difficulty.EASY)
                : List.of(Difficulty.MEDIUM, Difficulty.EASY, Difficulty.HARD);
    }

    private static int rank(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> 0;
            case MEDIUM -> 1;
            case HARD -> 2;
        };
    }

    /**
     * Bank entries as questions, with repeated or blank text dropped.
     *
     * <p>Defence in depth: selection already takes each entry at most once, so
     * this only matters if the bank itself ever carries the same question twice
     * in one pool - which no test would otherwise catch.
     */
    private static List<GeneratedQuestion> uniqueByText(List<Map<String, Object>> pool) {
        Set<String> seen = new HashSet<>();
        List<GeneratedQuestion> questions = new ArrayList<>();
        for (Map<String, Object> entry : pool) {
            String text = String.valueOf(entry.get("text")).trim();
            if (text.isBlank() || !seen.add(text.toLowerCase(Locale.ROOT))) {
                continue;
            }
            questions.add(new GeneratedQuestion(
                    text,
                    parseDifficulty(entry.get("difficulty")),
                    stringList(entry.get("expectedPoints"))));
        }
        return questions;
    }

    private List<Map<String, Object>> poolFor(QuestionRequest request) {
        Map<String, List<Map<String, Object>>> byDomain =
                bank.getOrDefault(request.interviewType().name(), Map.of());

        // HR questions are domain independent; technical ones fall back to a
        // general set if the chosen domain has no curated questions.
        if (request.interviewType() == InterviewType.HR_GENERAL) {
            return byDomain.getOrDefault(DEFAULT_KEY, List.of());
        }
        List<Map<String, Object>> exact = byDomain.get(request.domain());
        if (exact != null && !exact.isEmpty()) {
            return exact;
        }

        // Domains are free text - a recruiter may schedule "Rust and
        // WebAssembly" - so an unknown domain is now the normal case offline,
        // not an oddity.
        //
        // It falls to "_default": questions written to make sense in ANY
        // technical domain, naming no language or framework. This used to fall
        // to "Software Engineering", which is a domain in its own right rather
        // than a neutral one, so a Rust candidate was quietly asked about
        // estimation and code review as though that had been chosen. The last
        // resort remains an arbitrary domain, which is only reachable if the
        // bank file is malformed.
        List<Map<String, Object>> general = byDomain.get(DEFAULT_KEY);
        if (general != null && !general.isEmpty()) {
            log.debug("No curated questions for domain '{}' - using the general set",
                    request.domain());
            return general;
        }
        return byDomain.values().stream().findFirst().orElse(List.of());
    }

    // ---- evaluation ---------------------------------------------------------

    @Override
    public EvaluationResult evaluateAnswer(EvaluationRequest request) {
        String answer = request.cleanTranscript() == null ? "" : request.cleanTranscript().trim();
        List<String> expected = request.expectedPoints() == null ? List.of() : request.expectedPoints();

        if (answer.isBlank()) {
            return new EvaluationResult(0, 0, 0, 0,
                    "No answer was recorded for this question.",
                    List.of(), List.of("No answer given"));
        }

        String lower = answer.toLowerCase(Locale.ROOT);
        String[] words = answer.split("\\s+");
        int wordCount = words.length;

        // 1. Coverage: how many of the expected points are echoed in the answer.
        List<String> covered = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        for (String point : expected) {
            if (mentions(lower, point)) {
                covered.add(point);
            } else {
                missed.add(point);
            }
        }
        double coverage = expected.isEmpty() ? 0.5 : (double) covered.size() / expected.size();

        // 2. Substance: very short answers cannot demonstrate much. Past roughly
        //    120 words extra length stops earning credit.
        double substance = Math.min(1.0, wordCount / 120.0);

        // 3. Structure: multiple sentences suggest an organised explanation.
        int sentences = answer.split("[.!?]+").length;
        double structure = Math.min(1.0, sentences / 4.0);

        int technical = score(0.75 * coverage + 0.25 * substance);
        int relevance = score(0.85 * coverage + 0.15 * substance);
        int problemSolving = score(0.55 * coverage + 0.25 * structure + 0.20 * substance);
        int communication = score(0.50 * structure + 0.50 * substance);

        List<String> strengths = new ArrayList<>();
        if (!covered.isEmpty()) {
            strengths.add("Mentioned: " + String.join(", ", covered.subList(0, Math.min(3, covered.size()))));
        }
        if (wordCount >= 60) {
            strengths.add("Gave a reasonably detailed answer");
        }

        List<String> weaknesses = new ArrayList<>();
        if (!missed.isEmpty()) {
            weaknesses.add("Did not cover: " + String.join(", ", missed.subList(0, Math.min(3, missed.size()))));
        }
        if (wordCount < 30) {
            weaknesses.add("Answer was very brief");
        }

        String feedback = ("This answer was graded offline, without a language model, so it reflects "
                + "keyword coverage and answer structure rather than a full reading. It covered %d of %d "
                + "expected points in about %d words.")
                .formatted(covered.size(), expected.size(), wordCount);

        return new EvaluationResult(technical, relevance, problemSolving, communication,
                feedback, strengths, weaknesses);
    }

    /**
     * An expected point is "mentioned" if a meaningful word from it appears in
     * the answer. Deliberately loose - the fallback should under-claim rather
     * than pretend to comprehension it does not have.
     */
    private static boolean mentions(String lowerAnswer, String point) {
        for (String token : point.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() >= 5 && lowerAnswer.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** Maps a 0..1 ratio onto 25..95, so nothing scores 0 or 100 by accident. */
    private static int score(double ratio) {
        return (int) Math.round(25 + Math.max(0, Math.min(1, ratio)) * 70);
    }

    private static Difficulty parseDifficulty(Object value) {
        try {
            return Difficulty.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return Difficulty.MEDIUM;
        }
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
