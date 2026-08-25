package com.pulseink.sandbox.support;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Shared, disposable infrastructure for sandbox publishing integration tests. MySQL starts once;
 * Kafka is lazy so repository and HTTP tests remain focused and fast.
 */
public final class SandboxTestInfra {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7")
            .withDatabaseName("pulseink_channel")
            .withUsername("pulseink_channel")
            .withPassword("pulseink_channel_dev");

    static {
        MYSQL.start();
    }

    private SandboxTestInfra() {
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
