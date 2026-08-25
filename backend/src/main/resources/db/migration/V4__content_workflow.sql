CREATE TABLE content_item
(
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    run_id             BIGINT UNSIGNED NOT NULL,
    task_id            VARCHAR(64)     NOT NULL,
    current_version_no INT             NOT NULL DEFAULT 0,
    version            BIGINT          NOT NULL DEFAULT 0,
    created_at         TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_content_item_run
        FOREIGN KEY (run_id) REFERENCES campaign_run (id),
    CONSTRAINT uk_content_item_run_task UNIQUE (run_id, task_id),
    INDEX idx_content_item_run (run_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE content_version
(
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    content_item_id         BIGINT UNSIGNED NOT NULL,
    version_no              INT             NOT NULL,
    content_json            JSON            NOT NULL,
    source_refs_json        JSON            NOT NULL,
    origin                  VARCHAR(16)     NOT NULL,
    source_artifact_id      VARCHAR(128)    NULL,
    source_artifact_version INT             NULL,
    source_artifact_status  VARCHAR(32)     NULL,
    created_by              BIGINT          NULL,
    created_at              TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_content_version_item
        FOREIGN KEY (content_item_id) REFERENCES content_item (id),
    CONSTRAINT fk_content_version_user
        FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT uk_content_version_no UNIQUE (content_item_id, version_no),
    CONSTRAINT uk_content_version_artifact UNIQUE (source_artifact_id),
    INDEX idx_content_version_item_created (content_item_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE review_report
(
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    run_id                  BIGINT UNSIGNED NOT NULL,
    source_artifact_id      VARCHAR(128)    NOT NULL,
    source_artifact_version INT             NOT NULL,
    source_artifact_status  VARCHAR(32)     NOT NULL,
    passed                  BOOLEAN         NOT NULL,
    repair_round            INT             NOT NULL,
    created_at              TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_review_report_run
        FOREIGN KEY (run_id) REFERENCES campaign_run (id),
    CONSTRAINT uk_review_report_artifact UNIQUE (source_artifact_id),
    INDEX idx_review_report_run_created (run_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE review_issue
(
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    review_report_id BIGINT UNSIGNED NOT NULL,
    issue_index      INT             NOT NULL,
    issue_type       VARCHAR(32)     NOT NULL,
    affected_task_id VARCHAR(64)     NULL,
    message          VARCHAR(1000)   NOT NULL,
    created_at       TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_review_issue_report
        FOREIGN KEY (review_report_id) REFERENCES review_report (id),
    CONSTRAINT uk_review_issue_task UNIQUE
        (review_report_id, issue_index, affected_task_id),
    INDEX idx_review_issue_report (review_report_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE approval_record
(
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    content_version_id BIGINT UNSIGNED NOT NULL,
    actor_id            BIGINT          NOT NULL,
    comment_text        VARCHAR(1000)   NOT NULL,
    created_at          TIMESTAMP(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_approval_version
        FOREIGN KEY (content_version_id) REFERENCES content_version (id),
    CONSTRAINT fk_approval_actor
        FOREIGN KEY (actor_id) REFERENCES app_user (id),
    CONSTRAINT uk_approval_version UNIQUE (content_version_id),
    INDEX idx_approval_actor_created (actor_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
