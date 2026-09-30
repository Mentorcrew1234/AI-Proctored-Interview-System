package com.project.proctorinterview.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.user.UserRepository;

/** Loads accounts by email for both the form login and the JWT filter. */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;
    private final LoginAttemptService loginAttempts;

    public AppUserDetailsService(UserRepository users, LoginAttemptService loginAttempts) {
        this.users = users;
        this.loginAttempts = loginAttempts;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // Marked locked rather than refused outright: DaoAuthenticationProvider
        // checks isAccountNonLocked before it compares the password, so hooking
        // in here covers the form login and the JWT login endpoint at once -
        // both authenticate through that same provider.
        //
        // It deliberately does NOT reach an already-issued JWT. JwtAuthentication
        // Filter reloads the account itself and only checks isEnabled, so a
        // candidate part-way through an interview cannot be thrown out by
        // someone else failing logins against their address. A lockout stops new
        // sign-ins; revoking a live session is what disabling an account is for.
        boolean locked = loginAttempts.isBlocked(email);

        return users.findByEmailIgnoreCase(email)
                .map(user -> new AppUserDetails(user, !locked))
                // Same message whether the account is missing or the password is
                // wrong, so the response cannot be used to enumerate accounts.
                .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password"));
    }
}
