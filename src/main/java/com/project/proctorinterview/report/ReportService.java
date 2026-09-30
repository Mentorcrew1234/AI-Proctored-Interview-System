package com.project.proctorinterview.report;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.answer.Answer;
import com.project.proctorinterview.answer.AnswerService;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.CompletionReason;
import com.project.proctorinterview.common.Enums.EvaluatorType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.MonitoringCoverage;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.common.Enums.SessionStatus;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewRepository;
import com.project.proctorinterview.interview.InterviewSession;
import com.project.proctorinterview.interview.InterviewSessionRepository;
import com.project.proctorinterview.proctor.ProctorEvent;
import com.project.proctorinterview.proctor.ProctorEventService;
import com.project.proctorinterview.question.Question;
import com.project.proctorinterview.question.QuestionService;
import com.project.proctorinterview.report.dto.ReportDtos.ObservationCount;
import com.project.proctorinterview.report.dto.ReportDtos.ObservationRow;
import com.project.proctorinterview.report.dto.ReportDtos.QuestionResult;
import com.project.proctorinterview.report.dto.ReportDtos.ReportView;
import com.project.proctorinterview.report.dto.ReportDtos.TimingSummary;

/**
 * Builds the final report: the point where Product A and Product B meet, joined
 * on one session id.
 */
