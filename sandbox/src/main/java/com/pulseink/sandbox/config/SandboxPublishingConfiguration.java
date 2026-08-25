package com.pulseink.sandbox.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.sandbox.config.properties.FeedbackProperties;
import com.pulseink.sandbox.config.properties.OutboxProperties;
import com.pulseink.sandbox.outbox.EventOutboxRepository;
import com.pulseink.sandbox.outbox.KafkaOutboxMessageSender;
import com.pulseink.sandbox.outbox.OutboxMessageSender;
import com.pulseink.sandbox.outbox.OutboxPublisher;
import com.pulseink.sandbox.repository.ChannelPostRepository;
import com.pulseink.sandbox.service.ChannelPublishingService;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Composition root for the sandbox publishing pipeline: clock, business zone, idempotent
 * publishing service and the outbox publisher. Business rules stay in services; this class only
 * wires dependencies.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({OutboxProperties.class, FeedbackProperties.class})
public class SandboxPublishingConfiguration {

    @Bean
    Clock channelClock() {
        return Clock.systemUTC();
    }

    @Bean
    ChannelPublishingService channelPublishingService(
            ChannelPostRepository channelPostRepository,
            EventOutboxRepository eventOutboxRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Clock channelClock,
            @Value("${pulseink.business-zone:Asia/Shanghai}") String businessZone) {
        return new ChannelPublishingService(
                channelPostRepository,
                eventOutboxRepository,
                objectMapper,
                new TransactionTemplate(transactionManager),
                channelClock,
                ZoneId.of(businessZone));
    }

    @Bean
    OutboxMessageSender outboxMessageSender(KafkaTemplate<String, String> kafkaTemplate) {
        return new KafkaOutboxMessageSender(kafkaTemplate);
    }

    @Bean
    OutboxPublisher outboxPublisher(
            EventOutboxRepository eventOutboxRepository,
            OutboxMessageSender outboxMessageSender,
            OutboxProperties outboxProperties,
            FeedbackProperties feedbackProperties,
            Clock channelClock) {
        return new OutboxPublisher(
                eventOutboxRepository,
                outboxMessageSender,
                outboxProperties,
                feedbackProperties,
                channelClock);
    }
}
