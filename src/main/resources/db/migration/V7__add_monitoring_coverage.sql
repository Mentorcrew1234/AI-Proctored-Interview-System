-- =====================================================================
-- Monitoring coverage: whether the proctoring pipeline actually ran.
--
-- Until now a report could not tell "nothing happened" from "nothing was
-- watched". A session with zero proctor_events read as clean, and there
-- are several ways to reach zero events without the session being clean:
-- the detection models failing to load, the browser abandoning a batch of
-- events after repeated upload failures, or the tab closing before the
-- queue drained. None of them left any record.
--
-- These three columns are that record. They describe the OBSERVER, not
-- the candidate - nothing here is evidence about a person, and nothing
-- here changes a score or a recommendation. They exist so the report can
-- say "not observed" instead of implying "observed and clean".
--
-- The coverage verdict itself is DERIVED from these columns plus the
-- session's event count, never stored - the same reasoning that keeps the
-- interview deadline derived in V5. A stored verdict would go stale the
-- moment a late batch of events arrived.
-- =====================================================================

-- Three-valued on purpose, and the three values are genuinely different:
--
--   NULL   the session predates this migration. We do not know whether
--          its monitoring ran, and claiming either answer would be a
--          guess. Reported as "not recorded", not as a failure.
--   FALSE  set when the session is created, meaning "the browser has not
--          yet confirmed the detectors loaded". A session that stays
--          FALSE is one where that confirmation never arrived.
--   TRUE   the browser reported that both detectors loaded.
--
-- This is why the column is nullable rather than NOT NULL DEFAULT FALSE:
-- a default would silently relabel every historical session as a
-- monitoring failure, which is exactly the kind of overstatement this
-- change exists to remove.
ALTER TABLE interview_sessions
    ADD COLUMN monitoring_ready BOOLEAN NULL AFTER mic_granted;

-- Events the browser generated but gave up on uploading, after exhausting
-- its retries. Any value above zero means the stored event list is known
-- to be incomplete.
--
-- NOT NULL DEFAULT 0 - unlike monitoring_ready, zero is the honest value
-- for a historical row: no event was ever reported as dropped, because
-- nothing could report one.
ALTER TABLE interview_sessions
    ADD COLUMN monitoring_dropped_events INT NOT NULL DEFAULT 0 AFTER monitoring_ready;

-- The browser's own message when monitoring failed to start, stored so a
-- recruiter reading the report can see WHY rather than only THAT. Short
-- and free-text: it is a diagnostic for a human, not something any code
-- branches on.
ALTER TABLE interview_sessions
    ADD COLUMN monitoring_note VARCHAR(300) NULL AFTER monitoring_dropped_events;
