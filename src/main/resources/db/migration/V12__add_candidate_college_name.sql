-- College name on the candidate profile.
--
-- The platform is used mainly for college students, so where a candidate
-- studies is a first-class attribute of the person, not of one interview - it
-- belongs beside phone, candidate type and primary domain on the account.
--
-- NULLABLE on purpose, and with no default. Every candidate profile that
-- existed before this migration was created without ever being asked, so a
-- NOT NULL column with '' as a default would record "no college" as a fact
-- about them. Null means "not recorded", which is the truth.
--
-- 150 matches users.full_name: institution names are long ("Sri Venkateswara
-- College of Engineering and Technology, Chittoor") and 80, the width used for
-- primary_domain, truncates real ones.
ALTER TABLE candidate_profiles
    ADD COLUMN college_name VARCHAR(150) NULL AFTER phone;
