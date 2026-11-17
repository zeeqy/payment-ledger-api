package io.github.zeeqy.ledger.api;

import io.github.zeeqy.ledger.common.ApiException;
import io.github.zeeqy.ledger.common.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every exception to the one problem+json shape from {@link Problems}. */
@RestControllerAdvice
class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> api(ApiException e, HttpServletRequest req) {
        return Problems.response(e.status(), e.code(), e.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Object> invalid(MethodArgumentNotValidException e, HttpServletRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        Map<String, Object> body = Problems.body(
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request body is invalid", req.getRequestURI());
        body.put("errors", fields);
        return ResponseEntity.badRequest().contentType(Problems.PROBLEM_JSON).body(body);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    ResponseEntity<Object> invalidParam(Exception e, HttpServletRequest req) {
        return Problems.response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "A query or path parameter is invalid",
                req.getRequestURI());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Object> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        return Problems.response(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "Body is not valid JSON for this endpoint (amounts are integer sen, no decimals)",
                req.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Object> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest req) {
        return Problems.response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Bad value for '" + e.getName() + "'",
                req.getRequestURI());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Object> notFound(NoResourceFoundException e, HttpServletRequest req) {
        return Problems.response(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such endpoint", req.getRequestURI());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Object> method(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return Problems.response(
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", e.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Object> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest req) {
        return Problems.response(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "Send application/json",
                req.getRequestURI());
    }

    /** Safety net: the DB refused something the application should have caught first. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> integrity(DataIntegrityViolationException e, HttpServletRequest req) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        if (msg.contains("ck_no_overdraft")) {
            return Problems.response(
                    HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_FUNDS", "Insufficient funds", req.getRequestURI());
        }
        log.error("Integrity violation on {}", req.getRequestURI(), e);
        return Problems.response(
                HttpStatus.CONFLICT, "CONFLICT", "Request conflicts with current state", req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {}", req.getRequestURI(), e);
        return Problems.response(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong", req.getRequestURI());
    }
}
