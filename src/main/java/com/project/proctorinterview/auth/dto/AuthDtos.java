package com.project.proctorinterview.auth.dto;

import com.project.proctorinterview.common.Enums.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request/response shapes for the authentication endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "Email is required") @Email(message = "Must be a valid email") String email,
            @NotBlank(message = "Password is required") String password) {
    }

    /** Never includes the password hash. */
    public record UserSummary(Long id, String email, String fullName, Role role) {
    }

    public record LoginResponse(String token, long expiresInSeconds, UserSummary user) {
    }
}
