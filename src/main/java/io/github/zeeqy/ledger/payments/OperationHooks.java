package io.github.zeeqy.ledger.payments;

import io.github.zeeqy.ledger.ledger.LedgerService.EntryKind;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Cross-cutting checks and side effects around money movement. Called inside the operation's transaction. */
@Component
public class OperationHooks {

    /** Runs after the source account is locked, before posting. Throw ApiException to refuse. */
    public void beforeOutgoing(UUID userId, UUID fromAccount, UUID toAccount, EntryKind kind, long amountSen) {}

    /** Queues a merchant webhook. */
    public void afterMerchantEvent(UUID merchantId, String eventType, Map<String, ?> data) {}
}