@Service
@Transactional
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ReportRepository reports;
    private final InterviewSessionRepository sessions;
    private final InterviewRepository interviews;
    private final QuestionService questionService;
    private final AnswerService answerService;
    private final ProctorEventService proctorEvents;
    private final ScoreAggregator aggregator;
    private final RecommendationEngine recommendationEngine;

    public ReportService(ReportRepository reports, InterviewSessionRepository sessions,
            InterviewRepository interviews, QuestionService questionService,
            AnswerService answerService, ProctorEventService proctorEvents,
            ScoreAggregator aggregator, RecommendationEngine recommendationEngine) {
        this.reports = reports;
        this.sessions = sessions;
        this.interviews = interviews;
        this.questionService = questionService;
        this.answerService = answerService;
        this.proctorEvents = proctorEvents;
        this.aggregator = aggregator;
        this.recommendationEngine = recommendationEngine;
    }

    /**
     * Completes the session and produces its report.
     *
     * <p>Idempotent: a second call returns the existing report rather than
     * regenerating, so a retried request cannot change a result that has already
     * been shown to someone.
     *
     * <p>The check-then-insert below is not atomic, and there are now two
     * callers that can genuinely arrive together - the candidate pressing Finish
     * at the same moment the expiry sweep reaches their session. The
     * {@code uk_reports_session} unique constraint is what actually guarantees
     * only one report is ever written; the loser's insert fails and its
     * transaction rolls back. Callers recover by reading the report that won -
     * see {@link #findReport} - because a rolled-back transaction cannot be
     * salvaged from inside itself.
     */
    public Report completeSession(InterviewSession session) {
        Report existing = reports.findBySessionId(session.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }

        List<Question> questions = questionService.forSession(session.getId());
        List<Answer> answers = answerService.forSession(session.getId());

        var scores = aggregator.aggregate(answers, questions.size());
        Map<String, Object> proctorCounts = proctorEvents.countsByType(session.getId());
        // What the monitoring actually managed to see, as of now. It shapes the
        // wording of the explanation and nothing else - see
        // RecommendationEngine.decide.
        var coverage = session.coverageWith(proctorEvents.observationCount(session.getId()));
        var outcome = recommendationEngine.decide(scores, proctorCounts, coverage);

        Report report = new Report();
        report.setSession(session);
        report.setTechnicalScore(scores.technical());
        report.setCommunicationScore(scores.communication());
        report.setProblemSolvingScore(scores.problemSolving());
        report.setRelevanceScore(scores.relevance());
        report.setOverallScore(scores.overall());
        report.setRecommendation(outcome.recommendation());
        report.setExplanation(outcome.explanation());
        report.setProctorSummary(new HashMap<>(proctorCounts));
        report.setIntegrityFlag(outcome.integrityFlag());
        reports.save(report);

        // Why this interview ended, decided once, here.
        //
        // A session the server already expired keeps the end time and reason it
        // was closed with - overwriting them would relabel a timed-out interview
        // as one the candidate chose to finish.
        //
        // The deadline is re-checked even when nothing expired it earlier: a
        // candidate whose timer reaches zero calls /complete directly, and no
        // write happened in between to trip the check in requireActiveSession.
        // Without this, running out of time would be recorded as finishing
        // voluntarily.
        if (session.getCompletionReason() == null) {
            if (session.hasExpiredAt(Instant.now())) {
                session.setCompletionReason(CompletionReason.TIME_EXPIRED);
                // The deadline, not "now": time stopped when the interview did.
                session.setEndedAt(session.deadline());
            } else {
                session.setCompletionReason(CompletionReason.CANDIDATE_FINISHED);
                session.setEndedAt(Instant.now());
            }
        }
        session.setStatus(SessionStatus.COMPLETED);
        sessions.save(session);

        Interview interview = session.getInterview();
        interview.setStatus(InterviewStatus.COMPLETED);
        interviews.save(interview);

        log.info("Report for session {}: overall {} -> {}{}",
                session.getId(), scores.overall(), outcome.recommendation(),
                outcome.cappedByProctoring() ? " (held for review after proctoring observations)" : "");

        return report;
    }

    /**
     * Completes a session identified by id, if it is still running.
     *
     * <p>For callers that have an id rather than an attached entity - the expiry
     * sweep. Loading the session <em>inside</em> this transaction is the point:
     * {@link #completeSession} reads the session's interview through a lazy
     * association, which fails on an entity loaded outside a transaction.
     *
     * @return the report, or null if the session was already finished
     */
    public Report completeSessionById(Long sessionId) {
        InterviewSession session = sessions.findById(sessionId).orElse(null);
        if (session == null || session.getStatus() != SessionStatus.ACTIVE) {
            return null; // finished by the candidate in the meantime
        }
        return completeSession(session);
    }

    /** Assembles the full read model shown on the report page and API. */
    @Transactional(readOnly = true)
    public ReportView view(Long sessionId) {
        Report report = reports.findBySessionId(sessionId)
                .orElseThrow(() -> ApiException.notFound("Report"));

        InterviewSession session = report.getSession();
        Interview interview = session.getInterview();

        List<Question> questions = questionService.forSession(sessionId);
        Map<Long, Answer> answersByQuestion = new HashMap<>();
        for (Answer answer : answerService.forSession(sessionId)) {
            answersByQuestion.put(answer.getQuestion().getId(), answer);
        }

        List<QuestionResult> results = questions.stream()
                .map(q -> toQuestionResult(q, answersByQuestion.get(q.getId())))
                .toList();

        List<ObservationCount> counts = proctorEvents.summarise(sessionId).stream()
                .filter(s -> s.count() > 0)
                .map(s -> new ObservationCount(s.type(), s.count(), s.totalDurationMs()))
                .toList();

        List<ObservationRow> timeline = proctorEvents.timeline(sessionId).stream()
                .map(ReportService::toObservationRow)
                .toList();

        // Recomputed here rather than read off the report, deliberately. The
        // stored explanation is a snapshot of what was known at completion; the
        // page should show what is known now, because a late batch of events or
        // a final coverage report can land after the report was built.
        MonitoringCoverage coverage = session.coverageWith(timeline.size());

        // "AI evaluated" only if every answered question was graded by the LLM;
        // a partial fallback must not be presented as a full AI evaluation.
        boolean aiEvaluated = !answersByQuestion.isEmpty()
                && answersByQuestion.values().stream()
                        .allMatch(a -> a.getEvaluator() == EvaluatorType.LLM);

        return new ReportView(
                sessionId,
                interview.getCandidate().getFullName(),
                interview.getCandidate().getEmail(),
                interview.getRecruiter().getFullName(),
                interview.getDomain(),
                interview.getInterviewType(),
                interview.getCandidateType(),
                interview.getExperienceYears(),
                interview.getScheduledAt(),
                DISPLAY_FORMAT.format(interview.getScheduledAt()),
                session.getStartedAt(),
                session.getEndedAt(),
                durationText(session.getStartedAt(), session.getEndedAt()),
                buildTiming(interview, session, questions, answersByQuestion),
                report.getTechnicalScore(),
                report.getCommunicationScore(),
                report.getProblemSolvingScore(),
                report.getRelevanceScore(),
                report.getOverallScore(),
                answersByQuestion.size(),
                questions.size(),
                report.getRecommendation(),
                report.getExplanation(),
                report.isIntegrityFlag(),
                coverage,
                session.getMonitoringNote(),
                session.getMonitoringDroppedEvents(),
                results,
                counts,
                timeline,
                aiEvaluated,
                report.getGeneratedAt());
    }

    /**
     * The report for a session, if one exists.
     *
     * <p>Used to recover from a lost finalisation race: the winner's report is
     * the correct answer for both callers.
     */
    @Transactional(readOnly = true)
    public Report findReport(Long sessionId) {
        return reports.findBySessionId(sessionId).orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean exists(Long sessionId) {
        return reports.existsBySessionId(sessionId);
    }

    /**
     * Who may read a report. Kept in the service so the REST endpoint and the
     * Thymeleaf page cannot drift apart on it.
     *
     * <p>A candidate sees their own result only if the recruiter enabled it
     * when scheduling; that is the recruiter's call, not the candidate's.
     */
    @Transactional(readOnly = true)
    public void assertCanView(Long sessionId, Long userId, Role role) {
        InterviewSession session = sessions.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Report"));
        Interview interview = session.getInterview();

        switch (role) {
            case ADMIN -> {
                // Admins may view any report.
            }
            case RECRUITER -> {
                if (!interview.getRecruiter().getId().equals(userId)) {
                    throw ApiException.forbidden("This interview belongs to another recruiter");
                }
            }
            case CANDIDATE -> {
                if (!interview.getCandidate().getId().equals(userId)) {
                    // Not "forbidden": do not confirm that another candidate's
                    // session exists.
                    throw ApiException.notFound("Report");
                }
                if (!interview.isResultVisibleToCandidate()) {
                    throw ApiException.forbidden(
                            "Your recruiter has not made this result visible to you");
                }
            }
        }
    }

    private static QuestionResult toQuestionResult(Question question, Answer answer) {
        if (answer == null) {
            return new QuestionResult(question.getSequenceNo(), question.getText(),
                    question.getDifficulty(), question.getExpectedPoints(), false,
                    null, null, 0, 0, 0,
                    null, null, null, null, null, null, List.of(), List.of(), null, null);
        }
        return new QuestionResult(
                question.getSequenceNo(), question.getText(), question.getDifficulty(),
                question.getExpectedPoints(), true,
                answer.getRawTranscript(), answer.getCleanTranscript(),
                answer.getFillerCount(), answer.getWordCount(), answer.getDurationSeconds(),
                answer.getTechnicalScore(), answer.getRelevanceScore(),
                answer.getProblemSolvingScore(), answer.getCommunicationScore(),
                answer.getOverallScore(), answer.getFeedback(),
                answer.getStrengths(), answer.getWeaknesses(),
                answer.getEvaluator(), answer.getModelName());
    }

    private static ObservationRow toObservationRow(ProctorEvent event) {
        return new ObservationRow(
                event.getEventType(),
                event.getStartTime(),
                CLOCK_FORMAT.format(event.getStartTime()),
                event.getDurationMs(),
                humanDuration(event.getDurationMs()),
                event.getConfidence(),
                event.getDetails());
    }

    private static String humanDuration(Long millis) {
        if (millis == null || millis <= 0) {
            return "-";
        }
        if (millis < 1000) {
            return millis + " ms";
        }
        double seconds = millis / 1000.0;
        return seconds < 60
                ? "%.1f s".formatted(seconds)
                : "%d m %d s".formatted((long) seconds / 60, (long) seconds % 60);
    }

    /**
     * The interview's timing, entirely derived from what was already recorded:
     * the interview's configured duration and the session's own start and end.
     *
     * <p>Nothing here is a judgement. Running over the allocated time is stated
     * as a fact, not flagged as a problem - the server stops an interview at its
     * deadline, so an overrun only ever means the final answer was in flight.
     */
    private TimingSummary buildTiming(Interview interview, InterviewSession session,
            List<Question> questions, Map<Long, Answer> answersByQuestion) {

        long actualSeconds = session.getStartedAt() == null || session.getEndedAt() == null
                ? 0
                : Math.max(0, Duration.between(session.getStartedAt(), session.getEndedAt()).toSeconds());

        // Only answered questions have a duration; an unanswered one has no
        // time to report rather than a time of zero.
        List<Integer> answerSeconds = answersByQuestion.values().stream()
                .map(Answer::getDurationSeconds)
                .filter(seconds -> seconds > 0)
                .sorted()
                .toList();

        int allocated = interview.getDurationMinutes();
        CompletionReason reason = session.getCompletionReason();

        return new TimingSummary(
                allocated,
                allocated + (allocated == 1 ? " minute" : " minutes"),
                session.getStartedAt(),
                session.getEndedAt(),
                durationText(session.getStartedAt(), session.getEndedAt()),
                actualSeconds,
                reason,
                reason == null ? "Not recorded" : reason.label(),
                answersByQuestion.size(),
                questions.size(),
                Math.max(0, questions.size() - answersByQuestion.size()),
                answerSeconds.isEmpty() ? null : secondsText(
                        (long) answerSeconds.stream().mapToInt(Integer::intValue).average().orElse(0)),
                answerSeconds.isEmpty() ? null : secondsText(answerSeconds.getLast()),
                answerSeconds.isEmpty() ? null : secondsText(answerSeconds.getFirst()),
                actualSeconds > (long) allocated * 60);
    }

    /** "2 min 57 s", or "38 s" under a minute. */
    private static String secondsText(long totalSeconds) {
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? "%d min %d s".formatted(minutes, seconds) : "%d s".formatted(seconds);
    }

    private static String durationText(Instant start, Instant end) {
        if (start == null || end == null) {
            return "-";
        }
        Duration duration = Duration.between(start, end);
        long minutes = duration.toMinutes();
        long seconds = duration.toSecondsPart();
        return minutes > 0 ? "%d min %d s".formatted(minutes, seconds) : "%d s".formatted(seconds);
    }
}
