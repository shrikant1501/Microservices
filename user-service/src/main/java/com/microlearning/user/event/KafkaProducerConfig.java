package com.microlearning.user.event;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * KafkaProducerConfig — configures how user-service sends events.
 *
 * KEY CONFIG EXPLAINED:
 * - KEY_SERIALIZER:   String (userId as string for partition key)
 * - VALUE_SERIALIZER: JSON (event object → JSON bytes)
 * - TYPE_MAPPINGS:    tells the consumer side what Java type to deserialize to
 *   Without this, the consumer needs the same class on its classpath.
 *   With this, the JSON header carries "userCreated" and the consumer maps it.
 *
 * PRODUCTION ADDITIONS (not shown here for clarity):
 * - acks=all            → wait for all replicas to acknowledge (durability)
 * - retries=3           → retry on transient failures
 * - enable.idempotence  → exactly-once producer semantics
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // Type mappings: consumer side uses "userCreated" alias to deserialize
        config.put(JsonSerializer.TYPE_MAPPINGS,
                "userCreated:com.microlearning.user.event.UserCreatedEvent");
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
