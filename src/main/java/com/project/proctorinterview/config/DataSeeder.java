package com.project.proctorinterview.config;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.user.CandidateProfile;
import com.project.proctorinterview.user.CandidateProfileRepository;
import com.project.proctorinterview.user.RecruiterProfile;
import com.project.proctorinterview.user.RecruiterProfileRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Creates the bootstrap accounts on first startup so there is a way in and
 * something to click on. Idempotent: does nothing once any user exists.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository users;
    private final CandidateProfileRepository candidateProfiles;
    private final RecruiterProfileRepository recruiterProfiles;
    private final PasswordEncoder passwordEncoder;
    private final SeedProperties props;

    public DataSeeder(UserRepository users,
            CandidateProfileRepository candidateProfiles,
            RecruiterProfileRepository recruiterProfiles,
            PasswordEncoder passwordEncoder,
            SeedProperties props) {
        this.users = users;
        this.candidateProfiles = candidateProfiles;
        this.recruiterProfiles = recruiterProfiles;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!props.enabled()) {
            return;
        }

        // Checked per account rather than "is the table empty", so adding a new
        // seed account later still works on an existing database.
        boolean seededAnything = false;

        seededAnything |= createIfMissing(props.adminEmail(), props.adminPassword(),
                props.adminName(), Role.ADMIN, null);

        if (props.demoUsers()) {
            seededAnything |= createIfMissing("recruiter@demo.local", "Recruiter@123",
                    "Priya Raman", Role.RECRUITER, user -> {
                        RecruiterProfile profile = new RecruiterProfile();
                        profile.setUser(user);
                        profile.setDepartment("Engineering");
                        profile.setDesignation("Senior Engineer");
                        recruiterProfiles.save(profile);
                    });

            seededAnything |= createIfMissing("candidate@demo.local", "Candidate@123",
                    "Arun Kumar", Role.CANDIDATE, user -> {
                        CandidateProfile profile = new CandidateProfile();
                        profile.setUser(user);
                        profile.setPhone("9000000000");
                        profile.setCandidateType(CandidateType.FRESHER);
                        profile.setPrimaryDomain("Java");
                        candidateProfiles.save(profile);
                    });
        }

        if (seededAnything) {
            log.warn("Seeded development credentials. Change them before any real use.");
        }
    }

    /** @return true if the account was created, false if it already existed. */
    private boolean createIfMissing(String email, String rawPassword, String fullName,
            Role role, Consumer<User> profileFactory) {
        if (users.existsByEmailIgnoreCase(email)) {
            return false;
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName(fullName);
        user.setRole(role);
        user.setEnabled(true);
        users.save(user);

        if (profileFactory != null) {
            profileFactory.accept(user);
        }

        log.info("Seeded {}: {} / {}", role, email, rawPassword);
        return true;
    }
}
