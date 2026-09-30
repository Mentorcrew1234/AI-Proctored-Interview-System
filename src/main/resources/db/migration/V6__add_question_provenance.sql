-- =====================================================================
-- Question provenance: which model produced a question, and a stable
-- identity for its text.
--
-- Domain, interview type, candidate type and language are deliberately
-- NOT duplicated here - they are already reachable from every question
-- via session_id -> interview_sessions.interview_id -> interviews.*, and
-- copying them onto every question would just be a second copy of data
-- the existing foreign keys already answer.
-- =====================================================================

-- NULL for BANK questions before this migration only where the model is
-- genuinely unknowable (see the UPDATE below); NULL for LLM questions
-- generated before this column existed, because the model in use has
-- changed over the project's life (gemini-2.5-flash, then
-- gemini-3.1-flash-lite) and guessing which one produced an old row would
-- be dishonest. Going forward every question sets it, mirroring
-- answers.model_name on the evaluation side.
ALTER TABLE questions
    ADD COLUMN model_name VARCHAR(60) NULL AFTER source;

-- A stable identity for the question's text, so a future selector can look
-- up "has this text been used before" with an indexed equality check
-- instead of scanning TEXT columns (which MySQL cannot index directly).
-- Nullable only for the backfill step below; NOT NULL by the end of this
-- migration, and always set going forward via Question's own @PrePersist.
ALTER TABLE questions
    ADD COLUMN text_hash CHAR(64) NULL AFTER text;

-- Backfill: a pure function of each row's own already-stored text, so this
-- is metadata being added, not the question being changed - unlike
-- bank_question_id linkage would be, this is not a guess.
UPDATE questions
    SET text_hash = SHA2(LOWER(TRIM(text)), 256)
    WHERE text_hash IS NULL;

-- The one case where the historical model IS known exactly: FallbackLlmClient
-- has always reported a single constant model name, so every existing BANK
-- question was produced by it regardless of when it was generated.
UPDATE questions
    SET model_name = 'offline-fallback'
    WHERE source = 'BANK' AND model_name IS NULL;

ALTER TABLE questions
    MODIFY COLUMN text_hash CHAR(64) NOT NULL;

CREATE INDEX idx_questions_text_hash ON questions (text_hash);
