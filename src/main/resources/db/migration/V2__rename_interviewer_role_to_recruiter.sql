-- =====================================================================
-- Rename the INTERVIEWER role to RECRUITER.
--
-- Terminology only: no permission changes, no data loss. Existing accounts
-- keep their id, email, password and every interview they own - only the
-- role value and the identifier names change.
--
-- Order matters: the foreign key on interviews.interviewer_id has to be
-- dropped before the column can be renamed, then recreated under its new
-- name.
-- =====================================================================

-- 1. Existing users keep their accounts; only the role value changes.
--    Same row, same id, same password hash, same interviews - only the role.
UPDATE users SET role = 'RECRUITER' WHERE role = 'INTERVIEWER';

-- 1b. Rename the seeded demo account to match the new seed email.
--     Without this the seeder, which is idempotent per email, would see
--     recruiter@demo.local missing and create a SECOND staff account, leaving
--     the original one orphaned from the demo credentials. Renaming in place
--     keeps the existing account and everything it owns.
UPDATE users SET email = 'recruiter@demo.local' WHERE email = 'interviewer@demo.local';

-- 2. interviewer_profiles -> recruiter_profiles
ALTER TABLE interviewer_profiles DROP FOREIGN KEY fk_interviewer_profiles_user;
ALTER TABLE interviewer_profiles DROP INDEX uk_interviewer_profiles_user;
ALTER TABLE interviewer_profiles RENAME TO recruiter_profiles;
ALTER TABLE recruiter_profiles
    ADD CONSTRAINT uk_recruiter_profiles_user UNIQUE (user_id);
ALTER TABLE recruiter_profiles
    ADD CONSTRAINT fk_recruiter_profiles_user FOREIGN KEY (user_id) REFERENCES users (id);

-- 3. interviews.interviewer_id -> interviews.recruiter_id
--    The column still points at users; only its name changes.
ALTER TABLE interviews DROP FOREIGN KEY fk_interviews_interviewer;
DROP INDEX idx_interviews_interviewer_scheduled ON interviews;
ALTER TABLE interviews CHANGE COLUMN interviewer_id recruiter_id BIGINT NOT NULL;
ALTER TABLE interviews
    ADD CONSTRAINT fk_interviews_recruiter FOREIGN KEY (recruiter_id) REFERENCES users (id);
CREATE INDEX idx_interviews_recruiter_scheduled ON interviews (recruiter_id, scheduled_at);
