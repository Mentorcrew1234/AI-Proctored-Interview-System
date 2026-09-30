package com.project.proctorinterview.interview;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.InterviewFilter;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.InterviewTab;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

/**
 * Dynamic filtering for the interview management page.
 *
 * <p>One specification builder rather than a repository method per filter
 * combination - with eight independent filters that would be a combinatorial
 * mess of finders.
 *
 * <p>Every predicate here is optional and additive. The one predicate that is
 * <b>not</b> optional is ownership, applied separately in
 * {@link InterviewQueryService} so it cannot be forgotten or overridden by
 * anything a user submits.
 */
public final class InterviewSpecifications {

    private InterviewSpecifications() {
    }

    /** Restricts to one recruiter's own interviews. Never user-supplied. */
    public static Specification<Interview> ownedBy(Long recruiterId) {
        return (root, query, cb) -> cb.equal(root.get("recruiter").get("id"), recruiterId);
    }

    /** Everything the user asked for, combined with AND. */
    public static Specification<Interview> matching(InterviewFilter filter, ZoneId zone) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Joined once and reused; left joins so a row is never dropped just
            // because a filter did not mention the candidate.
            var candidate = root.join("candidate", JoinType.LEFT);

            // ---- search across name, candidate name and candidate email ----
            if (notBlank(filter.getSearch())) {
                String term = "%" + filter.getSearch().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(cb.coalesce(root.get("interviewName"), "")), term),
                        cb.like(cb.lower(candidate.get("fullName")), term),
                        cb.like(cb.lower(candidate.get("email")), term)));
            }

            // ---- date range, inclusive of both endpoints -------------------
            if (filter.getFromDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("scheduledAt"),
                        filter.getFromDate().atStartOfDay(zone).toInstant()));
            }
            if (filter.getToDate() != null) {
                // End of the chosen day, so "to 31 Aug" includes the 31st.
                predicates.add(cb.lessThan(root.get("scheduledAt"),
                        filter.getToDate().plusDays(1).atStartOfDay(zone).toInstant()));
            }

            // ---- straightforward equality filters --------------------------
            if (filter.getStatus() != null) {
                predicates.add(cb.equal(root.get("status"), filter.getStatus()));
            }
            if (notBlank(filter.getDomain())) {
                predicates.add(cb.equal(root.get("domain"), filter.getDomain()));
            }
            if (filter.getExperienceType() != null) {
                predicates.add(cb.equal(root.get("candidateType"), filter.getExperienceType()));
            }
            if (filter.getLanguage() != null) {
                predicates.add(cb.equal(root.get("language"), filter.getLanguage()));
            }
            if (filter.getInterviewType() != null) {
                predicates.add(cb.equal(root.get("interviewType"), filter.getInterviewType()));
            }
            if (filter.getRecruiterId() != null) {
                predicates.add(cb.equal(root.get("recruiter").get("id"), filter.getRecruiterId()));
            }

            // ---- the quick tabs, expressed in the same terms ---------------
            var now = java.time.Instant.now();
            switch (filter.resolvedTab()) {
                case TODAY -> {
                    LocalDate today = LocalDate.now(zone);
                    predicates.add(cb.greaterThanOrEqualTo(root.get("scheduledAt"),
                            today.atStartOfDay(zone).toInstant()));
                    predicates.add(cb.lessThan(root.get("scheduledAt"),
                            today.plusDays(1).atStartOfDay(zone).toInstant()));
                }
                case UPCOMING -> {
                    // Still to happen and not cancelled or already done.
                    predicates.add(cb.greaterThan(root.get("scheduledAt"), now));
                    predicates.add(root.get("status").in(
                            InterviewStatus.SCHEDULED, InterviewStatus.IN_PROGRESS));
                }
                case IN_PROGRESS -> predicates.add(
                        cb.equal(root.get("status"), InterviewStatus.IN_PROGRESS));
                case COMPLETED -> predicates.add(
                        cb.equal(root.get("status"), InterviewStatus.COMPLETED));
                case CANCELLED -> predicates.add(
                        cb.equal(root.get("status"), InterviewStatus.CANCELLED));
                case ALL -> {
                    // no additional restriction
                }
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Sorting fields the caller may name, mapped to entity paths. Anything
     * unrecognised falls back to the scheduled time rather than being passed
     * through to the query.
     */
    public static String sortProperty(String requested) {
        if (requested == null) {
            return "scheduledAt";
        }
        return switch (requested) {
            case "interviewName" -> "interviewName";
            case "candidateName" -> "candidate.fullName";
            case "createdAt" -> "createdAt";
            case "status" -> "status";
            case "domain" -> "domain";
            default -> "scheduledAt";
        };
    }

    /**
     * Default ordering per tab: what is coming up reads best oldest-first,
     * what is finished reads best newest-first.
     */
    public static boolean defaultAscending(InterviewTab tab) {
        return tab == InterviewTab.TODAY || tab == InterviewTab.UPCOMING;
    }

    static LocalTime endOfDay() {
        return LocalTime.MAX;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
