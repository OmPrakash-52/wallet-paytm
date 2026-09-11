package com.wallet.repository;

import com.wallet.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, String> {

    Optional<Wallet> findByUserId(String userId);

    boolean existsByUserId(String userId);

    /**
     * Acquires a PostgreSQL row-level lock (SELECT ... FOR UPDATE) on the wallet.
     * Must be called inside an active transaction. Used by the transfer service to
     * lock both wallets involved in a transfer, always in a fixed (sorted-by-id)
     * order across concurrent transactions, to avoid deadlocks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") String id);
}
