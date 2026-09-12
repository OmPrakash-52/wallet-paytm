package com.wallet.serviceImpl;

import com.wallet.entity.Transfer;
import com.wallet.entity.TransferStatus;
import com.wallet.entity.Wallet;
import com.wallet.repository.TransferRepository;
import com.wallet.repository.WalletRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Isolated, single-purpose transactional operation for transfers. Kept in its
 * own Spring bean (not private methods on TransferServiceImpl) so that
 * @Transactional actually gets proxied by Spring AOP - calling an annotated
 * method on "this" from within the same class bypasses the proxy entirely
 * and the annotation is silently ignored.
 */
@Component
public class TransferTxHelper {

    private static final Logger log = LoggerFactory.getLogger(TransferTxHelper.class);

    private final TransferRepository transferRepository;
    private final WalletRepository walletRepository;
    private final MeterRegistry meterRegistry;

    public TransferTxHelper(
            TransferRepository transferRepository,
            WalletRepository walletRepository,
            MeterRegistry meterRegistry) {
        this.transferRepository = transferRepository;
        this.walletRepository = walletRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Reserves the idempotency key AND settles the transfer in one
     * transaction: insert the candidate row, lock both wallets (always in
     * sorted-by-id order, regardless of which is "from"/"to", to prevent
     * deadlocks between opposite-direction transfers), apply the debit/credit
     * under a conditional balance check, and resolve to COMPLETED or
     * DECLINED_INSUFFICIENT_FUNDS - all before this transaction ever commits.
     *
     * This matters for two reasons:
     *  - No external reader can ever observe an intermediate PENDING row: the
     *    UNIQUE(idempotency_key) row only becomes visible once it already
     *    carries its final status, so a concurrent retry that loses the
     *    insert race is guaranteed to read the finished result, not a
     *    transient one.
     *  - If a wallet turns out not to exist, the whole transaction (including
     *    the insert) rolls back - the idempotency_key is never spent, instead
     *    of leaving a permanently stuck PENDING row.
     *
     * On a duplicate idempotency_key, this throws
     * DataIntegrityViolationException instead of catching it here - see
     * WalletTxHelper.tryCreateWallet for why catching inside this
     * @Transactional method would produce UnexpectedRollbackException instead
     * of a clean rollback. The caller (no active transaction of its own) is
     * where it's safe to catch it and look up the winner's already-committed
     * row.
     */
    @Transactional
    public Transfer createAndSettle(Transfer candidate) {

        Transfer transfer = transferRepository.saveAndFlush(candidate);

        log.info("transfer_created",
                kv("event", "transfer_created"),
                kv("transferId", transfer.getId()),
                kv("fromWalletId", transfer.getFromWalletId()),
                kv("toWalletId", transfer.getToWalletId()),
                kv("amountPaise", transfer.getAmountPaise()));
        // Avoid the literal word "created" anywhere in a counter name -
        // Micrometer's Prometheus naming convention strips it outright
        // (it collides with Prometheus's own auto-generated "*_created"
        // creation-timestamp companion metric for every counter), silently
        // collapsing e.g. "wallet_transfers_created" down to
        // "wallet_transfers". "_initiated" avoids the collision.
        meterRegistry.counter("wallet_transfers_initiated").increment();

        String fromId = transfer.getFromWalletId();
        String toId = transfer.getToWalletId();

        String firstId = fromId.compareTo(toId) <= 0 ? fromId : toId;
        String secondId = fromId.compareTo(toId) <= 0 ? toId : fromId;

        Wallet first = walletRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found: " + firstId));
        Wallet second = walletRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found: " + secondId));

        Wallet fromWallet = fromId.equals(first.getId()) ? first : second;
        Wallet toWallet = toId.equals(first.getId()) ? first : second;

        if (fromWallet.getBalancePaise() < transfer.getAmountPaise()) {
            transfer.setStatus(TransferStatus.DECLINED_INSUFFICIENT_FUNDS);

            log.info("transfer_declined_insufficient_funds",
                    kv("event", "transfer_declined_insufficient_funds"),
                    kv("transferId", transfer.getId()),
                    kv("fromWalletId", fromId),
                    kv("availableBalancePaise", fromWallet.getBalancePaise()),
                    kv("requestedAmountPaise", transfer.getAmountPaise()));
            // No explicit "_total" here either, for the same reason - let
            // Micrometer append the Prometheus counter suffix itself.
            meterRegistry.counter("wallet_transfers_declined_insufficient_funds").increment();
        } else {
            fromWallet.setBalancePaise(fromWallet.getBalancePaise() - transfer.getAmountPaise());
            toWallet.setBalancePaise(toWallet.getBalancePaise() + transfer.getAmountPaise());
            walletRepository.save(fromWallet);
            walletRepository.save(toWallet);
            transfer.setStatus(TransferStatus.COMPLETED);

            log.info("wallet_debited",
                    kv("event", "wallet_debited"),
                    kv("transferId", transfer.getId()),
                    kv("walletId", fromWallet.getId()),
                    kv("amountPaise", transfer.getAmountPaise()),
                    kv("newBalancePaise", fromWallet.getBalancePaise()));

            log.info("wallet_credited",
                    kv("event", "wallet_credited"),
                    kv("transferId", transfer.getId()),
                    kv("walletId", toWallet.getId()),
                    kv("amountPaise", transfer.getAmountPaise()),
                    kv("newBalancePaise", toWallet.getBalancePaise()));
        }

        return transferRepository.save(transfer);
    }
}
