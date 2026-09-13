# Wallet & P2P Transfer Service

A small wallet API — get-or-create a wallet, deposit into it, and move money peer-to-peer between two wallets — built as a concurrency/correctness exercise. The interesting part isn't the CRUD, it's making sure the money math holds up when things happen at the same time: two people creating a wallet at once, the same transfer request retried five times, a hundred transfers hitting five wallets simultaneously.

Stack: Spring Boot 3 (Java 21), PostgreSQL, Flyway, Spring Security + JWT, Micrometer/Prometheus, structured JSON logging.

## Running it

You need Docker. That's it — Postgres comes with it.

```bash
docker compose up -d --build
```

First run pulls a couple of base images and builds the jar, so give it a minute or two. Once it's up:

```bash
curl http://localhost:8081/actuator/health
```

should say `{"status":"UP"}`. If port 8081 is already taken on your machine, override it:

```bash
APP_PORT=9090 docker compose up -d --build
```

To tear everything down (including the database volume, so you start completely fresh next time):

```bash
docker compose down -v
```

### Running it without Docker

If you'd rather run it straight from an IDE or `mvn spring-boot:run`, you need your own Postgres reachable at `localhost:5432` (or point `DB_URL` somewhere else — see below), then:

```bash
mvn spring-boot:run
```

Flyway will create the schema for you on startup.

## Configuration

Everything's driven by environment variables, all with sane local defaults baked into `application.yml`. Copy `.env.example` to `.env` and tweak if you need to — `.env` is git-ignored, so nothing you put there ends up in the repo.

| Variable | Default | What it's for |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://postgres:5432/wallet` (compose) | Postgres connection string |
| `DB_USER` / `DB_PASSWORD` | `wallet` / `wallet` (compose) | Postgres credentials |
| `JWT_SECRET` | a placeholder dev value | HMAC signing key for JWTs — **change this before deploying anywhere real** |
| `JWT_EXPIRATION_MS` | `3600000` (1 hour) | how long an issued token is valid |
| `APP_PORT` | `8081` | host port `docker compose` maps to the container's `8080` |

## API

Auth is a normal signup/login flow — nothing exotic, just enough to identify who's calling.

```
POST /auth/signup     — create an account (username, password, 10-digit phoneNumber). No token issued here.
POST /auth/login      — verify credentials, get back a JWT
```

Everything below needs `Authorization: Bearer <token>`:

```
POST /wallets                      — get-or-create a wallet for the caller
GET  /wallets/{id}                 — check a balance
POST /wallets/{id}/deposit         — top up a wallet (test/dev utility — simulates money entering from outside, not a P2P transfer)
POST /transfers                    — move money between two wallets
GET  /transfers/{id}               — look up a transfer's status
```

Everything money-related is **integer paise**, never floats.

### A full walk-through

```bash
BASE=http://localhost:8081

# create an account
curl -s -X POST $BASE/auth/signup -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"secret123","phoneNumber":"9876543210"}'

# log in to get a token
TOKEN=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"secret123"}' | node -pe "JSON.parse(require('fs').readFileSync(0)).token")

# get-or-create a wallet
curl -s -X POST $BASE/wallets -H "Authorization: Bearer $TOKEN"
# -> {"walletId":"...","balancePaise":0,"message":"wallet created successfully"}

# fund it (dev-only, no such thing as a "real" deposit endpoint in production)
curl -s -X POST $BASE/wallets/<walletId>/deposit -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"amount_paise":100000}'

# transfer to someone else's wallet
curl -s -X POST $BASE/transfers -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"from":"<walletId>","to":"<otherWalletId>","amount_paise":1500,"idempotency_key":"some-unique-key"}'
```

There's a Postman collection at [`postman/Wallet-API.postman_collection.json`](postman/Wallet-API.postman_collection.json) that does all of this for you and chains the tokens/wallet ids automatically — just import it and hit Run.

## The correctness stuff (this is the actual point of the exercise)

Four things had to hold up under concurrency, and here's how each one is actually enforced — not just "we tried to be careful," but a specific DB-level mechanism backing each one:

