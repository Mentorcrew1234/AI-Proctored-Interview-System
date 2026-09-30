package com.project.proctorinterview.user;

import com.project.proctorinterview.common.Enums.CandidateType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Candidate-specific attributes. Defaults for a new interview are seeded from here. */
@Entity
@Table(name = "candidate_profiles")
@Getter
@Setter
public class CandidateProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(length = 30)
    private String phone;

    /**
     * Where the candidate studies. Optional, and null when it was never asked -
     * see {@code V12__add_candidate_college_name.sql}. A property of the person
     * rather than of one interview, which is why it lives here and not on
     * {@code interviews}.
     */
    @Column(name = "college_name", length = 150)
    private String collegeName;

    /**
     * Where the candidate is based. Optional; null means never asked. Free
     * text, because a fixed list of cities is the kind of thing that is wrong
     * the first time someone types one that is not on it.
     */
    @Column(length = 120)
    private String location;

    /**
     * A comma-separated skill list, e.g. {@code "Java, Spring Boot, SQL"}.
     *
     * <p>One column rather than a table or a row per skill - the standing rule
     * for candidate attributes. {@code UserService.normaliseSkills} is the one
     * place that decides how the list is written, so every reader can split on
     * a comma and trust the result.
     */
    @Column(length = 255)
    private String skills;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_type", length = 20)
    private CandidateType candidateType;

    @Column(name = "experience_years")
    private Integer experienceYears;

    @Column(name = "primary_domain", length = 80)
    private String primaryDomain;
}
