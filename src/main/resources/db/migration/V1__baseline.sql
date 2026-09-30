-- =====================================================================
-- AI-Based Proctored Interview System - baseline schema
-- MySQL 8. Flyway owns this schema; Hibernate runs in validate mode.
--
-- JSON-shaped columns are declared TEXT (not MySQL JSON) and converted in
-- Java, so the same entities also run against H2 in the test suite.
-- =====================================================================

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(190) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(150) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    enabled       BIT(1)       NOT NULL DEFAULT b'1',
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE = InnoDB;

CREATE TABLE candidate_profiles (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    user_id          BIGINT      NOT NULL,
    phone            VARCHAR(30),
    candidate_type   VARCHAR(20),
    experience_years INT,
    primary_domain   VARCHAR(80),
    PRIMARY KEY (id),
    CONSTRAINT uk_candidate_profiles_user UNIQUE (user_id),
    CONSTRAINT fk_candidate_profiles_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE TABLE interviewer_profiles (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    user_id     BIGINT NOT NULL,
    department  VARCHAR(120),
    designation VARCHAR(120),
    PRIMARY KEY (id),
    CONSTRAINT uk_interviewer_profiles_user UNIQUE (user_id),
    CONSTRAINT fk_interviewer_profiles_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE TABLE interviews (
    id                          BIGINT       NOT NULL AUTO_INCREMENT,
    interviewer_id              BIGINT       NOT NULL,
    candidate_id                BIGINT       NOT NULL,
    scheduled_at                DATETIME(6)  NOT NULL,
    candidate_type              VARCHAR(20)  NOT NULL,
    experience_years            INT,
    domain                      VARCHAR(80)  NOT NULL,
    language                    VARCHAR(20)  NOT NULL DEFAULT 'ENGLISH',
    interview_type              VARCHAR(20)  NOT NULL,
    question_count              INT          NOT NULL DEFAULT 5,
    status                      VARCHAR(20)  NOT NULL,
    invite_token                VARCHAR(64)  NOT NULL,
    result_visible_to_candidate BIT(1)       NOT NULL DEFAULT b'0',
    created_at                  DATETIME(6)  NOT NULL,
    updated_at                  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_interviews_invite_token UNIQUE (invite_token),
    CONSTRAINT fk_interviews_interviewer FOREIGN KEY (interviewer_id) REFERENCES users (id),
    CONSTRAINT fk_interviews_candidate FOREIGN KEY (candidate_id) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE INDEX idx_interviews_candidate_status ON interviews (candidate_id, status);
CREATE INDEX idx_interviews_interviewer_scheduled ON interviews (interviewer_id, scheduled_at);

CREATE TABLE interview_sessions (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    interview_id   BIGINT      NOT NULL,
    started_at     DATETIME(6) NOT NULL,
    ended_at       DATETIME(6),
    status         VARCHAR(20) NOT NULL,
    browser_info   VARCHAR(400),
    camera_granted BIT(1)      NOT NULL DEFAULT b'0',
    mic_granted    BIT(1)      NOT NULL DEFAULT b'0',
    PRIMARY KEY (id),
    CONSTRAINT uk_interview_sessions_interview UNIQUE (interview_id),
    CONSTRAINT fk_interview_sessions_interview FOREIGN KEY (interview_id) REFERENCES interviews (id)
) ENGINE = InnoDB;

CREATE TABLE questions (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    session_id      BIGINT      NOT NULL,
    sequence_no     INT         NOT NULL,
    text            TEXT        NOT NULL,
    difficulty      VARCHAR(20) NOT NULL,
    expected_points TEXT,
    source          VARCHAR(20) NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_questions_session_sequence UNIQUE (session_id, sequence_no),
    CONSTRAINT fk_questions_session FOREIGN KEY (session_id) REFERENCES interview_sessions (id)
) ENGINE = InnoDB;

CREATE TABLE answers (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    question_id           BIGINT      NOT NULL,
    raw_transcript        LONGTEXT,
    clean_transcript      LONGTEXT,
    filler_count          INT         NOT NULL DEFAULT 0,
    word_count            INT         NOT NULL DEFAULT 0,
    duration_seconds      INT         NOT NULL DEFAULT 0,
    answered_at           DATETIME(6) NOT NULL,
    -- evaluation (1:1 with the answer, so kept in the same row)
    technical_score       INT,
    relevance_score       INT,
    problem_solving_score INT,
    communication_score   INT,
    overall_score         INT,
    feedback              TEXT,
    strengths             TEXT,
    weaknesses            TEXT,
    evaluator             VARCHAR(20),
    model_name            VARCHAR(80),
    evaluated_at          DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_answers_question UNIQUE (question_id),
    CONSTRAINT fk_answers_question FOREIGN KEY (question_id) REFERENCES questions (id)
) ENGINE = InnoDB;

CREATE TABLE proctor_events (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    session_id      BIGINT       NOT NULL,
    event_type      VARCHAR(30)  NOT NULL,
    start_time      DATETIME(6)  NOT NULL,
    end_time        DATETIME(6),
    duration_ms     BIGINT,
    confidence      DECIMAL(4, 3),
    details         TEXT,
    client_event_id VARCHAR(64)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_proctor_events_client_event_id UNIQUE (client_event_id),
    CONSTRAINT fk_proctor_events_session FOREIGN KEY (session_id) REFERENCES interview_sessions (id)
) ENGINE = InnoDB;

CREATE INDEX idx_proctor_events_session_type ON proctor_events (session_id, event_type);
CREATE INDEX idx_proctor_events_session_start ON proctor_events (session_id, start_time);

CREATE TABLE reports (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    session_id            BIGINT      NOT NULL,
    technical_score       INT         NOT NULL,
    communication_score   INT         NOT NULL,
    problem_solving_score INT         NOT NULL,
    relevance_score       INT         NOT NULL,
    overall_score         INT         NOT NULL,
    recommendation        VARCHAR(30) NOT NULL,
    explanation           TEXT,
    proctor_summary       TEXT,
    integrity_flag        BIT(1)      NOT NULL DEFAULT b'0',
    generated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reports_session UNIQUE (session_id),
    CONSTRAINT fk_reports_session FOREIGN KEY (session_id) REFERENCES interview_sessions (id)
) ENGINE = InnoDB;
