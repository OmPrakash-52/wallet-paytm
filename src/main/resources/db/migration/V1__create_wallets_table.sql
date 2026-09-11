CREATE TABLE wallets (
    id             VARCHAR(36)  NOT NULL,
    user_id        VARCHAR(100) NOT NULL,
    balance_paise  BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    CONSTRAINT pk_wallets PRIMARY KEY (id),
    CONSTRAINT uq_wallets_user_id UNIQUE (user_id),
    CONSTRAINT ck_wallets_balance_non_negative CHECK (balance_paise >= 0)
);
