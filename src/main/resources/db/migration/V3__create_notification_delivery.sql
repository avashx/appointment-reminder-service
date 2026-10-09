-- Sending protection: one row per idempotency key that has been handed to the provider.
--
-- The row is inserted and committed BEFORE the provider is called. The primary key makes that
-- insert a one-time operation per key across threads, instances and restarts, so a retry after a
-- crash finds the key and sends nothing. The row is deleted only when the provider definitely
-- refused the message (nothing was delivered), so that the retry can really send.
CREATE TABLE notification_delivery (
    idempotency_key      VARCHAR(100) NOT NULL PRIMARY KEY,
    reminder_id          BIGINT       NOT NULL,
    attempt_no           INT          NOT NULL,     -- the attempt that handed it over
    channel              VARCHAR(10)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,     -- RESERVED until the provider acknowledges it
    provider_message_id  VARCHAR(100) NULL,
    recorded_at          DATETIME(6)  NOT NULL,
    accepted_at          DATETIME(6)  NULL,
    CONSTRAINT fk_delivery_reminder FOREIGN KEY (reminder_id) REFERENCES reminder (id),
    CONSTRAINT ck_delivery_status CHECK (status IN ('RESERVED', 'ACCEPTED')),
    CONSTRAINT ck_delivery_accepted CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL)),
    -- "Handed over but never acknowledged": the reconciliation list.
    INDEX ix_delivery_status (status, recorded_at)
);
