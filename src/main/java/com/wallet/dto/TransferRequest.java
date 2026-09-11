package com.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JSON wire format uses snake_case for amount_paise/idempotency_key to match
 * the exercise spec exactly, while the Java fields stay camelCase for
 * consistency with the rest of the codebase.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TransferRequest {

    @NotBlank
    private String from;

    @NotBlank
    private String to;

    @NotNull
    @Positive
    @JsonProperty("amount_paise")
    private Long amountPaise;

    @NotBlank
    @JsonProperty("idempotency_key")
    private String idempotencyKey;
}
