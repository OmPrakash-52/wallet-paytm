package com.wallet.service;

import com.wallet.dto.TransferRequest;
import com.wallet.dto.TransferResponse;

public interface TransferService {

    TransferResponse createTransfer(TransferRequest request);

    TransferResponse getTransfer(String transferId);
}
