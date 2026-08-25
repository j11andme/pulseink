package com.pulseink.sandbox.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pulseink.feedback")
public record FeedbackProperties(
        String topic,
        String dltTopic) {

    public FeedbackProperties {
        if (topic == null || topic.isBlank()) {
            topic = "pulseink.feedback.raw.v1";
        }
        if (dltTopic == null || dltTopic.isBlank()) {
            dltTopic = "pulseink.feedback.raw.v1-dlt";
        }
    }
}
