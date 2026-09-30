package com.project.proctorinterview.interview;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.Recommendation;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.dto.CandidateQueryDtos;
import com.project.proctorinterview.interview.dto.CandidateQueryDtos.CandidateFilter;
import com.project.proctorinterview.interview.dto.CandidateQueryDtos.CandidateFilterOptions;
import com.project.proctorinterview.report.Report;
import com.project.proctorinterview.report.ReportRepository;
import com.project.proctorinterview.user.CandidateProfile;
import com.project.proctorinterview.user.CandidateProfileRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * One candidate, their interviews and their results, in one place.
 *
 * <p>{@code docs/system/quality/LIMITATIONS.md} named this as missing and said why: there was
 * no recruiter-scoped candidate query, so candidates were reachable only
 * through the interview search. Adding the page meant adding the query first,
 * which is what this is.
 *
 * <h2>Scope</h2>
 *
 * <b>A recruiter sees only candidates they have scheduled, and only those
 * interviews.</b> The restriction is in the query rather than applied to an
 * unrestricted result afterwards - the same rule as everywhere else in the
 * system, and for the same reason: a result set that was ever unrestricted is
 * one forgotten filter away from a leak.
 *
 * <p>An administrator sees every candidate and every interview, which matches
 * their scope on every other page.
 *
 * <p><b>A candidate cannot reach this at all.</b> It is reached from
 * {@code /admin/**} and {@code /recruiter/**}, both of which a candidate is
 * refused by {@code SecurityConfig}, and the service refuses the role
 * regardless so the URL prefix is not the only control.
 */
@Service
public class CandidateViewService {

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    private final InterviewRepository interviews;
    private final InterviewSessionRepository sessions;
    private final ReportRepository reports;
    private final UserRepository users;
    private final CandidateProfileRepository candidateProfiles;

    public CandidateViewService(InterviewRepository interviews, InterviewSessionRepository sessions,
            ReportRepository reports, UserRepository users,
            CandidateProfileRepository candidateProfiles) {
        this.interviews = interviews;
        this.sessions = sessions;
        this.reports = reports;
        this.users = users;
        this.candidateProfiles = candidateProfiles;
    }

    /**
     * One row on the candidate list. Counts are within the caller's scope.
     *
     * @param collegeName from the candidate's profile, or null when it was
     *                    never recorded. Carried here rather than looked up per
     *                    row so the upcoming college filter has the value it
     *                    needs already in the result set - the summary query
     *                    left-joins the profile, so a candidate without one is
     *                    still listed.
     */
    public record CandidateRow(
            Long candidateId,
            String fullName,
            String email,
            String collegeName,
            String location,
            String skills,
            String primaryDomain,
            CandidateType candidateType,
            Integer experienceYears,
            long interviewCount,
            long completedCount,
            Instant latestScheduledAt,
            String latestScheduledAtText) {

        /** The skill list as parts, for the row to render as chips. */
        public List<String> skillList() {
            return CandidateQueryDtos.splitSkills(skills);
        }

        /** "Fresher", "4 years", or "-" when it was never recorded. */
        public String experienceText() {
            if (candidateType == CandidateType.FRESHER) {
                return "Fresher";
            }
            if (candidateType == null) {
                return "-";
            }
            return experienceYears == null ? "Experienced" : experienceYears + " years";
        }
    }

    /** One interview belonging to a candidate, with its result if there is one. */
    public record CandidateInterviewRow(
            Long interviewId,
            String displayName,
            String domain,
            InterviewStatus status,
            Instant scheduledAt,
            String scheduledAtText,
            Long sessionId,
            boolean hasReport,
            Integer overallScore,
            Recommendation recommendation) {
    }

    /**
     * @param interviewCount total within scope, so a recruiter's view of a
     *                       shared candidate counts only their own interviews
     */
    public record CandidateDetail(
            Long candidateId,
            String fullName,
            String email,
            String collegeName,
            String location,
            String skills,
            long interviewCount,
            long completedCount,
            List<CandidateInterviewRow> interviews) {

        public boolean hasAnyResult() {
            return interviews.stream().anyMatch(CandidateInterviewRow::hasReport);
        }
    }

    /**
     * Candidates within the caller's scope, most recently scheduled first,
     * narrowed by every filter the caller supplied.
     *
     * <p>Filters combine with AND and every one is optional, so an empty filter
     * returns exactly what the unfiltered page always did.
     *
     * <p><b>Filtered in Java over the already-scoped, already-aggregated
     * rows</b> - one row per candidate, not per interview. That is the choice
     * this page already made for its free-text search and the reasoning has not
     * changed: at prototype scale the list is short, the scope predicate is
     * already in the query where it cannot be forgotten, and pushing seven
     * optional predicates into SQL would buy a specification builder for a list
     * that fits on a screen. It also keeps the skill filter honest - skills are
     * a comma-separated column, and a SQL LIKE over it would match "JavaScript"
     * when the user asked for "Java".
     */
    @Transactional(readOnly = true)
    public List<CandidateRow> list(Long userId, Role role, CandidateFilter filter) {
        return scopedRows(userId, role).stream().filter(row -> matches(row, filter)).toList();
    }

    /**
     * What each dropdown offers, built from the caller's own rows.
     *
     * <p>Deliberately not a global {@code select distinct}: a college or a city
     * reached only through another recruiter's candidate would disclose that
     * the candidate exists. Same reason the counts on this page are the
     * viewer's rather than the candidate's whole history.
     *
     * <p>Built from the UNFILTERED scoped rows, so applying a filter never
     * empties the dropdown that would let the user change or clear it.
     */
    @Transactional(readOnly = true)
    public CandidateFilterOptions filterOptions(Long userId, Role role) {
        List<CandidateRow> rows = scopedRows(userId, role);
        return new CandidateFilterOptions(
                distinct(rows.stream().map(CandidateRow::collegeName)),
                distinct(rows.stream().map(CandidateRow::location)),
                distinct(rows.stream().flatMap(r -> r.skillList().stream())),
                distinct(rows.stream().map(CandidateRow::primaryDomain)));
    }

    /** Every candidate the caller may see, unfiltered. */
    private List<CandidateRow> scopedRows(Long userId, Role role) {
        requireStaff(role);

        List<Object[]> rows = role == Role.ADMIN
                ? interviews.findCandidateSummariesForAll()
                : interviews.findCandidateSummariesForRecruiter(userId);

        List<CandidateRow> result = new ArrayList<>();
        for (Object[] row : rows) {
            Instant latest = (Instant) row[4];
            result.add(new CandidateRow(
                    (Long) row[0], (String) row[1], (String) row[2],
                    (String) row[6], (String) row[7], (String) row[8], (String) row[9],
                    (CandidateType) row[10], (Integer) row[11],
                    ((Number) row[3]).longValue(),
                    row[5] == null ? 0L : ((Number) row[5]).longValue(),
                    latest,
                    latest == null ? "-" : DISPLAY.format(latest)));
        }
        return result;
    }

    /**
     * Every supplied filter must hold. A filter left blank is not a filter.
     *
     * <p>A candidate with nothing recorded for a field is excluded when that
     * field is filtered on, rather than counted as a match: asking for
     * "College = ABC" and being shown people whose college is unknown would be
     * a strange reading, and the unfiltered list is one click away.
     */
    private static boolean matches(CandidateRow row, CandidateFilter filter) {
        if (filter == null) {
            return true;
        }
        if (notBlank(filter.getSearch())) {
            String needle = filter.getSearch().trim().toLowerCase(Locale.ROOT);
            if (!row.fullName().toLowerCase(Locale.ROOT).contains(needle)
                    && !row.email().toLowerCase(Locale.ROOT).contains(needle)) {
                return false;
            }
        }
        if (notBlank(filter.getCollegeName())
                && !equalsIgnoreCase(row.collegeName(), filter.getCollegeName())) {
            return false;
        }
        if (notBlank(filter.getLocation())
                && !equalsIgnoreCase(row.location(), filter.getLocation())) {
            return false;
        }
        if (notBlank(filter.getDomain())
                && !equalsIgnoreCase(row.primaryDomain(), filter.getDomain())) {
            return false;
        }
        if (notBlank(filter.getSkill())
                && !CandidateQueryDtos.hasSkill(row.skills(), filter.getSkill())) {
            return false;
        }
        if (filter.getExperienceType() != null
                && row.candidateType() != filter.getExperienceType()) {
            return false;
        }
        // A fresher has no recorded years, so a years bound cannot be satisfied
        // by one. That is the honest answer rather than counting them as zero.
        if (filter.getMinExperienceYears() != null
                && (row.experienceYears() == null
                        || row.experienceYears() < filter.getMinExperienceYears())) {
            return false;
        }
        if (filter.getMaxExperienceYears() != null
                && (row.experienceYears() == null
                        || row.experienceYears() > filter.getMaxExperienceYears())) {
            return false;
        }
        return true;
    }

    /** Sorted, de-duplicated, blanks dropped - what a dropdown needs. */
    private static List<String> distinct(Stream<String> values) {
        return values.filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static boolean equalsIgnoreCase(String stored, String wanted) {
        return stored != null && stored.trim().equalsIgnoreCase(wanted.trim());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * One candidate, with every interview the caller is entitled to see.
     *
     * <p>Assembled inside the transaction, and returned as records rather than
     * entities - {@code open-in-view} is off, so anything a template touches
     * must be resolved here. Same reasoning as {@code InterviewDetailView}.
     *
     * @throws ApiException 404 if the candidate does not exist, or if this
     *                      caller has never interviewed them. Not 403: a
     *                      recruiter has no business learning that a candidate
     *                      exists in someone else's pipeline.
     */
    @Transactional(readOnly = true)
    public CandidateDetail detail(Long candidateId, Long userId, Role role) {
        requireStaff(role);

        User candidate = users.findById(candidateId)
                .filter(u -> u.getRole() == Role.CANDIDATE)
                .orElseThrow(() -> ApiException.notFound("Candidate"));

        List<Interview> owned = role == Role.ADMIN
                ? interviews.findByCandidateIdOrderByScheduledAtDesc(candidateId)
                : interviews.findByCandidateIdAndRecruiterIdOrderByScheduledAtDesc(candidateId, userId);

        if (owned.isEmpty()) {
            // A recruiter who has never interviewed this person is told the
            // same thing they would be told about a candidate who does not
            // exist. An admin with an empty list means the candidate genuinely
            // has no interviews, which is a real and different state - but they
            // reach it through a list that only shows candidates who have some.
            throw ApiException.notFound("Candidate");
        }

        List<CandidateInterviewRow> rows = new ArrayList<>();
        long completed = 0;
        for (Interview interview : owned) {
            if (interview.getStatus() == InterviewStatus.COMPLETED) {
                completed++;
            }
            Long sessionId = sessions.findByInterviewId(interview.getId())
                    .map(InterviewSession::getId)
                    .orElse(null);
            Report report = sessionId == null
                    ? null
                    : reports.findBySessionId(sessionId).orElse(null);

            rows.add(new CandidateInterviewRow(
                    interview.getId(),
                    interview.getDisplayName(),
                    interview.getDomain(),
                    interview.getStatus(),
                    interview.getScheduledAt(),
                    DISPLAY.format(interview.getScheduledAt()),
                    sessionId,
                    report != null,
                    report == null ? null : report.getOverallScore(),
                    report == null ? null : report.getRecommendation()));
        }

        // One lookup for one candidate, resolved inside the transaction like
        // everything else this template will touch - open-in-view is off.
        CandidateProfile profile = candidateProfiles.findByUserId(candidateId).orElse(null);

        return new CandidateDetail(candidateId, candidate.getFullName(), candidate.getEmail(),
                profile == null ? null : profile.getCollegeName(),
                profile == null ? null : profile.getLocation(),
                profile == null ? null : profile.getSkills(),
                rows.size(), completed, rows);
    }

    /**
     * Candidates never reach this, whatever the URL says.
     *
     * <p>{@code SecurityConfig} already refuses them {@code /admin/**} and
     * {@code /recruiter/**}. This is the second lock: the service does not rely
     * on the path prefix being the control, which is the mistake the codebase
     * warns about most often.
     */
    private static void requireStaff(Role role) {
        if (role == Role.CANDIDATE) {
            throw ApiException.notFound("Candidate");
        }
    }
}
