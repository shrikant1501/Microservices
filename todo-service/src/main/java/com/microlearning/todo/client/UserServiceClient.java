package com.microlearning.todo.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * UserServiceClient — OpenFeign declarative HTTP client.
 *
 * ═══════════════════════════════════════════════════════════════
 * THIS IS THE HEART OF PHASE 3. READ THIS CAREFULLY.
 * ═══════════════════════════════════════════════════════════════
 *
 * WHAT THIS REPLACES:
 * In the monolith, TodoService had:
 *   @Autowired UserRepository userRepository;
 *   userRepository.findById(userId);   // in-process, zero network
 *
 * Now it has:
 *   @Autowired UserServiceClient userServiceClient;
 *   userServiceClient.getUserById(userId);  // HTTP over network
 *
 * SAME METHOD SIGNATURE. Completely different underlying mechanism.
 * This is the beauty of the OpenFeign abstraction.
 *
 * HOW @FeignClient WORKS:
 * 1. @EnableFeignClients scans and finds this interface at startup.
 * 2. Spring creates a JDK dynamic proxy implementing this interface.
 * 3. The proxy is registered as a Spring bean named "user-service".
 * 4. When getUserById(7) is called on the proxy:
 *    → reads @GetMapping("/api/users/{id}")
 *    → substitutes {id} = 7
 *    → resolves base URL from application.properties (Phase 3)
 *       or from Eureka registry by service name (Phase 5)
 *    → sends: GET http://localhost:8081/api/users/7
 *    → deserializes JSON response → UserResponse object
 *    → returns it to the caller
 *
 * name = "user-service":
 *   Phase 3: matched to user-service.url in application.properties
 *   Phase 5: matched to the service registered in Eureka as "user-service"
 *
 * url = "${user-service.url:}":
 *   Phase 3: reads from property (http://localhost:8081)
 *   Phase 5: REMOVE this — Eureka resolves by name automatically
 */
@FeignClient(name = "user-service", url = "${user-service.url:}")
public interface UserServiceClient {

    /**
     * Fetches a user by ID from user-service.
     *
     * Maps to: GET http://<user-service>/api/users/{id}
     *
     * Returns UserResponse DTO — todo-service has NO access to the
     * User JPA entity. It only knows the public API contract.
     *
     * If user-service returns 404: FeignException.NotFound is thrown.
     * If user-service is down:     IOException / FeignException is thrown.
     * Phase 8 wraps this with a Circuit Breaker + Fallback.
     */
    @GetMapping("/api/users/{id}")
    UserResponse getUserById(@PathVariable("id") Long id);
}
