package com.microlearning.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * ConfigServerApplication — Phase 7
 *
 * @EnableConfigServer  — that's the entire implementation.
 * Spring Cloud configures REST endpoints:
 *   GET /{application}/{profile}         → returns merged config
 *   GET /{application}/{profile}/{label} → label = Git branch/tag
 *
 * CLIENTS call it at startup via:
 *   spring.config.import=optional:configserver:http://localhost:8888
 *
 * TEST IT:
 *   curl http://localhost:8888/user-service/default
 *   curl http://localhost:8888/todo-service/prod
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
