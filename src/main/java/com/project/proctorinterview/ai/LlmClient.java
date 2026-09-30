package com.project.proctorinterview.ai;

import java.util.List;

import com.project.proctorinterview.ai.dto.AiDtos.EvaluationRequest;
import com.project.proctorinterview.ai.dto.AiDtos.EvaluationResult;
import com.project.proctorinterview.ai.dto.AiDtos.GeneratedQuestion;
import com.project.proctorinterview.ai.dto.AiDtos.QuestionRequest;

/**
 * Question generation and answer evaluation.
 *
 * <p>Two implementations only: {@link GeminiLlmClient} and
 * {@link FallbackLlmClient}. That is the whole abstraction - no plugin
 * registry, no provider discovery. It exists so the interview still completes
 * when the API key is missing, the free-tier quota is exhausted, or the network
 * drops mid-session.
 */
public interface LlmClient {

    /** Short identifier recorded against every evaluation, e.g. "gemini-2.5-flash". */
    String modelName();

    /** False when the client cannot be used (e.g. no API key configured). */
    boolean isAvailable();

    List<GeneratedQuestion> generateQuestions(QuestionRequest request);

    EvaluationResult evaluateAnswer(EvaluationRequest request);
}
