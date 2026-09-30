package com.project.proctorinterview.passwordreset;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /** Lookup is always by hash - the raw token exists only in the email. */
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Spends every pending token for one account.
     *
     * <p>Called when a new reset is requested and again when one is used, so a
     * forgotten or intercepted earlier link stops working the moment a newer
     * one is issued. Marked used rather than deleted, so the row still records
     * that the token existed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordResetToken t set t.usedAt = :now "
            + "where t.user.id = :userId and t.usedAt is null")
    int invalidateAllFor(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Housekeeping: tokens that are spent or long expired have no further use.
     * Nothing depends on this running - every check is made against
     * {@code usedAt} and {@code expiresAt} directly - so a missed sweep is
     * untidy, never unsafe.
     */
    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
