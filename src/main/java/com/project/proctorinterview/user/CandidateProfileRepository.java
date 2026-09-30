package com.project.proctorinterview.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CandidateProfileRepository extends JpaRepository<CandidateProfile, Long> {

    Optional<CandidateProfile> findByUserId(Long userId);

    /** Every profile for the given accounts, in one query rather than one each. */
    List<CandidateProfile> findByUserIdIn(Collection<Long> userIds);

    /**
     * The colleges actually recorded, for a filter control to offer.
     *
     * <p>Distinct values in use, not a curated list - the same choice the domain
     * filter makes ({@code findDistinctDomains}), and for the same reason: the
     * field is free text, so a fixed list would leave anything typed outside it
     * unfilterable.
     */
    @Query("""
            select distinct p.collegeName from CandidateProfile p
            where p.collegeName is not null
            order by p.collegeName
            """)
    List<String> findDistinctCollegeNames();
}
