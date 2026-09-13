package com.wallet.serviceImpl;

import com.wallet.dto.CreateWalletResponse;
import com.wallet.entity.Wallet;
import com.wallet.repository.WalletRepository;
import com.wallet.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class WalletServiceImpl implements WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletServiceImpl.class);

    private final WalletRepository walletRepository;
    private final WalletTxHelper walletTxHelper;

    public WalletServiceImpl(WalletRepository walletRepository, WalletTxHelper walletTxHelper) {
        this.walletRepository = walletRepository;
        this.walletTxHelper = walletTxHelper;
    }

    @Override
    public CreateWalletResponse getOrCreateWallet(String userId) {

        // Fast path: wallet already exists.
        Optional<Wallet> existing = walletRepository.findByUserId(userId);
        if (existing.isPresent()) {
            return mapToResponse(existing.get(), "wallet already exists");
        }

        // Race-free create: attempt an insert in its own transaction. If a
        // concurrent request wins the race, this throws (caught here, outside
        // any transaction of our own) and we simply re-read the row the
        // winner committed - never two wallets.
        try {
            return mapToResponse(walletTxHelper.tryCreateWallet(userId), "wallet created successfully");
        } catch (DataIntegrityViolationException e) {
            // fall through to re-fetch below
        }

        Wallet winner = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Wallet insert conflicted but no existing wallet found for user " + userId));

        return mapToResponse(winner, "wallet already exists");
    }

    @Override
    @Transactional(readOnly = true)
    public CreateWalletResponse getWallet(String walletId) {

        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found: " + walletId));

        return mapToResponse(wallet, "wallet retrieved successfully");
    }

    @Override
    @Transactional
    public CreateWalletResponse deposit(String walletId, Long amountPaise) {

        // Locking isn't strictly required here (no second wallet is involved,
        // so there's no deadlock-ordering concern), but it's used anyway so a
        // concurrent transfer touching the same wallet can't interleave with
        // this credit.
        Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found: " + walletId));

        wallet.setBalancePaise(wallet.getBalancePaise() + amountPaise);
        walletRepository.save(wallet);

        log.info("wallet_deposited",
                kv("event", "wallet_deposited"),
                kv("walletId", wallet.getId()),
                kv("amountPaise", amountPaise),
                kv("newBalancePaise", wallet.getBalancePaise()));

        return mapToResponse(wallet, "deposit successful");
    }

    private CreateWalletResponse mapToResponse(Wallet wallet, String message) {

        CreateWalletResponse response = new CreateWalletResponse();

        response.setWalletId(wallet.getId());
        response.setBalancePaise(wallet.getBalancePaise());
        response.setMessage(message);

        return response;
    }
}
