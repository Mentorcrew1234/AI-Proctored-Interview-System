package com.project.proctorinterview.bulk;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.bulk.dto.BulkDtos.RawRow;
import com.project.proctorinterview.bulk.dto.BulkDtos.RowError;
import com.project.proctorinterview.bulk.dto.BulkDtos.ValidatedRow;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewRepository;
import com.project.proctorinterview.interview.InterviewService;
import com.project.proctorinterview.interview.dto.InterviewDtos;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Validates every uploaded row before anything is written.
 *
 * <p>Runs in five levels: the file itself (in {@link BulkExcelService}), then
 * per-row required fields and formats, conditional rules, duplicates inside the
 * file, and finally checks against the database.
 *
 * <p>Rules are taken from the existing single-schedule flow rather than
 * invented: the same domain list, the same enums, and the same
 * experienced-needs-years rule that {@code InterviewService.create} enforces.
 * Bulk scheduling must not be able to create an interview that the single form
 * would have rejected.
 */
@Service
public class BulkValidationService {

    /** Deliberately permissive - matching the single form's Bean Validation. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH));

    private static final List<DateTimeFormatter> TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("HH:mm"),
            DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH));

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);

    private static final int MIN_QUESTIONS = 1;
    private static final int MAX_QUESTIONS = 10;

    private final UserRepository users;
    private final InterviewRepository interviews;

    public BulkValidationService(UserRepository users, InterviewRepository interviews) {
        this.users = users;
        this.interviews = interviews;
    }

    /**
     * Validates all rows together, because duplicate detection needs the whole
     * file in view.
     */
    @Transactional(readOnly = true)
    public List<ValidatedRow> validate(List<RawRow> rawRows) {
        Set<String> seenKeys = new HashSet<>();
        List<ValidatedRow> results = new ArrayList<>();

        for (RawRow raw : rawRows) {
            results.add(validateRow(raw, seenKeys));
        }
        return results;
    }

    private ValidatedRow validateRow(RawRow raw, Set<String> seenKeys) {
        List<RowError> errors = new ArrayList<>();

        // ---- level 2: required fields and formats ---------------------------

        String interviewName = trim(raw.interviewName());
        if (interviewName.isEmpty()) {
            errors.add(new RowError("Interview Name", "Interview Name is required."));
        } else if (interviewName.length() > 150) {
            errors.add(new RowError("Interview Name", "Keep it under 150 characters."));
        }

        String name = trim(raw.candidateName());
        if (name.isEmpty()) {
            errors.add(new RowError("Candidate Name", "Required."));
        }

        String email = trim(raw.candidateEmail()).toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            errors.add(new RowError("Candidate Email", "Required."));
        } else if (!EMAIL.matcher(email).matches()) {
            errors.add(new RowError("Candidate Email", "Not a valid email address."));
        }

        // Optional, and only ever used to populate a NEW candidate account.
        // Length is the only rule: it is free text, exactly like Domain, and a
        // college the system has not seen before is the normal case rather
        // than an error.
        String collegeName = null;
        String rawCollege = trim(raw.collegeName());
        if (rawCollege.length() > 150) {
            errors.add(new RowError("College Name", "Keep the college name under 150 characters."));
        } else if (!rawCollege.isEmpty()) {
            collegeName = rawCollege;
        }

        String location = null;
        String rawLocation = trim(raw.location());
        if (rawLocation.length() > 120) {
            errors.add(new RowError("Location", "Keep the location under 120 characters."));
        } else if (!rawLocation.isEmpty()) {
            location = rawLocation;
        }

        // Left exactly as typed here. UserService.normaliseSkills is the single
        // place that decides how a skill list is written, and re-deciding it in
        // the validator is how the spreadsheet path and the form path drift into
        // producing two different shapes of the same column.
        String skills = null;
        String rawSkills = trim(raw.skills());
        if (rawSkills.length() > 255) {
            errors.add(new RowError("Skills", "Keep the skill list under 255 characters."));
        } else if (!rawSkills.isEmpty()) {
            skills = rawSkills;
        }

