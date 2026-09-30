package com.project.proctorinterview.auth;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.user.User;

/**
 * Authenticated principal. Carries the user id so controllers can check
 * ownership (e.g. "is this JWT's subject really this interview's candidate?")
 * without another database lookup.
 */
public class AppUserDetails implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final String fullName;
    private final Role role;
    private final boolean enabled;
    private final boolean accountNonLocked;

    public AppUserDetails(User user) {
        this(user, true);
    }

    public AppUserDetails(User user, boolean accountNonLocked) {
        this(user.getId(), user.getEmail(), user.getPasswordHash(),
                user.getFullName(), user.getRole(), user.isEnabled(), accountNonLocked);
    }

    public AppUserDetails(Long id, String email, String passwordHash,
            String fullName, Role role, boolean enabled) {
        this(id, email, passwordHash, fullName, role, enabled, true);
    }

    public AppUserDetails(Long id, String email, String passwordHash,
            String fullName, Role role, boolean enabled, boolean accountNonLocked) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.enabled = enabled;
        this.accountNonLocked = accountNonLocked;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // hasRole("ADMIN") checks for the ROLE_ prefix.
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * False while login throttling is holding this account off.
     *
     * <p>Spring's DaoAuthenticationProvider checks this before comparing the
     * password, which is why the throttle hooks in here: one place covers both
     * the form login and the JWT endpoint, because both authenticate through
     * the same provider.
     */
    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
