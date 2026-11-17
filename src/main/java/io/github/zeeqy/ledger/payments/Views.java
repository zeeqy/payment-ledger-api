package io.github.zeeqy.ledger.payments;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Response bodies. All money fields are integer sen. */
public final class Views {

    private Views() {}

    public record WalletView(UUID walletId, UUID ownerId, String currency, long balanceSen) {}

    public record MerchantView(UUID merchantId, UUID accountId, String name, String webhookUrl, String webhookSecret) {}

    /** Result of any money-moving operation, from the point of view of the caller's account. */
    public record OperationResult(
            UUID entryId, String kind, long amountSen, UUID accountId, long balanceAfterSen, UUID relatedEntryId) {}

    /** One line of an account statement. {@code amountSen} is signed from this account's point of view. */
    public record StatementLine(
            UUID entryId,
            String kind,
            long amountSen,
            UUID counterpartyAccountId,
            String description,
            UUID relatedEntryId,
            OffsetDateTime createdAt) {}
}
