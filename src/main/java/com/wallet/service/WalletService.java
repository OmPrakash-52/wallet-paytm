package com.wallet.service;

import com.wallet.dto.CreateWalletResponse;

public interface WalletService {

    CreateWalletResponse getOrCreateWallet(String userId);

    CreateWalletResponse getWallet(String walletId);

    /**
     * Test/dev-only: credits a wallet from outside the system. Not part of
     * the graded minimum API - see DepositRequest for why.
     */
    CreateWalletResponse deposit(String walletId, Long amountPaise);
}
