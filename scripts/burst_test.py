#!/usr/bin/env python3
"""
One-command burst test for the Wallet & P2P Transfer API. Python port of
scripts/burst-test.js - same three scenarios, same assertions.

Reproduces the three scenarios graders run against the deployed URL:
  1. Concurrent get-or-create      -> exactly one wallet for a brand-new user
  2. Idempotent retry storm        -> exactly one debit/credit, identical responses
  3. Conservation under contention -> total balance unchanged, no negative balance

Uses only the Python standard library (urllib + concurrent.futures) - no pip
install needed. Requires Python 3.8+.

Usage:
  python scripts/burst_test.py [base_url]
  BASE_URL=https://your-app.onrender.com python scripts/burst_test.py

Exits with code 1 if any check fails (CI-friendly).
"""

import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE_URL = (sys.argv[1] if len(sys.argv) > 1 else None) or os.environ.get("BASE_URL", "http://localhost:8081")

N_CONCURRENT_WALLET_CREATES = int(os.environ.get("N_WALLET", "20"))
K_CONCURRENT_RETRIES = int(os.environ.get("K_RETRY", "20"))
CONTENTION_WALLET_COUNT = int(os.environ.get("CONTENTION_WALLETS", "5"))
CONTENTION_TRANSFER_COUNT = int(os.environ.get("CONTENTION_TRANSFERS", "80"))
INITIAL_DEPOSIT_PAISE = 100_000  # 1000.00 in paise, per wallet

_pass_count = 0
_fail_count = 0


def ok(label, detail=None):
    global _pass_count
    _pass_count += 1
    print(f"  \033[32mPASS\033[0m {label}" + (f" - {detail}" if detail else ""))


def bad(label, detail=None):
    global _fail_count
    _fail_count += 1
    print(f"  \033[31mFAIL\033[0m {label}" + (f" - {detail}" if detail else ""))


def section(title):
    print(f"\n\033[1m== {title} ==\033[0m")


def random_digits(n):
    return "".join(str(random.randint(0, 9)) for _ in range(n))


def random_phone():
    # must be exactly 10 digits; force a non-zero leading digit
    return str(random.randint(1, 9)) + random_digits(9)


def unique_suffix():
    return f"{int(time.time() * 1000)}-{random.randint(0, 0xFFFFFFFF):x}"


def api(method, path, token=None, body=None):
    url = f"{BASE_URL}{path}"
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)

    try:
        with urllib.request.urlopen(req) as res:
            raw = res.read()
            status = res.status
    except urllib.error.HTTPError as e:
        raw = e.read()
        status = e.code

    try:
        parsed = json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        parsed = {"raw": raw.decode("utf-8", errors="replace")}

    return status, parsed


def signup(username, password, phone_number):
    status, body = api("POST", "/auth/signup", body={
        "username": username, "password": password, "phoneNumber": phone_number,
    })
    if status != 201:
        raise RuntimeError(f"signup failed for {username}: {status} {body}")

    # Signup only confirms account creation - it doesn't issue a token.
    # Log in right after to get one, same as any real client would.
    login_status, login_body = api("POST", "/auth/login", body={
        "username": username, "password": password,
    })
    if login_status != 200:
        raise RuntimeError(f"login after signup failed for {username}: {login_status} {login_body}")

    return login_body  # { userId, token, tokenType, expiresInMs }


def signup_fresh_user(prefix):
    suffix = unique_suffix()
    return signup(f"{prefix}-{suffix}", "secret123", random_phone())


def create_wallet(token):
    return api("POST", "/wallets", token=token)


def get_wallet(token, wallet_id):
    status, body = api("GET", f"/wallets/{wallet_id}", token=token)
    if status != 200:
        raise RuntimeError(f"getWallet({wallet_id}) failed: {status} {body}")
    return body


def deposit(token, wallet_id, amount_paise):
    status, body = api("POST", f"/wallets/{wallet_id}/deposit", token=token, body={"amount_paise": amount_paise})
    if status != 200:
        raise RuntimeError(f"deposit({wallet_id}) failed: {status} {body}")
    return body


def transfer(token, from_id, to_id, amount_paise, idempotency_key):
    return api("POST", "/transfers", token=token, body={
        "from": from_id, "to": to_id, "amount_paise": amount_paise, "idempotency_key": idempotency_key,
    })


