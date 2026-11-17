package io.github.zeeqy.ledger.api;

import io.github.zeeqy.ledger.audit.AuditService;
import io.github.zeeqy.ledger.audit.AuditService.AuditEntry;
import io.github.zeeqy.ledger.auth.UserService;
import io.github.zeeqy.ledger.auth.UserService.Token;
import io.github.zeeqy.ledger.ledger.ReconciliationService;
import io.github.zeeqy.ledger.ledger.ReconciliationService.Report;
import io.github.zeeqy.ledger.payments.OperationsService;
import io.github.zeeqy.ledger.payments.Views.MerchantView;
import io.github.zeeqy.ledger.payments.Views.OperationResult;
import io.github.zeeqy.ledger.payments.Views.StatementLine;
import io.github.zeeqy.ledger.payments.Views.WalletView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** All HTTP endpoints. Thin: validate, pick the user from the JWT, delegate. */
public final class Controllers {

    private Controllers() {}

    private static final String KEY_DOC = "Unique per logical operation (e.g. a UUID). Retries must reuse it.";

    @RestController
    @Tag(name = "Auth")
    static class AuthController {
        private final UserService users;
        private final OperationsService ops;

        AuthController(UserService users, OperationsService ops) {
            this.users = users;
            this.ops = ops;
        }

        @PostMapping("/auth/register")
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Register a wallet user and get a token")
        Token register(@Valid @RequestBody Requests.Register r) {
            UUID id = users.register(r.email(), r.password(), "USER");
            return users.issue(id, "USER");
        }

        public record MerchantRegistered(MerchantView merchant, Token token) {}

        @PostMapping("/auth/register/merchant")
        @ResponseStatus(HttpStatus.CREATED)
        @Transactional
        @Operation(
                summary = "Register a merchant",
                description = "Returns the webhook signing secret once. Store it; it is used to verify webhooks.")
        MerchantRegistered registerMerchant(@Valid @RequestBody Requests.RegisterMerchant r) {
            UUID id = users.register(r.email(), r.password(), "MERCHANT");
            MerchantView merchant = ops.registerMerchant(id, r.name(), r.webhookUrl());
            return new MerchantRegistered(merchant, users.issue(id, "MERCHANT"));
        }

        @PostMapping("/auth/login")
        @Operation(summary = "Exchange email + password for a JWT")
        Token login(@Valid @RequestBody Requests.Login r) {
            return users.login(r.email(), r.password());
        }
    }

    @RestController
    @Validated
    @Tag(name = "Wallets")
    static class WalletController {
        private final OperationsService ops;
        private final Idempotent idempotent;

        WalletController(OperationsService ops, Idempotent idempotent) {
            this.ops = ops;
            this.idempotent = idempotent;
        }

        @PostMapping("/wallets")
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Open your MYR wallet (one per user)")
        WalletView create(@AuthenticationPrincipal Jwt jwt) {
            return ops.createWallet(Idempotent.user(jwt));
        }

        @GetMapping("/wallets/me")
        @Operation(summary = "Your wallet (or merchant account) and balance")
        WalletView me(@AuthenticationPrincipal Jwt jwt) {
            return ops.wallet(Idempotent.user(jwt));
        }

        @GetMapping("/wallets/me/statement")
        @Operation(summary = "Latest postings on your account, newest first")
        List<StatementLine> statement(
                @AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
            return ops.statement(Idempotent.user(jwt), limit);
        }

