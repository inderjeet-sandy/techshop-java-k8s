package com.techshop.controller;

import com.newrelic.api.agent.NewRelic;
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

    @PostMapping("/chaos")
    public ResponseEntity<Map<String, Object>> chaos(@RequestBody Map<String, Object> body) {
        String flag  = (String) body.get("flag");
        Boolean enabled = (Boolean) body.getOrDefault("enabled", false);
        NewRelic.getAgent().getInsights().recordCustomEvent("ChaosToggleEvent",
            Map.of("flag", flag, "enabled", enabled));
        return ResponseEntity.ok(Map.of("flag", flag, "enabled", enabled,
            "note", "Restart pod or use kubectl set env to apply"));
    }
}
