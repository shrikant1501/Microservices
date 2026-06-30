package com.microlearning.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * EurekaServerApplication — Phase 5
 *
 * @EnableEurekaServer — that's the entire implementation.
 * Spring Cloud does everything else: REST endpoints, dashboard,
 * registry storage, heartbeat handling, peer replication.
 *
 * Dashboard: http://localhost:8761
 * API:       http://localhost:8761/eureka/apps
 *
 * PRODUCTION NOTE:
 * Run 2+ Eureka servers in a cluster with peer awareness.
 * Each peer replicates its registry to the other.
 * Clients can be configured with all peer URLs:
 *   eureka.client.service-url.defaultZone=
 *     http://eureka1:8761/eureka,http://eureka2:8762/eureka
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
