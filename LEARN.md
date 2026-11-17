# LEARN.md: Payment Ledger API (project 06)

## 1. What it does (3 sentences)

It is the backend of a small e-wallet: users open a MYR wallet, top up, send money to each other, pay merchants, and
merchants can refund. Every movement is stored as a double-entry journal entry whose postings sum to zero, and
PostgreSQL itself refuses anything that would unbalance the books, overdraw a wallet or edit history. Tests prove it
stays correct under 100 parallel transfers, duplicate retries and random operation sequences, and a k6 load test
measures how fast it is.

## 2. Decisions

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| Language/framework | Java 25 + Spring Boot 4 | TypeScript + Fastify (the original spec) | Targeting banks: Malaysian bank backends are mostly Java/Spring. |
| DB access | Spring `JdbcClient` with plain SQL | JPA/Hibernate | Every lock and statement is visible in the code; Hibernate hides when it flushes and how it locks, which is risky for money. |
| Money type | `bigint` sen, JSON integers, decimals rejected | `BigDecimal` / `numeric` | Integers can't have rounding errors, are fast, and MYR has a fixed 2 decimals. Jackson is configured to reject `12.5` instead of silently making it 12. |
| Ledger model | Double entry (entries + postings) | One `balance` column | Every balance can be explained and recomputed; the zero-sum rule detects bugs. |
| Balance | Cached column updated **only** by a DB trigger on posting insert | Sum postings on every read | O(1) reads, and the cache can't drift from postings because the app never writes it. Reconciliation checks it. |
| Concurrency | `SELECT ... FOR UPDATE`, locks in ascending id order | `SERIALIZABLE` isolation | Conflicting requests wait instead of failing with retry errors; fixed order prevents deadlocks. Cost: a hot account is serial. |
| Invariants in DB | Deferred constraint trigger (zero-sum), `CHECK` (no overdraft), append-only triggers | App code only | A bug or a manual SQL session still can't corrupt the ledger. |
| Idempotency | Key row in the same transaction as the money movement, savepoint for business errors | Redis cache / separate table written after | No window where money moved but the key wasn't saved; concurrent duplicates block on the key row and then replay. |
| Stored response | `json` column | `jsonb` | `jsonb` reorders keys; replays must be byte-identical (a test caught this). |
| Webhooks | Transactional outbox + poller with `SKIP LOCKED` lease | Call merchant inside the transaction | Merchant is never told about a payment that rolled back, and never misses one that committed. |
| Retry policy | Exponential backoff `base*2^(n-1)` + 10% jitter, 8 attempts, then DEAD | Fixed interval | Gives broken endpoints time to recover without hammering them; jitter avoids synchronized retries. |
| Signature | `t=<ts>,v1=HMAC-SHA256(secret, ts.body)` | Plain HMAC of body | Timestamp in the signature blocks replay of old webhooks. |
| Auth | Stateless HS256 JWT, BCrypt | Sessions / OAuth provider | Simple and enough for one service; would move to RS256+JWKS with several services. |
| Rate limit key | User id (after JWT), IP only before login | IP | Many Malaysians share a carrier NAT IP; per-IP would throttle strangers together. |
| Risk check placement | Before taking row locks; fail-open by default | After locks; fail-closed | A slow model must not hold a wallet locked. An outage shouldn't stop all payments; it's audited instead. |
| Error format | RFC 9457 problem+json + `code` | Ad-hoc JSON | One shape for every error path (validation, auth, 429, replayed errors), standard, machine-readable. |
| Tests | Testcontainers (real Postgres 17) | H2 in-memory | The correctness lives in Postgres features (triggers, `FOR UPDATE`, `SKIP LOCKED`); H2 can't test them. |
| Property test | Seeded random sequences + in-memory model | jqwik | jqwik-spring isn't certified for Spring 7 / JUnit 6 yet; fixed seeds keep failures reproducible. |
| Build | Maven + wrapper | Gradle | More common in banks; wrapper means no install needed. |

## 3. Concepts I must understand

**Double-entry bookkeeping.** Every movement debits one account and credits another by the same amount, so the
signed postings of each entry sum to zero and the whole ledger sums to zero. Fixes are new reversing entries, never
edits. *Used in* `db/migration/V1__ledger_schema.sql`, `ledger/LedgerService.java`.

