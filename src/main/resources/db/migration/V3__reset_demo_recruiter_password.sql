-- =====================================================================
-- Realign the seeded demo recruiter's password with the documented one.
--
-- V2 renamed interviewer@demo.local to recruiter@demo.local, keeping the
-- account and its password. But DataSeeder only sets a password when it
-- creates an account, so on an existing database that account kept the old
-- Interviewer@123 while the login page, README and seeder all now advertise
-- Recruiter@123 - the documented credentials would simply be wrong.
--
-- This affects exactly one row: the seeded demo account, whose credentials
-- are published in the repository anyway. No other user's password is
-- touched, and no other account is matched.
--
-- On a fresh database this is a no-op: Flyway runs before the seeder, so
-- there is no such row yet and the seeder creates it with the right
-- password.
--
-- The value is a BCrypt hash of 'Recruiter@123' (cost 10), the same scheme
-- BCryptPasswordEncoder produces.
-- =====================================================================

UPDATE users
SET password_hash = '$2a$10$Z09O2caEh3B2O9LkQxQ5w.Vq844DdXscwICvOlvUrz46WAuLnU3Du'
WHERE email = 'recruiter@demo.local';
