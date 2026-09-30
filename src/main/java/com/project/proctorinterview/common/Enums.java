package com.project.proctorinterview.common;

/**
 * Domain enumerations, grouped in one file so the whole vocabulary of the
 * system can be read at a glance. All are persisted with
 * {@code @Enumerated(EnumType.STRING)} so the database stays readable.
 */
public final class Enums {

    private Enums() {
    }

    public enum Role {
        ADMIN, RECRUITER, CANDIDATE
    }

    public enum CandidateType {
        FRESHER, EXPERIENCED
    }

    public enum InterviewType {
        TECHNICAL, HR_GENERAL
    }

    /** English only in the prototype. Tamil is documented as future scope. */
    public enum InterviewLanguage {
        ENGLISH
    }

    public enum InterviewStatus {
        SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED
    }

    public enum SessionStatus {
        ACTIVE, COMPLETED, ABANDONED
    }

    /**
     * Why a session stopped being ACTIVE. Deliberately separate from
     * {@link SessionStatus}: the status says what state the session is in, this
     * says why it got there. A time-expired interview is still COMPLETED, so
     * every query that looks for COMPLETED keeps working.
     *
     * <p>Null while the interview is still running, and null for sessions that
     * finished before this was recorded - that reason is genuinely unknown
     * rather than assumed.
     */
    public enum CompletionReason {
        /** The candidate pressed Finish, whether or not everything was answered. */
        CANDIDATE_FINISHED,
        /** The configured duration ran out and the server closed the session. */
        TIME_EXPIRED;

        public String label() {
            return switch (this) {
                case CANDIDATE_FINISHED -> "Candidate finished";
                case TIME_EXPIRED -> "Time expired";
            };
        }
    }

    /**
     * How much of the interview the proctoring pipeline actually observed.
     *
     * <p>This describes the <b>observer</b>, never the candidate. It exists
     * because a session with no recorded observations used to be
     * indistinguishable from a session nobody watched, and a report that reads
     * as clean when the honest answer is "not observed" is exactly the kind of
     * overstatement this system is built to avoid.
     *
     * <p>Derived from the session's monitoring columns and its event count -
     * never stored, because a stored verdict would go stale the moment a late
     * batch of events arrived.
     *
     * <p>Nothing here changes a score or a recommendation. Incomplete
     * monitoring is not the candidate's doing and must not cost them anything;
     * it is surfaced so the human who decides can see it.
     */
    public enum MonitoringCoverage {
        /** Monitoring ran and recorded at least one observation. */
        OBSERVATIONS_RECORDED,
        /** Monitoring ran for the whole interview and saw nothing worth recording. */
        NONE_OBSERVED,
        /**
         * Monitoring did not run, stopped, or is known to have lost events. The
         * absence of observations here proves nothing either way.
         */
        INCOMPLETE,
        /**
         * The session predates coverage recording, so whether monitoring ran is
         * genuinely unknown. Deliberately distinct from {@link #INCOMPLETE}:
         * calling an old session a monitoring failure would be as dishonest as
         * calling it clean.
         */
        NOT_RECORDED;

        public String label() {
            return switch (this) {
                case OBSERVATIONS_RECORDED -> "Observations recorded";
                case NONE_OBSERVED -> "Monitored, nothing observed";
                case INCOMPLETE -> "Monitoring incomplete";
                case NOT_RECORDED -> "Coverage not recorded";
            };
        }

        /** True when the observation list cannot be trusted as the full picture. */
        public boolean isUnreliable() {
            return this == INCOMPLETE || this == NOT_RECORDED;
        }
    }

    /**
     * What a recruiter or admin decided after reading a report.
     *
     * <p>Deliberately <b>different words</b> from {@link Recommendation}. The
     * model produces RECOMMENDED / FURTHER_REVIEW / NOT_RECOMMENDED; a person
     * produces ADVANCED / ON_HOLD / DECLINED. Reusing one vocabulary for both
     * would make "the system said RECOMMENDED" and "the recruiter said
     * RECOMMENDED" indistinguishable at a glance, in a system whose entire
     * design rests on keeping those two apart.
     *
     * <p>This is also the only place in the system where a decision about a
     * person is recorded as a decision. Everything the AI produces is advisory
     * by construction - see {@code RecommendationEngine}.
     */
    public enum ReviewDecision {
        /** Take this candidate forward. */
        ADVANCED,
        /** Not decided yet - more information, another conversation, a second opinion. */
        ON_HOLD,
        /** Do not take this candidate forward. */
        DECLINED;

        public String label() {
            return switch (this) {
                case ADVANCED -> "Advanced";
                case ON_HOLD -> "On hold";
                case DECLINED -> "Declined";
            };
        }

        /**
         * Whether this decision lines up with what the model suggested.
         *
         * <p>Presentation only, and derived rather than stored - the report's
         * recommendation never changes, so there is nothing to snapshot.
         *
         * <p>Worth noting what this quietly accumulates: a record of how often
         * humans agree with the model is the beginning of the calibration data
         * {@code docs/system/quality/LIMITATIONS.md} says the scoring lacks. Recording it is
         * not the same as having done that study, and nothing here claims
         * otherwise.
         */
        public boolean agreesWith(Recommendation recommendation) {
            return switch (this) {
                case ADVANCED -> recommendation == Recommendation.RECOMMENDED;
                case ON_HOLD -> recommendation == Recommendation.FURTHER_REVIEW;
                case DECLINED -> recommendation == Recommendation.NOT_RECOMMENDED;
            };
        }
    }

    /**
     * How an interview's questions are produced.
     *
     * <p>Lives here rather than on {@code QuestionModeProperties} because it is
     * now a property of an <b>interview</b>, stored on the row, and only
     * secondarily a server default. The config class still resolves the
     * fallback for interviews that do not specify one.
     */
    public enum QuestionMode {
        /** The whole set, generated once, before the candidate sees question one. */
        FIXED,
        /** One at a time, informed by the previous answer's Java-computed score. */
        ADAPTIVE;

        public String label() {
            return this == FIXED ? "Fixed set" : "Adaptive";
        }
    }

    public enum Difficulty {
        EASY, MEDIUM, HARD
    }

    /** Whether a question came from the LLM or the offline fallback bank. */
    public enum QuestionSource {
        LLM, BANK
    }

    /** Whether an answer was scored by the LLM or the offline heuristic. */
    public enum EvaluatorType {
        LLM, FALLBACK
    }

    /**
     * Proctoring observations. A detection is an observation, never a verdict:
     * PHONE_DETECTED means "a phone was detected", not "the candidate cheated".
     */
    public enum ProctorEventType {
        FACE_PRESENT,
        NO_FACE,
        MULTIPLE_FACES,
        MULTIPLE_PERSONS,
        PHONE_DETECTED,
        OBJECT_DETECTED,
        HEAD_TURN,
        TAB_SWITCH,
        WINDOW_BLUR,
        FULLSCREEN_EXIT
    }

    public enum Recommendation {
        RECOMMENDED, FURTHER_REVIEW, NOT_RECOMMENDED
    }
}
