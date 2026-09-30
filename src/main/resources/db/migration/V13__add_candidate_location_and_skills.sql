-- Location and skills on the candidate profile, so recruiters can filter on
-- them. Both nullable, and both for the same reason college_name (V12) is:
-- every profile that existed before was created without the question being
-- put, and a NOT NULL DEFAULT '' would record "no location" and "no skills"
-- as facts about all of them rather than as the absence of an answer.
--
-- skills is a COMMA-SEPARATED LIST in one column, not a table and not a row
-- per skill. That is the user's standing rule for candidate attributes, and at
-- prototype scale it holds: the filter needs "does this candidate have skill
-- X", which is a contains-match over a short string, and the option list is
-- built by splitting the column. A join table would buy normalisation nobody
-- here is going to spend.
--
-- 255 fits roughly 20 skills at an average of 12 characters. Longer than that
-- is a CV, not a filter key, and UserService caps it.
ALTER TABLE candidate_profiles
    ADD COLUMN location VARCHAR(120) NULL AFTER college_name,
    ADD COLUMN skills   VARCHAR(255) NULL AFTER location;
