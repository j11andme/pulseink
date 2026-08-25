CREATE TABLE knowledge_document
(
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_id            CHAR(36)        NOT NULL,
    original_filename    VARCHAR(255)    NOT NULL,
    storage_key          VARCHAR(255)    NOT NULL,
    declared_mime_type   VARCHAR(128)    NOT NULL,
    detected_mime_type   VARCHAR(128)    NULL,
    size_bytes           BIGINT          NOT NULL,
    checksum_sha256      CHAR(64)        NOT NULL,
    knowledge_type       VARCHAR(32)     NOT NULL,
    authority            VARCHAR(32)     NOT NULL,
    document_version     INT             NOT NULL DEFAULT 1,
    status               VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    embedding_profile_id VARCHAR(128)    NULL,
    index_name           VARCHAR(255)    NULL,
    chunk_count          INT             NOT NULL DEFAULT 0,
    failure_code         VARCHAR(64)     NULL,
    created_by           BIGINT UNSIGNED NOT NULL,
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_document_source (source_id),
    UNIQUE KEY uk_knowledge_document_storage (storage_key),
    UNIQUE KEY uk_knowledge_document_checksum_type (checksum_sha256, knowledge_type),
    KEY idx_knowledge_document_created_by (created_by),
    KEY idx_knowledge_document_status_type_created (status, knowledge_type, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE ingestion_job
(
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    job_id       CHAR(36)        NOT NULL,
    document_id  BIGINT UNSIGNED NOT NULL,
    status       VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    attempt      INT             NOT NULL DEFAULT 0,
    failure_code VARCHAR(64)     NULL,
    started_at   TIMESTAMP(6)    NULL,
    completed_at TIMESTAMP(6)    NULL,
    version      BIGINT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ingestion_job_id (job_id),
    UNIQUE KEY uk_ingestion_job_document (document_id),
    CONSTRAINT fk_ingestion_job_document
        FOREIGN KEY (document_id) REFERENCES knowledge_document (id),
    KEY idx_ingestion_job_status_created (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
