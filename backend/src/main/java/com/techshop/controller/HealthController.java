package com.techshop.controller;

import com.newrelic.api.agent.NewRelic;
import com.techshop.config.ChaosConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChaosConfig chaosConfig;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        String dbStatus = "UP";
        try { jdbcTemplate.queryForObject("SELECT 1", Integer.class); }
        catch (Exception e) { dbStatus = "DOWN"; }

        return ResponseEntity.ok(Map.of(
            "status",        "UP",
            "service",       "TechShop-Java-Backend",
            "database",      dbStatus,
            "javaVersion",   System.getProperty("java.version"),
            "newRelicAgent", NewRelic.getAgent().getClass().getSimpleName()
        ));
    }

    /** GET /api/chaos — returns current state of all chaos flags */
    @GetMapping("/chaos")
    public ResponseEntity<Map<String, Boolean>> getChaosState() {
        return ResponseEntity.ok(chaosConfig.snapshot());
    }

    /** POST /api/chaos — toggle a chaos flag at runtime, no pod restart needed */
    @PostMapping("/chaos")
    public ResponseEntity<Map<String, Object>> setChaos(@RequestBody Map<String, Object> body) {
        String  flag    = (String)  body.get("flag");
        Boolean enabled = (Boolean) body.getOrDefault("enabled", false);

        try {
            chaosConfig.toggle(flag, enabled);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        // Record the toggle in New Relic for observability
        NewRelic.getAgent().getInsights().recordCustomEvent("ChaosToggleEvent",
            Map.of("flag", flag, "enabled", enabled));

        return ResponseEntity.ok(Map.of(
            "flag",    flag,
            "enabled", enabled,
            "state",   chaosConfig.snapshot()
        ));
    }
}