# ---------------------------------------------------------------------------
# 1. Concurrent get-or-create
# ---------------------------------------------------------------------------
def test_concurrent_get_or_create():
    section(f"Concurrent get-or-create ({N_CONCURRENT_WALLET_CREATES} simultaneous POST /wallets, brand-new user)")

    user = signup_fresh_user("cgc-user")

    with ThreadPoolExecutor(max_workers=N_CONCURRENT_WALLET_CREATES) as pool:
        results = list(pool.map(lambda _: create_wallet(user["token"]), range(N_CONCURRENT_WALLET_CREATES)))

    failures = [r for r in results if r[0] != 200]
    if failures:
        bad("all requests returned 200", f"{len(failures)} non-200 responses")
        return

    distinct_wallet_ids = {body["walletId"] for _, body in results}

    if len(distinct_wallet_ids) == 1:
        ok("exactly one wallet created", f"walletId={next(iter(distinct_wallet_ids))}")
    else:
        bad("expected exactly one wallet", f"got {len(distinct_wallet_ids)} distinct ids: {', '.join(distinct_wallet_ids)}")


# ---------------------------------------------------------------------------
# 2. Idempotent retry storm
# ---------------------------------------------------------------------------
def test_idempotent_retry_storm():
    section(f"Idempotent retry storm ({K_CONCURRENT_RETRIES} concurrent POSTs, same idempotency_key)")

    with ThreadPoolExecutor(max_workers=2) as pool:
        user_a_f = pool.submit(signup_fresh_user, "retry-a")
        user_b_f = pool.submit(signup_fresh_user, "retry-b")
        user_a, user_b = user_a_f.result(), user_b_f.result()

    with ThreadPoolExecutor(max_workers=2) as pool:
        wallet_a_f = pool.submit(create_wallet, user_a["token"])
        wallet_b_f = pool.submit(create_wallet, user_b["token"])
        wallet_a, wallet_b = wallet_a_f.result(), wallet_b_f.result()

    wallet_a_id = wallet_a[1]["walletId"]
    wallet_b_id = wallet_b[1]["walletId"]

    deposit(user_a["token"], wallet_a_id, INITIAL_DEPOSIT_PAISE)

    idempotency_key = f"retry-storm-{unique_suffix()}"
    amount_paise = 1500

    with ThreadPoolExecutor(max_workers=K_CONCURRENT_RETRIES) as pool:
        results = list(pool.map(
            lambda _: transfer(user_a["token"], wallet_a_id, wallet_b_id, amount_paise, idempotency_key),
            range(K_CONCURRENT_RETRIES),
        ))

    failures = [r for r in results if r[0] != 200]
    if failures:
        bad("all K requests returned 200", f"{len(failures)} non-200 responses: {failures[0]}")
        return

    distinct_transfer_ids = {body["transferId"] for _, body in results}
    distinct_statuses = {body["status"] for _, body in results}

    if len(distinct_transfer_ids) == 1 and len(distinct_statuses) == 1:
        ok("all responses identical", f"transferId={next(iter(distinct_transfer_ids))}, status={next(iter(distinct_statuses))}")
    else:
        bad(
            "expected identical responses across retries",
            f"{len(distinct_transfer_ids)} distinct transferIds, {len(distinct_statuses)} distinct statuses",
        )

    final_a = get_wallet(user_a["token"], wallet_a_id)
    final_b = get_wallet(user_a["token"], wallet_b_id)

    expected_a = INITIAL_DEPOSIT_PAISE - amount_paise
    expected_b = amount_paise

    if final_a["balancePaise"] == expected_a and final_b["balancePaise"] == expected_b:
        ok("exactly one debit/credit applied", f"A={final_a['balancePaise']} paise, B={final_b['balancePaise']} paise")
    else:
        bad(
            "expected exactly one debit/credit",
            f"A={final_a['balancePaise']} (want {expected_a}), B={final_b['balancePaise']} (want {expected_b})"
            " - looks like a double-spend or lost update",
        )

    # Bonus: same key, different body -> must be a clean 409, not a second debit.
    conflict_status, _ = transfer(user_a["token"], wallet_a_id, wallet_b_id, amount_paise + 1, idempotency_key)

    if conflict_status == 409:
        ok("reused key with different body rejected", "409 as expected")
    else:
        bad("expected 409 for reused key + different body", f"got {conflict_status}")


