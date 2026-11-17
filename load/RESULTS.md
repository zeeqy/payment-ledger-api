# Load test results

Run on 2026-11-17 with [`ledger.js`](ledger.js) (k6 1.4.2), inside the same Docker Compose network as the API.

**Machine:** laptop, AMD Ryzen 7 7435HS (8 cores / 16 threads), 44 GB RAM, Windows 11 + Docker Desktop (16 vCPU,
21 GB to the VM). API (1 container, JDK 25, virtual threads, Hikari pool 20) and Postgres 17 (1 container, default
config) share the machine with k6. Rate limit raised for the test (`RATE_LIMIT_PER_MINUTE=1000000`).

**Traffic mix (open model, constant arrival rate, 60 s per step):** 60% P2P transfers, 30% balance reads, 10% merchant
payments, spread over 50 funded wallets and **one** merchant. Every write has a fresh `Idempotency-Key`.

| Target rate | Achieved | Failed | Transfer p50 / p95 | Payment p50 / p95 | Balance p50 / p95 |
|---|---|---|---|---|---|
| 400 req/s | 399 req/s | 0% | 13.5 / 33.0 ms | 15.9 / 63.3 ms | 2.5 / 6.4 ms |
| 600 req/s | 600 req/s | 0% | 15.7 / 69.0 ms | 25.1 / 156.8 ms | 2.4 / 35.6 ms |
| 800 req/s | **677 req/s** (saturated, 7,353 iterations dropped) | 0% | 438 / 991 ms | 554 / 1,121 ms | 456 / 1,379 ms |

After the runs, `GET /admin/reconciliation` returned `ok: true` (postings sum to zero, every cached balance equals its
postings, nothing overdrawn).

## Reading it

- **~600 req/s sustained** on one laptop with p95 under 70 ms for transfers. Above ~680 req/s latency climbs fast
  (queueing): that's the ceiling for this setup.
- **Payments are slower than transfers** at the same load, though they do the same amount of SQL. Every payment locks
  the *same* merchant account row, so they queue behind each other. This is the classic "hot account" problem; see
  `docs/DESIGN.md` for the fixes (sub-accounts / sharded merchant balances, or async settlement).
- Each write takes ~6 statements in one transaction (key insert, 2 row locks, limit query, entry, 2 postings with
  balance triggers, audit, key update), so 600 req/s is roughly 2,000-3,000 statements/s on a default Postgres.

## Caveats (say these out loud)

- Single run per step on a shared laptop; numbers vary maybe ±20% between runs. Not a cloud benchmark.
- k6 runs on the same machine, so it competes for CPU with the API and the database.
- An earlier attempt that sent traffic through Docker Desktop's host port-forwarding (`host.docker.internal`) hit
  occasional TCP dial timeouts in the forwarder itself (the API logged no errors); that's why the test runs inside the
  Compose network.

Reproduce: see README, "Load test".
