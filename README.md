# LedgerLine

A double-entry ledger and payment-processing engine built to stay correct under
concurrency, retries, and partial failures — with a tamper-evident, hash-chained
transaction history.

This is **Phase 1**: the core correctness engine (Postgres + Spring Boot).
Kafka, the transactional outbox, Redis rate limiting, reconciliation, and
Kubernetes come in later phases — this phase has to be provably correct first.

## What's actually being proven here

1. **Idempotency** — the same request, retried any number of times with the
   same `Idempotency-Key` header, produces exactly one transaction. Enforced
   by a `UNIQUE` constraint on `idempotency_key`, not just an in-memory check.
2. **No double-spending under concurrency** — two simultaneous transfers out
   of the same account can't both succeed past the balance the account
   actually has. Enforced by `SELECT ... FOR UPDATE` row locking inside a
   single `@Transactional` method — not an application-level lock.
3. **No deadlocks on opposite-direction transfers** — A→B and B→A at the
   same time won't deadlock, because both always lock accounts in the same
   deterministic order (lower UUID first), never "source then destination."
4. **Tamper evidence** — every `LedgerTransaction` is hash-chained to the one
   before it (see `HashChainUtil`). Mutate any historical row directly in
   Postgres and every hash after it stops matching what's stored.

All four are exercised by real, concurrent Testcontainers-backed tests in
`PaymentServiceConcurrencyTest` — not asserted, *demonstrated*.

## Running it locally

```bash
docker compose up -d          # starts Postgres, seeds nothing (Flyway does that)
mvn spring-boot:run           # applies V1__init_schema.sql automatically on boot
```

Then, in a second terminal, serve the frontend (any static server works):

```bash
cd frontend
python3 -m http.server 5500
# open http://localhost:5500 in a browser
```

You'll see Alice and Bob's live balances, a form to send a real payment
between them, a button to resend the exact same request (proving idempotency
— balances won't move twice), and a button to verify the hash chain.

Seed accounts (created by the migration):
- Alice — `11111111-1111-1111-1111-111111111111` — balance 1000.00 INR
- Bob — `22222222-2222-2222-2222-222222222222` — balance 500.00 INR

Send a payment:

```bash
curl -X POST http://localhost:8080/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-key-001" \
  -d '{
    "sourceAccountId": "11111111-1111-1111-1111-111111111111",
    "destinationAccountId": "22222222-2222-2222-2222-222222222222",
    "amount": 50.00,
    "currency": "INR"
  }'
```

Send it again with the *same* `Idempotency-Key` — you'll get back the exact
same `transactionId` and `hash`, and Alice's balance won't move a second time.

## Running the concurrency proofs

```bash
mvn test
```

This pulls a real Postgres via Testcontainers (needs Docker running) and
fires 20 genuinely concurrent duplicate requests, plus 10 concurrent
overdraft attempts, at the actual service. Read the test file — the
assertions are the whole point of the project.

## Verifying the chain — and the false-positive fix

`ChainVerifier` walks every transaction from genesis, recomputing each hash
and comparing it to what's stored. Two things had to be fixed for this to be
trustworthy rather than noisy:

- **Timestamp precision**: `Instant.now()` has nanosecond precision, but
  Postgres `TIMESTAMPTZ` only stores microseconds. Hashing the untruncated
  value at write time and recomputing after a round trip through the
  database would disagree on every single row — a permanent false positive.
  `HashChainUtil.normalizeInstant()` truncates to microseconds *before* the
  value is either stored or hashed, so write time and verify time always
  agree.
- **BigDecimal scale**: a value re-read from a `NUMERIC(19,4)` column can
  have a different internal scale than it was constructed with, which
  changes `toPlainString()` output even though the value is unchanged.
  `HashChainUtil.normalizeAmount()` rescales to a fixed 4 decimal places
  before hashing, both on write and on verify.

Hit it directly:

```bash
curl http://localhost:8080/admin/chain/verify
```

Returns `200` with `"valid": true` on a clean chain, or `409` with the exact
`brokenTransactionId` and a `reason` on a broken one.

**Live demo of the tamper-evidence feature:**

```bash
# 1. Send a payment, note the transactionId from the response
# 2. Corrupt it directly in the database, bypassing the app entirely:
docker exec -it ledgerline-postgres psql -U ledgerline -d ledgerline \
  -c "UPDATE ledger_transactions SET amount = 999.00 WHERE id = '<transactionId>';"
# 3. Ask the verifier:
curl http://localhost:8080/admin/chain/verify
# -> 409, brokenTransactionId matches the row you just edited
```

`ChainVerifierTamperDetectionTest` automates exactly this sequence.

## A known, deliberate trade-off

Every transaction locks the single `chain_state` row to append to the hash
chain, which serializes that one step across all transactions — even ones
touching completely unrelated accounts. This is a genuine throughput cost in
exchange for a single, globally verifiable chain of custody. Worth being able
to explain and defend, not worth hiding.

## What's next (later phases)

- Transactional outbox → Kafka, so other services hear about settled payments
  without ever losing an event, even across a crash between commit and publish
- A scheduled reconciliation job that independently re-derives every account's
  balance from `ledger_entries` and walks the hash chain to verify no
  historical row has been altered
- Redis for idempotency-key read acceleration and per-API-key rate limiting
  (never as the source of truth for account balance)
- Containerize with Docker, then Kubernetes: the Payment API as a horizontally
  scaled `Deployment`, reconciliation as a `CronJob`
