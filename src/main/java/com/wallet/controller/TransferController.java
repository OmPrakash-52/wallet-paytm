package com.wallet.controller;

import com.wallet.dto.TransferRequest;
import com.wallet.dto.TransferResponse;
import com.wallet.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    // POST /transfers
    // Transfer money between wallets
    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(
            @Valid @RequestBody TransferRequest request) {

        TransferResponse response =
                transferService.createTransfer(request);

        return ResponseEntity.ok(response);
    }

    // GET /transfers/{id}
    // Get transfer status
    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getTransfer(
            @PathVariable String id) {

        TransferResponse response =
                transferService.getTransfer(id);

        return ResponseEntity.ok(response);
    }
}
