package com.pulseink.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.client.channel.ChannelHttpAdapter;
import com.pulseink.config.properties.ChannelProperties;
import com.pulseink.config.properties.FeedbackProperties;
import com.pulseink.config.properties.PublicationProperties;
import com.pulseink.service.content.ContentWorkflowRepository;
import com.pulseink.service.campaign.RunRepository;
import com.pulseink.service.publishing.ChannelPort;
import com.pulseink.service.publishing.PublicationRepository;
import com.pulseink.service.publishing.PublicationWorker;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Composition root for the publishing pipeline: properties, clock, the Channel HTTP client and
 * the due-task worker. No business rules live here.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
        ChannelProperties.class, PublicationProperties.class, FeedbackProperties.class})
public class PublishingConfiguration {

    @Bean
    Clock publicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    ChannelPort channelPort(ChannelProperties properties, ObjectMapper objectMapper) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        var client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
        return new ChannelHttpAdapter(client, objectMapper);
    }

    @Bean
    PublicationWorker publicationWorker(
            PublicationRepository publicationRepository,
            ContentWorkflowRepository contentWorkflowRepository,
            RunRepository runRepository,
            ChannelPort channelPort,
            PublicationProperties properties,
            PlatformTransactionManager transactionManager,
            Clock publicationClock) {
        return new PublicationWorker(
                publicationRepository,
                contentWorkflowRepository,
                runRepository,
                channelPort,
                properties,
                new TransactionTemplate(transactionManager),
                publicationClock);
    }

    /**
     * Scheduler trigger for the publication worker; absent when the worker is disabled so
     * ordinary tests never start background polling. {@link PublicationWorker#processBatch()}
     * remains directly callable.
     */
    @Bean
    @ConditionalOnProperty(name = "pulseink.publication.worker-enabled",
            havingValue = "true", matchIfMissing = false)
    PublicationWorkerPolling publicationWorkerPolling(PublicationWorker worker) {
        return new PublicationWorkerPolling(worker);
    }

    /**
     * Bounded retry with DLT forwarding for the feedback listener: at most
     * {@code consumerMaxAttempts} deliveries, then the poison record is published to the DLT
     * topic with the original topic/partition/offset audit headers.
     */
    @Bean
    org.springframework.kafka.listener.DefaultErrorHandler feedbackErrorHandler(
            org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate,
            FeedbackProperties properties) {
        var recoverer = new org.springframework.kafka.listener.DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new org.apache.kafka.common.TopicPartition(
                        properties.dltTopic(), record.partition()));
        return new org.springframework.kafka.listener.DefaultErrorHandler(
                recoverer,
                new org.springframework.util.backoff.FixedBackOff(
                        0L, properties.consumerMaxAttempts() - 1));
    }

    public static final class PublicationWorkerPolling {

        private final PublicationWorker worker;

        PublicationWorkerPolling(PublicationWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${pulseink.publication.poll-delay:1s}",
                initialDelayString = "${pulseink.publication.poll-delay:1s}")
        public void poll() {
            worker.processBatch();
        }
    }
}
