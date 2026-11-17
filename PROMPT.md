# 06 Payment Ledger API

Backend for a mini e-wallet: the kind of system a fintech software engineer actually works on. No UI beyond API docs.
Tracks shown: SWE (backend), fintech, cloud-ready.

## Stack
TypeScript, Fastify, PostgreSQL, Drizzle, Docker Compose, Vitest.
Ask me once at the start: if I say I am targeting banks, use Java + Spring Boot instead.

## Core
1. Double-entry ledger: accounts, journal entries, postings. Every entry sums to zero. Balances derive from postings.
2. Operations: create wallet, top-up, P2P transfer, merchant payment, refund/reversal.
3. Idempotency keys on every money-moving endpoint (same key, same result, no double charge).
4. Concurrency safety: DB transactions with row locks or serializable isolation. Money as integer sen, never floats.
5. JWT auth, per-user rate limiting, request validation, one consistent error format.
6. Merchant webhooks with HMAC signature and retries with backoff (outbox pattern).
7. Audit log, daily transfer limits, optional risk hook that calls project 04's `/predict`.
8. OpenAPI docs at `/docs`.

## Proof (this is what makes it portfolio-grade)
- Concurrency test: 100 parallel transfers out of one wallet, final balance correct, no overdraft.
- Idempotency test: same request sent 5 times, exactly one posting.
- Property test: sum of all postings is always zero.
- k6 load test, with p95 latency and throughput in the README.
- `docs/DESIGN.md`: schema diagram, why double entry, how idempotency and the outbox work, what breaks first at scale.
