# Error codes

Every error is `application/problem+json` (RFC 9457) with one extra field, `code`, that clients can switch on:

```json
{
  "type": "https://github.com/zeeqy/payment-ledger-api/blob/main/docs/ERRORS.md#insufficient_funds",
  "title": "Unprocessable Content",
  "status": 422,
  "code": "INSUFFICIENT_FUNDS",
  "detail": "Balance is 100 sen, needs 999 sen",
  "instance": "/transfers"
}
```

Validation errors add `errors: {field: message}`.

| Status | `code` | When |
|---|---|---|
| 400 | <a id="validation_failed"></a>`VALIDATION_FAILED` | Body or parameter breaks a rule (amount <= 0, bad email, ...) |
| 400 | <a id="malformed_request"></a>`MALFORMED_REQUEST` | Not JSON, wrong type, **decimal amount**, or unknown field |
| 400 | <a id="idempotency_key_required"></a>`IDEMPOTENCY_KEY_REQUIRED` | Money-moving POST without `Idempotency-Key` |
| 401 | <a id="unauthenticated"></a>`UNAUTHENTICATED` | Missing, invalid or expired bearer token |
| 401 | <a id="bad_credentials"></a>`BAD_CREDENTIALS` | Login failed (same message for unknown email and wrong password) |
| 403 | <a id="forbidden"></a>`FORBIDDEN` | Role can't do this (e.g. a user calling a refund or `/admin`) |
| 403 | <a id="not_a_merchant"></a>`NOT_A_MERCHANT` | Refund attempted by a non-merchant account |
| 404 | <a id="not_found"></a>`NOT_FOUND` | No such endpoint |
| 404 | `ACCOUNT_NOT_FOUND`, `NO_WALLET`, `NO_ACCOUNT`, `MERCHANT_NOT_FOUND`, `PAYMENT_NOT_FOUND`, `ENTRY_NOT_FOUND` | Referenced thing doesn't exist (or isn't yours) |
| 409 | <a id="wallet_exists"></a>`WALLET_EXISTS`, `MERCHANT_EXISTS`, `EMAIL_TAKEN` | Already created |
| 409 | <a id="already_reversed"></a>`ALREADY_REVERSED`, `HAS_REFUNDS`, `PAYMENT_REVERSED` | Reversal/refund conflicts with what already happened |
| 422 | <a id="insufficient_funds"></a>`INSUFFICIENT_FUNDS` | Balance too low |
| 422 | <a id="daily_limit_exceeded"></a>`DAILY_LIMIT_EXCEEDED` | Today's outgoing total (Malaysia time) would pass the limit |
| 422 | <a id="amount_above_limit"></a>`AMOUNT_ABOVE_LIMIT` | Single transfer/payment above the per-transaction cap |
| 422 | <a id="refund_exceeds_payment"></a>`REFUND_EXCEEDS_PAYMENT` | Refunds would total more than the payment |
| 422 | <a id="risk_declined"></a>`RISK_DECLINED` | Fraud model flagged the transaction |
| 422 | `SAME_ACCOUNT`, `NOT_A_WALLET`, `NOT_REVERSIBLE` | Transfer to self / to a merchant; reversing a refund or reversal |
| 422 | <a id="idempotency_key_reused"></a>`IDEMPOTENCY_KEY_REUSED` | Same key, different request body or path |
| 429 | <a id="rate_limited"></a>`RATE_LIMITED` | Over the per-user limit; see `Retry-After` |
| 503 | <a id="risk_unavailable"></a>`RISK_UNAVAILABLE` | Fraud model down and `RISK_FAIL_OPEN=false` |
| 500 | <a id="internal_error"></a>`INTERNAL_ERROR` | Bug. Safe to retry with the same `Idempotency-Key` (5xx results are not stored) |

4xx results of money-moving requests **are** stored against the idempotency key and replayed: retrying an
`INSUFFICIENT_FUNDS` with the same key returns the same error even after a top-up. Use a new key for a new attempt.
