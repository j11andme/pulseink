package com.pulseink.support;

import org.testcontainers.elasticsearch.ElasticsearchContainer;

/**
 * Shared Elasticsearch Testcontainer for memory-index integration tests: started once
 * per test JVM and reused by every test class in the same Maven invocation.
 */
public final class MemoryElasticsearchTestContainer {

    private static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.4.2")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("discovery.type", "single-node")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    static {
        ELASTICSEARCH.start();
    }

    private MemoryElasticsearchTestContainer() {
    }

    public static String httpHostAddress() {
        return ELASTICSEARCH.getHttpHostAddress();
    }
}
