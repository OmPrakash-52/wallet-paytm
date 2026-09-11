package com.wallet.dto;

import com.wallet.entity.TransferStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TransferResponse {

    private String transferId;

    private TransferStatus status;

    private String fromWalletId;

    private String toWalletId;

    private Long amountPaise;
}
