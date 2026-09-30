package com.project.proctorinterview.user.dto;

import java.time.Instant;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

public final class UserDtos {

    private UserDtos() {
    }

    /**
     * New account details. A mutable bean rather than a record so Thymeleaf's
     * {@code th:field} can re-render a rejected form with the entered values.
     */
    @Getter
    @Setter
    public static class CreateUserRequest {

        @NotBlank(message = "Full name is required")
        private String fullName;

        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email")
        private String email;

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Use at least 8 characters")
        private String password;

        @NotNull(message = "Select a role")
        private Role role = Role.CANDIDATE;

        // candidate-only
        private String phone;

        /** Optional. Most candidates on this platform are college students. */
        @Size(max = 150, message = "Keep the college name under 150 characters")
        private String collegeName;

        @Size(max = 120, message = "Keep the location under 120 characters")
        private String location;

        /** Comma-separated, e.g. "Java, Spring Boot, SQL". */
        @Size(max = 255, message = "Keep the skill list under 255 characters")
        private String skills;

        private CandidateType candidateType = CandidateType.FRESHER;
        private Integer experienceYears;
        private String primaryDomain;

        // recruiter-only
        private String department;
        private String designation;
    }

    /**
     * Corrections to an existing account.
     *
     * <p>Deliberately NOT a subset of {@link CreateUserRequest}: it carries
     * only what may be corrected. <b>Email, role, password and enabled are
     * absent, so no request can change them</b> - not omitted from the form and
     * silently ignored, but absent from the type, which is the only version of
     * that guarantee a crafted POST cannot argue with.
     *
     * <ul>
     *   <li><b>Email</b> is the sign-in name and the identity every invite and
     *       reset link was issued against. Changing it is a different operation
     *       from correcting a detail.
     *   <li><b>Role</b> decides what the account is. An account with interviews
     *       against it cannot become a different kind of thing.
     *   <li><b>Password</b> has its own flow (password reset), and
     *       <b>enabled</b> has its own control on the list.
     * </ul>
     *
     * <p>A mutable bean rather than a record for the reason design decision 5
     * gives: Thymeleaf's {@code th:field} needs JavaBean accessors to re-render
     * a rejected form with what the user actually typed.
     */
    @Getter
    @Setter
    public static class UpdateUserRequest {

        @NotBlank(message = "Full name is required")
        private String fullName;

        // candidate-only
        private String phone;

        @Size(max = 150, message = "Keep the college name under 150 characters")
        private String collegeName;

        @Size(max = 120, message = "Keep the location under 120 characters")
        private String location;

        /** Comma-separated, e.g. "Java, Spring Boot, SQL". */
        @Size(max = 255, message = "Keep the skill list under 255 characters")
        private String skills;

        private CandidateType candidateType;
        private Integer experienceYears;
        private String primaryDomain;

        // recruiter-only
        private String department;
        private String designation;
    }

    /**
     * One candidate in the pool a scheduling form can pick from.
     *
     * <p>Separate from {@link UserRow} because it answers a different question:
     * not "what does the admin table show about this account" but "what might
     * someone narrow the pool by before choosing". It carries the profile
     * fields the scheduling forms filter on, which the browser reads off each
     * option as data attributes.
     *
     * <p>{@code id}, {@code fullName} and {@code email} keep the names the
     * scheduling templates already used, so the option markup is unchanged.
     */
    public record SelectableCandidate(
            Long id,
            String fullName,
            String email,
            String collegeName,
            String location,
            String skills,
            String primaryDomain,
            CandidateType candidateType,
            Integer experienceYears) {

        /** "Fresher" or "Experienced" - what the pool filter offers. */
        public String experienceKey() {
            return candidateType == null ? "" : candidateType.name();
        }
    }

    /**
     * Row shown in the admin user table. Never carries the password hash.
     *
     * @param collegeName the candidate's college, or null - always null for a
     *                    recruiter or an administrator, who have no candidate
     *                    profile, and also null for a candidate who was never
     *                    asked
     */
    public record UserRow(Long id, String email, String fullName, Role role,
            boolean enabled, Instant createdAt, String collegeName, String location) {
    }
}
