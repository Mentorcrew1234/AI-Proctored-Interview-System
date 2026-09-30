package com.project.proctorinterview.interview;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.interview.dto.CandidateDtos.CandidateDashboardView;
import com.project.proctorinterview.interview.dto.CandidateDtos.CandidateInterviewRow;

/**
 * The candidate's own view of their interviews: one permanent account, many
 * independent interviews, exactly as scheduling (single or bulk) already
 * creates them.
 *
 * <p>Ownership is enforced by construction, not by filtering: every row comes
 * from {@link InterviewRepository#findByCandidateIdOrderByScheduledAtDesc},
 * scoped to the id of whoever is asking. There is no candidate-supplied id
 * anywhere in this path that could widen the result.
 */
@Service
@Transactional(readOnly = true)
public class CandidateInterviewService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final InterviewRepository interviews;
    private final InterviewSessionRepository sessions;

    public CandidateInterviewService(InterviewRepository interviews, InterviewSessionRepository sessions) {
        this.interviews = interviews;
        this.sessions = sessions;
    }

    public CandidateDashboardView dashboard(Long candidateId) {
        List<Interview> mine = interviews.findByCandidateIdOrderByScheduledAtDesc(candidateId);

        Map<Long, InterviewSession> sessionByInterviewId = sessions
                .findByInterviewIdIn(mine.stream().map(Interview::getId).toList()).stream()
                .collect(Collectors.toMap(s -> s.getInterview().getId(), Function.identity()));

        LocalDate today = LocalDate.now(ZoneId.systemDefault());

        List<CandidateInterviewRow> todayRows = new ArrayList<>();
        List<CandidateInterviewRow> upcomingRows = new ArrayList<>();
        List<CandidateInterviewRow> inProgressRows = new ArrayList<>();
        List<CandidateInterviewRow> completedRows = new ArrayList<>();
        List<CandidateInterviewRow> cancelledRows = new ArrayList<>();

        for (Interview interview : mine) {
            CandidateInterviewRow row = toRow(interview, sessionByInterviewId.get(interview.getId()));
            LocalDate scheduledDate = interview.getScheduledAt().atZone(ZoneId.systemDefault()).toLocalDate();

            switch (interview.getStatus()) {
                case IN_PROGRESS -> inProgressRows.add(row);
                case COMPLETED -> completedRows.add(row);
                case CANCELLED -> cancelledRows.add(row);
                case SCHEDULED -> {
                    if (scheduledDate.isEqual(today)) {
                        todayRows.add(row);
                    } else {
                        upcomingRows.add(row);
                    }
                }
            }
        }

        // Nearest first for what is still ahead; most recent first for what is behind.
        todayRows.sort(Comparator.comparing(CandidateInterviewRow::scheduledAt));
        upcomingRows.sort(Comparator.comparing(CandidateInterviewRow::scheduledAt));
        inProgressRows.sort(Comparator.comparing(CandidateInterviewRow::scheduledAt));
        completedRows.sort(Comparator.comparing(CandidateInterviewRow::scheduledAt, Comparator.reverseOrder()));
        cancelledRows.sort(Comparator.comparing(CandidateInterviewRow::scheduledAt, Comparator.reverseOrder()));

        return new CandidateDashboardView(todayRows, upcomingRows, inProgressRows, completedRows, cancelledRows,
                mine.size());
    }

    private CandidateInterviewRow toRow(Interview interview, InterviewSession session) {
        // A SCHEDULED interview only becomes startable from the dashboard once its
        // time has arrived - IN_PROGRESS is always resumable since it already
        // passed that point once. This only gates the dashboard button; the
        // underlying start endpoint is unchanged (SessionLifecycleTest relies on
        // starting a next-day interview immediately for the exam flow itself).
        boolean startable = interview.getStatus() == InterviewStatus.IN_PROGRESS
                || (interview.getStatus() == InterviewStatus.SCHEDULED
                        && !interview.getScheduledAt().isAfter(Instant.now()));
        boolean reportViewable = interview.getStatus() == InterviewStatus.COMPLETED
                && interview.isResultVisibleToCandidate()
                && session != null;

        return new CandidateInterviewRow(
                interview.getId(),
                interview.getDisplayName(),
                interview.getScheduledAt(),
                DATE_FORMAT.format(interview.getScheduledAt()),
                TIME_FORMAT.format(interview.getScheduledAt()),
                interview.getDomain(),
                interview.getInterviewType(),
                interview.getCandidateType(),
                interview.getExperienceYears(),
                interview.getLanguage().name(),
                interview.getQuestionCount(),
                interview.getStatus(),
                interview.getInviteToken(),
                startable,
                session == null ? null : session.getId(),
                reportViewable);
    }
}
