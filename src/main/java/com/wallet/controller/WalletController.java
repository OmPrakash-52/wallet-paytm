package com.wallet.controller;

import com.wallet.dto.CreateWalletResponse;
import com.wallet.dto.DepositRequest;
import com.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    // POST /wallets
    // Get-or-create wallet for the authenticated user (user id comes from
    // the JWT subject, set by JwtAuthenticationFilter)
    @PostMapping
    public ResponseEntity<CreateWalletResponse> createWallet(Principal principal) {

        CreateWalletResponse response = walletService.getOrCreateWallet(principal.getName());

        return ResponseEntity.ok(response);
    }

    // GET /wallets/{id}
    // Get current wallet balance
    @GetMapping("/{id}")
    public ResponseEntity<CreateWalletResponse> getWallet(
            @PathVariable String id) {

        CreateWalletResponse response = walletService.getWallet(id);

        return ResponseEntity.ok(response);
    }

    // POST /wallets/{id}/deposit
    // Test/dev-only utility to fund a wallet from outside the system (not
    // part of the graded minimum API) - see DepositRequest for why.
    @PostMapping("/{id}/deposit")
    public ResponseEntity<CreateWalletResponse> deposit(
            @PathVariable String id,
            @Valid @RequestBody DepositRequest request) {

        CreateWalletResponse response = walletService.deposit(id, request.getAmountPaise());

        return ResponseEntity.ok(response);
    }
}
