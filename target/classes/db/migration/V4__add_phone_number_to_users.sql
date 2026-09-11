ALTER TABLE users
    ADD COLUMN phone_number VARCHAR(10);

-- Backfill would be required here if the users table already had rows in a
-- real environment before this migration ran. For this exercise the column
-- is set NOT NULL/UNIQUE directly since no prior signups are expected yet.
ALTER TABLE users
    ALTER COLUMN phone_number SET NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT uq_users_phone_number UNIQUE (phone_number);

ALTER TABLE users
    ADD CONSTRAINT ck_users_phone_number_10_digits CHECK (phone_number ~ '^[0-9]{10}$');
