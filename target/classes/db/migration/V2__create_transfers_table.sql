CREATE TABLE transfers (
    id               VARCHAR(36)  NOT NULL,
    from_wallet_id   VARCHAR(36)  NOT NULL,
    to_wallet_id     VARCHAR(36)  NOT NULL,
    amount_paise     BIGINT       NOT NULL,
    idempotency_key  VARCHAR(100) NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    status           VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    CONSTRAINT pk_transfers PRIMARY KEY (id),
    CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_transfers_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT ck_transfers_not_self CHECK (from_wallet_id <> to_wallet_id),
    CONSTRAINT ck_transfers_status CHECK (status IN ('PENDING', 'COMPLETED', 'DECLINED_INSUFFICIENT_FUNDS')),
    CONSTRAINT fk_transfers_from_wallet FOREIGN KEY (from_wallet_id) REFERENCES wallets (id),
    CONSTRAINT fk_transfers_to_wallet FOREIGN KEY (to_wallet_id) REFERENCES wallets (id)
);

CREATE INDEX idx_transfers_from_wallet_id ON transfers (from_wallet_id);
CREATE INDEX idx_transfers_to_wallet_id ON transfers (to_wallet_id);
