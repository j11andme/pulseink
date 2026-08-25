CREATE TABLE campaign
(
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name             VARCHAR(128)    NOT NULL,
    objective        TEXT            NOT NULL,
    audience         TEXT            NOT NULL,
    channels_json    JSON            NOT NULL,
    constraints_json JSON            NOT NULL,
    status           VARCHAR(32)     NOT NULL DEFAULT 'DRAFT',
    created_by       BIGINT UNSIGNED NOT NULL,
    version          BIGINT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_campaign_created_by_status (created_by, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE campaign_run
(
    id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    campaign_id              BIGINT UNSIGNED NOT NULL,
    requested_policy         VARCHAR(32)     NOT NULL,
    selected_mode            VARCHAR(32)     NULL,
    selector_policy_version  VARCHAR(64)     NULL,
    selection_reason_json    JSON            NULL,
    selection_feature_json   JSON            NULL,
    estimated_token_budget   BIGINT          NULL,
    state                    VARCHAR(32)     NOT NULL DEFAULT 'CREATED',
    failure_reason           VARCHAR(1024)   NULL,
    started_at               TIMESTAMP(6)     NULL,
    completed_at             TIMESTAMP(6)     NULL,
    version                  BIGINT          NOT NULL DEFAULT 0,
    created_at               TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_campaign_run_campaign
        FOREIGN KEY (campaign_id) REFERENCES campaign (id),
    INDEX idx_campaign_run_campaign_created (campaign_id, created_at),
    INDEX idx_campaign_run_state (state)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE run_event
(
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    run_id       BIGINT UNSIGNED NOT NULL,
    sequence_no  BIGINT UNSIGNED NOT NULL,
    event_type   VARCHAR(64)     NOT NULL,
    payload_json JSON            NOT NULL,
    created_at   TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_run_event_run
        FOREIGN KEY (run_id) REFERENCES campaign_run (id),
    CONSTRAINT uk_run_event_sequence UNIQUE (run_id, sequence_no),
    INDEX idx_run_event_run_created (run_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE run_checkpoint
(
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    run_id               BIGINT UNSIGNED NOT NULL,
    checkpoint_type      VARCHAR(64)     NOT NULL,
    checkpoint_data_json JSON            NOT NULL,
    schema_version       INTEGER         NOT NULL,
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_run_checkpoint_run
        FOREIGN KEY (run_id) REFERENCES campaign_run (id),
    INDEX idx_run_checkpoint_run_created (run_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
