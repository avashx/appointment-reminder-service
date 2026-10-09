-- Append-only audit of every claim of a reminder: written as STARTED in the same transaction as
-- the claim (before anything is sent), then completed with what actually happened.
CREATE TABLE notification_attempt (
    id                   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    reminder_id          BIGINT       NOT NULL,
    attempt_no           INT          NOT NULL,
    claim_token          VARCHAR(36)  NOT NULL,
    idempotency_key      VARCHAR(100) NOT NULL,
    channel              VARCHAR(10)  NOT NULL,
    recipient            VARCHAR(254) NOT NULL,
    payload              JSON         NOT NULL,     -- exactly what was handed to the sender
    outcome              VARCHAR(20)  NOT NULL,
    provider_message_id  VARCHAR(100) NULL,
    error                VARCHAR(500) NULL,
    started_at           DATETIME(6)  NOT NULL,
    completed_at         DATETIME(6)  NULL,
    CONSTRAINT fk_attempt_reminder FOREIGN KEY (reminder_id) REFERENCES reminder (id),
    CONSTRAINT uk_attempt_no UNIQUE (reminder_id, attempt_no),
    CONSTRAINT ck_attempt_outcome CHECK (outcome IN ('STARTED', 'ACCEPTED', 'SUPPRESSED', 'RETRYABLE',
                                                     'REJECTED', 'UNKNOWN', 'ABANDONED'))
);
