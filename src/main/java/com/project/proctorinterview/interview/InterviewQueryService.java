package com.project.proctorinterview.interview;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.dto.InterviewDtos.InterviewSummary;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.AttentionItem;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.InterviewFilter;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.InterviewDetailView;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.InterviewInsights;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.ObservationCount;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.InterviewPageResult;
import com.project.proctorinterview.interview.dto.InterviewExportDtos.ExportPreflight;
import com.project.proctorinterview.interview.dto.InterviewExportDtos.ExportRow;
import com.project.proctorinterview.report.Report;
import com.project.proctorinterview.report.ReportRepository;
import com.project.proctorinterview.report.dto.ReportQueryDtos.ReportPageResult;
import com.project.proctorinterview.report.dto.ReportQueryDtos.ReportRow;

/**
 * Reads for the interview management page: search, filters, paging, summary
 * counts and the "needs attention" list.
 *
 * <p><b>Authorization is applied here, not in the browser.</b> Every query
 * starts from a scope predicate derived from the authenticated principal and
 * ANDs the user's filters onto it. A recruiter cannot widen their result set by
 * changing a form field, and searching for another recruiter's candidate simply
 * returns nothing.
 *
 * <p>Read-only: this service creates and changes nothing. Scheduling stays in
 * {@link InterviewService}.
 */
