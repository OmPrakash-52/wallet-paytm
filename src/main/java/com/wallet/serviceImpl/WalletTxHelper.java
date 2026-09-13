package com.wallet.serviceImpl;

import com.wallet.entity.Wallet;
import com.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Isolated, single-purpose transactional operations for wallet creation.
 * Kept in its own Spring bean (not as private methods on WalletServiceImpl)
 * so that @Transactional actually gets proxied - a private method calling
 * itself within the same class bypasses Spring AOP entirely.
 */
@Component
public class WalletTxHelper {

    private static final Logger log = LoggerFactory.getLogger(WalletTxHelper.class);

    private final WalletRepository walletRepository;

    public WalletTxHelper(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    /**
     * Attempts to insert a brand-new wallet for userId in its own transaction.
     * Relies on the DB-level UNIQUE(user_id) constraint to make concurrent
     * get-or-create races safe: if two callers race here, only one insert
     * succeeds.
     *
     * Throws DataIntegrityViolationException on conflict instead of catching
     * it here - catching it inside this @Transactional method would NOT work
     * (a subtle but important Spring/JPA gotcha): the failed flush already
     * marks this transaction rollback-only via Spring's exception
     * translation, before our own catch block would even run. Returning
     * normally afterward makes Spring detect "rollback-only but no exception
     * propagated" at commit time and throw UnexpectedRollbackException
     * instead of committing/rolling back cleanly. Letting the exception
     * propagate lets Spring roll back this isolated transaction the normal
     * way; the caller (which has no active transaction of its own) is where
     * it's safe to catch it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Wallet tryCreateWallet(String userId) {
        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        wallet.setBalancePaise(0L);
        Wallet created = walletRepository.saveAndFlush(wallet);

        log.info("wallet_created",
                kv("event", "wallet_created"),
                kv("walletId", created.getId()),
                kv("userId", created.getUserId()));

        return created;
    }
}
