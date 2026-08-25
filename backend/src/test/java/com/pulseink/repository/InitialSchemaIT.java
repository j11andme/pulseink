package com.pulseink.repository;

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

class InitialSchemaIT {

    @Test
    void flywayCreatesPersistenceContracts() throws SQLException {
        try (var mysql = new MySQLContainer("mysql:8.4.7")
                .withDatabaseName("pulseink")
                .withUsername("pulseink")
                .withPassword("pulseink_dev")) {
            mysql.start();

            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                assertThat(tableNames(connection))
                        .contains(
                                "campaign",
                                "campaign_run",
                                "run_event",
                                "run_checkpoint",
                                "app_user");
                assertThat(columnNames(connection, "campaign_run"))
                        .contains(
                                "requested_policy",
                                "selected_mode",
                                "selector_policy_version",
                                "selection_reason_json",
                                "version");
                assertThat(columnNames(connection, "app_user"))
                        .contains(
                                "username",
                                "password_hash",
                                "role",
                                "enabled",
                                "created_at",
                                "updated_at");
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
}
