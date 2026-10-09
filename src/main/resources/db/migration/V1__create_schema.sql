-- All DATETIME(6) columns hold UTC (microsecond precision).
--
-- This file and V3 are where "a customer never receives the same reminder twice" is enforced:
-- by constraints the database checks on every write, not by application discipline.

CREATE TABLE appointment (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    dealership_id      BIGINT       NOT NULL,           -- owned by another system; a plain tenant column here
    customer_name      VARCHAR(200) NOT NULL,
    customer_phone     VARCHAR(16)  NULL,               -- E.164: '+' and up to 15 digits
    customer_email     VARCHAR(254) NULL,
    preferred_channel  VARCHAR(10)  NOT NULL,
    vehicle            VARCHAR(200) NULL,
    service_type       VARCHAR(100) NULL,
    scheduled_at       DATETIME(6)  NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    revision           INT          NOT NULL DEFAULT 1, -- bumped on reschedule; part of a reminder's identity
    idempotency_key    VARCHAR(100) NULL,
    request_hash       VARCHAR(64)  NULL,               -- SHA-256 of the original request, to detect key reuse
    version            BIGINT       NOT NULL DEFAULT 0, -- JPA optimistic locking
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    -- A retried POST with the same key can never create a second appointment (NULL keys never collide).
    CONSTRAINT uk_appointment_idempotency UNIQUE (dealership_id, idempotency_key),
    CONSTRAINT ck_appointment_contact CHECK (customer_phone IS NOT NULL OR customer_email IS NOT NULL),
    CONSTRAINT ck_appointment_channel CHECK (preferred_channel IN ('SMS', 'EMAIL')),
    CONSTRAINT ck_appointment_status CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    INDEX ix_appointment_dealership_time (dealership_id, scheduled_at)
);

CREATE TABLE reminder (
    id                    BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    appointment_id        BIGINT       NOT NULL,
    appointment_revision  INT          NOT NULL,
    reminder_type         VARCHAR(20)  NOT NULL,
    due_at                DATETIME(6)  NOT NULL,    -- when it should go out
    next_attempt_at       DATETIME(6)  NOT NULL,    -- when a worker may claim it (pushed back on retry)
    status                VARCHAR(20)  NOT NULL,
    status_reason         VARCHAR(255) NULL,
    idempotency_key       VARCHAR(100) NOT NULL,    -- appt-{id}-r{revision}-{type}: derived, identical on every retry
    attempt_count         INT          NOT NULL DEFAULT 0,  -- incremented by every claim
    claim_token           VARCHAR(36)  NULL,        -- fencing token of the current claim
    claimed_by            VARCHAR(100) NULL,        -- which instance claimed it (debugging only)
    claimed_at            DATETIME(6)  NULL,
    processing_until      DATETIME(6)  NULL,        -- the lease: after this, any worker may reclaim the row
    sent_at               DATETIME(6)  NULL,
    provider_message_id   VARCHAR(100) NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    CONSTRAINT fk_reminder_appointment FOREIGN KEY (appointment_id) REFERENCES appointment (id),
    -- Scheduling protection: exactly one row per logical reminder (one 24H and one 2H per revision).
    CONSTRAINT uk_reminder_logical UNIQUE (appointment_id, appointment_revision, reminder_type),
    CONSTRAINT uk_reminder_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_reminder_type CHECK (reminder_type IN ('REMINDER_24H', 'REMINDER_2H')),
    CONSTRAINT ck_reminder_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED',
                                                    'SKIPPED', 'EXPIRED', 'CANCELLED', 'SUPERSEDED')),
    -- A claim (token + lease) exists exactly while a worker owns the row, and SENT always says when.
    CONSTRAINT ck_reminder_lease CHECK ((status = 'PROCESSING') = (processing_until IS NOT NULL)),
    CONSTRAINT ck_reminder_claim CHECK ((status = 'PROCESSING') = (claim_token IS NOT NULL)),
    CONSTRAINT ck_reminder_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    -- Claim query: a range scan over due PENDING rows only, however large the table grows.
    INDEX ix_reminder_poll (status, next_attempt_at),
    -- Reclaim query: PROCESSING rows whose lease has run out.
    INDEX ix_reminder_lease (status, processing_until)
);
