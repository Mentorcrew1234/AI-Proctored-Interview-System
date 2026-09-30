package com.project.proctorinterview.interview;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.mail.MailEvents.InterviewScheduled;

import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.config.QuestionModeProperties;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.dto.BulkEditDtos.BulkEditFields;
import com.project.proctorinterview.interview.dto.InterviewDtos;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.interview.dto.InterviewDtos.InterviewSummary;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.report.ReportRepository;
import com.project.proctorinterview.user.UserRepository;

/**
 * Interview scheduling and lifecycle. Shared by the Thymeleaf pages and the
 * REST controller so the rules live in exactly one place.
 */
@Service
@Transactional
public class InterviewService {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    private final InterviewRepository interviews;
    private final InterviewSessionRepository sessions;
    private final UserRepository users;
    /** Only to tell the list whether a report exists; reporting is untouched. */
    private final ReportRepository reports;
    /**
     * Announces a committed interview so the candidate can be emailed. Publishing
     * rather than calling a mail service keeps scheduling unaware of email
     * entirely: the listener is what waits for the commit, and with
     * {@code app.mail.enabled=false} nothing acts on the event at all.
     */
    private final ApplicationEventPublisher events;
    /** Only to resolve "inherit" into a concrete mode for display. */
    private final QuestionModeProperties questionModeProperties;

    public InterviewService(InterviewRepository interviews, InterviewSessionRepository sessions,
            UserRepository users, ReportRepository reports, ApplicationEventPublisher events,
            QuestionModeProperties questionModeProperties) {
        this.interviews = interviews;
        this.sessions = sessions;
        this.users = users;
        this.reports = reports;
        this.events = events;
        this.questionModeProperties = questionModeProperties;
    }

    public Interview create(Long recruiterId, CreateInterviewRequest request) {
        User recruiter = users.findById(recruiterId)
                .orElseThrow(() -> ApiException.notFound("Recruiter"));

        User candidate = users.findById(request.getCandidateId())
                .orElseThrow(() -> ApiException.notFound("Candidate"));
        if (candidate.getRole() != Role.CANDIDATE) {
            throw ApiException.badRequest("The selected user is not a candidate");
        }
        if (!candidate.isEnabled()) {
            throw ApiException.badRequest("That candidate account is disabled");
        }

        // An experienced candidate must say how many years; a fresher must not.
        Integer years = request.getCandidateType() == CandidateType.EXPERIENCED
                ? request.getExperienceYears()
                : null;
        if (request.getCandidateType() == CandidateType.EXPERIENCED
                && (years == null || years < 1)) {
            throw ApiException.badRequest("Enter the years of experience for an experienced candidate");
        }

        Interview interview = new Interview();
        interview.setInterviewName(request.getInterviewName() == null
                ? null
                : request.getInterviewName().trim());
        interview.setRecruiter(recruiter);
        interview.setCandidate(candidate);
        interview.setScheduledAt(request.getScheduledAt().atZone(ZoneId.systemDefault()).toInstant());
        interview.setCandidateType(request.getCandidateType());
        interview.setExperienceYears(years);
        interview.setDomain(normaliseDomain(request.getDomain()));
        interview.setInterviewType(request.getInterviewType());
        interview.setQuestionCount(request.getQuestionCount());
        // Null is "inherit the server default" - see Interview.questionMode.
        interview.setQuestionMode(request.getQuestionMode());
        interview.setDurationMinutes(validDuration(request.getDurationMinutes()));
        interview.setStatus(InterviewStatus.SCHEDULED);
        // Random, unguessable, and unique - this is the candidate's entry credential.
        interview.setInviteToken(UUID.randomUUID().toString());
        interview.setResultVisibleToCandidate(request.isResultVisibleToCandidate());

        Interview saved = interviews.save(interview);

        // Every scheduling path reaches this line - the single form, the
        // multi-candidate form and the Excel bulk upload all call this method -
        // so no scenario can quietly skip the invitation. The snapshot is built
        // here, while candidate and recruiter are still attached; the listener
        // runs after commit, when they would no longer be loadable because
        // open-in-view is off.
        events.publishEvent(new InterviewScheduled(
                saved.getId(),
                candidate.getFullName(),
                candidate.getEmail(),
                recruiter.getFullName(),
                saved.getInterviewName(),
                saved.getDomain(),
                saved.getInterviewType(),
                saved.getScheduledAt(),
                saved.getDurationMinutes(),
                saved.getQuestionCount(),
                saved.getInviteToken()));

        return saved;
    }

