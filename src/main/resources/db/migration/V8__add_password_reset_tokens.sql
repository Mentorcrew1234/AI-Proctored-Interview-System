-- =====================================================================
-- Password reset.
--
-- Until now there was no way back into an account whose password was
-- lost, except an administrator editing it. That was tolerable while
-- every account was seeded by hand, but bulk scheduling creates candidate
-- accounts with generated passwords that are shown once and never
-- persisted (see docs/BULK-SCHEDULING.md) - so "I lost the email" had no
-- answer at all.
--
-- One table, deliberately. A reset is a short-lived capability, not part
-- of the user record, and keeping it separate means the users table gains
-- no nullable columns that are meaningless 99% of the time.
-- =====================================================================

CREATE TABLE password_reset_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,

    -- The SHA-256 of the token, never the token itself.
    --
    -- The raw value exists only in the email. Anyone who can read this
    -- table - a backup, a log, a careless query - therefore cannot use
    -- what they find, exactly as with password_hash. This is the same
    -- reasoning that keeps bulk-generated passwords out of the database.
    token_hash CHAR(64) NOT NULL,

    expires_at DATETIME(6) NOT NULL,

    -- Set when the token is spent. Kept rather than deleted so a second
    -- click on the same link can be told "already used" instead of
    -- "invalid", and so the row is evidence the reset happened.
    used_at DATETIME(6) NULL,

    created_at DATETIME(6) NOT NULL,

    PRIMARY KEY (id),
    -- Lookup is by hash, and a collision would be a second valid route
    -- into one account.
    CONSTRAINT uk_password_reset_tokens_hash UNIQUE (token_hash),
    -- ON DELETE CASCADE: a deleted account's pending resets must not
    -- outlive it. Every other FK in this schema is restrictive because it
    -- guards interview history; this one guards nothing worth keeping.
    CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Supports "invalidate this user's other pending tokens", which is what
-- makes requesting a second reset safely supersede the first.
CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens (user_id, used_at);
