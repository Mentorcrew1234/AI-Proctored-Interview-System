-- =====================================================================
-- A record that data was erased.
--
-- The system stores a great deal about a candidate - what they said,
-- word for word, what was observed of them through their own camera, and
-- how they were scored - and until now there was no way to remove any of
-- it. docs/LIMITATIONS.md admitted that plainly ("No data retention policy
-- or deletion endpoint"). For a system that records and scores people,
-- "we cannot delete your data" is not a small gap.
--
-- This table is not the deletion; it is the proof one happened.
-- =====================================================================

CREATE TABLE data_deletions (
    id BIGINT NOT NULL AUTO_INCREMENT,

    -- The id of the account whose data went. Deliberately a bare number
    -- with NO foreign key: the row it referred to is gone, which is the
    -- entire point. An FK here would either block the deletion or cascade
    -- away the very record we are trying to keep.
    --
    -- Email and name are NOT stored. Keeping them would mean an erasure
    -- request left the identity behind in a new table - erasure that does
    -- not erase. The id is enough to answer "was this account's data
    -- removed, and when", and after the deletion it identifies nobody.
    subject_user_id BIGINT NOT NULL,

    -- Whether the account itself went too, or only its interview data.
    -- These are genuinely different requests and the record has to say
    -- which one was carried out.
    scope VARCHAR(30) NOT NULL,

    -- Who did it. This one IS a real reference: an administrator taking a
    -- destructive action must remain attributable, and they are not the
    -- subject of the erasure.
    performed_by BIGINT NOT NULL,

    -- Free text, optional: the ticket, the request, the reason.
    reason VARCHAR(500) NULL,

    -- What was actually removed, as counts. Enough to show the deletion
    -- did what it claimed without retaining any of the content.
    interviews_deleted INT NOT NULL DEFAULT 0,
    sessions_deleted INT NOT NULL DEFAULT 0,
    questions_deleted INT NOT NULL DEFAULT 0,
    answers_deleted INT NOT NULL DEFAULT 0,
    proctor_events_deleted INT NOT NULL DEFAULT 0,
    reports_deleted INT NOT NULL DEFAULT 0,
    reviews_deleted INT NOT NULL DEFAULT 0,

    created_at DATETIME(6) NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_data_deletions_performed_by FOREIGN KEY (performed_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_data_deletions_created ON data_deletions (created_at);
