package com.microlearning.todo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TodoServiceApplication — Phase 3
 *
 * BOUNDED CONTEXT : Task Management
 * SUBDOMAIN TYPE  : Core Domain
 * AGGREGATE ROOT  : Todo
 * OWNS            : todos table (:8082)
 * COMMUNICATES    : user-service via OpenFeign (REST)
 * PUBLISHES       : TodoCompleted event (Phase 4)
 *
 * @EnableFeignClients scans for @FeignClient interfaces in this package
 * and registers them as Spring beans (backed by JDK dynamic proxies).
 */
@SpringBootApplication
@EnableFeignClients
@EnableScheduling   // required for @Scheduled in OutboxPublisher
public class TodoServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TodoServiceApplication.class, args);
    }
}
