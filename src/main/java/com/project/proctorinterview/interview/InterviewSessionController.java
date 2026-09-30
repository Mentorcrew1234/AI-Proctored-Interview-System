package com.project.proctorinterview.interview;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.proctorinterview.ai.AiService;
import com.project.proctorinterview.answer.Answer;
import com.project.proctorinterview.answer.AnswerService;
import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.common.Enums.QuestionMode;
import com.project.proctorinterview.common.Enums.QuestionSource;
import com.project.proctorinterview.config.QuestionModeProperties;
import com.project.proctorinterview.interview.dto.SessionDtos.CompleteSessionResponse;
import com.project.proctorinterview.interview.dto.SessionDtos.QuestionListResponse;
import com.project.proctorinterview.interview.dto.SessionDtos.QuestionView;
import com.project.proctorinterview.interview.dto.SessionDtos.SubmitAnswerRequest;
import com.project.proctorinterview.interview.dto.SessionDtos.SubmitAnswerResponse;
import com.project.proctorinterview.question.Question;
import com.project.proctorinterview.question.QuestionService;
import com.project.proctorinterview.report.ReportService;

import jakarta.validation.Valid;

/** Product B endpoints: fetching questions and submitting answers. */
@RestController
@RequestMapping("/api/interview-sessions/{sessionId}")
public class InterviewSessionController {

    private final ExamService examService;
    private final QuestionService questionService;
    private final AnswerService answerService;
    private final ReportService reportService;
    private final AiService aiService;
    private final QuestionModeProperties questionMode;

    public InterviewSessionController(ExamService examService, QuestionService questionService,
            AnswerService answerService, ReportService reportService, AiService aiService,
            QuestionModeProperties questionMode) {
        this.examService = examService;
        this.questionService = questionService;
        this.answerService = answerService;
        this.reportService = reportService;
        this.aiService = aiService;
        this.questionMode = questionMode;
    }

    /**
     * The session's questions.
     *
     * <p>{@code FIXED} mode (the default, unchanged): the whole set is
     * generated on first call and fixed thereafter, so a reload shows the same
     * interview.
     *
     * <p>{@code ADAPTIVE} mode: returns however many questions exist so far,
     * generating the next one first when the session needs it - the first
     * question on a brand-new session, or the next one if the most recently
     * generated question has already been answered. {@link
     * QuestionService#ensureNextAdaptiveQuestion} is a no-op (returns without
     * writing anything) in every other case - already answered questions
     * awaiting their next one, or an interview that already has every question
     * it calls for - so calling it on every GET is what makes a refresh both
     * safe to repeat and able to recover a question that a lost response never
     * delivered.
     */
    @GetMapping("/questions")
    public QuestionListResponse questions(@PathVariable Long sessionId,
            @AuthenticationPrincipal AppUserDetails me) {

        InterviewSession session = examService.requireOwnedSession(sessionId, me.getId());
        List<Question> questions;
        if (isAdaptive(session)) {
            questionService.ensureNextAdaptiveQuestion(session);
            questions = questionService.forSession(sessionId);
        } else {
            questions = questionService.ensureQuestions(session);
        }

        Set<Long> answeredIds = answerService.forSession(sessionId).stream()
                .map(a -> a.getQuestion().getId())
                .collect(Collectors.toSet());

        List<QuestionView> views = questions.stream()
                .map(q -> new QuestionView(q.getId(), q.getSequenceNo(), q.getText(),
                        q.getDifficulty(), answeredIds.contains(q.getId())))
                .toList();

        boolean aiGenerated = !questions.isEmpty()
                && questions.getFirst().getSource() == QuestionSource.LLM;

        return new QuestionListResponse(sessionId, questions.size(), answeredIds.size(),
                aiGenerated, aiService.isMockMode(),
                session.remainingSecondsAt(java.time.Instant.now()), views);
    }

    @PostMapping("/answers")
    public SubmitAnswerResponse submitAnswer(@PathVariable Long sessionId,
            @Valid @RequestBody SubmitAnswerRequest request,
            @AuthenticationPrincipal AppUserDetails me) {

        InterviewSession session = examService.requireActiveSession(sessionId, me.getId());
        Answer answer = answerService.submit(session, request.questionId(),
                request.rawTranscript(), request.durationSeconds());

        // ADAPTIVE mode's actual trigger: the question just answered is now
        // scored, which is exactly the context ensureNextAdaptiveQuestion
        // needs. It is its own no-op past the interview's questionCount, so
        // answering the final question does not generate an extra one - total
        // below then correctly stays equal to answered, and complete is true.
        if (isAdaptive(session)) {
            questionService.ensureNextAdaptiveQuestion(session);
        }

        int total = questionService.forSession(sessionId).size();
        int answered = answerService.forSession(sessionId).size();

        return new SubmitAnswerResponse(
                answer.getId(),
                answer.getQuestion().getSequenceNo(),
                answer.getCleanTranscript(),
                answer.getFillerCount(),
                answer.getWordCount(),
                answered,
                total,
                answered >= total,
                session.remainingSecondsAt(java.time.Instant.now()));
    }

    /**
     * Whether THIS interview runs adaptively.
     *
     * <p>The interview's own setting wins; an interview that names no mode
     * falls back to the server-wide default, which is how every interview
     * scheduled before the column existed keeps behaving as it always did.
     *
     * <p>Reads through the session's interview association, so it must be
     * called inside the transaction - open-in-view is off.
     */
    private boolean isAdaptive(InterviewSession session) {
        return questionMode.effectiveFor(session.getInterview().getQuestionMode())
                == QuestionMode.ADAPTIVE;
    }

    /**
     * Ends the interview and produces the report.
     *
     * <p>Uses the owned-session check rather than the active-session one, so a
     * candidate whose session already closed gets their existing report back
     * instead of an error. Report generation is idempotent.
     */
    @PostMapping("/complete")
    public CompleteSessionResponse complete(@PathVariable Long sessionId,
            @AuthenticationPrincipal AppUserDetails me) {

        InterviewSession session = examService.requireOwnedSession(sessionId, me.getId());

        // The expiry sweep can reach the same session at the same moment the
        // candidate presses Finish. Only one report can exist - the unique
        // constraint on reports.session_id sees to that - so if this call lost
        // the race, the right answer is the report that won rather than an
        // error at the finish line.
        com.project.proctorinterview.report.Report report;
        try {
            report = reportService.completeSession(session);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            report = reportService.findReport(sessionId);
            if (report == null) {
                throw e; // a different constraint failed; do not hide it
            }
        }

        boolean visible = session.getInterview().isResultVisibleToCandidate();
        return new CompleteSessionResponse(
                sessionId,
                true,
                visible,
                visible ? report.getOverallScore() : null,
                visible ? report.getRecommendation().name() : null,
                session.getCompletionReason() == null ? null : session.getCompletionReason().name());
    }
}
