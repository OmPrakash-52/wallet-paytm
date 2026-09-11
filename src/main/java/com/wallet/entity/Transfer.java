package com.wallet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transfers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Transfer {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @NotNull
    @Column(name = "from_wallet_id", nullable = false, updatable = false, length = 36)
    private String fromWalletId;

    @NotNull
    @Column(name = "to_wallet_id", nullable = false, updatable = false, length = 36)
    private String toWalletId;

    @NotNull
    @Column(name = "amount_paise", nullable = false, updatable = false)
    private Long amountPaise;

    @NotNull
    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true, length = 100)
    private String idempotencyKey;

    @NotNull
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TransferStatus status = TransferStatus.PENDING;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID().toString();
        }
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = TransferStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