**Pessimistic row locking (`SELECT ... FOR UPDATE`).** Locks the selected rows until the transaction ends; other
transactions trying to lock them wait. Lets "check balance, then debit" be atomic. *Used in* `LedgerService.lock`.

**Deadlock and lock ordering.** T1 holds A wants B, T2 holds B wants A: both wait forever (Postgres kills one).
Always locking in the same global order (sorted ids) makes that cycle impossible. *Used in* `LedgerService.lock`
(`TreeSet`), proven by `ConcurrencyTest.opposingTransfersDoNotDeadlock`.

**Deferred constraint trigger.** A trigger declared `DEFERRABLE INITIALLY DEFERRED` runs at `COMMIT` instead of per
statement, so you can insert both postings of an entry and only then check they sum to zero. *Used in* the
`trg_entry_balanced` trigger in `V1__ledger_schema.sql`.

**Idempotency key.** A client-chosen id for one logical operation; the server stores the result under it and returns
the stored result for repeats. Makes retries safe on unreliable mobile networks. *Used in*
`idempotency/IdempotencyService.java`, `api/Idempotent.java`.

**Savepoint (nested transaction).** A marker inside a transaction you can roll back to without losing the whole
transaction. Lets us undo a refused operation's writes but still commit the stored error response. *Used in*
`IdempotencyService` (`PROPAGATION_NESTED`).