        @PostMapping("/wallets/me/topups")
        @Operation(summary = "Top up from the (simulated) bank / FPX")
        @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = OperationResult.class)))
        ResponseEntity<String> topUp(
                @AuthenticationPrincipal Jwt jwt,
                @Parameter(description = KEY_DOC) @RequestHeader(value = Idempotent.HEADER, required = false)
                        String key,
                @Valid @RequestBody Requests.TopUp r,
                HttpServletRequest req) {
            return idempotent.run(jwt, key, req, r, () -> ops.topUp(Idempotent.user(jwt), r.amountSen()));
        }
    }

    @RestController
    @Tag(name = "Transfers")
    static class TransferController {
        private final OperationsService ops;
        private final Idempotent idempotent;

        TransferController(OperationsService ops, Idempotent idempotent) {
            this.ops = ops;
            this.idempotent = idempotent;
        }

        @PostMapping("/transfers")
        @Operation(summary = "P2P transfer to another wallet")
        @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = OperationResult.class)))
        ResponseEntity<String> transfer(
                @AuthenticationPrincipal Jwt jwt,
                @Parameter(description = KEY_DOC) @RequestHeader(value = Idempotent.HEADER, required = false)
                        String key,
                @Valid @RequestBody Requests.Transfer r,
                HttpServletRequest req) {
            return idempotent.run(
                    jwt,
                    key,
                    req,
                    r,
                    () -> ops.transfer(Idempotent.user(jwt), r.toWalletId(), r.amountSen(), r.note()));
        }
    }

    @RestController
    @Tag(name = "Payments")
    static class PaymentController {
        private final OperationsService ops;
        private final Idempotent idempotent;

        PaymentController(OperationsService ops, Idempotent idempotent) {
            this.ops = ops;
            this.idempotent = idempotent;
        }

        @PostMapping("/payments")
        @Operation(summary = "Pay a merchant from your wallet; the merchant gets a payment.succeeded webhook")
        @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = OperationResult.class)))
        ResponseEntity<String> pay(
                @AuthenticationPrincipal Jwt jwt,
                @Parameter(description = KEY_DOC) @RequestHeader(value = Idempotent.HEADER, required = false)
                        String key,
                @Valid @RequestBody Requests.Payment r,
                HttpServletRequest req) {
            return idempotent.run(
                    jwt,
                    key,
                    req,
                    r,
                    () -> ops.pay(Idempotent.user(jwt), r.merchantId(), r.amountSen(), r.reference()));
        }

        @PostMapping("/payments/{paymentId}/refunds")
        @Operation(summary = "Merchant: refund all or part of a payment (total refunds <= payment)")
        @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = OperationResult.class)))
        ResponseEntity<String> refund(
                @AuthenticationPrincipal Jwt jwt,
                @Parameter(description = KEY_DOC) @RequestHeader(value = Idempotent.HEADER, required = false)
                        String key,
                @PathVariable UUID paymentId,
                @Valid @RequestBody Requests.Refund r,
                HttpServletRequest req) {
            return idempotent.run(
                    jwt, key, req, r, () -> ops.refund(Idempotent.user(jwt), paymentId, r.amountSen(), r.reason()));
        }
    }

    @RestController
    @Validated
    @Tag(name = "Admin")
    static class AdminController {
        private final OperationsService ops;
        private final Idempotent idempotent;
        private final ReconciliationService reconciliation;
        private final AuditService audit;

        AdminController(
                OperationsService ops,
                Idempotent idempotent,
                ReconciliationService reconciliation,
                AuditService audit) {
            this.ops = ops;
            this.idempotent = idempotent;
            this.reconciliation = reconciliation;
            this.audit = audit;
        }

        @PostMapping("/admin/entries/{entryId}/reversal")
        @Operation(summary = "Operator: reverse a top-up, transfer or payment with a mirror entry")
        @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = OperationResult.class)))
        ResponseEntity<String> reverse(
                @AuthenticationPrincipal Jwt jwt,
                @Parameter(description = KEY_DOC) @RequestHeader(value = Idempotent.HEADER, required = false)
                        String key,
                @PathVariable UUID entryId,
                @Valid @RequestBody Requests.Reversal r,
                HttpServletRequest req) {
            return idempotent.run(jwt, key, req, r, () -> ops.reverse(Idempotent.user(jwt), entryId, r.reason()));
        }

        @GetMapping("/admin/reconciliation")
        @Operation(summary = "Check ledger invariants: postings sum to zero, cached balances match postings")
        Report reconcile() {
            return reconciliation.run();
        }

        @GetMapping("/admin/audit")
        @Operation(summary = "Latest audit log entries, optionally for one user")
        List<AuditEntry> audit(
                @RequestParam(required = false) UUID actorId,
                @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
            return audit.recent(actorId, limit);
        }
    }
}
