package com.wallet.errorhandling;

import com.wallet.dto.ErrorResponse;
import com.wallet.logging.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Single place where every uncaught exception in the request path is turned
 * into a response. Two things this fixes that Spring's default error
 * handling doesn't do on its own:
 *
 *  1. correlationId is attached to every error body, not just the server-side
 *     log line - a caller can quote it back when reporting an issue.
 *  2. Our own carefully-written reasons (ResponseStatusException's "reason")
 *     actually reach the client - Spring Boot's default error page omits
 *     the "message" field unless explicitly configured, so today a 409
 *     conflict or 401 shows the client nothing but a bare status code.
 *
 * Anything not explicitly handled here (e.g. a genuine bug - a NullPointer,
 * an IllegalStateException from a broken invariant) falls to the generic
 * handler at the bottom: logged at ERROR with the full stack trace and
 * correlationId server-side, but the client only ever sees a generic message
 * plus the correlationId - never the internal exception detail.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {

        HttpStatusCode status = ex.getStatusCode();

        log.warn("request_failed",
                kv("status", status.value()),
                kv("path", request.getRequestURI()),
                kv("reason", ex.getReason()));

        ErrorResponse body = ErrorResponse.of(
                status.value(),
                HttpStatus.valueOf(status.value()).getReasonPhrase(),
                ex.getReason(),
                currentCorrelationId(),
                request.getRequestURI());

        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fieldErrors.put(fe.getField(), fe.getDefaultMessage()));

        log.warn("request_validation_failed",
                kv("path", request.getRequestURI()),
                kv("fieldErrors", fieldErrors));

        ErrorResponse body = ErrorResponse.ofValidation(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "request validation failed - see fieldErrors",
                currentCorrelationId(),
                request.getRequestURI(),
                fieldErrors);

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedBody(HttpMessageNotReadableException ex, HttpServletRequest request) {

        log.warn("request_body_malformed",
                kv("path", request.getRequestURI()));

        ErrorResponse body = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "request body is missing or not valid JSON",
                currentCorrelationId(),
                request.getRequestURI());

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {

        String correlationId = currentCorrelationId();

        // Full detail goes to the server log only - the client never sees
        // exception internals, just enough to report the issue.
        log.error("unhandled_exception",
                kv("path", request.getRequestURI()),
                kv("exceptionType", ex.getClass().getName()),
                ex);

        ErrorResponse body = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "an unexpected error occurred - reference correlationId when reporting this",
                correlationId,
                request.getRequestURI());

        return ResponseEntity.internalServerError().body(body);
    }

    private String currentCorrelationId() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }
}
