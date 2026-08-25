CREATE TABLE channel_post
(
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    external_post_id      CHAR(36)        NOT NULL,
    idempotency_key       CHAR(36)        NOT NULL,
    source_publication_id BIGINT UNSIGNED NOT NULL,
    content_version_id    BIGINT UNSIGNED NOT NULL,
    channel               VARCHAR(32)     NOT NULL,
    content_json          JSON            NOT NULL,
    source_refs_json      JSON            NOT NULL,
    payload_hash          CHAR(64)        NOT NULL,
    published_at          TIMESTAMP(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_channel_post_external UNIQUE (external_post_id),
    CONSTRAINT uk_channel_post_idempotency UNIQUE (idempotency_key),
    INDEX idx_channel_post_channel (channel)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE channel_metric
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    channel_post_id BIGINT UNSIGNED NOT NULL,
    metric_date     DATE            NOT NULL,
    views           BIGINT          NOT NULL,
    clicks          BIGINT          NOT NULL,
    likes           BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_channel_metric_post
        FOREIGN KEY (channel_post_id) REFERENCES channel_post (id),
    CONSTRAINT uk_channel_metric_post_date UNIQUE (channel_post_id, metric_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE event_outbox
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id        CHAR(36)        NOT NULL,
    aggregate_type  VARCHAR(32)     NOT NULL,
    aggregate_id    CHAR(36)        NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    schema_version  INT             NOT NULL,
    payload_json    JSON            NOT NULL,
    status          VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    attempt_count   INT             NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6)     NOT NULL,
    last_error      VARCHAR(1024)   NULL,
    created_at      TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at    TIMESTAMP(6)     NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_event_outbox_event UNIQUE (event_id),
    INDEX idx_event_outbox_due (status, next_attempt_at, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