    @Transactional(readOnly = true)
    public List<InterviewSummary> listForRecruiter(Long recruiterId) {
        return interviews.findByRecruiterIdOrderByScheduledAtDesc(recruiterId)
                .stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public List<InterviewSummary> listAll() {
        return interviews.findAllByOrderByScheduledAtDesc()
                .stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public List<InterviewSummary> listForCandidate(Long candidateId) {
        return interviews.findByCandidateIdOrderByScheduledAtDesc(candidateId)
                .stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public InterviewSummary getSummary(Long id) {
        return toSummary(interviews.findById(id).orElseThrow(() -> ApiException.notFound("Interview")));
    }

    /** An recruiter may only touch their own interviews; an admin may touch any. */
    @Transactional(readOnly = true)
    public Interview getForActor(Long interviewId, Long actorId, Role actorRole) {
        Interview interview = interviews.findById(interviewId)
                .orElseThrow(() -> ApiException.notFound("Interview"));
        if (actorRole == Role.RECRUITER && !interview.getRecruiter().getId().equals(actorId)) {
            throw ApiException.forbidden("This interview belongs to another recruiter");
        }
        return interview;
    }

    /**
     * As {@link #getForActor}, but also resolves the candidate association
     * before the transaction closes. Open-in-view is off, so a caller that
     * reads {@code interview.getCandidate().getFullName()} after this method
     * returns (e.g. to render a read-only candidate field on the edit form)
     * would otherwise hit {@code LazyInitializationException}.
     */
    @Transactional(readOnly = true)
    public Interview getForActorWithCandidate(Long interviewId, Long actorId, Role actorRole) {
        Interview interview = getForActor(interviewId, actorId, actorRole);
        interview.getCandidate().getFullName();
        return interview;
    }

    public void cancel(Long interviewId, Long actorId, Role actorRole) {
        Interview interview = getForActor(interviewId, actorId, actorRole);
        if (interview.getStatus() == InterviewStatus.COMPLETED) {
            throw ApiException.conflict("A completed interview cannot be cancelled");
        }
        interview.setStatus(InterviewStatus.CANCELLED);
        interviews.save(interview);
    }

    /**
     * Edits a scheduled interview's configuration. The candidate, recruiter,
     * invite token and status are never touched here - only {@link #cancel} and
     * the candidate-facing exam flow change status, and reassigning the
     * candidate or recruiter is a reschedule, not an edit.
     *
     * <p>Once a session exists the questions were already generated against the
     * old domain/question count/candidate type, so editing is only allowed while
     * the interview is still {@code SCHEDULED}.
     */
    public Interview update(Long interviewId, Long actorId, Role actorRole, CreateInterviewRequest request) {
        Interview interview = getForActor(interviewId, actorId, actorRole);
        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw ApiException.conflict("Only a scheduled interview can be edited");
        }

        Instant scheduledAt = request.getScheduledAt().atZone(ZoneId.systemDefault()).toInstant();
        if (!scheduledAt.isAfter(Instant.now())) {
            throw ApiException.badRequest("Scheduled date and time must be in the future");
        }

        // An experienced candidate must say how many years; a fresher must not.
        Integer years = request.getCandidateType() == CandidateType.EXPERIENCED
                ? request.getExperienceYears()
                : null;
        if (request.getCandidateType() == CandidateType.EXPERIENCED
                && (years == null || years < 1)) {
            throw ApiException.badRequest("Enter the years of experience for an experienced candidate");
        }

        interview.setInterviewName(request.getInterviewName() == null
                ? null
                : request.getInterviewName().trim());
        interview.setScheduledAt(scheduledAt);
        interview.setCandidateType(request.getCandidateType());
        interview.setExperienceYears(years);
        interview.setDomain(normaliseDomain(request.getDomain()));
        interview.setInterviewType(request.getInterviewType());
        interview.setQuestionCount(request.getQuestionCount());
        // Null is "inherit the server default" - see Interview.questionMode.
        interview.setQuestionMode(request.getQuestionMode());
        interview.setDurationMinutes(validDuration(request.getDurationMinutes()));
        interview.setResultVisibleToCandidate(request.isResultVisibleToCandidate());

        return interviews.save(interview);
    }

    /**
     * Applies a bulk edit's field mask to one interview: only a field whose
     * "apply" flag is set is changed, everything else is left as is. Same
     * guards and per-field validation as {@link #update}, so a bulk edit can
     * never produce a state the single-interview form would have rejected.
     */
    public Interview bulkUpdateFields(Long interviewId, Long actorId, Role actorRole, BulkEditFields fields) {
        Interview interview = getForActor(interviewId, actorId, actorRole);
        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw ApiException.conflict("Only a scheduled interview can be edited");
        }

        if (fields.isApplyInterviewName()) {
            interview.setInterviewName(fields.getInterviewName() == null
                    ? null
                    : fields.getInterviewName().trim());
        }
        if (fields.isApplyScheduledAt()) {
            if (fields.getScheduledAt() == null) {
                throw ApiException.badRequest("Enter a scheduled date and time");
            }
            Instant scheduledAt = fields.getScheduledAt().atZone(ZoneId.systemDefault()).toInstant();
            if (!scheduledAt.isAfter(Instant.now())) {
                throw ApiException.badRequest("Scheduled date and time must be in the future");
            }
            // Double-booking a candidate is intentionally allowed here, same as
            // a single edit - there is no cross-interview conflict check yet.
            interview.setScheduledAt(scheduledAt);
        }
        if (fields.isApplyDomain()) {
            if (fields.getNewDomain() == null || fields.getNewDomain().isBlank()) {
                throw ApiException.badRequest("Enter a domain");
            }
            interview.setDomain(normaliseDomain(fields.getNewDomain()));
        }
        if (fields.isApplyInterviewType()) {
            if (fields.getNewInterviewType() == null) {
                throw ApiException.badRequest("Select an interview type");
            }
            interview.setInterviewType(fields.getNewInterviewType());
        }
        if (fields.isApplyQuestionCount()) {
            Integer count = fields.getQuestionCount();
            if (count == null || count < 1 || count > 10) {
                throw ApiException.badRequest("Number of questions must be between 1 and 10");
            }
            interview.setQuestionCount(count);
        }
        if (fields.isApplyDurationMinutes()) {
            interview.setDurationMinutes(validDuration(fields.getDurationMinutes()));
        }
        if (fields.isApplyResultVisibleToCandidate()) {
            interview.setResultVisibleToCandidate(fields.isResultVisibleToCandidate());
        }
        if (fields.isApplyCandidateType()) {
            CandidateType type = fields.getCandidateType();
            if (type == null) {
                throw ApiException.badRequest("Select fresher or experienced");
            }
            Integer years = type == CandidateType.EXPERIENCED ? fields.getExperienceYears() : null;
            if (type == CandidateType.EXPERIENCED && (years == null || years < 1)) {
                throw ApiException.badRequest("Enter the years of experience for an experienced candidate");
            }
            interview.setCandidateType(type);
            interview.setExperienceYears(years);
        }

        return interviews.save(interview);
    }

    /**
     * Deletes a scheduled interview. Restricted to {@code SCHEDULED} interviews
     * that have never had a session started against them, so there is never any
     * dependent question, answer, proctor-event or report data to reconcile - a
     * plain hard delete is safe.
     *
     * <p>Once the candidate has started (or finished) an interview, that history
     * is preserved permanently; deletion is refused rather than attempting to
     * cascade through it.
     */
    public void delete(Long interviewId, Long actorId, Role actorRole) {
        Interview interview = getForActor(interviewId, actorId, actorRole);
        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw ApiException.conflict(
                    "Only a scheduled interview with no session can be deleted; "
                            + "this one has already started or finished");
        }
        // Defence in depth: status alone should imply no session exists (ExamService
        // flips status to IN_PROGRESS in the same transaction it creates one), but
        // never delete a parent row while a dependent session still references it.
        if (sessions.findByInterviewId(interviewId).isPresent()) {
            throw ApiException.conflict(
                    "This interview already has a session and cannot be deleted");
        }
        interviews.delete(interview);
    }

