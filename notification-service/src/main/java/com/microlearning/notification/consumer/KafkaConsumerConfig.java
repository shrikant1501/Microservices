package com.microlearning.notification.consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * KafkaConsumerConfig — configures how notification-service reads from Kafka.
 *
 * KEY CONFIG EXPLAINED:
 * - GROUP_ID: "notification-group"
 *   All instances of notification-service share this group.
 *   Kafka assigns each partition to ONE instance — no duplicate processing.
 *
 * - TRUSTED_PACKAGES: allows Jackson to deserialize JSON to our local event classes.
 *
 * - AUTO_OFFSET_RESET: "earliest"
 *   If this consumer group has never read from the topic before,
 *   start from the beginning (don't miss messages sent before startup).
 *   Use "latest" in production if you only care about new messages.
 *
 * - ENABLE_AUTO_COMMIT: false
 *   Offset is committed AFTER successful processing (at-least-once delivery).
 *   If the consumer crashes mid-processing, the message is redelivered.
 *   Pair with idempotent consumer logic to handle duplicates safely.
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, UserCreatedEvent> userCreatedConsumerFactory() {
        JsonDeserializer<UserCreatedEvent> deser = new JsonDeserializer<>(UserCreatedEvent.class);
        deser.addTrustedPackages("*");
        deser.setUseTypeHeaders(false); // use target class directly, ignore type header

        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,  bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG,           "notification-group");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,  "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), deser);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, UserCreatedEvent> kafkaListenerContainerFactory() {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, UserCreatedEvent>();
        factory.setConsumerFactory(userCreatedConsumerFactory());
        // Process 1 message at a time per listener (safe default)
        // Increase concurrency = more threads = more partitions consumed in parallel
        factory.setConcurrency(1);
        return factory;
    }
}
