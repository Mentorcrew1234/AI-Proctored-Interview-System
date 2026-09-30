package com.project.proctorinterview.interview.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;

import lombok.Getter;
import lombok.Setter;

public final class InterviewQueryDtos {

    private InterviewQueryDtos() {
    }

    /** The quick tabs. Each is a shorthand for a filter, not a separate page. */
    public enum InterviewTab {
        ALL, TODAY, UPCOMING, IN_PROGRESS, COMPLETED, CANCELLED;

        public static InterviewTab parse(String value) {
            if (value == null || value.isBlank()) {
                return ALL;
            }
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return ALL;
            }
        }

        public String label() {
            return switch (this) {
                case ALL -> "All";
                case TODAY -> "Today";
                case UPCOMING -> "Upcoming";
                case IN_PROGRESS -> "In Progress";
                case COMPLETED -> "Completed";
                case CANCELLED -> "Cancelled";
            };
        }
    }

    /**
     * Everything the management page can filter by.
     *
     * <p>A mutable bean rather than a record so Thymeleaf's {@code th:field} can
     * bind it and re-render the form with the user's selections intact - the
     * same reason the scheduling form uses one.
     *
     * <p>Note what is <b>not</b> here: any way to widen the result set beyond
     * what the caller may see. Ownership is applied server-side from the
     * authenticated principal and is not part of this object.
     */
    @Getter
    @Setter
    public static class InterviewFilter {

        /** Matches interview name, candidate name or candidate email. */
        private String search;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate fromDate;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate toDate;

        private InterviewStatus status;
        private String domain;
        private CandidateType experienceType;
        private InterviewLanguage language;
        private InterviewType interviewType;

        /** Admin only; ignored for a recruiter, who is already scoped to themselves. */
        private Long recruiterId;

        private String tab = "ALL";

        private String sortBy = "scheduledAt";
        private String sortDirection;

        private int page = 0;
        private int size = 20;

        public InterviewTab resolvedTab() {
            return InterviewTab.parse(tab);
        }

        /**
         * True when a filter other than the tab or the free-text search is
         * applied.
         *
         * <p>Drives whether the collapsed filter panel starts open. Computed
         * here rather than as a chain of null checks in the template, for the
         * same reason the chips are: Thymeleaf evaluates {@code th:unless}
         * (precedence 300) before {@code th:with} (400), so a value defined and
         * tested on one element is still null when the test runs.
         *
         * <p>The tab and the search box are excluded deliberately - both have
         * their own visible control, so neither is a hidden setting the user
         * needs the panel opened to discover.
         */
        public boolean hasAdvancedFilters() {
            return fromDate != null || toDate != null || status != null || notBlank(domain)
                    || experienceType != null || language != null || interviewType != null
                    || recruiterId != null;
        }

        /** True when anything beyond the default tab is in play. */
        public boolean hasActiveFilters() {
            return notBlank(search) || fromDate != null || toDate != null || status != null
                    || notBlank(domain) || experienceType != null || language != null
                    || interviewType != null || recruiterId != null
                    || resolvedTab() != InterviewTab.ALL;
        }

        /** Human-readable chips, so the user can see exactly what is applied. */
        public List<ActiveFilter> activeFilters() {
            List<ActiveFilter> chips = new ArrayList<>();
            if (resolvedTab() != InterviewTab.ALL) {
                chips.add(new ActiveFilter("tab", resolvedTab().label()));
            }
            if (notBlank(search)) {
                chips.add(new ActiveFilter("search", "\"" + search.trim() + "\""));
            }
            if (fromDate != null && toDate != null) {
                chips.add(new ActiveFilter("dates", fromDate + " to " + toDate));
            } else if (fromDate != null) {
                chips.add(new ActiveFilter("dates", "from " + fromDate));
            } else if (toDate != null) {
                chips.add(new ActiveFilter("dates", "until " + toDate));
            }
            if (status != null) {
                chips.add(new ActiveFilter("status", status.name().replace('_', ' ')));
            }
            if (notBlank(domain)) {
                chips.add(new ActiveFilter("domain", domain));
            }
            if (experienceType != null) {
                chips.add(new ActiveFilter("experienceType", experienceType.name()));
            }
            if (language != null) {
                chips.add(new ActiveFilter("language", language.name()));
            }
            if (interviewType != null) {
                chips.add(new ActiveFilter("interviewType", interviewType.name().replace('_', ' ')));
            }
            if (recruiterId != null) {
                chips.add(new ActiveFilter("recruiterId", "recruiter selected"));
            }
            return chips;
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }

    /** One removable chip in the active-filter bar. */
    public record ActiveFilter(String field, String label) {
    }

    /**
     * Counts for the summary cards. Every value is a database count under the
     * caller's own authorization scope - never derived in the browser.
     */
    public record InterviewInsights(
            long total,
            long today,
            long upcoming,
            long inProgress,
            long completed,
            long cancelled) {
    }

    /**
     * Something a person should look at, derived only from data the system
     * already records. No status was invented for this.
     */
    public record AttentionItem(Long interviewId, String displayName, String candidateName,
            String reason, String detail) {
    }

    /**
     * Everything the detail page shows, resolved inside one transaction.
     *
     * <p>Assembled in the service rather than handing entities to the template:
     * open-in-view is off, so a lazy association read during rendering would
     * fail. This carries plain values only.
     */
    public record InterviewDetailView(
            InterviewDtos.InterviewSummary interview,
            String displayName,
            Long sessionId,
            String sessionStatus,
            boolean cameraGranted,
            boolean micGranted,
            int answerCount,
            boolean hasReport,
            Integer technicalScore,
            Integer problemSolvingScore,
            Integer communicationScore,
            Integer overallScore,
            String recommendation,
            List<ObservationCount> observations) {
    }

    /** One proctoring observation type and how often it occurred. */
    public record ObservationCount(String type, long count) {
    }

    /** One page of results plus everything the page needs to render itself. */
    public record InterviewPageResult(
            List<InterviewDtos.InterviewSummary> rows,
            int page,
            int size,
            int totalPages,
            long totalElements,
            boolean hasPrevious,
            boolean hasNext,
            int firstItem,
            int lastItem) {
    }
}
