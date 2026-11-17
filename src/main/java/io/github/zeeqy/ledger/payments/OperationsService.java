package io.github.zeeqy.ledger.payments;

import io.github.zeeqy.ledger.audit.AuditService;
import io.github.zeeqy.ledger.common.ApiException;
import io.github.zeeqy.ledger.ledger.LedgerService;
import io.github.zeeqy.ledger.ledger.LedgerService.EntryKind;
import io.github.zeeqy.ledger.ledger.LedgerService.Leg;
import io.github.zeeqy.ledger.payments.Views.MerchantView;
import io.github.zeeqy.ledger.payments.Views.OperationResult;
import io.github.zeeqy.ledger.payments.Views.StatementLine;
import io.github.zeeqy.ledger.payments.Views.WalletView;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The e-wallet's business operations. Each one is a single DB transaction (joining the idempotency transaction when
 * called through the API): lock -> check rules -> post a balanced entry -> audit (-> outbox).
 */
@Service
public class OperationsService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final AuditService audit;
    private final OperationHooks hooks;

    public OperationsService(JdbcClient jdbc, LedgerService ledger, AuditService audit, OperationHooks hooks) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.audit = audit;
        this.hooks = hooks;
    }

    // ---------------------------------------------------------------- accounts

    @Transactional
    public WalletView createWallet(UUID userId) {
        UUID id;
        try {
            id = jdbc.sql("INSERT INTO accounts (owner_id, kind) VALUES (?, 'WALLET') RETURNING id")
                    .param(userId)
                    .query(UUID.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("WALLET_EXISTS", "This user already has an account");
        }
        audit.record(userId, "WALLET_CREATED", "account", id, Map.of());
        return new WalletView(id, userId, "MYR", 0);
    }

    @Transactional
    public MerchantView registerMerchant(UUID userId, String name, String webhookUrl) {
        UUID accountId;
        try {
            accountId = jdbc.sql("INSERT INTO accounts (owner_id, kind) VALUES (?, 'MERCHANT') RETURNING id")
                    .param(userId)
                    .query(UUID.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("MERCHANT_EXISTS", "This user already has an account");
        }
        byte[] secretBytes = new byte[32];
        RANDOM.nextBytes(secretBytes);
        String secret = "whsec_" + HexFormat.of().formatHex(secretBytes);
        jdbc.sql("""
                        INSERT INTO merchants (user_id, account_id, name, webhook_url, webhook_secret)
                        VALUES (?, ?, ?, ?, ?)""").params(userId, accountId, name, webhookUrl, secret).update();
        audit.record(userId, "MERCHANT_REGISTERED", "merchant", userId, Map.of("name", name));
        return new MerchantView(userId, accountId, name, webhookUrl, secret);
    }

    public WalletView wallet(UUID userId) {
        UUID id = accountOf(userId);
        return new WalletView(id, userId, "MYR", ledger.balance(id));
    }

    public List<StatementLine> statement(UUID userId, int limit) {
        UUID account = accountOf(userId);
        return jdbc.sql("""
                        SELECT e.id, e.kind, p.amount_sen, e.description, e.related_entry_id, e.created_at,
                               (SELECT o.account_id FROM postings o
                                 WHERE o.entry_id = e.id AND o.account_id <> p.account_id ORDER BY o.id LIMIT 1)
                                   AS counterparty
                        FROM postings p JOIN journal_entries e ON e.id = p.entry_id
                        WHERE p.account_id = ?
                        ORDER BY p.id DESC LIMIT ?""")
                .params(account, limit)
                .query((rs, n) -> new StatementLine(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getLong("amount_sen"),
                        rs.getObject("counterparty", UUID.class),
                        rs.getString("description"),
                        rs.getObject("related_entry_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    // ---------------------------------------------------------------- money movement

    /** Simulated FPX / card top-up: money comes in from the funding (bank settlement) account. */
    @Transactional
    public OperationResult topUp(UUID userId, long amountSen) {
        UUID wallet = walletOf(userId);
        UUID entry = ledger.post(
                EntryKind.TOPUP,
                "Top-up",
                null,
                userId,
                List.of(new Leg(ledger.fundingAccountId(), -amountSen), new Leg(wallet, amountSen)));
        audit.record(userId, "TOPUP", "entry", entry, Map.of("amountSen", amountSen));
        return result(entry, EntryKind.TOPUP, amountSen, wallet, null);
    }

    @Transactional
    public OperationResult transfer(UUID userId, UUID toWalletId, long amountSen, String note) {
        UUID from = walletOf(userId);
        if (from.equals(toWalletId)) {
            throw ApiException.unprocessable("SAME_ACCOUNT", "Cannot transfer to your own wallet");
        }
        var locked = ledger.lock(List.of(from, toWalletId));
        if (!"WALLET".equals(locked.get(toWalletId).kind())) {
            throw ApiException.unprocessable("NOT_A_WALLET", "Transfers go to wallets; pay merchants via /payments");
        }
        hooks.beforeOutgoing(userId, from, toWalletId, EntryKind.TRANSFER, amountSen);
        UUID entry = ledger.post(
                EntryKind.TRANSFER,
                note,
                null,
                userId,
                List.of(new Leg(from, -amountSen), new Leg(toWalletId, amountSen)));
        audit.record(userId, "TRANSFER", "entry", entry, Map.of("amountSen", amountSen, "to", toWalletId));
        return result(entry, EntryKind.TRANSFER, amountSen, from, null);
    }

    @Transactional
    public OperationResult pay(UUID userId, UUID merchantId, long amountSen, String reference) {
        UUID from = walletOf(userId);
        UUID merchantAccount = merchantAccount(merchantId)
                .orElseThrow(
                        () -> ApiException.notFound("MERCHANT_NOT_FOUND", "Merchant " + merchantId + " not found"));
        ledger.lock(List.of(from, merchantAccount));
        hooks.beforeOutgoing(userId, from, merchantAccount, EntryKind.PAYMENT, amountSen);
        UUID entry = ledger.post(
                EntryKind.PAYMENT,
                reference,
                null,
                userId,
                List.of(new Leg(from, -amountSen), new Leg(merchantAccount, amountSen)));
        audit.record(userId, "PAYMENT", "entry", entry, Map.of("amountSen", amountSen, "merchant", merchantId));
        hooks.afterMerchantEvent(
                merchantId,
                "payment.succeeded",
                Map.of(
                        "paymentId", entry,
                        "amountSen", amountSen,
                        "currency", "MYR",
                        "reference", reference == null ? "" : reference,
                        "customerWalletId", from));
        return result(entry, EntryKind.PAYMENT, amountSen, from, null);
    }

    /** Merchant refunds (part of) a payment it received. Total refunds can never exceed the payment. */
    @Transactional
    public OperationResult refund(UUID merchantUserId, UUID paymentId, long amountSen, String reason) {
        UUID merchantAccount = merchantAccount(merchantUserId)
                .orElseThrow(() -> ApiException.forbidden("NOT_A_MERCHANT", "Only merchants can refund"));
        Payment payment = payment(paymentId);
        if (!payment.merchantAccount().equals(merchantAccount)) {
            throw ApiException.notFound("PAYMENT_NOT_FOUND", "Payment " + paymentId + " not found");
        }
        // Lock first, then read refund totals: concurrent refunds of one payment serialize on the merchant row.
        ledger.lock(List.of(merchantAccount, payment.customerWallet()));
        if (isReversed(paymentId)) {
            throw ApiException.conflict("PAYMENT_REVERSED", "Payment was reversed");
        }
        long refunded = jdbc.sql("""
                        SELECT coalesce(sum(p.amount_sen), 0) FROM journal_entries e
                        JOIN postings p ON p.entry_id = e.id AND p.account_id = ?
                        WHERE e.kind = 'REFUND' AND e.related_entry_id = ?""")
                .params(payment.customerWallet(), paymentId)
                .query(Long.class)
                .single();
        long refundable = payment.amountSen() - refunded;
        if (amountSen > refundable) {
            throw ApiException.unprocessable(
                    "REFUND_EXCEEDS_PAYMENT", "Refundable amount is " + refundable + " sen, requested " + amountSen);
        }
        UUID entry = ledger.post(
                EntryKind.REFUND,
                reason,
                paymentId,
                merchantUserId,
                List.of(new Leg(merchantAccount, -amountSen), new Leg(payment.customerWallet(), amountSen)));
        audit.record(merchantUserId, "REFUND", "entry", entry, Map.of("amountSen", amountSen, "payment", paymentId));
        hooks.afterMerchantEvent(
                merchantUserId,
                "refund.succeeded",
                Map.of("refundId", entry, "paymentId", paymentId, "amountSen", amountSen, "currency", "MYR"));
        return result(entry, EntryKind.REFUND, amountSen, merchantAccount, paymentId);
    }

    /**
     * Operator correction: posts the exact mirror of an entry. The original stays untouched (append-only). Fails if
     * the receiver has already spent the money, like a real chargeback would need manual handling.
     */
    @Transactional
    public OperationResult reverse(UUID adminId, UUID entryId, String reason) {
        String kind = jdbc.sql("SELECT kind FROM journal_entries WHERE id = ?")
                .param(entryId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("ENTRY_NOT_FOUND", "Entry " + entryId + " not found"));
        if (!List.of("TOPUP", "TRANSFER", "PAYMENT").contains(kind)) {
            throw ApiException.unprocessable("NOT_REVERSIBLE", kind + " entries cannot be reversed");
        }
        List<Leg> original = ledger.postingsOf(entryId);
        ledger.lock(original.stream().map(Leg::accountId).toList());
        if (isReversed(entryId)) {
            throw ApiException.conflict("ALREADY_REVERSED", "Entry " + entryId + " was already reversed");
        }
        boolean hasRefunds = jdbc.sql(
                        "SELECT exists(SELECT 1 FROM journal_entries WHERE kind = 'REFUND' AND related_entry_id = ?)")
                .param(entryId)
                .query(Boolean.class)
                .single();
        if (hasRefunds) {
            throw ApiException.conflict("HAS_REFUNDS", "Payment has refunds; reverse is not allowed");
        }
        List<Leg> mirror = original.stream()
                .map(l -> new Leg(l.accountId(), -l.amountSen()))
                .toList();
        UUID entry = ledger.post(EntryKind.REVERSAL, reason, entryId, adminId, mirror);
        long amount =
                original.stream().mapToLong(Leg::amountSen).filter(a -> a > 0).sum();
        audit.record(adminId, "REVERSAL", "entry", entry, Map.of("original", entryId, "reason", reason));
        if ("PAYMENT".equals(kind)) {
            UUID merchantAccount = original.stream()
                    .filter(l -> l.amountSen() > 0)
                    .findFirst()
                    .orElseThrow()
                    .accountId();
            UUID merchantId = jdbc.sql("SELECT user_id FROM merchants WHERE account_id = ?")
                    .param(merchantAccount)
                    .query(UUID.class)
                    .single();
            hooks.afterMerchantEvent(
                    merchantId,
                    "payment.reversed",
                    Map.of("paymentId", entryId, "reversalId", entry, "amountSen", amount, "currency", "MYR"));
        }
        UUID firstAccount = original.getFirst().accountId();
        return result(entry, EntryKind.REVERSAL, amount, firstAccount, entryId);
    }

    // ---------------------------------------------------------------- helpers

    private record Payment(UUID customerWallet, UUID merchantAccount, long amountSen) {}

    private Payment payment(UUID paymentId) {
        String kind = jdbc.sql("SELECT kind FROM journal_entries WHERE id = ?")
                .param(paymentId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (!"PAYMENT".equals(kind)) {
            throw ApiException.notFound("PAYMENT_NOT_FOUND", "Payment " + paymentId + " not found");
        }
        List<Leg> legs = ledger.postingsOf(paymentId);
        Leg debit = legs.stream().filter(l -> l.amountSen() < 0).findFirst().orElseThrow();
        Leg credit = legs.stream().filter(l -> l.amountSen() > 0).findFirst().orElseThrow();
        return new Payment(debit.accountId(), credit.accountId(), credit.amountSen());
    }

    private boolean isReversed(UUID entryId) {
        return jdbc.sql("SELECT exists(SELECT 1 FROM journal_entries WHERE kind = 'REVERSAL' AND related_entry_id = ?)")
                .param(entryId)
                .query(Boolean.class)
                .single();
    }

    private UUID accountOf(UUID userId) {
        return jdbc.sql("SELECT id FROM accounts WHERE owner_id = ?")
                .param(userId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("NO_ACCOUNT", "Create a wallet first (POST /wallets)"));
    }

    private UUID walletOf(UUID userId) {
        return jdbc.sql("SELECT id FROM accounts WHERE owner_id = ? AND kind = 'WALLET'")
                .param(userId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("NO_WALLET", "Create a wallet first (POST /wallets)"));
    }

    private Optional<UUID> merchantAccount(UUID merchantId) {
        return jdbc.sql("SELECT account_id FROM merchants WHERE user_id = ?")
                .param(merchantId)
                .query(UUID.class)
                .optional();
    }

    private OperationResult result(UUID entry, EntryKind kind, long amountSen, UUID account, UUID related) {
        return new OperationResult(entry, kind.name(), amountSen, account, ledger.balance(account), related);
    }
}
