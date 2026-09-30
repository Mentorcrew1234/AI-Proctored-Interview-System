package com.project.proctorinterview.interview.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.interview.dto.InterviewQueryDtos.ActiveFilter;

import lombok.Getter;
import lombok.Setter;

public final class CandidateQueryDtos {

    private CandidateQueryDtos() {
    }

    /**
     * Everything the candidate list can be filtered by.
     *
     * <p>A mutable bean rather than a record so Thymeleaf's {@code th:field}
     * re-renders the panel with the user's selections intact - the same reason
     * {@link InterviewQueryDtos.InterviewFilter} is one.
     *
     * <p>Note what is <b>not</b> here, exactly as on the interview filter: any
     * way to widen the result beyond what the caller may see. Scope comes from
     * the authenticated principal in {@code CandidateViewService} and no field
     * on this object can reach it.
     *
     * <p>Every filter is optional and they combine with AND, so
     * {@code College = ABC} plus {@code Experience = Fresher} means both.
     */
    @Getter
    @Setter
    public static class CandidateFilter {

        /** Matches candidate name or email. */
        private String search;

        private String collegeName;
        private String location;

        /** One skill. A candidate matches when their list contains it. */
        private String skill;

        private String domain;
        private CandidateType experienceType;
        private Integer minExperienceYears;
        private Integer maxExperienceYears;

        /**
         * True when a filter beyond the free-text search is applied, which is
         * what decides whether the collapsed panel starts open.
         *
         * <p>Computed here rather than as a chain of null tests in the
         * template: Thymeleaf evaluates {@code th:unless} (precedence 300)
         * before {@code th:with} (400), so a value defined and tested on one
         * element is still null when the test runs.
         */
        public boolean hasAdvancedFilters() {
            return notBlank(collegeName) || notBlank(location) || notBlank(skill)
                    || notBlank(domain) || experienceType != null
                    || minExperienceYears != null || maxExperienceYears != null;
        }

        public boolean hasActiveFilters() {
            return notBlank(search) || hasAdvancedFilters();
        }

        /** Human-readable chips, so the user can see exactly what is applied. */
        public List<ActiveFilter> activeFilters() {
            List<ActiveFilter> chips = new ArrayList<>();
            if (notBlank(search)) {
                chips.add(new ActiveFilter("search", "\"" + search.trim() + "\""));
            }
            if (notBlank(collegeName)) {
                chips.add(new ActiveFilter("collegeName", "College: " + collegeName.trim()));
            }
            if (notBlank(location)) {
                chips.add(new ActiveFilter("location", "Location: " + location.trim()));
            }
            if (notBlank(skill)) {
                chips.add(new ActiveFilter("skill", "Skill: " + skill.trim()));
            }
            if (notBlank(domain)) {
                chips.add(new ActiveFilter("domain", "Domain: " + domain.trim()));
            }
            if (experienceType != null) {
                chips.add(new ActiveFilter("experienceType",
                        "Experience: " + label(experienceType)));
            }
            if (minExperienceYears != null) {
                chips.add(new ActiveFilter("minExperienceYears",
                        "From " + minExperienceYears + " years"));
            }
            if (maxExperienceYears != null) {
                chips.add(new ActiveFilter("maxExperienceYears",
                        "To " + maxExperienceYears + " years"));
            }
            return chips;
        }

        private static String label(CandidateType type) {
            return type == CandidateType.FRESHER ? "Fresher" : "Experienced";
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }

    /**
     * The values each dropdown offers.
     *
     * <p><b>Built from the candidate data, never hardcoded</b> - and built from
     * the rows the caller is already entitled to see, not from a global
     * {@code select distinct}. A recruiter's college list naming a college only
     * reached through someone else's candidate would disclose that the
     * candidate exists, which is the same thing the scoped counts exist to
     * avoid.
     */
    public record CandidateFilterOptions(
            List<String> colleges,
            List<String> locations,
            List<String> skills,
            List<String> domains) {

        public boolean isEmpty() {
            return colleges.isEmpty() && locations.isEmpty()
                    && skills.isEmpty() && domains.isEmpty();
        }
    }

    /**
     * Splits a stored skill list into its parts.
     *
     * <p>{@code UserService.normaliseSkills} is what makes a plain comma split
     * safe here; this is the only other place that has to know the column is a
     * list at all.
     */
    public static List<String> splitSkills(String skills) {
        if (skills == null || skills.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : skills.split(",")) {
            String skill = part.trim();
            if (!skill.isEmpty()) {
                parts.add(skill);
            }
        }
        return parts;
    }

    /** Case-insensitive membership, which is how the skill filter matches. */
    public static boolean hasSkill(String storedSkills, String wanted) {
        String needle = wanted.trim().toLowerCase(Locale.ROOT);
        return splitSkills(storedSkills).stream()
                .anyMatch(s -> s.toLowerCase(Locale.ROOT).equals(needle));
    }
}
