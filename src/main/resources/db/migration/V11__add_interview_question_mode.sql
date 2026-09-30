-- =====================================================================
-- Question mode, per interview.
--
-- FIXED and ADAPTIVE both worked, but the choice was a single
-- server-wide setting (app.interview.question-mode) that needed a
-- restart to change - so one drive could not use adaptive questioning
-- while another used a fixed set, and trying it meant taking the whole
-- application down. docs/LIMITATIONS.md listed this as future scope: the
-- adaptive behaviour itself was already built, only the per-interview
-- choice was missing.
-- =====================================================================

-- NULLABLE, and null means "inherit the server-wide default" rather than
-- "unknown".
--
-- That distinction matters twice. For the 48 interviews scheduled before
-- this column existed, null is the honest value: they ran under whatever
-- the server setting was at the time, and stamping them all FIXED would
-- be a guess dressed as a record. And going forward it gives the
-- scheduling form a real third option - "use the server default" - so an
-- installation that wants to switch everything at once still can,
-- exactly as it could before.
--
-- Contrast with duration_minutes (V5), which is NOT NULL with a default:
-- a missing duration has no sensible display and would need special
-- casing everywhere, whereas a missing mode has a perfectly good answer
-- already sitting in configuration.
ALTER TABLE interviews
    ADD COLUMN question_mode VARCHAR(20) NULL AFTER question_count;
