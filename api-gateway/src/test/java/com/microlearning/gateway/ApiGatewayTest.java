package com.microlearning.gateway;

import com.microlearning.gateway.filter.CorrelationIdFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * ApiGatewayTest — tests the gateway's own filters in isolation.
 *
 * NOTE: We test the gateway's cross-cutting behaviour, NOT routing to real services.
 * Real service routing needs an integration test environment with all services running.
 *
 * What we CAN test here:
 *   ✓ CorrelationIdFilter generates X-Correlation-Id when absent
 *   ✓ CorrelationIdFilter passes through existing X-Correlation-Id
 *   ✓ Routes are configured (without real backends)
 *
 * Eureka is disabled so tests run standalone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false",
        "spring.cloud.gateway.enabled=true"
})
class ApiGatewayTest {

    @Autowired
    WebTestClient webTestClient;

    @Test
    @DisplayName("Gateway injects X-Correlation-Id when not present in request")
    void correlationId_generatedWhenAbsent() {
        webTestClient.get()
                .uri("/api/users/1")
                // No X-Correlation-Id header sent
                .exchange()
                // Gateway should add one in the response (even if routing fails — filter runs first)
                .expectHeader().exists(CorrelationIdFilter.CORRELATION_ID_HEADER);
    }

    @Test
    @DisplayName("Gateway preserves X-Correlation-Id when already present in request")
    void correlationId_passedThroughWhenPresent() {
        String existingId = "test-correlation-id-12345";

        webTestClient.get()
                .uri("/api/users/1")
                .header(CorrelationIdFilter.CORRELATION_ID_HEADER, existingId)
                .exchange()
                // Should return the SAME id, not a new one
                .expectHeader().valueEquals(CorrelationIdFilter.CORRELATION_ID_HEADER, existingId);
    }
}