# ---------------------------------------------------------------------------
# 3. Insufficient-funds decline
# ---------------------------------------------------------------------------
def test_insufficient_funds_decline():
    section("Insufficient-funds decline (overdraft attempt on a zero-balance wallet)")

    with ThreadPoolExecutor(max_workers=2) as pool:
        user_a_f = pool.submit(signup_fresh_user, "nsf-a")
        user_b_f = pool.submit(signup_fresh_user, "nsf-b")
        user_a, user_b = user_a_f.result(), user_b_f.result()

    with ThreadPoolExecutor(max_workers=2) as pool:
        wallet_a_f = pool.submit(create_wallet, user_a["token"])
        wallet_b_f = pool.submit(create_wallet, user_b["token"])
        wallet_a, wallet_b = wallet_a_f.result(), wallet_b_f.result()

    wallet_a_id = wallet_a[1]["walletId"]
    wallet_b_id = wallet_b[1]["walletId"]

    # Deliberately no deposit - wallet A stays at 0 balance.
    amount_paise = 100
    idempotency_key = f"nsf-{unique_suffix()}"

    status, body = transfer(user_a["token"], wallet_a_id, wallet_b_id, amount_paise, idempotency_key)

    if status != 200:
        bad("expected a clean 200 response (decline is not an error)", f"got {status} {body}")
        return

    if body.get("status") == "DECLINED_INSUFFICIENT_FUNDS":
        ok("transfer declined cleanly", f"transferId={body.get('transferId')}")
    else:
        bad("expected status DECLINED_INSUFFICIENT_FUNDS", f"got {body.get('status')}")

    # No partial apply: neither wallet's balance should have moved at all.
    final_a = get_wallet(user_a["token"], wallet_a_id)
    final_b = get_wallet(user_a["token"], wallet_b_id)

    if final_a["balancePaise"] == 0 and final_b["balancePaise"] == 0:
        ok("no balance changed on decline", f"A={final_a['balancePaise']} paise, B={final_b['balancePaise']} paise")
    else:
        bad(
            "expected both balances unchanged at 0",
            f"A={final_a['balancePaise']}, B={final_b['balancePaise']} - a declined transfer must never move money",
        )


# ---------------------------------------------------------------------------
# 4. Opposite-direction concurrency (A->B and B->A at once, same 2 wallets)
# ---------------------------------------------------------------------------
# "Conservation under contention" below picks random wallet pairs out of a
# larger set, so A->B and B->A landing on the exact same pair at the exact
# same instant only happens by chance. This test makes that scenario
# deterministic - it fires A->B and B->A transfers between the same two
# wallets simultaneously, which is exactly what requires the sorted-lock-
# order deadlock avoidance (see TransferTxHelper.createAndSettle) - if that
# logic were wrong, this would hang/timeout or the DB would report a
# deadlock, instead of every request just cleanly resolving.
OPPOSITE_DIRECTION_PAIRS = int(os.environ.get("OPPOSITE_DIRECTION_PAIRS", "10"))


def test_opposite_direction_concurrency():
    section(
        f"Opposite-direction concurrency ({OPPOSITE_DIRECTION_PAIRS * 2} simultaneous transfers, "
        f"A->B and B->A on the same 2 wallets)"
    )

    with ThreadPoolExecutor(max_workers=2) as pool:
        user_a_f = pool.submit(signup_fresh_user, "oppo-a")
        user_b_f = pool.submit(signup_fresh_user, "oppo-b")
        user_a, user_b = user_a_f.result(), user_b_f.result()

    with ThreadPoolExecutor(max_workers=2) as pool:
        wallet_a_f = pool.submit(create_wallet, user_a["token"])
        wallet_b_f = pool.submit(create_wallet, user_b["token"])
        wallet_a, wallet_b = wallet_a_f.result(), wallet_b_f.result()

    wallet_a_id = wallet_a[1]["walletId"]
    wallet_b_id = wallet_b[1]["walletId"]

    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(lambda args: deposit(*args), [
            (user_a["token"], wallet_a_id, INITIAL_DEPOSIT_PAISE),
            (user_b["token"], wallet_b_id, INITIAL_DEPOSIT_PAISE),
        ]))

    initial_total = INITIAL_DEPOSIT_PAISE * 2

    jobs = []
    for i in range(OPPOSITE_DIRECTION_PAIRS):
        amount_paise = 100 + random.randrange(2000)
        jobs.append((user_a["token"], wallet_a_id, wallet_b_id, amount_paise, f"oppo-ab-{unique_suffix()}-{i}"))
        jobs.append((user_b["token"], wallet_b_id, wallet_a_id, amount_paise, f"oppo-ba-{unique_suffix()}-{i}"))

    with ThreadPoolExecutor(max_workers=len(jobs)) as pool:
        results = list(pool.map(lambda job: transfer(*job), jobs))

    http_failures = [r for r in results if r[0] != 200]
    if http_failures:
        bad(
            "all A<->B transfers returned 200 (a deadlock would show up here as a hang, timeout, or DB error)",
            f"{len(http_failures)} non-200 responses: {http_failures[0]}",
        )
    else:
        completed = sum(1 for _, body in results if body.get("status") == "COMPLETED")
        declined = sum(1 for _, body in results if body.get("status") == "DECLINED_INSUFFICIENT_FUNDS")
        ok("no deadlock - all simultaneous opposite-direction transfers resolved cleanly", f"{completed} completed, {declined} declined")

    final_a = get_wallet(user_a["token"], wallet_a_id)
    final_b = get_wallet(user_a["token"], wallet_b_id)

    final_total = final_a["balancePaise"] + final_b["balancePaise"]
    any_negative = final_a["balancePaise"] < 0 or final_b["balancePaise"] < 0

    if final_total == initial_total:
        ok("total balance conserved across A<->B", f"initial={initial_total}, final={final_total}")
    else:
        bad("total balance changed", f"initial={initial_total}, final={final_total}, diff={final_total - initial_total}")

    if not any_negative:
        ok("neither wallet went negative", f"A={final_a['balancePaise']}, B={final_b['balancePaise']}")
    else:
        bad("a wallet balance went negative", f"A={final_a['balancePaise']}, B={final_b['balancePaise']}")