        LocalDate date = null;
        if (trim(raw.interviewDate()).isEmpty()) {
            errors.add(new RowError("Interview Date", "Required."));
        } else {
            date = parseDate(trim(raw.interviewDate()));
            if (date == null) {
                errors.add(new RowError("Interview Date",
                        "Not a valid date. Use YYYY-MM-DD, for example 2026-09-01."));
            }
        }

        LocalTime time = null;
        if (trim(raw.interviewTime()).isEmpty()) {
            errors.add(new RowError("Interview Time", "Required."));
        } else {
            time = parseTime(trim(raw.interviewTime()));
            if (time == null) {
                errors.add(new RowError("Interview Time",
                        "Not a valid time. Use 24-hour HH:MM, for example 14:30."));
            }
        }

        LocalDateTime scheduledAt = null;
        if (date != null && time != null) {
            scheduledAt = LocalDateTime.of(date, time);
            if (scheduledAt.isBefore(LocalDateTime.now())) {
                errors.add(new RowError("Interview Date", "Interview date and time cannot be in the past."));
            }
        }

        String domain = null;
        String rawDomain = trim(raw.domain());
        if (rawDomain.isEmpty()) {
            errors.add(new RowError("Domain", "Required."));
        } else if (rawDomain.length() > 80) {
            errors.add(new RowError("Domain", "Keep the domain under 80 characters."));
        } else {
            // Any topic is allowed, matching the single scheduling form. This
            // used to reject anything off a fixed list, which meant bulk could
            // do LESS than the single form - the opposite of the rule that bulk
            // must never do more. Normalising still folds "java" onto "Java" so
            // a spreadsheet's casing does not fragment the filter list.
            domain = InterviewService.normaliseDomain(rawDomain);
        }

        CandidateType candidateType = null;
        String rawType = trim(raw.experienceType());
        if (rawType.isEmpty()) {
            errors.add(new RowError("Experience Type", "Required."));
        } else {
            candidateType = parseEnum(CandidateType.class, rawType);
            if (candidateType == null) {
                errors.add(new RowError("Experience Type", "Must be FRESHER or EXPERIENCED."));
            }
        }

        InterviewLanguage language = null;
        String rawLanguage = trim(raw.language());
        if (rawLanguage.isEmpty()) {
            errors.add(new RowError("Language", "Required."));
        } else {
            language = parseEnum(InterviewLanguage.class, rawLanguage);
            if (language == null) {
                errors.add(new RowError("Language",
                        "Only English is supported in this version."));
            }
        }

        InterviewType interviewType = null;
        String rawInterviewType = trim(raw.interviewType());
        if (rawInterviewType.isEmpty()) {
            errors.add(new RowError("Interview Type", "Required."));
        } else {
            interviewType = parseEnum(InterviewType.class, rawInterviewType.replace(' ', '_').replace('/', '_'));
            if (interviewType == null) {
                errors.add(new RowError("Interview Type", "Must be TECHNICAL or HR_GENERAL."));
            }
        }

        // ---- level 3: conditional -------------------------------------------

        Integer experienceYears = null;
        String rawYears = trim(raw.yearsOfExperience());
        if (!rawYears.isEmpty()) {
            try {
                experienceYears = Integer.parseInt(rawYears);
                if (experienceYears < 0 || experienceYears > 50) {
                    errors.add(new RowError("Years of Experience", "Must be between 0 and 50."));
                    experienceYears = null;
                }
            } catch (NumberFormatException e) {
                errors.add(new RowError("Years of Experience", "Must be a whole number."));
            }
        }

        if (candidateType == CandidateType.EXPERIENCED) {
            if (experienceYears == null && rawYears.isEmpty()) {
                errors.add(new RowError("Years of Experience",
                        "Required when Experience Type is EXPERIENCED."));
            } else if (experienceYears != null && experienceYears < 1) {
                errors.add(new RowError("Years of Experience",
                        "Must be at least 1 for an experienced candidate."));
            }
        } else if (candidateType == CandidateType.FRESHER) {
            // A fresher may leave this blank or put 0. Neither is an error, and
            // the stored value is always null so the two cases stay distinct.
            experienceYears = null;
        }

