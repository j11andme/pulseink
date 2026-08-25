package com.pulseink.support;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Shared, disposable infrastructure for publishing feedback integration tests. MySQL starts once
 * when this class is used; Kafka starts lazily so database-only tests do not pay its startup cost.
 */
public final class BackendTestInfra {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7")
            .withDatabaseName("pulseink")
            .withUsername("pulseink")
            .withPassword("pulseink_dev");

    static {
        MYSQL.start();
    }

    private BackendTestInfra() {
    }

    public static String datasourceUrl() {
        return MYSQL.getJdbcUrl();
    }

    public static String datasourceUsername() {
        return MYSQL.getUsername();
    }

    public static String datasourcePassword() {
        return MYSQL.getPassword();
    }

    public static String kafkaBootstrapServers() {
        return KafkaHolder.INSTANCE.getBootstrapServers();
    }

    private static final class KafkaHolder {
        private static final KafkaContainer INSTANCE = startKafka();

        private static KafkaContainer startKafka() {
            var kafka = new KafkaContainer("apache/kafka:4.3.1");
            kafka.start();
            return kafka;
        }
    }
}
