package io.github.zeeqy.ledger.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The single error shape of the API (RFC 9457 problem+json plus a stable {@code code}). Every error path - business
 * rules, validation, auth, rate limits, replayed idempotent errors - builds its body here so clients parse one format.
 */
public final class Problems {

    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private Problems() {}

    public static Map<String, Object> body(HttpStatus status, String code, String detail, String instance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://github.com/zeeqy/payment-ledger-api/blob/main/docs/ERRORS.md#" + code.toLowerCase());
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("code", code);
        body.put("detail", detail);
        if (instance != null) {
            body.put("instance", instance);
        }
        return body;
    }

    public static Map<String, Object> body(ApiException e, String instance) {
        return body(e.status(), e.code(), e.getMessage(), instance);
    }

    public static ResponseEntity<Object> response(HttpStatus status, String code, String detail, String instance) {
        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(body(status, code, detail, instance));
    }
}
