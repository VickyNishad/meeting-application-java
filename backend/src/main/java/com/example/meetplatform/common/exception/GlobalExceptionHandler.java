package com.example.meetplatform.common.exception;

import com.example.meetplatform.common.observability.TraceContext;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String SQLSTATE_EXCLUSION_VIOLATION = "23P01";
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    private final TraceContext traceContext;

    public GlobalExceptionHandler(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException ex) {
        ProblemDetail body = problem(ex.code(), ex.getMessage());
        HttpHeaders headers = new HttpHeaders();
        if (ex.retryAfterSeconds() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
        }
        return ResponseEntity.status(ex.code().status()).headers(headers).body(body);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<Object> handleAccessDenied(RuntimeException ex) {
        return ResponseEntity.status(403).body(problem(ErrorCode.FORBIDDEN, "You are not allowed to perform this action."));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(409).body(problem(ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                "The resource was modified by someone else. Reload and try again."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleIntegrity(DataIntegrityViolationException ex) {
        String sqlState = sqlState(ex);
        if (SQLSTATE_EXCLUSION_VIOLATION.equals(sqlState)) {
            return ResponseEntity.status(409).body(problem(ErrorCode.SLOT_UNAVAILABLE,
                    "The selected time slot is no longer available."));
        }
        if (SQLSTATE_UNIQUE_VIOLATION.equals(sqlState)) {
            return ResponseEntity.status(409).body(problem(ErrorCode.CONFLICT,
                    "The request conflicts with an existing resource."));
        }
        log.error("Data integrity violation (sqlState={})", sqlState, ex);
        return ResponseEntity.status(409).body(problem(ErrorCode.CONFLICT, "The request violates a data constraint."));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail body = problem(ErrorCode.VALIDATION_FAILED, "Request validation failed.");
        body.setProperty("errors", ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList());
        return ResponseEntity.badRequest().body(body);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return ResponseEntity.status(413).body(problem(ErrorCode.FILE_TOO_LARGE, "The uploaded file is too large."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(500).body(problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail body = problem(ErrorCode.VALIDATION_FAILED, "Request validation failed.");
        List<Map<String, String>> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> Map.of(
                        "field", error instanceof FieldError fe ? fe.getField() : error.getObjectName(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().headers(headers).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        ProblemDetail body = problem(ErrorCode.VALIDATION_FAILED, "Request validation failed.");
        body.setProperty("errors", ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> Map.of("field", r.getMethodParameter().getParameterName() == null
                                        ? "parameter" : r.getMethodParameter().getParameterName(),
                                "message", String.valueOf(e.getDefaultMessage()))))
                .toList());
        return ResponseEntity.badRequest().headers(headers).body(body);
    }

    /** Adds {@code code} and {@code traceId} to problem details produced by Spring MVC itself (404, 405, 415...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail pd && (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
            pd.setProperty("code", ErrorCode.forStatus(statusCode.value()).name());
            pd.setProperty("traceId", traceContext.currentTraceId());
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
        pd.setProperty("code", code.name());
        pd.setProperty("traceId", traceContext.currentTraceId());
        return pd;
    }

    private static String sqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
