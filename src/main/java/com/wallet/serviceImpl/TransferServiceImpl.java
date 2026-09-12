package com.wallet.serviceImpl;

import com.wallet.dto.TransferRequest;
import com.wallet.dto.TransferResponse;
import com.wallet.entity.Transfer;
import com.wallet.entity.TransferStatus;
import com.wallet.repository.TransferRepository;
import com.wallet.service.TransferService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class TransferServiceImpl implements TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferServiceImpl.class);

    private final TransferRepository transferRepository;
    private final TransferTxHelper transferTxHelper;
    private final MeterRegistry meterRegistry;

    public TransferServiceImpl(
            TransferRepository transferRepository,
            TransferTxHelper transferTxHelper,
            MeterRegistry meterRegistry) {
        this.transferRepository = transferRepository;
        this.transferTxHelper = transferTxHelper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public TransferResponse createTransfer(TransferRequest request) {

        String requestHash = computeRequestHash(request);

        Transfer candidate = new Transfer();
        candidate.setFromWalletId(request.getFrom());
        candidate.setToWalletId(request.getTo());
        candidate.setAmountPaise(request.getAmountPaise());
        candidate.setIdempotencyKey(request.getIdempotencyKey());
        candidate.setRequestHash(requestHash);
        candidate.setStatus(TransferStatus.PENDING);

        // 1) Try to reserve the idempotency key and settle the transfer, all
        //    in one transaction (see TransferTxHelper.createAndSettle for why
        //    that atomicity matters). If this succeeds, we own this transfer
        //    and its result is already final.
        try {
            Transfer settled = transferTxHelper.createAndSettle(candidate);
            return mapToResponse(settled);
        } catch (DataIntegrityViolationException e) {
            // 2) Someone already holds this key (possibly this exact retry).
            //    Because the winning insert blocks concurrent inserters on the
            //    unique index until it commits, the row we read here is
            //    guaranteed to be the final, committed one.
            Transfer existing = transferRepository.findByIdempotencyKey(request.getIdempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "idempotency_key conflicted but no existing transfer was found: "
                                    + request.getIdempotencyKey()));

            if (!existing.getRequestHash().equals(requestHash)) {
                log.warn("idempotency_key_conflict",
                        kv("event", "idempotency_key_conflict"),
                        kv("idempotencyKey", request.getIdempotencyKey()),
                        kv("existingTransferId", existing.getId()));

                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "idempotency_key already used with a different request body");
            }

            log.info("idempotent_replay",
                    kv("event", "idempotent_replay"),
                    kv("idempotencyKey", request.getIdempotencyKey()),
                    kv("transferId", existing.getId()),
                    kv("status", existing.getStatus()));
            // No explicit "_total" here either - Micrometer appends the
            // Prometheus counter suffix itself (see TransferTxHelper for why
            // adding it manually can collide with Prometheus's reserved
            // "_created" convention).
            meterRegistry.counter("wallet_idempotent_replays").increment();

            return mapToResponse(existing);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public TransferResponse getTransfer(String transferId) {

        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found: " + transferId));

        return mapToResponse(transfer);
    }

    private TransferResponse mapToResponse(Transfer transfer) {

        TransferResponse response = new TransferResponse();

        response.setTransferId(transfer.getId());
        response.setStatus(transfer.getStatus());
        response.setFromWalletId(transfer.getFromWalletId());
        response.setToWalletId(transfer.getToWalletId());
        response.setAmountPaise(transfer.getAmountPaise());

        return response;
    }

    private String computeRequestHash(TransferRequest request) {
        String raw = request.getFrom() + "|" + request.getTo() + "|" + request.getAmountPaise();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
