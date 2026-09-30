package com.project.proctorinterview.auth;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.project.proctorinterview.user.UserRepository;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates /api/** requests from a {@code Authorization: Bearer <jwt>}
 * header.
 *
 * <p>The account is re-read from the database on each request so that a
 * disabled or deleted user stops being accepted immediately, rather than
 * staying valid until their token expires.
 *
 * <p>A missing or invalid token is not an error here — the request simply stays
 * unauthenticated and the authorization rules decide what happens next.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository users;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            Claims claims = jwtService.parse(header.substring(PREFIX.length()));
            if (claims != null) {
                users.findById(jwtService.userId(claims))
                        .filter(com.project.proctorinterview.user.User::isEnabled)
                        .map(AppUserDetails::new)
                        .ifPresent(principal -> {
                            var authentication = new UsernamePasswordAuthenticationToken(
                                    principal, null, principal.getAuthorities());
                            authentication.setDetails(
                                    new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                        });
            }
        }

        chain.doFilter(request, response);
    }
}
