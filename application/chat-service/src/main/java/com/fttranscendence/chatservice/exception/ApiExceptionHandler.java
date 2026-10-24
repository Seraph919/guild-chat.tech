
package com.fttranscendence.chatservice.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every error leaves this service in the spec's envelope:
 *   { "error": { "code": "...", "message": "..." } }
 *
 * Applies to REST only — a WebSocket frame never passes through Spring MVC,
 * so the handler there writes an error frame instead.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(body(e.getCode(), e.getMessage()));
    }

    /** e.g. /rooms/abc/messages — "abc" is not a Long. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(body("INVALID_PARAMETER", "'" + e.getName() + "' has an invalid value"));
    }

    /** A body that is not JSON at all, e.g. {"content": — the client's mistake, not a 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(body("MALFORMED_JSON", "Request body is not valid JSON"));
    }

    /** Spring's own routing errors keep their status (405, 415, 404) instead of becoming a 500. */
    @ExceptionHandler({
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            NoResourceFoundException.class })
    public ResponseEntity<Map<String, Object>> handleRoutingError(Exception e) {
        HttpStatus status = HttpStatus.valueOf(((ErrorResponse) e).getStatusCode().value());
        return ResponseEntity.status(status).body(body(status.name(), status.getReasonPhrase()));
    }

    /** Anything unplanned: log the detail, tell the client nothing useful to an attacker. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", "Unexpected error"));
    }

    private Map<String, Object> body(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> HandleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
        .forEach(error -> details.put(snakeCase(error.getField()) ,error.getDefaultMessage()));

        return ResponseEntity.badRequest().body(Map.of("error" ,Map.of(
            "code", "VALIDATION_ERROR",
            "message", "Request body is invalid",
            "details" , details
         )));
    }

    /**
     * Field names come from the Java records (attachmentUploadIds); the API is
     * snake_case, so details must name the JSON field the client actually sent.
     * An element keeps its index: attachmentUploadIds[0] -> attachment_upload_ids[0].
     */
    static String snakeCase(String field) {
        return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
