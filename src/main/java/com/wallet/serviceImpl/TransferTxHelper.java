package com.wallet.serviceImpl;

import com.wallet.entity.Transfer;
import com.wallet.entity.TransferStatus;
import com.wallet.entity.Wallet;
import com.wallet.repository.TransferRepository;
import com.wallet.repository.WalletRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Isolated, single-purpose transactional operation for transfers. Kept in its
 * own Spring bean (not private methods on TransferServiceImpl) so that
 * @Transactional actually gets proxied by Spring AOP - calling an annotated
 * method on "this" from within the same class bypasses the proxy entirely
 * and the annotation is silently ignored.
 */
@Component
public class TransferTxHelper {

    private final TransferRepository transferRepository;
    private final WalletRepository walletRepository;

    public TransferTxHelper(TransferRepository transferRepository, WalletRepository walletRepository) {
        this.transferRepository = transferRepository;
        this.walletRepository = walletRepository;
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
        } else {
            fromWallet.setBalancePaise(fromWallet.getBalancePaise() - transfer.getAmountPaise());
            toWallet.setBalancePaise(toWallet.getBalancePaise() + transfer.getAmountPaise());
            walletRepository.save(fromWallet);
            walletRepository.save(toWallet);
            transfer.setStatus(TransferStatus.COMPLETED);
        }

        return transferRepository.save(transfer);
    }
}