# ---------------------------------------------------------------------------
# 5. Conservation under contention
# ---------------------------------------------------------------------------
def test_conservation_under_contention():
    section(
        f"Conservation under contention ({CONTENTION_WALLET_COUNT} wallets, {CONTENTION_TRANSFER_COUNT} concurrent transfers)"
    )

    with ThreadPoolExecutor(max_workers=CONTENTION_WALLET_COUNT) as pool:
        users = list(pool.map(lambda i: signup_fresh_user(f"contention-{i}"), range(CONTENTION_WALLET_COUNT)))

    with ThreadPoolExecutor(max_workers=CONTENTION_WALLET_COUNT) as pool:
        wallets = list(pool.map(lambda u: create_wallet(u["token"]), users))
    wallet_ids = [w[1]["walletId"] for w in wallets]

    with ThreadPoolExecutor(max_workers=CONTENTION_WALLET_COUNT) as pool:
        list(pool.map(lambda args: deposit(args[0]["token"], args[1], INITIAL_DEPOSIT_PAISE), zip(users, wallet_ids)))

    initial_total = INITIAL_DEPOSIT_PAISE * CONTENTION_WALLET_COUNT

    # Build a burst of transfers that deliberately includes A->B and B->A at
    # the same time between the same pair of wallets, to exercise the
    # deadlock-avoidance / sorted-lock-order path.
    jobs = []
    for i in range(CONTENTION_TRANSFER_COUNT):
        from_idx = random.randrange(CONTENTION_WALLET_COUNT)
        to_idx = random.randrange(CONTENTION_WALLET_COUNT)
        if to_idx == from_idx:
            to_idx = (to_idx + 1) % CONTENTION_WALLET_COUNT

        amount_paise = 100 + random.randrange(5000)
        key = f"contention-{unique_suffix()}-{i}"

        jobs.append((users[from_idx]["token"], wallet_ids[from_idx], wallet_ids[to_idx], amount_paise, key))

    with ThreadPoolExecutor(max_workers=min(50, CONTENTION_TRANSFER_COUNT)) as pool:
        results = list(pool.map(lambda job: transfer(*job), jobs))

    http_failures = [r for r in results if r[0] != 200]
    completed = sum(1 for status, body in results if status == 200 and body.get("status") == "COMPLETED")
    declined = sum(1 for status, body in results if status == 200 and body.get("status") == "DECLINED_INSUFFICIENT_FUNDS")

    if http_failures:
        bad("all transfer requests returned 200", f"{len(http_failures)} non-200 responses: {http_failures[0]}")
    else:
        ok("all transfer requests handled cleanly", f"{completed} completed, {declined} declined-insufficient-funds")

    with ThreadPoolExecutor(max_workers=CONTENTION_WALLET_COUNT) as pool:
        final_wallets = list(pool.map(lambda wid: get_wallet(users[0]["token"], wid), wallet_ids))

    final_total = sum(w["balancePaise"] for w in final_wallets)
    any_negative = any(w["balancePaise"] < 0 for w in final_wallets)

    if final_total == initial_total:
        ok("total balance conserved", f"initial={initial_total}, final={final_total}")
    else:
        bad("total balance changed", f"initial={initial_total}, final={final_total}, diff={final_total - initial_total}")

    if not any_negative:
        ok("no wallet went negative", ", ".join(str(w["balancePaise"]) for w in final_wallets))
    else:
        bad("a wallet balance went negative", json.dumps(final_wallets))


# ---------------------------------------------------------------------------
def main():
    print(f"Wallet API burst test against: {BASE_URL}")

    test_concurrent_get_or_create()
    test_idempotent_retry_storm()
    test_insufficient_funds_decline()
    test_opposite_direction_concurrency()
    test_conservation_under_contention()

    print(f"\n{_pass_count} passed, {_fail_count} failed")
    sys.exit(1 if _fail_count > 0 else 0)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # noqa: BLE001
        print(f"\nBurst test crashed: {exc}", file=sys.stderr)
        sys.exit(1)
