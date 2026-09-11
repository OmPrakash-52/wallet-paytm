#!/usr/bin/env node
/**
 * One-command burst test for the Wallet & P2P Transfer API.
 *
 * Reproduces the three scenarios graders run against the deployed URL:
 *   1. Concurrent get-or-create   -> exactly one wallet for a brand-new user
 *   2. Idempotent retry storm     -> exactly one debit/credit, identical responses
 *   3. Conservation under contention -> total balance unchanged, no negative balance
 *
 * Requires Node.js 18+ (uses the built-in fetch, no npm install needed).
 *
 * Usage:
 *   node scripts/burst-test.js [baseUrl]
 *   BASE_URL=https://your-app.onrender.com node scripts/burst-test.js
 *
 * Exits with code 1 if any check fails (CI-friendly).
 */

const BASE_URL = process.argv[2] || process.env.BASE_URL || "http://localhost:8081";

const N_CONCURRENT_WALLET_CREATES = Number(process.env.N_WALLET || 20);
const K_CONCURRENT_RETRIES = Number(process.env.K_RETRY || 20);
const CONTENTION_WALLET_COUNT = Number(process.env.CONTENTION_WALLETS || 5);
const CONTENTION_TRANSFER_COUNT = Number(process.env.CONTENTION_TRANSFERS || 80);
const INITIAL_DEPOSIT_PAISE = 100_000; // 1000.00 in paise, per wallet

let passCount = 0;
let failCount = 0;

function pass(label, detail) {
    passCount++;
    console.log(`  \x1b[32mPASS\x1b[0m ${label}${detail ? " - " + detail : ""}`);
}

function fail(label, detail) {
    failCount++;
    console.log(`  \x1b[31mFAIL\x1b[0m ${label}${detail ? " - " + detail : ""}`);
}

function section(title) {
    console.log(`\n\x1b[1m== ${title} ==\x1b[0m`);
}

function randomDigits(n) {
    let s = "";
    for (let i = 0; i < n; i++) s += Math.floor(Math.random() * 10);
    return s;
}

function randomPhone() {
    // must be exactly 10 digits; force a non-zero leading digit
    return String(1 + Math.floor(Math.random() * 9)) + randomDigits(9);
}

