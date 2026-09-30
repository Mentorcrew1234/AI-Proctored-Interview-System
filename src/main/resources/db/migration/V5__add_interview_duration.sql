-- =====================================================================
-- Interview test duration, and why an interview ended.
--
-- Two columns only. Everything else about timing is derived:
--
--   deadline       = interview_sessions.started_at + duration_minutes
--   actual duration = interview_sessions.ended_at  - started_at
--
-- Neither is stored. A stored deadline would go stale the moment the
-- duration was edited, and a stored duration would be a second copy of
-- something the timestamps already answer exactly.
-- =====================================================================

-- NOT NULL with a default, unlike interview_name (V4), which was left
-- nullable. The difference is that a missing name has an honest fallback
-- ("Interview #id") whereas a missing duration has no sensible display and
-- would have to be special-cased at every use. 30 minutes is the value the
-- scheduling form already offers as its default, so existing rows get the
-- same duration a recruiter would have chosen for them.
ALTER TABLE interviews
    ADD COLUMN duration_minutes INT NOT NULL DEFAULT 30 AFTER question_count;

-- Why the interview ended, kept separate from the session's status.
--
-- status says WHAT state the session is in (ACTIVE / COMPLETED / ABANDONED);
-- this says WHY it left ACTIVE. Folding "ran out of time" into the status
-- enum would mean a TIME_EXPIRED session was no longer COMPLETED, and every
-- existing query that looks for COMPLETED would silently start missing them.
--
-- NULL while the session is still running, and NULL for sessions that
-- finished before this column existed - their reason genuinely is not known,
-- and CANDIDATE_FINISHED would be a guess.
ALTER TABLE interview_sessions
    ADD COLUMN completion_reason VARCHAR(30) NULL AFTER status;
