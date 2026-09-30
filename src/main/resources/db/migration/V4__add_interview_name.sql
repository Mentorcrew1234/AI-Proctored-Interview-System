-- =====================================================================
-- Interview naming, for the interview management page.
--
-- The name identifies the assessment or drive from the recruiter's point of
-- view ("Java Developer - Campus Drive 2026"). It is not the candidate name,
-- and many interviews may share one.
--
-- Nullable on purpose: interviews created before this column existed keep
-- their rows untouched, and the application falls back to "Interview #id"
-- when displaying them. Backfilling a made-up name would invent data that
-- was never entered.
-- =====================================================================

ALTER TABLE interviews ADD COLUMN interview_name VARCHAR(150) NULL AFTER id;

-- The management page filters by status and orders by scheduled_at on almost
-- every request (the All/Today/Upcoming/Completed tabs are exactly this), so
-- one composite index covers the common path.
CREATE INDEX idx_interviews_status_scheduled ON interviews (status, scheduled_at);

-- Search matches the start of the interview name, which this index can serve.
CREATE INDEX idx_interviews_name ON interviews (interview_name);
