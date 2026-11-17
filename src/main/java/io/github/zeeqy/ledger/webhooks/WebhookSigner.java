package io.github.zeeqy.ledger.webhooks;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Header {@code Ledger-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}. Signing the
 * timestamp lets merchants reject replays older than a few minutes; signing the raw body detects any tampering.
 */
public final class WebhookSigner {

    public static final String HEADER = "Ledger-Signature";

    private WebhookSigner() {}

    public static String sign(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + hmac(secret, timestamp + "." + body);
    }

    /** What a merchant runs on receipt. Constant-time comparison; rejects stale timestamps. */
    public static boolean verify(String secret, String header, String body, long nowSeconds, long toleranceSeconds) {
        long t = -1;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t < 0 || v1 == null || Math.abs(nowSeconds - t) > toleranceSeconds) {
            return false;
        }
        byte[] expected = hmac(secret, t + "." + body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, v1.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Exponential backoff: base * 2^(attempt-1), capped at one hour. Attempt starts at 1. */
    public static long backoffMs(int attempt, long baseMs) {
        int exp = Math.min(Math.max(attempt - 1, 0), 20);
        return Math.min(baseMs * (1L << exp), 3_600_000L);
    }
}
