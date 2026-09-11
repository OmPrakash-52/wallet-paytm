package com.wallet.serviceImpl;

import com.wallet.entity.User;
import com.wallet.repository.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolated, single-purpose transactional insert for signup - kept off
 * UserServiceImpl (see WalletTxHelper/TransferTxHelper for why: a private
 * @Transactional method calling itself within the same class bypasses
 * Spring's proxy and silently does nothing).
 */
@Component
public class UserTxHelper {

    private final UserRepository userRepository;

    public UserTxHelper(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Attempts to insert a brand-new user in its own transaction. Relies on
     * the DB-level UNIQUE(username)/UNIQUE(phone_number) constraints: if two
     * signups race with the same value, only one insert succeeds.
     *
     * Throws DataIntegrityViolationException on conflict instead of catching
     * it here - see WalletTxHelper.tryCreateWallet for why catching inside
     * this @Transactional method would produce UnexpectedRollbackException
     * instead of a clean result. The caller (no active transaction of its
     * own) is where it's safe to catch it and reject the signup cleanly.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User tryCreateUser(User candidate) {
        return userRepository.saveAndFlush(candidate);
    }
}
