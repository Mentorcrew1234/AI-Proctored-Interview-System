package com.project.proctorinterview.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.proctorinterview.auth.dto.AuthDtos.LoginRequest;
import com.project.proctorinterview.auth.dto.AuthDtos.LoginResponse;
import com.project.proctorinterview.auth.dto.AuthDtos.UserSummary;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.user.UserRepository;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository users;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService, UserRepository users) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.users = users;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (AuthenticationException e) {
            // Deliberately identical for unknown email and wrong password.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        var user = users.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));

        return new LoginResponse(
                jwtService.issue(user),
                jwtService.expirySeconds(),
                new UserSummary(user.getId(), user.getEmail(), user.getFullName(), user.getRole()));
    }

    @GetMapping("/me")
    public UserSummary me(@AuthenticationPrincipal AppUserDetails principal) {
        return new UserSummary(principal.getId(), principal.getEmail(),
                principal.getFullName(), principal.getRole());
    }

    /**
     * JWTs are stateless, so there is nothing to invalidate server-side. This
     * endpoint exists so clients have a documented place to clear their token;
     * a real deployment would add a short expiry plus a revocation list.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }
}