**Transactional outbox.** Write the event to a table in the same transaction as the business change; a separate worker
publishes it. Solves "dual write" (DB + HTTP can't be atomic). Gives at-least-once delivery. *Used in*
`webhooks/Outbox.java`, `webhooks/WebhookDispatcher.java`.

**`FOR UPDATE SKIP LOCKED`.** Lock rows, but silently skip rows another transaction already locked. Standard way to
build a job queue in Postgres where several workers each grab different jobs. *Used in* `WebhookDispatcher.claim`.

**HMAC signature.** `HMAC-SHA256(secret, message)` proves the sender knows the shared secret and that the message
wasn't changed. Compare with a constant-time function so timing doesn't leak the right answer. *Used in*
`webhooks/WebhookSigner.java`.

**Token bucket rate limiting.** Each user has a bucket of N tokens refilled at N per minute; each request takes one;
empty bucket -> 429 with `Retry-After`. Allows short bursts while capping the average. *Used in*
`auth/RateLimitFilter.java`.

**JWT.** A signed token carrying claims (`sub`, `role`, `exp`). The server verifies the signature instead of looking up
a session, so it's stateless. Can't be revoked before expiry without extra work (short TTL here). *Used in*
`auth/SecurityConfig.java`, `auth/UserService.issue`.

**p95 latency / open-model load test.** p95 = 95% of requests were faster than this. "Constant arrival rate" keeps
sending N req/s even when the server slows down (like real users), so queueing shows up honestly as latency.
*Used in* `load/ledger.js`, `load/RESULTS.md`.

**Model-based property testing.** Generate random operation sequences, run them on the real system and on a trivially
correct model, and assert they agree plus invariants hold after every step. *Used in* `proof/LedgerPropertyTest.java`.

## 4. Interview questions

**1. Why double entry instead of just a balance column?**
With one column, a wrong balance has no explanation. With double entry each balance is the sum of its postings, every
entry sums to zero, and nothing is ever edited, so any number can be audited and the "sum of everything is zero" check
catches bugs immediately. It's also how accountants and core banking systems already think.

**2. Two requests try to spend the last RM 10 at the same time. What happens?**
Both transactions try `SELECT ... FOR UPDATE` on the wallet row. One gets the lock, checks the balance, posts, commits.
The other waits, then reads the new balance (0) and gets `INSUFFICIENT_FUNDS`. The test with 100 parallel transfers
shows exactly 66 of 100 succeed for a balance that covers 66.

**3. Why not SERIALIZABLE isolation?**
It's also correct, but Postgres resolves conflicts by aborting one transaction with a serialization failure, so every
endpoint needs a retry loop. Row locks make the second request wait instead. I'd consider serializable if the logic
read many rows that are hard to lock explicitly.

**4. How do you avoid deadlocks?**
Every operation locks its accounts in ascending UUID order. Deadlocks need a cycle (A waits for B, B waits for A), and
a single global order makes cycles impossible. I tested 100 simultaneous A->B and B->A transfers: all succeed.

**5. Walk me through idempotency. What if two identical requests arrive at the same millisecond?**
The first inserts the key row and does the work in the same transaction. The second's `INSERT ... ON CONFLICT DO
NOTHING` blocks on the first's uncommitted row; when the first commits, the second sees the stored response and
returns it with `Idempotent-Replayed: true`. If the first crashes, its key row rolls back and the second runs the work
itself. Same key with a different body returns 422.

**6. Why the outbox pattern for webhooks?**
You can't make a DB commit and an HTTP call atomic. Calling the merchant inside the transaction risks telling them
about a payment that then rolls back; calling after commit risks losing the event if we crash. Writing the event row in
the same transaction and sending it from a worker means it's sent if and only if the payment committed, at least once.

**7. A merchant gets the same webhook twice. Is that a bug?**
No, it's at-least-once delivery by design: we could crash after their 200 but before marking it delivered. Each event
has a stable `id`, so the merchant deduplicates. Exactly-once delivery over HTTP isn't possible; exactly-once
*processing* is the receiver's job with that id.

**8. What breaks first if traffic grows 10x?**
Hot accounts: every payment to one big merchant locks the same row, so they're serial; the load test already shows
payment p95 at about twice transfer p95. Fix with merchant sub-accounts or async batched settlement. After that the
single Postgres primary, then table growth (partition `postings`, expire idempotency keys), then in-memory rate limits
(move to Redis).

**9. How do you know the balances are right?**
Three layers: the DB triggers make an unbalanced entry or overdraft impossible to commit; `/admin/reconciliation`
recomputes every balance from raw postings and checks the total is zero; and the property test runs 1,600 random
operations comparing each balance to an in-memory model after every step.

**10. What's your load test result and how much do you trust it?**
About 600 req/s sustained with transfer p95 69 ms and 0 errors; it saturates around 680 req/s. It's one laptop with
k6, the API and Postgres competing for the same CPU, single runs, so I'd trust the shape (where it saturates, payments
slower than transfers) more than the exact numbers. I also found that going through Docker Desktop's port-forwarder
caused dial timeouts, so I moved k6 into the Compose network.

## 5. What is weak, and how to say it

- **It's a simulation.** "Top-ups are simulated and there are no bank withdrawals or KYC tiers, so it's the ledger core
  of an e-wallet, not a licensed payment system. I focused on the part that has to be correct."
- **Single currency.** "Everything is MYR. Multi-currency would need an account per currency and FX entries between
  them; the schema has a currency column ready but enforces MYR."
- **Laptop benchmark.** "The numbers are from one machine with the load generator on it too. I report the method and
  the caveats in RESULTS.md and use them to find the bottleneck, not as a capacity promise."
- **Home-made property test.** "I generate seeded random sequences and compare against a model myself because jqwik's
  Spring integration doesn't support Spring 7 yet. It finds bugs, but it doesn't shrink a failure to the smallest
  case like a real property-testing library would."
- **Scale shortcuts.** "Rate limits are in memory and webhooks are polled. Both are fine for one instance; I wrote down
  the fixes (Redis, CDC) in DESIGN.md."
- **The fraud hook uses a model trained on PaySim,** so its scores aren't calibrated for MYR wallets; the integration
  pattern (timeout, fail-open, audited) is the point.
- **Built fast with an AI assistant.** "I built it with Claude Code; I can explain every decision and I've run every
  test and the load test myself."

## 6. Resume bullets

- **[SWE]** Built a double-entry e-wallet ledger API in Java 25 / Spring Boot 4 / PostgreSQL with idempotency keys,
  ordered row locking and DB-enforced invariants; 100 parallel transfers from one wallet produce exactly the
  affordable 66 with zero overdrafts or deadlocks.
- **[SWE / cloud]** Load-tested the API with k6 at 600 req/s (transfer p95 69 ms, 0% errors) and identified the
  hot-merchant-account lock as the first bottleneck (payment p95 2.3x transfers), documenting sharding and async
  settlement fixes.
- **[SWE]** Implemented signed merchant webhooks with a transactional outbox (HMAC-SHA256, exponential backoff,
  dead-letter, `SKIP LOCKED` workers) and a 61-test suite on Testcontainers, including a model-based property test
  verifying postings sum to zero across 1,600 random operations.
