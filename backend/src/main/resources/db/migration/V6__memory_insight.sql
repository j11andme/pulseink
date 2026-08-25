CREATE TABLE campaign_insight
(
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    campaign_id             BIGINT UNSIGNED NOT NULL,
    run_id                  BIGINT UNSIGNED NOT NULL,
    category                VARCHAR(32)     NOT NULL,
    title                   VARCHAR(200)    NOT NULL,
    insight_text            TEXT            NOT NULL,
    scope_type              VARCHAR(32)     NOT NULL,
    scope_value             VARCHAR(64)     NULL,
    applicable_channels_json JSON            NOT NULL,
    evidence_refs_json      JSON            NOT NULL,
    limitations_json        JSON            NOT NULL,
    confidence              DECIMAL(5, 4)   NOT NULL,
    source_snapshot_hash    CHAR(64)        NOT NULL,
    prompt_version          VARCHAR(32)     NOT NULL,
    status                  VARCHAR(32)     NOT NULL,
    index_status            VARCHAR(32)     NOT NULL,
    index_attempts          INT             NOT NULL DEFAULT 0,
    next_index_attempt_at   TIMESTAMP(6)     NULL,
    last_index_error        VARCHAR(1024)   NULL,
    created_by              BIGINT          NOT NULL,
    reviewed_by             BIGINT          NULL,
    review_comment          VARCHAR(1000)   NULL,
    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reviewed_at             TIMESTAMP(6)     NULL,
    indexed_at              TIMESTAMP(6)     NULL,
    updated_at              TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_insight_campaign
        FOREIGN KEY (campaign_id) REFERENCES campaign (id),
    CONSTRAINT fk_insight_run
        FOREIGN KEY (run_id) REFERENCES campaign_run (id),
    CONSTRAINT fk_insight_created_by
        FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT fk_insight_reviewed_by
        FOREIGN KEY (reviewed_by) REFERENCES app_user (id),
    CONSTRAINT uk_insight_snapshot UNIQUE (run_id, source_snapshot_hash, prompt_version),
    INDEX idx_insight_index_due (status, index_status, next_index_attempt_at, id),
    INDEX idx_insight_campaign_created (campaign_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
