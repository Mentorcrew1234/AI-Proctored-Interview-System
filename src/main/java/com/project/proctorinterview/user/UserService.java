package com.project.proctorinterview.user;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.dto.CandidateQueryDtos;
import com.project.proctorinterview.interview.dto.CandidateQueryDtos.CandidateFilterOptions;
import com.project.proctorinterview.user.dto.UserDtos.CreateUserRequest;
import com.project.proctorinterview.user.dto.UserDtos.SelectableCandidate;
import com.project.proctorinterview.user.dto.UserDtos.UpdateUserRequest;
import com.project.proctorinterview.user.dto.UserDtos.UserRow;

/** Account management for the admin screens and the /api/users endpoints. */
@Service
@Transactional
public class UserService {

    /** Matches the column width; see V13. */
    static final int MAX_SKILLS_LENGTH = 255;

    private final UserRepository users;
    private final CandidateProfileRepository candidateProfiles;
    private final RecruiterProfileRepository recruiterProfiles;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, CandidateProfileRepository candidateProfiles,
            RecruiterProfileRepository recruiterProfiles, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.candidateProfiles = candidateProfiles;
        this.recruiterProfiles = recruiterProfiles;
        this.passwordEncoder = passwordEncoder;
    }

    public User create(CreateUserRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("An account with that email already exists");
        }
        if (request.getRole() == Role.ADMIN) {
            throw ApiException.badRequest("Admin accounts cannot be created from this screen");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setFullName(request.getFullName().trim());
        user.setRole(request.getRole());
        user.setEnabled(true);
        users.save(user);

        if (request.getRole() == Role.CANDIDATE) {
            CandidateProfile profile = new CandidateProfile();
            profile.setUser(user);
            profile.setPhone(blankToNull(request.getPhone()));
            profile.setCollegeName(blankToNull(request.getCollegeName()));
            profile.setLocation(blankToNull(request.getLocation()));
            profile.setSkills(normaliseSkills(request.getSkills()));
            profile.setCandidateType(request.getCandidateType() == null
                    ? CandidateType.FRESHER
                    : request.getCandidateType());
            profile.setExperienceYears(request.getExperienceYears());
            profile.setPrimaryDomain(blankToNull(request.getPrimaryDomain()));
            candidateProfiles.save(profile);
        } else {
            RecruiterProfile profile = new RecruiterProfile();
            profile.setUser(user);
            profile.setDepartment(blankToNull(request.getDepartment()));
            profile.setDesignation(blankToNull(request.getDesignation()));
            recruiterProfiles.save(profile);
        }

        return user;
    }

    /**
     * Corrects the details on an existing account.
     *
     * <p>There was no way to fix a mistyped detail before this: a profile was
     * written once at account creation and never again, so a wrong college,
     * phone or domain could only be repaired in the database. {@code
     * LIMITATIONS.md} said so plainly.
     *
     * <p><b>What it cannot do is the point.</b> {@link UpdateUserRequest} has no
     * email, role, password or enabled field, so this method could not change
     * them if it wanted to. Each of those is a different operation with its own
     * control, and folding them into "edit the details" is how an innocuous
     * screen quietly becomes the one that can do anything.
     *
     * <p>The profile row is created if it is missing rather than assumed to
     * exist. Accounts predating {@code CandidateProfile} - the seeded
     * administrator has none at all, and nothing guarantees an imported row does
     * - would otherwise be uneditable for exactly the fields this exists to fix.
     */
    public User update(Long userId, UpdateUserRequest request) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));

        user.setFullName(request.getFullName().trim());
        users.save(user);

        if (user.getRole() == Role.CANDIDATE) {
            CandidateProfile profile = candidateProfiles.findByUserId(userId)
                    .orElseGet(() -> {
                        CandidateProfile fresh = new CandidateProfile();
                        fresh.setUser(user);
                        return fresh;
                    });
            profile.setPhone(blankToNull(request.getPhone()));
            profile.setCollegeName(blankToNull(request.getCollegeName()));
            profile.setLocation(blankToNull(request.getLocation()));
            profile.setSkills(normaliseSkills(request.getSkills()));
            profile.setCandidateType(request.getCandidateType() == null
                    ? CandidateType.FRESHER
                    : request.getCandidateType());
            profile.setExperienceYears(request.getExperienceYears());
            profile.setPrimaryDomain(blankToNull(request.getPrimaryDomain()));
            candidateProfiles.save(profile);
        } else if (user.getRole() == Role.RECRUITER) {
            RecruiterProfile profile = recruiterProfiles.findByUserId(userId)
                    .orElseGet(() -> {
                        RecruiterProfile fresh = new RecruiterProfile();
                        fresh.setUser(user);
                        return fresh;
                    });
            profile.setDepartment(blankToNull(request.getDepartment()));
            profile.setDesignation(blankToNull(request.getDesignation()));
            recruiterProfiles.save(profile);
        }
        // An administrator has no profile of either kind, so their name is the
        // whole of what there is to correct - and the form shows only that.

        return user;
    }

    /**
     * The edit form, filled in with what is currently stored.
     *
     * <p>Resolved into the request bean inside the transaction rather than
     * handed to the template as entities: {@code open-in-view} is off, and the
     * profile association is lazy.
     */
    @Transactional(readOnly = true)
    public UpdateUserRequest editForm(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));

        UpdateUserRequest form = new UpdateUserRequest();
        form.setFullName(user.getFullName());

        if (user.getRole() == Role.CANDIDATE) {
            candidateProfiles.findByUserId(userId).ifPresent(profile -> {
                form.setPhone(profile.getPhone());
                form.setCollegeName(profile.getCollegeName());
                form.setLocation(profile.getLocation());
                form.setSkills(profile.getSkills());
                form.setCandidateType(profile.getCandidateType());
                form.setExperienceYears(profile.getExperienceYears());
                form.setPrimaryDomain(profile.getPrimaryDomain());
            });
        } else if (user.getRole() == Role.RECRUITER) {
            recruiterProfiles.findByUserId(userId).ifPresent(profile -> {
                form.setDepartment(profile.getDepartment());
                form.setDesignation(profile.getDesignation());
            });
        }
        return form;
    }

    /** One account as a row - the header the edit page names the account with. */
    @Transactional(readOnly = true)
    public UserRow row(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        return toRow(user, candidateProfiles.findByUserId(userId).orElse(null));
    }

    /** Disabling is preferred over deleting: interviews keep referencing the user. */
    public void setEnabled(Long userId, boolean enabled) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (user.getRole() == Role.ADMIN && !enabled) {
            throw ApiException.badRequest("The administrator account cannot be disabled");
        }
        user.setEnabled(enabled);
        users.save(user);
    }

    @Transactional(readOnly = true)
    public List<UserRow> listAll() {
        return toRows(users.findAll());
    }

    @Transactional(readOnly = true)
    public List<UserRow> listByRole(Role role) {
        return toRows(users.findByRoleOrderByFullNameAsc(role));
    }

    /**
     * The admin user list, filtered by role, a free-text match on name or
     * email, and enabled/disabled status - any combination, all optional.
     *
     * <p>A stream over the role-scoped rows rather than a query per field
     * combination: the accounts table is small at prototype scale (the same
     * reasoning documented for interview search), and a role is already an
     * indexed lookup, so this adds one pass over an already-small list rather
     * than a second query.
     *
     * @param role   restricts to one role, or every role if null
     * @param search matched case-insensitively against full name and email;
     *               ignored if null or blank
     * @param enabled restricts to enabled ({@code true}) or disabled
     *               ({@code false}) accounts, or both if null
     */
    @Transactional(readOnly = true)
    public List<UserRow> search(Role role, String search, Boolean enabled) {
        List<User> scoped = role == null
                ? users.findAllByOrderByFullNameAsc()
                : users.findByRoleOrderByFullNameAsc(role);

        String needle = (search == null || search.isBlank()) ? null : search.trim().toLowerCase();

        return toRows(scoped.stream()
                .filter(u -> needle == null
                        || u.getFullName().toLowerCase().contains(needle)
                        || u.getEmail().toLowerCase().contains(needle))
                .filter(u -> enabled == null || u.isEnabled() == enabled)
                .toList());
    }

    /**
     * How many accounts hold a role, and how many of those are enabled.
     *
     * <p>Two count queries rather than fetching every user and counting in
     * Java, which is what a dashboard tile used to cost.
     */
    @Transactional(readOnly = true)
    public RoleCount countByRole(Role role) {
        return new RoleCount(users.countByRole(role), users.countByRoleAndEnabled(role, true));
    }

    /** Accounts of one role, split into enabled and total. */
    public record RoleCount(long total, long enabled) {
        public long disabled() {
            return total - enabled;
        }
    }

    /** Enabled candidates only - the pool an recruiter can schedule against. */
    @Transactional(readOnly = true)
    public List<SelectableCandidate> selectableCandidates() {
        List<User> pool = users.findByRoleOrderByFullNameAsc(Role.CANDIDATE).stream()
                .filter(User::isEnabled)
                .toList();
        if (pool.isEmpty()) {
            return List.of();
        }
        // One profile query for the whole pool, as the user list does - not one
        // lookup per option.
        Map<Long, CandidateProfile> profiles = candidateProfiles
                .findByUserIdIn(pool.stream().map(User::getId).toList()).stream()
                .collect(Collectors.toMap(p -> p.getUser().getId(), p -> p));

        return pool.stream().map(u -> {
            CandidateProfile profile = profiles.get(u.getId());
            return new SelectableCandidate(u.getId(), u.getFullName(), u.getEmail(),
                    profile == null ? null : profile.getCollegeName(),
                    profile == null ? null : profile.getLocation(),
                    profile == null ? null : profile.getSkills(),
                    profile == null ? null : profile.getPrimaryDomain(),
                    profile == null ? null : profile.getCandidateType(),
                    profile == null ? null : profile.getExperienceYears());
        }).toList();
    }

    /**
     * The values the scheduling pool filter offers, from the pool itself.
     *
     * <p>Unlike the candidates page, this is not scoped to one recruiter: the
     * pool a scheduling form can pick from has always been every enabled
     * candidate, so the options describe exactly the list already on screen and
     * disclose nothing that list does not.
     */
    @Transactional(readOnly = true)
    public CandidateFilterOptions selectablePoolOptions() {
        List<SelectableCandidate> pool = selectableCandidates();
        return new CandidateFilterOptions(
                distinct(pool.stream().map(SelectableCandidate::collegeName)),
                distinct(pool.stream().map(SelectableCandidate::location)),
                distinct(pool.stream().flatMap(c -> CandidateQueryDtos.splitSkills(c.skills()).stream())),
                distinct(pool.stream().map(SelectableCandidate::primaryDomain)));
    }

    private static List<String> distinct(Stream<String> values) {
        return values.filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /**
     * Maps accounts to rows, attaching each candidate's college name.
     *
     * <p>The profiles are fetched in ONE query keyed by user id rather than one
     * lookup per row - the difference between a second query and N of them. The
     * same reasoning that keeps the search filter in Java applies to reading
     * them all: at prototype scale the profile table is as short as the account
     * table, and a per-row lookup on a list this small is the only version of
     * this that would actually cost anything.
     *
     * <p>A user with no candidate profile - every recruiter and administrator -
     * simply gets null, which is what the row already documents.
     */
    private List<UserRow> toRows(List<User> accounts) {
        if (accounts.isEmpty()) {
            return List.of();
        }
        List<Long> ids = accounts.stream().map(User::getId).toList();
        Map<Long, CandidateProfile> profiles = candidateProfiles.findByUserIdIn(ids).stream()
                .collect(Collectors.toMap(p -> p.getUser().getId(), p -> p));

        return accounts.stream().map(u -> toRow(u, profiles.get(u.getId()))).toList();
    }

    private static UserRow toRow(User user, CandidateProfile profile) {
        return new UserRow(user.getId(), user.getEmail(), user.getFullName(),
                user.getRole(), user.isEnabled(), user.getCreatedAt(),
                profile == null ? null : profile.getCollegeName(),
                profile == null ? null : profile.getLocation());
    }

    /**
     * An untyped-in field submits "" rather than null, and a blank Excel cell
     * arrives the same way. Storing that would make "recorded as empty"
     * indistinguishable from "never asked", which is exactly the distinction
     * the nullable column exists to keep.
     */
    /**
     * Writes a skill list in the one shape every reader may assume:
     * comma-separated, each entry trimmed, blanks dropped, duplicates removed
     * case-insensitively, first spelling wins, order preserved.
     *
     * <p>Doing this once on the way in is what lets the filter and the option
     * list split on a comma and trust the pieces. The alternative - every
     * reader re-cleaning the string - is how "Java" and " java " end up as two
     * different filter options.
     *
     * <p>Truncation is deliberate rather than a validation error at this layer:
     * the DTOs already reject an over-long list with a message, and this is the
     * backstop for a caller that is not a validated form.
     */
    static String normaliseSkills(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Map<String, String> bySpelling = new LinkedHashMap<>();
        for (String part : value.split(",")) {
            String skill = part.trim();
            if (!skill.isEmpty()) {
                bySpelling.putIfAbsent(skill.toLowerCase(Locale.ROOT), skill);
            }
        }
        if (bySpelling.isEmpty()) {
            return null;
        }
        String joined = String.join(", ", bySpelling.values());
        return joined.length() <= MAX_SKILLS_LENGTH ? joined : joined.substring(0, MAX_SKILLS_LENGTH);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