    public InterviewSummary toSummary(Interview interview) {
        Long sessionId = sessions.findByInterviewId(interview.getId())
                .map(InterviewSession::getId)
                .orElse(null);

        return new InterviewSummary(
                interview.getId(),
                interview.getInterviewName(),
                interview.getDisplayName(),
                interview.getCandidate().getFullName(),
                interview.getCandidate().getEmail(),
                interview.getRecruiter().getFullName(),
                interview.getScheduledAt(),
                DISPLAY_FORMAT.format(interview.getScheduledAt()),
                interview.getCandidateType(),
                interview.getExperienceYears(),
                interview.getDomain(),
                interview.getInterviewType(),
                interview.getQuestionCount(),
                // Resolved here so every reader sees what will actually happen,
                // not the raw null that means "inherit".
                questionModeProperties.effectiveFor(interview.getQuestionMode()),
                interview.getDurationMinutes(),
                interview.getStatus(),
                interview.getInviteToken(),
                interview.isResultVisibleToCandidate(),
                sessionId,
                sessionId != null && reports.existsBySessionId(sessionId));
    }

    /**
     * The server's own check on test duration.
     *
     * <p>Bean Validation already covers the two forms, but bulk scheduling and
     * bulk edit do not go through it. Putting the rule here means every path
     * that can write a duration is held to the same bounds - a bulk action can
     * never produce a duration the single form would have rejected.
     */
    static int validDuration(Integer minutes) {
        if (minutes == null) {
            throw ApiException.badRequest("Test duration is required");
        }
        if (minutes < InterviewDtos.MIN_DURATION_MINUTES || minutes > InterviewDtos.MAX_DURATION_MINUTES) {
            throw ApiException.badRequest("Test duration must be between "
                    + InterviewDtos.MIN_DURATION_MINUTES + " and "
                    + InterviewDtos.MAX_DURATION_MINUTES + " minutes");
        }
        return minutes;
    }