        int questionCount = 5;
        String rawCount = trim(raw.questionCount());
        if (!rawCount.isEmpty()) {
            try {
                questionCount = Integer.parseInt(rawCount);
                if (questionCount < MIN_QUESTIONS || questionCount > MAX_QUESTIONS) {
                    errors.add(new RowError("Question Count",
                            "Must be between " + MIN_QUESTIONS + " and " + MAX_QUESTIONS + "."));
                    questionCount = 5;
                }
            } catch (NumberFormatException e) {
                errors.add(new RowError("Question Count", "Must be a whole number."));
            }
        }

        // Test duration. Optional in the file so an older template still
        // uploads; the default matches the scheduling form's own default, so a
        // blank cell produces the same interview the single form would have.
        int durationMinutes = InterviewDtos.DEFAULT_DURATION_MINUTES;
        String rawDuration = trim(raw.testDuration());
        if (!rawDuration.isEmpty()) {
            try {
                durationMinutes = Integer.parseInt(rawDuration);
                if (durationMinutes < InterviewDtos.MIN_DURATION_MINUTES
                        || durationMinutes > InterviewDtos.MAX_DURATION_MINUTES) {
                    errors.add(new RowError("Test Duration (minutes)",
                            "Must be between " + InterviewDtos.MIN_DURATION_MINUTES + " and "
                                    + InterviewDtos.MAX_DURATION_MINUTES + "."));
                    durationMinutes = InterviewDtos.DEFAULT_DURATION_MINUTES;
                }
            } catch (NumberFormatException e) {
                errors.add(new RowError("Test Duration (minutes)", "Must be a whole number."));
            }
        }

        // ---- level 4: duplicates within the file ----------------------------

        if (!email.isEmpty() && scheduledAt != null) {
            String key = email + "|" + scheduledAt;
            if (!seenKeys.add(key)) {
                errors.add(new RowError("Candidate Email",
                        "Duplicate of an earlier row: same candidate at the same date and time."));
            }
        }

        // ---- level 5: database and business rules ---------------------------

        boolean candidateExists = false;
        if (!email.isEmpty() && EMAIL.matcher(email).matches()) {
            User existing = users.findByEmailIgnoreCase(email).orElse(null);
            if (existing != null) {
                candidateExists = true;
                // Same rules the single-schedule form applies.
                if (existing.getRole() != Role.CANDIDATE) {
                    errors.add(new RowError("Candidate Email",
                            "That email belongs to a non-candidate account."));
                } else if (!existing.isEnabled()) {
                    errors.add(new RowError("Candidate Email", "That candidate account is disabled."));
                } else if (scheduledAt != null && hasClashingInterview(existing, scheduledAt)) {
                    errors.add(new RowError("Interview Date",
                            "This candidate already has an interview scheduled at that time."));
                }
            }
        }

        boolean valid = errors.isEmpty();
        return new ValidatedRow(
                raw.excelRowNumber(),
                interviewName,
                name,
                email,
                collegeName,
                location,
                skills,
                valid ? scheduledAt : null,
                scheduledAt == null ? "" : scheduledAt.format(DISPLAY),
                domain,
                candidateType,
                experienceYears,
                language,
                interviewType,
                questionCount,
                durationMinutes,
                valid,
                List.copyOf(errors),
                candidateExists);
    }

    /** An existing, still-open interview for the same candidate at the same time. */
    private boolean hasClashingInterview(User candidate, LocalDateTime scheduledAt) {
        var instant = scheduledAt.atZone(ZoneId.systemDefault()).toInstant();
        return interviews.findByCandidateIdOrderByScheduledAtDesc(candidate.getId()).stream()
                .filter(i -> i.getStatus() == InterviewStatus.SCHEDULED
                        || i.getStatus() == InterviewStatus.IN_PROGRESS)
                .map(Interview::getScheduledAt)
                .anyMatch(existing -> existing.equals(instant));
    }

    // ---- parsing helpers ----------------------------------------------------

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static LocalDate parseDate(String value) {
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // try the next accepted format
            }
        }
        return null;
    }

    private static LocalTime parseTime(String value) {
        String normalised = value.toUpperCase(Locale.ENGLISH).replace(".", "");
        for (DateTimeFormatter format : TIME_FORMATS) {
            try {
                return LocalTime.parse(normalised, format);
            } catch (DateTimeParseException ignored) {
                // try the next accepted format
            }
        }
        return null;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
