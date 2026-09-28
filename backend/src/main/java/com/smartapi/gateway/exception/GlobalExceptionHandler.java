package com.smartapi.gateway.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import com.smartapi.gateway.web.RequestIdFilter;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into consistent RFC 9457 "problem+json" responses:
 * {@code {"type","title","status","detail","instance", ...}}.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own exceptions (malformed JSON,
 * wrong HTTP method, unknown URL, type mismatch like /api/services/abc) keep their correct 4xx
 * status. Without it, the catch-all {@code Exception} handler below would turn them all into 500s.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * All gateway failures (400/404/429/502/503/504). The status, title and extra headers come from the
     * exception subclass itself, so this one method covers every case.
     */
    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ProblemDetail> handleGateway(GatewayException ex) {
        return ResponseEntity.status(ex.getStatus())
                .headers(ex.responseHeaders())
                .body(problem(ex.getStatus(), ex.getTitle(), ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage());
    }

    /** Login failure. Same message for unknown user and wrong password (no username enumeration). */
    @ExceptionHandler(org.springframework.security.authentication.BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(org.springframework.security.authentication.BadCredentialsException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage());
    }

    /** Thrown by @PreAuthorize-style checks inside controllers; the filter chain handles URL rules itself. */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "Your role does not allow this operation");
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ProblemDetail handleDuplicate(DuplicateResourceException ex) {
        return problem(HttpStatus.CONFLICT, "Duplicate resource", ex.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
    }

    /** Backstop for races the service-layer checks can't catch (unique/foreign-key violations). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.info("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        // Don't echo the SQL error to the client: it leaks table and constraint names.
        return problem(HttpStatus.CONFLICT, "Conflict", "The request conflicts with existing data");
    }

    /**
     * The database is unreachable (connection refused / pool timeout). This is a temporary dependency
     * outage, not a bug in our code, so 503 (retry later) rather than 500.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ProblemDetail handleDatabaseUnavailable(Exception ex) {
        log.warn("Database unavailable: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable", "The database is temporarily unavailable");
    }

    /** Anything unexpected: log the stack trace for us, return a generic message to the client. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "An unexpected error occurred");
    }

    /** Adds {@code "errors": {"field": "message"}} to @Valid failures so clients can show them per field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException ex,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        // Lets a client quote the id from the error body when reporting a problem; we grep logs for it.
        String requestId = RequestIdFilter.currentRequestId();
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }
}
