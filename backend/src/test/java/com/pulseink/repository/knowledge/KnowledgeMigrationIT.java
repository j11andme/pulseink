package com.pulseink.repository.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

class KnowledgeMigrationIT {

    @Test
    void v3CreatesKnowledgeTablesWithoutTouchingV1V2Checksums() throws SQLException {
        try (var mysql = new MySQLContainer("mysql:8.4.7")
                .withDatabaseName("pulseink")
                .withUsername("pulseink")
                .withPassword("pulseink_dev")) {
            mysql.start();

            var flyway = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration")
                    .load();
            var result = flyway.migrate();
            assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(3);

            // A second migrate must find nothing pending: this also proves the V1/V2 file
            // checksums are untouched (Flyway validates checksums on every run).
            var secondRun = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            assertThat(secondRun.migrationsExecuted).isZero();

            try (var connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                assertThat(tableNames(connection))
                        .contains("knowledge_document", "ingestion_job");
                assertThat(columnNames(connection, "knowledge_document"))
                        .contains(
                                "source_id", "storage_key", "original_filename",
                                "declared_mime_type", "detected_mime_type",
                                "size_bytes", "checksum_sha256", "knowledge_type",
                                "authority", "document_version", "status",
                                "embedding_profile_id", "index_name", "chunk_count",
                                "failure_code", "created_by", "version",
                                "created_at", "updated_at");
                assertThat(columnNames(connection, "ingestion_job"))
                        .contains(
                                "job_id", "document_id", "status", "attempt",
                                "failure_code", "started_at", "completed_at",
                                "version", "created_at", "updated_at");
                assertThat(indexColumns(connection, "knowledge_document"))
                        .contains("source_id", "storage_key", "checksum_sha256");
            }
        }
    }

    private static Set<String> tableNames(Connection connection) throws SQLException {
        var names = new LinkedHashSet<String>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(
                connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                names.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static Set<String> columnNames(Connection connection, String table) throws SQLException {
        var names = new LinkedHashSet<String>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, table, "%")) {
            while (columns.next()) {
                names.add(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static Set<String> indexColumns(Connection connection, String table) throws SQLException {
        var names = new LinkedHashSet<String>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getIndexInfo(
                connection.getCatalog(), null, table, false, false)) {
            while (columns.next()) {
                names.add(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }
}
