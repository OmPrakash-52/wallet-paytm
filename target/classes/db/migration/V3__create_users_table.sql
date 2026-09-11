CREATE TABLE users (
    id             VARCHAR(36)  NOT NULL,
    username       VARCHAR(100) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username)
);
