package com.microlearning.user.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ConfigDemoController — demonstrates @RefreshScope live config refresh.
 *
 * ═══════════════════════════════════════════════════════════════
 * INTERVIEW DEMO SCENARIO
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. Start config-server and user-service.
 * 2. GET http://localhost:8081/api/config/feature-flags
 *    → returns { "newDashboard": false, "betaSignup": false }
 *
 * 3. Edit config-repo/user-service.yml:
 *    features.new-dashboard: true
 *
 * 4. POST http://localhost:8081/actuator/refresh
 *    → Spring re-fetches config, destroys and recreates this bean
 *
 * 5. GET http://localhost:8081/api/config/feature-flags again
 *    → returns { "newDashboard": true, "betaSignup": false }
 *    → NO restart. Running service updated live.
 *
 * This is the @RefreshScope power demonstrated end-to-end.
 */
@RestController
@RequestMapping("/api/config")
@RefreshScope   // ← re-created on POST /actuator/refresh
public class ConfigDemoController {

    private static final Logger log = LoggerFactory.getLogger(ConfigDemoController.class);

    @Value("${features.new-dashboard:false}")
    private boolean newDashboard;

    @Value("${features.beta-signup:false}")
    private boolean betaSignup;

    @Value("${spring.application.name}")
    private String serviceName;

    @GetMapping("/feature-flags")
    public ResponseEntity<Map<String, Object>> featureFlags() {
        log.info("[user-service] Feature flags requested — newDashboard={}, betaSignup={}",
                newDashboard, betaSignup);
        return ResponseEntity.ok(Map.of(
                "service",      serviceName,
                "newDashboard", newDashboard,
                "betaSignup",   betaSignup
        ));
    }
}
