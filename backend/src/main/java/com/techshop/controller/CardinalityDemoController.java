package com.techshop.controller;

import com.techshop.service.CardinalityDemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/cardinality-demo")
public class CardinalityDemoController {

    private final CardinalityDemoService cardinalityDemoService;

    public CardinalityDemoController(CardinalityDemoService cardinalityDemoService) {
        this.cardinalityDemoService = cardinalityDemoService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        boolean on = cardinalityDemoService.isBombEnabled();
        return ResponseEntity.ok(Map.of(
            "cardinalityBombEnabled", on,
            "mode", on ? "WRONG — DigiCert pattern (unbounded productId tag)"
                       : "RIGHT — bounded category tag (15 series max)",
            "nrqlToCompare",
                "SELECT uniqueCount(dimensions) FROM Metric " +
                "WHERE metricName LIKE 'techshop.product.views.%' " +
                "FACET metricName SINCE 30 minutes ago TIMESERIES 1 minute",
            "enableBomb",
                "kubectl set env deployment/techshop-java-backend " +
                "TECHSHOP_FEATURE_CARDINALITY_BOMB_ENABLED=true -n techshop-java",
            "disableBomb",
                "kubectl set env deployment/techshop-java-backend " +
                "TECHSHOP_FEATURE_CARDINALITY_BOMB_ENABLED=false -n techshop-java"
        ));
    }

    @PostMapping("/burst")
    public ResponseEntity<Map<String, Object>> burst(
            @RequestParam(defaultValue = "100") int count) {
        if (count > 1000) return ResponseEntity.badRequest()
            .body(Map.of("error", "max 1000"));
        return ResponseEntity.ok(cardinalityDemoService.simulateBurst(count));
    }
}
