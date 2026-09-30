package com.project.proctorinterview.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import jakarta.servlet.http.HttpServletRequest;

/**
 * One consistent JSON error shape for every /api/** failure.
 *
 * <p>Scoped to {@code @RestController} beans only, so a failure on a
 * server-rendered Thymeleaf page still produces an HTML error page rather than
 * a JSON body the browser would just display as text.
 */
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(Instant timestamp, int status, String error, String message, String path,
            Map<String, String> fieldErrors) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException e, HttpServletRequest request) {
        return ResponseEntity.status(e.getStatus())
                .body(body(e.getStatus(), e.getMessage(), request.getRequestURI(), null));
    }

    /** Bean-validation failures become a field-by-field map the UI can render. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(body(HttpStatus.BAD_REQUEST, "Validation failed", request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request, WebRequest webRequest) {
        // Log the detail, return a generic message - internals never leak to the client.
        log.error("Unhandled error on {}", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side",
                        request.getRequestURI(), null));
    }

    private static ApiError body(HttpStatus status, String message, String path, Map<String, String> fieldErrors) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path, fieldErrors);
    }
}