**Conservation & no overdraft.** [`TransferTxHelper.createAndSettle`](src/main/java/com/wallet/serviceImpl/TransferTxHelper.java) locks both wallets with `SELECT ... FOR UPDATE`, *always* in sorted-by-id order regardless of which one is "from" or "to" — that's what stops two opposite-direction transfers from deadlocking each other. The balance check happens after the lock is held, so nothing can read a stale balance mid-transfer. Backed up by a DB-level `CHECK (balance_paise >= 0)` in case anything ever bypasses the app layer.

**Exactly-once transfers.** The `idempotency_key` has a real `UNIQUE` constraint on it — the insert either wins or fails, no in-between. A retry with the same key returns the original result; a retry with the same key but a different body gets a clean `409`. Reserving the key and settling the transfer happen in one transaction, so nobody can ever observe a half-finished state.

**Race-free get-or-create.** Same trick, different table: `UNIQUE(user_id)` on wallets. Two people hammering `POST /wallets` for a brand-new user at the same instant both try to insert; exactly one wins, the other just re-reads the row the winner committed.

There's a write-up on *why* these particular mechanisms (row locks + unique constraints) over heavier alternatives like serializable isolation — ask if you want the longer version, it's mostly "this is the simplest thing that's actually correct, and heavier tools solve problems we don't have here."

### Proving it, not just claiming it

```bash
node scripts/burst-test.js http://localhost:8081
```

No dependencies to install — just Node 18+. Fires concurrent requests at a running instance and checks the invariants actually hold: one wallet out of N concurrent creates, one debit out of K concurrent retries, a clean decline on an empty wallet, no deadlock when A→B and B→A happen on the same two wallets at once, and total balance conserved across a burst of random transfers among several wallets.

There's a Python port too (`scripts/burst_test.py`, stdlib only) if that's more your thing.

## Logs & metrics

Every log line is JSON, and every request gets a correlation id (an incoming `X-Correlation-Id` header if you send one, otherwise a generated UUID) that's echoed back in the response and stamped on every log line for that request — logs, error responses, all of it. Domain events (`transfer_created`, `wallet_debited`, `wallet_credited`, `transfer_declined_insufficient_funds`, `idempotent_replay`, `user_created`, `wallet_created`, ...) get logged with structured fields, not just a text message.

```bash
docker logs <container> --tail 50
```

Metrics are exposed at `GET /metrics` in Prometheus text format — request rate, latency histograms (p50/p95/p99), error rate, plus three domain counters: `wallet_transfers_initiated_total`, `wallet_transfers_declined_insufficient_funds_total`, `wallet_idempotent_replays_total`. There's a second Postman collection ([`postman/Wallet-Metrics.postman_collection.json`](postman/Wallet-Metrics.postman_collection.json)) that snapshots `/metrics` before and after a burst and computes the actual rate/error-rate/percentiles for you instead of leaving you to read raw Prometheus output by hand.

## Project layout

```
src/main/java/com/wallet/
  controller/      REST endpoints
  service/         interfaces
  serviceImpl/     the actual logic, including the *TxHelper classes where
                   the locking/transaction boundaries live
  entity/          JPA entities
  repository/      Spring Data repositories
  dto/             request/response shapes
  security/        JWT issuing + verification
  errorhandling/   global exception -> consistent error response mapping
  logging/         correlation id filter
  config/          OpenAPI/Swagger setup

src/main/resources/db/migration/   Flyway migrations (schema lives here, not in JPA ddl-auto)
scripts/                            burst-test.js / burst_test.py
postman/                            two collections - one for the API, one for metrics
```

## Things worth knowing that aren't bugs

- `POST /wallets/{id}/deposit` is explicitly **not** part of the graded P2P surface — it's a stand-in for "money entering from a bank," which is why it's outside the "conservation across transfers" invariant. Real deployments would replace this with an actual payment rail integration.
- Auth is deliberately simple (a signed JWT, no refresh tokens, no roles) — it's there to identify a caller, not to be a security showcase.
- There's currently no check that the caller actually owns the `from` wallet on a transfer. Fine for this exercise's scope; would need fixing for anything real.

## Swagger

`http://localhost:8081/swagger-ui/index.html` once it's running — hit **Authorize** with a token from `/auth/login` to try authenticated endpoints straight from the browser.
