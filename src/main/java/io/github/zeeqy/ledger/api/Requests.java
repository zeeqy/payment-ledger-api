package io.github.zeeqy.ledger.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Validated request bodies. Amounts are integer sen: RM 12.34 is 1234. Floats are rejected by the JSON parser. */
public final class Requests {

    /** RM 1,000,000.00. Business limits (per transaction, per day) are checked separately and are lower. */
    static final long MAX_SEN = 100_000_000L;

    private Requests() {}

    public record Register(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 128) String password) {}

    public record RegisterMerchant(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 128) String password,
            @NotBlank @Size(max = 100) String name,

            @Pattern(regexp = "^https?://.+", message = "must be an http(s) URL") @Size(max = 500)
            String webhookUrl) {}

    public record Login(@NotBlank String email, @NotBlank String password) {}

    public record TopUp(
            @Schema(example = "5000") @Positive @Max(MAX_SEN)
            long amountSen) {}

    public record Transfer(
            @NotNull UUID toWalletId,

            @Schema(example = "1250") @Positive @Max(MAX_SEN)
            long amountSen,

            @Size(max = 140) String note) {}

    public record Payment(
            @NotNull UUID merchantId,

            @Schema(example = "2990") @Positive @Max(MAX_SEN)
            long amountSen,

            @Size(max = 100) String reference) {}

    public record Refund(
            @Positive @Max(MAX_SEN) long amountSen,
            @Size(max = 140) String reason) {}

    public record Reversal(@NotBlank @Size(max = 200) String reason) {}
}
