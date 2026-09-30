package com.project.proctorinterview.answer;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.ai.AiService;
import com.project.proctorinterview.ai.dto.AiDtos.EvaluationRequest;
import com.project.proctorinterview.answer.TranscriptCleaner.CleanedTranscript;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.config.ScoringProperties;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewSession;
import com.project.proctorinterview.question.Question;
import com.project.proctorinterview.question.QuestionRepository;

/**
 * Records an answer and evaluates it.
 *
 * <p>Cleaning happens here, on the server, rather than trusting whatever the
 * browser sends: the client also cleans so the candidate can see what will be
 * graded, but this copy is the authoritative one.
 *
 * <p>The overall score is computed in Java from configured weights. The model
 * supplies the four sub-scores and never the outcome.
 */
@Service
@Transactional
public class AnswerService {

    private final AnswerRepository answers;
    private final QuestionRepository questions;
    private final TranscriptCleaner cleaner;
    private final AiService ai;
    private final ScoringProperties scoring;

    public AnswerService(AnswerRepository answers, QuestionRepository questions,
            TranscriptCleaner cleaner, AiService ai, ScoringProperties scoring) {
        this.answers = answers;
        this.questions = questions;
        this.cleaner = cleaner;
        this.ai = ai;
        this.scoring = scoring;
    }

    public Answer submit(InterviewSession session, Long questionId, String rawTranscript,
            int durationSeconds) {

        Question question = questions.findById(questionId)
                .orElseThrow(() -> ApiException.notFound("Question"));

        if (!question.getSession().getId().equals(session.getId())) {
            throw ApiException.forbidden("That question belongs to another interview session");
        }
        if (answers.existsByQuestionId(questionId)) {
            // An interview moves forward only. Re-answering would let a candidate
            // revise after seeing later questions.
            throw ApiException.conflict("This question has already been answered");
        }

        CleanedTranscript cleaned = cleaner.clean(rawTranscript);

        Answer answer = new Answer();
        answer.setQuestion(question);
        answer.setRawTranscript(rawTranscript);
        answer.setCleanTranscript(cleaned.cleanText());
        answer.setFillerCount(cleaned.fillerCount());
        answer.setWordCount(cleaned.wordCount());
        answer.setDurationSeconds(Math.max(0, durationSeconds));
        answer.setAnsweredAt(Instant.now());

        Interview interview = session.getInterview();
        AiService.AnswerScore scored = ai.evaluate(new EvaluationRequest(
                question.getText(),
                question.getExpectedPoints(),
                cleaned.cleanText(),
                interview.getDomain(),
                interview.getInterviewType(),
                question.getDifficulty()));

        answer.setTechnicalScore(scored.result().technicalScore());
        answer.setRelevanceScore(scored.result().relevanceScore());
        answer.setProblemSolvingScore(scored.result().problemSolvingScore());
        answer.setCommunicationScore(scored.result().communicationScore());
        answer.setOverallScore(scoring.weightedOverall(
                scored.result().technicalScore(),
                scored.result().problemSolvingScore(),
                scored.result().communicationScore(),
                scored.result().relevanceScore()));
        answer.setFeedback(scored.result().feedback());
        answer.setStrengths(scored.result().strengths());
        answer.setWeaknesses(scored.result().weaknesses());
        answer.setEvaluator(scored.evaluator());
        answer.setModelName(scored.modelName());
        answer.setEvaluatedAt(Instant.now());

        return answers.save(answer);
    }

    @Transactional(readOnly = true)
    public List<Answer> forSession(Long sessionId) {
        return answers.findByQuestionSessionIdOrderByQuestionSequenceNoAsc(sessionId);
    }
}