function uniqueSuffix() {
    return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

async function api(method, path, { token, body } = {}) {
    const headers = { "Content-Type": "application/json" };
    if (token) headers["Authorization"] = `Bearer ${token}`;

    const res = await fetch(`${BASE_URL}${path}`, {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
    });

    const text = await res.text();
    let json;
    try {
        json = text ? JSON.parse(text) : {};
    } catch {
        json = { raw: text };
    }

    return { status: res.status, body: json };
}

async function signup(username, password, phoneNumber) {
    const { status, body } = await api("POST", "/auth/signup", {
        body: { username, password, phoneNumber },
    });
    if (status !== 200) {
        throw new Error(`signup failed for ${username}: ${status} ${JSON.stringify(body)}`);
    }
    return body; // { userId, token, tokenType, expiresInMs }
}

async function signupFreshUser(prefix) {
    const suffix = uniqueSuffix();
    return signup(`${prefix}-${suffix}`, "secret123", randomPhone());
}

async function createWallet(token) {
    return api("POST", "/wallets", { token });
}

async function getWallet(token, walletId) {
    const { status, body } = await api("GET", `/wallets/${walletId}`, { token });
    if (status !== 200) {
        throw new Error(`getWallet(${walletId}) failed: ${status} ${JSON.stringify(body)}`);
    }
    return body;
}

async function deposit(token, walletId, amountPaise) {
    const { status, body } = await api("POST", `/wallets/${walletId}/deposit`, {
        token,
        body: { amount_paise: amountPaise },
    });
    if (status !== 200) {
        throw new Error(`deposit(${walletId}) failed: ${status} ${JSON.stringify(body)}`);
    }
    return body;
}

async function transfer(token, from, to, amountPaise, idempotencyKey) {
    return api("POST", "/transfers", {
        token,
        body: { from, to, amount_paise: amountPaise, idempotency_key: idempotencyKey },
    });
}

// ---------------------------------------------------------------------------
// 1. Concurrent get-or-create
// ---------------------------------------------------------------------------
async function testConcurrentGetOrCreate() {
    section(`Concurrent get-or-create (${N_CONCURRENT_WALLET_CREATES} simultaneous POST /wallets, brand-new user)`);

    const user = await signupFreshUser("cgc-user");

    const results = await Promise.all(
        Array.from({ length: N_CONCURRENT_WALLET_CREATES }, () => createWallet(user.token))
    );

    const failures = results.filter((r) => r.status !== 200);
    if (failures.length > 0) {
        fail("all requests returned 200", `${failures.length} non-200 responses`);
        return;
    }

    const distinctWalletIds = new Set(results.map((r) => r.body.walletId));

    if (distinctWalletIds.size === 1) {
        pass("exactly one wallet created", `walletId=${[...distinctWalletIds][0]}`);
    } else {
        fail("expected exactly one wallet", `got ${distinctWalletIds.size} distinct ids: ${[...distinctWalletIds].join(", ")}`);
    }
}

// ---------------------------------------------------------------------------
// 2. Idempotent retry storm
// ---------------------------------------------------------------------------
async function testIdempotentRetryStorm() {
    section(`Idempotent retry storm (${K_CONCURRENT_RETRIES} concurrent POSTs, same idempotency_key)`);

    const [userA, userB] = await Promise.all([
        signupFreshUser("retry-a"),
        signupFreshUser("retry-b"),
    ]);

    const [walletA, walletB] = await Promise.all([
        createWallet(userA.token),
        createWallet(userB.token),
    ]);

    await deposit(userA.token, walletA.body.walletId, INITIAL_DEPOSIT_PAISE);

    const idempotencyKey = `retry-storm-${uniqueSuffix()}`;
    const amountPaise = 1500;

    const results = await Promise.all(
        Array.from({ length: K_CONCURRENT_RETRIES }, () =>
            transfer(userA.token, walletA.body.walletId, walletB.body.walletId, amountPaise, idempotencyKey)
        )
    );

    const failures = results.filter((r) => r.status !== 200);
    if (failures.length > 0) {
        fail("all K requests returned 200", `${failures.length} non-200 responses: ${JSON.stringify(failures[0])}`);
        return;
    }

    const distinctTransferIds = new Set(results.map((r) => r.body.transferId));
    const distinctStatuses = new Set(results.map((r) => r.body.status));

    if (distinctTransferIds.size === 1 && distinctStatuses.size === 1) {
        pass("all responses identical", `transferId=${[...distinctTransferIds][0]}, status=${[...distinctStatuses][0]}`);
    } else {
        fail(
            "expected identical responses across retries",
            `${distinctTransferIds.size} distinct transferIds, ${distinctStatuses.size} distinct statuses`
        );
    }

    const [finalA, finalB] = await Promise.all([
        getWallet(userA.token, walletA.body.walletId),
        getWallet(userA.token, walletB.body.walletId),
    ]);

    const expectedA = INITIAL_DEPOSIT_PAISE - amountPaise;
    const expectedB = amountPaise;

    if (finalA.balancePaise === expectedA && finalB.balancePaise === expectedB) {
        pass("exactly one debit/credit applied", `A=${finalA.balancePaise} paise, B=${finalB.balancePaise} paise`);
    } else {
        fail(
            "expected exactly one debit/credit",
            `A=${finalA.balancePaise} (want ${expectedA}), B=${finalB.balancePaise} (want ${expectedB}) - looks like a double-spend or lost update`
        );
    }

    // Bonus: same key, different body -> must be a clean 409, not a second debit.
    const conflictResult = await transfer(
        userA.token,
        walletA.body.walletId,
        walletB.body.walletId,
        amountPaise + 1, // different amount => different body
        idempotencyKey
    );

    if (conflictResult.status === 409) {
        pass("reused key with different body rejected", "409 as expected");
    } else {
        fail("expected 409 for reused key + different body", `got ${conflictResult.status}`);
    }
}

// ---------------------------------------------------------------------------
// 3. Conservation under contention
// ---------------------------------------------------------------------------
async function testConservationUnderContention() {
    section(
        `Conservation under contention (${CONTENTION_WALLET_COUNT} wallets, ${CONTENTION_TRANSFER_COUNT} concurrent transfers)`
    );

    const users = await Promise.all(
        Array.from({ length: CONTENTION_WALLET_COUNT }, (_, i) => signupFreshUser(`contention-${i}`))
    );

    const wallets = await Promise.all(users.map((u) => createWallet(u.token)));
    const walletIds = wallets.map((w) => w.body.walletId);

    await Promise.all(
        walletIds.map((id, i) => deposit(users[i].token, id, INITIAL_DEPOSIT_PAISE))
    );

    const initialTotal = INITIAL_DEPOSIT_PAISE * CONTENTION_WALLET_COUNT;

    // Build a burst of transfers that deliberately includes A->B and B->A at
    // the same time between the same pair of wallets, to exercise the
    // deadlock-avoidance / sorted-lock-order path.
    const jobs = [];
    for (let i = 0; i < CONTENTION_TRANSFER_COUNT; i++) {
        const fromIdx = Math.floor(Math.random() * CONTENTION_WALLET_COUNT);
        let toIdx = Math.floor(Math.random() * CONTENTION_WALLET_COUNT);
        if (toIdx === fromIdx) toIdx = (toIdx + 1) % CONTENTION_WALLET_COUNT;

        const amountPaise = 100 + Math.floor(Math.random() * 5000);
        const key = `contention-${uniqueSuffix()}-${i}`;

        jobs.push(
            transfer(users[fromIdx].token, walletIds[fromIdx], walletIds[toIdx], amountPaise, key)
        );
    }

    const results = await Promise.all(jobs);

    const httpFailures = results.filter((r) => r.status !== 200);
    const completed = results.filter((r) => r.status === 200 && r.body.status === "COMPLETED").length;
    const declined = results.filter(
        (r) => r.status === 200 && r.body.status === "DECLINED_INSUFFICIENT_FUNDS"
    ).length;

    if (httpFailures.length > 0) {
        fail("all transfer requests returned 200", `${httpFailures.length} non-200 responses: ${JSON.stringify(httpFailures[0])}`);
    } else {
        pass("all transfer requests handled cleanly", `${completed} completed, ${declined} declined-insufficient-funds`);
    }

    const finalWallets = await Promise.all(walletIds.map((id) => getWallet(users[0].token, id)));
    const finalTotal = finalWallets.reduce((sum, w) => sum + w.balancePaise, 0);
    const anyNegative = finalWallets.some((w) => w.balancePaise < 0);

    if (finalTotal === initialTotal) {
        pass("total balance conserved", `initial=${initialTotal}, final=${finalTotal}`);
    } else {
        fail("total balance changed", `initial=${initialTotal}, final=${finalTotal}, diff=${finalTotal - initialTotal}`);
    }

    if (!anyNegative) {
        pass("no wallet went negative", finalWallets.map((w) => w.balancePaise).join(", "));
    } else {
        fail("a wallet balance went negative", JSON.stringify(finalWallets));
    }
}

// ---------------------------------------------------------------------------
async function main() {
    console.log(`Wallet API burst test against: ${BASE_URL}`);

    await testConcurrentGetOrCreate();
    await testIdempotentRetryStorm();
    await testConservationUnderContention();

    console.log(`\n${passCount} passed, ${failCount} failed`);
    process.exit(failCount > 0 ? 1 : 0);
}

main().catch((err) => {
    console.error("\nBurst test crashed:", err);
    process.exit(1);
});
