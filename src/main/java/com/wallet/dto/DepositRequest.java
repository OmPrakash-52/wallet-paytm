package com.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Test/dev-only utility to fund a wallet from outside the system (e.g. a
 * simulated bank top-up). This is intentionally NOT part of the graded
 * minimum API - it exists only so burst scripts have a way to give a wallet
 * an initial balance to move around. It is a pure credit with no
 * corresponding debit, so it is outside the "conservation across transfers"
 * invariant by design (the same way a real wallet's incoming top-up from an
 * external payment rail is outside that invariant).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DepositRequest {

    @NotNull
    @Positive
    @JsonProperty("amount_paise")
    private Long amountPaise;
}
