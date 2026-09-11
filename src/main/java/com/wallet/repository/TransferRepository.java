package com.wallet.repository;

import com.wallet.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, String> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

}
