package com.pulseink.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pulseink.feedback")
public record FeedbackProperties(
        String topic,
        String dltTopic,
        String consumerGroup,
        Integer consumerMaxAttempts,
        Boolean consumerEnabled) {

    public FeedbackProperties {
        if (topic == null || topic.isBlank()) {
            topic = "pulseink.feedback.raw.v1";
        }
        if (dltTopic == null || dltTopic.isBlank()) {
            dltTopic = "pulseink.feedback.raw.v1-dlt";
        }
        if (consumerGroup == null || consumerGroup.isBlank()) {
            consumerGroup = "pulseink-feedback-v1";
        }
        if (consumerMaxAttempts == null || consumerMaxAttempts <= 0) {
            consumerMaxAttempts = 3;
        }
        if (consumerEnabled == null) {
            consumerEnabled = true;
        }
    }
}
