package com.pulseink.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Shared Testcontainers for memory integration tests: one MySQL and one Redis container per
 * test JVM, so a whole checkpoint or the related aggregate run starts each container exactly
 * once. Tests never touch the shared development database.
 */
public final class MemoryTestContainers {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7")
            .withDatabaseName("pulseink")
            .withUsername("pulseink")
            .withPassword("pulseink_dev");

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.1-alpine")
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    private MemoryTestContainers() {
    }

    public static String mysqlUrl() {
        return MYSQL.getJdbcUrl();
    }

    public static String mysqlUsername() {
        return MYSQL.getUsername();
    }

    public static String mysqlPassword() {
        return MYSQL.getPassword();
    }

    public static String redisHost() {
        return REDIS.getHost();
    }

    public static int redisPort() {
        return REDIS.getMappedPort(6379);
    }

    public static String redisUrl() {
        return "redis://" + redisHost() + ":" + redisPort() + "/0";
    }
}
