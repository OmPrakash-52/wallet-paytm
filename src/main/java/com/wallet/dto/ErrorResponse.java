package com.wallet.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Uniform error body for every failure path in the API. correlationId lets a
 * caller quote back exactly which request they mean when reporting an issue,
 * and matches the same value already attached to every server-side log line
 * for that request (see CorrelationIdFilter) - so a support/debugging
 * conversation can go straight from "here's what the client got" to
 * "here's every log line the server produced for it."
 *
 * fieldErrors is null for most errors and populated only for request
 * validation failures (field name -> validation message).
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String correlationId,
        String path,
        Map<String, String> fieldErrors
) {
    public static ErrorResponse of(int status, String error, String message, String correlationId, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, correlationId, path, null);
    }

    public static ErrorResponse ofValidation(int status, String error, String message, String correlationId,
                                              String path, Map<String, String> fieldErrors) {
        return new ErrorResponse(Instant.now(), status, error, message, correlationId, path, fieldErrors);
    }
}