@Service
@Transactional(readOnly = true)
public class InterviewQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final List<Integer> ALLOWED_SIZES = List.of(20, 50, 100);

    /** How soon counts as "starting soon" for the attention list. */
    private static final Duration STARTING_SOON = Duration.ofHours(1);
    /** How late a scheduled interview must be before it looks like a no-show. */
    private static final Duration NO_SHOW_AFTER = Duration.ofHours(2);
    /** How long an interview may sit in progress before it looks stalled. */
    private static final Duration STALLED_AFTER = Duration.ofHours(3);

    // Export uses ISO-ish date and separate time columns so a spreadsheet can
    // sort and filter them, rather than the prose format the pages display.
    private static final DateTimeFormatter EXPORT_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter EXPORT_TIME =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter EXPORT_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final InterviewRepository interviews;
    private final InterviewSessionRepository sessions;
    private final ReportRepository reports;
    private final InterviewService interviewService;
    private final com.project.proctorinterview.answer.AnswerService answerService;
    /** Used only by the export, for its batched per-session answer counts. */
    private final com.project.proctorinterview.answer.AnswerRepository answerRepository;
    private final com.project.proctorinterview.proctor.ProctorEventService proctorEvents;
    private final ZoneId zone = ZoneId.systemDefault();

    public InterviewQueryService(InterviewRepository interviews, InterviewSessionRepository sessions,
            ReportRepository reports, InterviewService interviewService,
            com.project.proctorinterview.answer.AnswerService answerService,
            com.project.proctorinterview.answer.AnswerRepository answerRepository,
            com.project.proctorinterview.proctor.ProctorEventService proctorEvents) {
        this.interviews = interviews;
        this.sessions = sessions;
        this.reports = reports;
        this.interviewService = interviewService;
        this.answerService = answerService;
        this.answerRepository = answerRepository;
        this.proctorEvents = proctorEvents;
    }

    /**
     * An always-true predicate for an admin's unrestricted scope.
     *
     * <p>An explicit predicate rather than null, because {@code count(null)} and
     * {@code findAll(null)} are rejected by Spring Data - and because a scope
     * that is always a real object cannot be accidentally dropped.
     */
    private static final Specification<Interview> UNRESTRICTED =
            (root, query, cb) -> cb.conjunction();

    /**
     * The one place authorization is decided. An admin sees everything; a
     * recruiter sees only interviews they own.
     */
    private Specification<Interview> scopeFor(Long userId, Role role) {
        return role == Role.ADMIN ? UNRESTRICTED : InterviewSpecifications.ownedBy(userId);
    }

    /** Scope AND the caller's filters. Scope is never replaceable by a filter. */
    private Specification<Interview> scopedAnd(Long userId, Role role, Specification<Interview> extra) {
        Specification<Interview> scope = scopeFor(userId, role);
        return extra == null ? scope : scope.and(extra);
    }

    // ---- the table ----------------------------------------------------------

    /**
     * Every interview id matching a filter, under the caller's own scope,
     * ignoring paging entirely. This is what backs "select all matching
     * filter" for bulk edit/delete - the browser only ever sends the filter
     * description, never the resulting id list, so there is nothing for a
     * tampered request to widen.
     */
    public List<Long> idsMatching(InterviewFilter filter, Long userId, Role role) {
        validate(filter);
        Specification<Interview> spec = scopedAnd(userId, role, InterviewSpecifications.matching(filter, zone));
        return interviews.findAll(spec).stream().map(Interview::getId).toList();
    }

    public InterviewPageResult search(InterviewFilter filter, Long userId, Role role) {
        validate(filter);

        int size = ALLOWED_SIZES.contains(filter.getSize())
                ? filter.getSize()
                : Math.min(Math.max(filter.getSize(), 1), MAX_PAGE_SIZE);
        int page = Math.max(0, filter.getPage());

        String property = InterviewSpecifications.sortProperty(filter.getSortBy());
        boolean ascending = filter.getSortDirection() == null || filter.getSortDirection().isBlank()
                ? InterviewSpecifications.defaultAscending(filter.resolvedTab())
                : "asc".equalsIgnoreCase(filter.getSortDirection());

        Sort sort = Sort.by(ascending ? Sort.Direction.ASC : Sort.Direction.DESC, property);
        // Stable tie-break, so paging cannot repeat or skip a row when several
        // interviews share a timestamp.
        sort = sort.and(Sort.by(Sort.Direction.DESC, "id"));

        Specification<Interview> spec =
                scopedAnd(userId, role, InterviewSpecifications.matching(filter, zone));
        Page<Interview> result = interviews.findAll(spec, PageRequest.of(page, size, sort));

        List<InterviewSummary> rows = result.getContent().stream()
                .map(interviewService::toSummary)
                .toList();

        int firstItem = result.getTotalElements() == 0 ? 0 : page * size + 1;
        int lastItem = (int) Math.min((long) (page + 1) * size, result.getTotalElements());

        return new InterviewPageResult(rows, page, size, result.getTotalPages(),
                result.getTotalElements(), result.hasPrevious(), result.hasNext(),
                firstItem, lastItem);
    }

    /** From > To is the one filter combination that is simply wrong. */
    private void validate(InterviewFilter filter) {
        if (filter.getFromDate() != null && filter.getToDate() != null
                && filter.getFromDate().isAfter(filter.getToDate())) {
            throw ApiException.badRequest("\"From\" date cannot be after the \"To\" date.");
        }
    }

    // ---- summary cards ------------------------------------------------------

    /**
     * Counts under the caller's own scope. Deliberately separate count queries
     * rather than counting a fetched list, so the numbers stay correct
     * regardless of paging.
     */
    public InterviewInsights insights(Long userId, Role role) {
        LocalDate today = LocalDate.now(zone);
        Instant startOfToday = today.atStartOfDay(zone).toInstant();
        Instant startOfTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant();
        Instant now = Instant.now();

        long total = interviews.count(scopedAnd(userId, role, null));
        long today_ = interviews.count(scopedAnd(userId, role, (root, q, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("scheduledAt"), startOfToday),
                cb.lessThan(root.get("scheduledAt"), startOfTomorrow))));
        long upcoming = interviews.count(scopedAnd(userId, role, (root, q, cb) -> cb.and(
                cb.greaterThan(root.get("scheduledAt"), now),
                root.get("status").in(InterviewStatus.SCHEDULED, InterviewStatus.IN_PROGRESS))));

        return new InterviewInsights(total, today_, upcoming,
                countByStatus(userId, role, InterviewStatus.IN_PROGRESS),
                countByStatus(userId, role, InterviewStatus.COMPLETED),
                countByStatus(userId, role, InterviewStatus.CANCELLED));
    }

    private long countByStatus(Long userId, Role role, InterviewStatus status) {
        return interviews.count(scopedAnd(userId, role,
                (root, q, cb) -> cb.equal(root.get("status"), status)));
    }

    // ---- needs attention ----------------------------------------------------

    /**
     * Interviews a person should look at.
     *
     * <p>Every condition is derived from data the system already records - no
     * status was invented and nothing is inferred by a model. A "no show" here
     * means literally: it was scheduled, the time has passed, and no session was
     * ever started.
     */
    public List<AttentionItem> needsAttention(Long userId, Role role) {
        Instant now = Instant.now();
        List<AttentionItem> items = new ArrayList<>();

        // Scheduled and imminent.
        for (Interview interview : interviews.findAll(scopedAnd(userId, role, (root, q, cb) -> cb.and(
                cb.equal(root.get("status"), InterviewStatus.SCHEDULED),
                cb.greaterThan(root.get("scheduledAt"), now),
                cb.lessThanOrEqualTo(root.get("scheduledAt"), now.plus(STARTING_SOON)))))) {
            items.add(item(interview, "Starting soon", "Scheduled within the next hour"));
        }

        // Scheduled, well past its time, and never started.
        for (Interview interview : interviews.findAll(scopedAnd(userId, role, (root, q, cb) -> cb.and(
                cb.equal(root.get("status"), InterviewStatus.SCHEDULED),
                cb.lessThan(root.get("scheduledAt"), now.minus(NO_SHOW_AFTER)))))) {
            if (sessions.findByInterviewId(interview.getId()).isEmpty()) {
                items.add(item(interview, "Possible no show",
                        "Scheduled time passed and the candidate never started"));
            }
        }

        // Started but never finished.
        for (Interview interview : interviews.findAll(scopedAnd(userId, role, (root, q, cb) -> cb.and(
                cb.equal(root.get("status"), InterviewStatus.IN_PROGRESS),
                cb.lessThan(root.get("scheduledAt"), now.minus(STALLED_AFTER)))))) {
            items.add(item(interview, "Incomplete",
                    "Still in progress well after its scheduled time"));
        }

        // Completed but with no report - the report step did not finish.
        for (Interview interview : interviews.findAll(scopedAnd(userId, role,
                (root, q, cb) -> cb.equal(root.get("status"), InterviewStatus.COMPLETED)))) {
            var session = sessions.findByInterviewId(interview.getId()).orElse(null);
            if (session != null && !reports.existsBySessionId(session.getId())) {
                items.add(item(interview, "Missing report",
                        "Marked completed but no report was generated"));
            }
        }

        // Keep it a short, actionable list rather than a second table.
        return items.size() > 10 ? items.subList(0, 10) : items;
    }

    private AttentionItem item(Interview interview, String reason, String detail) {
        return new AttentionItem(interview.getId(), interview.getDisplayName(),
                interview.getCandidate().getFullName(), reason, detail);
    }

    // ---- detail view --------------------------------------------------------

    /**
     * Everything the detail page needs, resolved while the session is still
     * open. Reads only - it does not regenerate reports or alter the interview.
     */
    public InterviewDetailView detail(Long interviewId, Long userId, Role role) {
        Interview interview = interviewService.getForActor(interviewId, userId, role);
        var session = sessions.findByInterviewId(interview.getId()).orElse(null);

        if (session == null) {
            return new InterviewDetailView(interviewService.toSummary(interview),
                    interview.getDisplayName(), null, null, false, false, 0, false,
                    null, null, null, null, null, List.of());
        }

        var report = reports.findBySessionId(session.getId()).orElse(null);
        List<ObservationCount> observations = proctorEvents.summarise(session.getId()).stream()
                .filter(o -> o.count() > 0)
                .map(o -> new ObservationCount(o.type().name(), o.count()))
                .toList();

        return new InterviewDetailView(
                interviewService.toSummary(interview),
                interview.getDisplayName(),
                session.getId(),
                session.getStatus().name(),
                session.isCameraGranted(),
                session.isMicGranted(),
                answerService.forSession(session.getId()).size(),
                report != null,
                report == null ? null : report.getTechnicalScore(),
                report == null ? null : report.getProblemSolvingScore(),
                report == null ? null : report.getCommunicationScore(),
                report == null ? null : report.getOverallScore(),
                report == null ? null : report.getRecommendation().name(),
                observations);
    }

    // ---- export -------------------------------------------------------------

    /**
     * Rows above this are only exported when the caller explicitly confirms, so
     * a broad filter cannot silently produce an enormous download.
     */
    public static final int EXPORT_SOFT_LIMIT = 1000;

    /**
     * A runaway guard, not a policy. Even a confirmed export stops here rather
     * than trying to stream an unbounded result set.
     */
    public static final int EXPORT_HARD_LIMIT = 50_000;

    /**
     * How many interviews a filter matches, under the caller's own scope.
     *
     * <p>A count query, not a fetch: the page needs the number before deciding
     * whether building a workbook of that size is reasonable.
     */
    public ExportPreflight exportPreflight(InterviewFilter filter, Long userId, Role role) {
        validate(filter);
        long matching = interviews.count(
                scopedAnd(userId, role, InterviewSpecifications.matching(filter, zone)));

        return new ExportPreflight(matching, EXPORT_SOFT_LIMIT,
                matching > EXPORT_SOFT_LIMIT, matching > EXPORT_HARD_LIMIT, EXPORT_HARD_LIMIT);
    }

    /**
     * Every interview matching the filter, under the caller's own scope,
     * assembled for export.
     *
     * <p><b>Paging is deliberately ignored.</b> "Export" means the whole
     * filtered set, not the page the user happens to be looking at - the same
     * reasoning as {@link #idsMatching}, and like it, the browser sends only the
     * filter, never a row list, so there is nothing to tamper with.
     *
     * <p>Assembled in three queries rather than by mapping each row through
     * {@code InterviewService.toSummary}, which costs a session lookup and a
     * report check <em>per interview</em> - fine for a 20-row page, ruinous for
     * a few thousand.
     */
    public List<ExportRow> exportRows(InterviewFilter filter, Long userId, Role role) {
        validate(filter);

        String property = InterviewSpecifications.sortProperty(filter.getSortBy());
        boolean ascending = filter.getSortDirection() == null || filter.getSortDirection().isBlank()
                ? InterviewSpecifications.defaultAscending(filter.resolvedTab())
                : "asc".equalsIgnoreCase(filter.getSortDirection());
        Sort sort = Sort.by(ascending ? Sort.Direction.ASC : Sort.Direction.DESC, property)
                .and(Sort.by(Sort.Direction.DESC, "id"));

        // 1 - the interviews themselves, in the same order the page shows them.
        List<Interview> matching = interviews.findAll(
                scopedAnd(userId, role, InterviewSpecifications.matching(filter, zone)), sort);

        if (matching.size() > EXPORT_HARD_LIMIT) {
            matching = matching.subList(0, EXPORT_HARD_LIMIT);
        }
        if (matching.isEmpty()) {
            return List.of();
        }

        // 2 - every session for those interviews, in one query.
        Map<Long, InterviewSession> sessionByInterview = sessions
                .findByInterviewIdIn(matching.stream().map(Interview::getId).toList()).stream()
                .collect(Collectors.toMap(s -> s.getInterview().getId(), Function.identity()));

        // 3 - every report for those sessions, in one query.
        List<Long> sessionIds = sessionByInterview.values().stream()
                .map(InterviewSession::getId)
                .toList();
        Map<Long, Report> reportBySession = sessionIds.isEmpty()
                ? Map.of()
                : reports.findBySessionIdIn(sessionIds).stream()
                        .collect(Collectors.toMap(r -> r.getSession().getId(), Function.identity()));

        // 4 - answer counts for every session at once, rather than per row.
        Map<Long, Long> answersBySession = sessionIds.isEmpty()
                ? Map.of()
                : answerRepository.countBySessionIdIn(sessionIds).stream()
                        .collect(Collectors.toMap(
                                row -> (Long) row[0],
                                row -> (Long) row[1]));

        return matching.stream()
                .map(interview -> toExportRow(interview, sessionByInterview.get(interview.getId()),
                        reportBySession, answersBySession))
                .toList();
    }

    private ExportRow toExportRow(Interview interview, InterviewSession session,
            Map<Long, Report> reportBySession, Map<Long, Long> answersBySession) {

        Report report = session == null ? null : reportBySession.get(session.getId());

        return new ExportRow(
                interview.getId(),
                interview.getDisplayName(),
                interview.getCandidate().getFullName(),
                interview.getCandidate().getEmail(),
                interview.getRecruiter().getFullName(),
                EXPORT_DATE.format(interview.getScheduledAt()),
                EXPORT_TIME.format(interview.getScheduledAt()),
                interview.getDomain(),
                interview.getInterviewType(),
                interview.getCandidateType(),
                interview.getExperienceYears(),
                interview.getLanguage().name(),
                interview.getQuestionCount(),
                interview.getDurationMinutes(),
                interview.getStatus(),
                session == null ? null : session.getStatus().name(),
                session == null || session.getStartedAt() == null
                        ? null : EXPORT_DATE_TIME.format(session.getStartedAt()),
                session == null || session.getEndedAt() == null
                        ? null : EXPORT_DATE_TIME.format(session.getEndedAt()),
                session == null ? null : durationText(session.getStartedAt(), session.getEndedAt()),
                session == null || session.getCompletionReason() == null
                        ? null : session.getCompletionReason().label(),
                report != null,
                report == null ? null : report.getTechnicalScore(),
                report == null ? null : report.getProblemSolvingScore(),
                report == null ? null : report.getCommunicationScore(),
                report == null ? null : report.getRelevanceScore(),
                report == null ? null : report.getOverallScore(),
                report == null ? null : report.getRecommendation(),
                report == null ? null : report.isIntegrityFlag(),
                session == null ? null : answersBySession.getOrDefault(session.getId(), 0L).intValue(),
                report == null ? null : EXPORT_DATE_TIME.format(report.getGeneratedAt()));
    }

    private static String durationText(Instant start, Instant end) {
        if (start == null || end == null) {
            return null;
        }
        Duration duration = Duration.between(start, end);
        long minutes = duration.toMinutes();
        return minutes > 0
                ? "%d min %d s".formatted(minutes, duration.toSecondsPart())
                : "%d s".formatted(duration.toSecondsPart());
    }

    // ---- reports page -------------------------------------------------------

    /**
     * A page of finished interviews with their results, for the Reports screen.
     *
     * <p>Built on top of {@link #search} rather than beside it. That is the
     * whole point: search already applies the caller's authorization scope,
     * validates the filter, sorts and pages. Reusing it means the Reports page
     * cannot drift from the interview list, and cannot reach a report the
     * interview list would not already have shown - there is no second place
     * where scope could be got wrong.
     *
     * <p>Cost is the page's own query plus <b>two</b> batched lookups, not one
     * per row: the sessions for this page's interviews, then the reports for
     * those sessions.
     *
     * <p>A completed interview with no report is kept in the result and
     * reported as pending rather than hidden. That state is real - it is what
     * the "Missing report" attention item refers to - and hiding it would make
     * a page called "Reports" quietly disagree with the interview list.
     */
    public ReportPageResult reportPage(InterviewFilter filter, Long userId, Role role) {
        InterviewPageResult page = search(filter, userId, role);

        List<Long> sessionIds = page.rows().stream()
                .map(InterviewSummary::sessionId)
                .filter(java.util.Objects::nonNull)
                .toList();

        Map<Long, Report> reportBySession = sessionIds.isEmpty()
                ? Map.of()
                : reports.findBySessionIdIn(sessionIds).stream()
                        .collect(Collectors.toMap(r -> r.getSession().getId(), Function.identity()));

        List<ReportRow> rows = page.rows().stream()
                .map(summary -> toReportRow(summary,
                        summary.sessionId() == null ? null : reportBySession.get(summary.sessionId())))
                .toList();

        return new ReportPageResult(rows, page.page(), page.size(), page.totalPages(),
                page.totalElements(), page.hasPrevious(), page.hasNext(),
                page.firstItem(), page.lastItem());
    }

    private ReportRow toReportRow(InterviewSummary summary, Report report) {
        return new ReportRow(
                summary.id(),
                summary.displayName(),
                summary.candidateName(),
                summary.candidateEmail(),
                summary.recruiterName(),
                summary.scheduledAtText(),
                summary.domain(),
                summary.sessionId(),
                report != null,
                report == null ? null : report.getOverallScore(),
                report == null ? null : report.getTechnicalScore(),
                report == null ? null : report.getProblemSolvingScore(),
                report == null ? null : report.getCommunicationScore(),
                report == null ? null : report.getRelevanceScore(),
                report == null ? null : report.getRecommendation(),
                report != null && report.isIntegrityFlag(),
                report == null ? null : EXPORT_DATE_TIME.format(report.getGeneratedAt()));
    }

    // ---- filter option lists ------------------------------------------------

    /**
     * Domains actually present, so the dropdown never offers an empty filter -
     * and, since domains became free text, never omits one either.
     *
     * <p>Previously this returned the fixed suggestion list, which was the same
     * thing only because nothing else could be stored. Now a recruiter can type
     * "Rust and WebAssembly", and a filter built from suggestions would have no
     * way to find it.
     */
    @Transactional(readOnly = true)
    public List<String> domainOptions() {
        return interviews.findDistinctDomains();
    }
}
