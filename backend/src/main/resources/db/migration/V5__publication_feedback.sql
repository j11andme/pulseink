CREATE TABLE publication
(
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    run_id             BIGINT UNSIGNED NOT NULL,
    content_version_id BIGINT UNSIGNED NOT NULL,
    approval_record_id BIGINT UNSIGNED NOT NULL,
    requested_by       BIGINT          NOT NULL,
    channel            VARCHAR(32)     NOT NULL,
    idempotency_key    CHAR(36)        NOT NULL,
    status             VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    attempt_count      INT             NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMP(6)     NULL,
    version            BIGINT          NOT NULL DEFAULT 0,
    external_post_id   CHAR(36)        NULL,
    receipt_json       JSON            NULL,
    failure_code       VARCHAR(64)     NULL,
    failure_message    VARCHAR(1024)   NULL,
    created_at         TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    published_at       TIMESTAMP(6)     NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_publication_run
        FOREIGN KEY (run_id) REFERENCES campaign_run (id),
    CONSTRAINT fk_publication_version
        FOREIGN KEY (content_version_id) REFERENCES content_version (id),
    CONSTRAINT fk_publication_approval
        FOREIGN KEY (approval_record_id) REFERENCES approval_record (id),
    CONSTRAINT fk_publication_requested_by
        FOREIGN KEY (requested_by) REFERENCES app_user (id),
    CONSTRAINT uk_publication_version_channel UNIQUE (content_version_id, channel),
    CONSTRAINT uk_publication_idempotency UNIQUE (idempotency_key),
    INDEX idx_publication_due (status, next_attempt_at, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE feedback_inbox
(
    event_id         CHAR(36)        NOT NULL,
    publication_id   BIGINT UNSIGNED NOT NULL,
    schema_version   INT             NOT NULL,
    source_topic     VARCHAR(128)    NOT NULL,
    source_partition INT             NOT NULL,
    source_offset    BIGINT          NOT NULL,
    received_at      TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id),
    CONSTRAINT fk_feedback_inbox_publication
        FOREIGN KEY (publication_id) REFERENCES publication (id),
    INDEX idx_feedback_inbox_publication (publication_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE content_metric_daily
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    publication_id BIGINT UNSIGNED NOT NULL,
    metric_date    DATE            NOT NULL,
    views          BIGINT          NOT NULL DEFAULT 0,
    clicks         BIGINT          NOT NULL DEFAULT 0,
    likes          BIGINT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_metric_daily_publication
        FOREIGN KEY (publication_id) REFERENCES publication (id),
    CONSTRAINT uk_metric_daily_publication_date UNIQUE (publication_id, metric_date),
    INDEX idx_metric_daily_publication_date (publication_id, metric_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