    /** Domains offered in the interview form. */
    /**
     * Domains offered as <b>suggestions</b>, not as a whitelist.
     *
     * <p>Any topic may be typed. The AI generator takes the domain straight
     * into the prompt and is perfectly capable of "Rust and WebAssembly" or
     * "Spring Boot + Kafka"; it was only ever the offline bank that needed a
     * known name, and constraining the whole system to the bank's vocabulary
     * was the wrong way round.
     *
     * <p>What an off-list domain costs: if the AI is unavailable - or the
     * server is in MOCK mode - the interview is served from the offline bank,
     * which has no curated questions for it and falls back to a general
     * engineering set. That is stated on the scheduling form rather than
     * discovered afterwards, and {@link #bankedDomains()} is what the form uses
     * to say which domains do have their own questions.
     */
    public static List<String> availableDomains() {
        return List.of(
                // Languages and platforms
                "Java", "Python", "JavaScript", "TypeScript", "C++", "C",
                "C#", "Go", "Rust", "PHP", "Ruby", "Kotlin", "Swift", "SQL",
                // Application areas
                "Web Development", "Frontend Development", "Backend Development",
                "Full Stack Development", "Mobile Development", "Android Development",
                "iOS Development", "Game Development", "Embedded Systems",
                // Frameworks and stacks
                "React", "Angular", "Vue", "Node.js", "Spring Boot", ".NET",
                "Django", "Flask", "Express.js", "React Native", "Flutter",
                // Data and AI
                "Database", "Data Structures & Algorithms", "Data Science",
                "Machine Learning", "Deep Learning", "Data Engineering",
                "Big Data", "Data Analytics", "Natural Language Processing",
                "Computer Vision",
                // Infrastructure and operations
                "Cloud Computing", "AWS", "Azure", "DevOps", "Docker",
                "Kubernetes", "Linux", "Networking", "Cybersecurity",
                "Site Reliability Engineering",
                // Foundations and practice
                "Software Engineering", "System Design", "Operating Systems",
                "Computer Networks", "Object Oriented Programming",
                "Testing & QA", "Software Architecture", "Microservices",
                "API Development", "Version Control & Git", "Agile & Scrum",
                "UI/UX Design", "Business Analysis");
    }

    /**
     * The subset that has its own curated questions in the offline bank.
     *
     * <p>Read from the bank itself rather than duplicated here, so the two
     * cannot drift apart: adding a domain to {@code question-bank.json} is
     * enough to make the form stop warning about it.
     */
    public static List<String> bankedDomains() {
        return OfflineBankIndex.technicalDomains();
    }

    /**
     * Tidies a typed domain without rejecting it.
     *
     * <p>Free text needs canonicalising or "java", "Java " and "JAVA" become
     * three different domains in the filter list and three different bank
     * lookups. A value that matches a suggestion case-insensitively takes the
     * suggestion's spelling; anything else keeps the recruiter's own wording,
     * trimmed and with runs of whitespace collapsed.
     */
    public static String normaliseDomain(String raw) {
        if (raw == null) {
            return null;
        }
        String tidied = raw.trim().replaceAll("\\s+", " ");
        if (tidied.isEmpty()) {
            return tidied;
        }
        for (String known : availableDomains()) {
            if (known.equalsIgnoreCase(tidied)) {
                return known;
            }
        }
        return tidied;
    }

    @Transactional(readOnly = true)
    public Instant now() {
        return Instant.now();
    }
}
