-- =====================================================================
-- The human decision the system already defers to.
--
-- Every report ends with "advisory input for a human decision, not a
-- hiring decision". RecommendationEngine says it, the report page says
-- it, LIMITATIONS.md says it. And until now there was nowhere to put the
-- human's actual decision - so the system deferred to a person and then
-- discarded their answer.
--
-- This is the same shape of gap as V7: a claim the system makes about
-- itself that was not yet true.
-- =====================================================================

CREATE TABLE report_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,

    -- Deliberately NOT unique. A decision can be revised, and a hiring
    -- decision that silently changed is exactly the kind of thing that
    -- should stay traceable, so this is an append-only history and the
    -- most recent row is the current decision. Overwriting would save one
    -- index and lose the only record that anyone changed their mind.
    report_id BIGINT NOT NULL,

    -- Who decided. Never derived from the report - the point of the table
    -- is that a named person took responsibility.
    reviewer_id BIGINT NOT NULL,

    -- ADVANCED | ON_HOLD | DECLINED.
    --
    -- Deliberately different words from reports.recommendation
    -- (RECOMMENDED | FURTHER_REVIEW | NOT_RECOMMENDED). Reusing that
    -- vocabulary would make "the model said RECOMMENDED" and "the
    -- recruiter said RECOMMENDED" indistinguishable at a glance, in a
    -- system whose whole design rests on keeping those apart.
    decision VARCHAR(20) NOT NULL,

    -- Why. Optional, because forcing a justification produces empty ones,
    -- but prompted for in the UI because a decision without a reason is
    -- much less useful to whoever reads it next.
    note TEXT NULL,

    created_at DATETIME(6) NOT NULL,

    PRIMARY KEY (id),
    -- Restrictive, like every other FK here except password reset: this is
    -- interview history and must not vanish because a row elsewhere went.
    CONSTRAINT fk_report_reviews_report FOREIGN KEY (report_id) REFERENCES reports (id),
    CONSTRAINT fk_report_reviews_reviewer FOREIGN KEY (reviewer_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- "The decisions on this report, newest first" is the only read pattern.
CREATE INDEX idx_report_reviews_report ON report_reviews (report_id, created_at);
