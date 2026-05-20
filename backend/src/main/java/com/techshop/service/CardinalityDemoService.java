package com.techshop.service;

import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * CardinalityDemoService — Live Replay of the DigiCert Cardinality Bomb
 * ═══════════════════════════════════════════════════════════════════════
 *
 * DigiCert's MPIC service tagged DNS corroboration counters with "tld"
 * (every domain being validated) — an unbounded dimension that combined
 * with other tags to create millions of unique metric series.
 *
 * This service recreates that pattern against TechShop product views
 * so you can see the cardinality difference in New Relic in real time.
 *
 * WRONG mode (bomb ON):  productId x source x region = 144+ series
 * RIGHT mode (bomb OFF): category x source            = 15 series MAX
 *
 * Toggle: kubectl set env deployment/techshop-java-backend \
 *           TECHSHOP_FEATURE_CARDINALITY_BOMB_ENABLED=true -n techshop-java
 *
 * Watch in NR:
 *   SELECT uniqueCount(dimensions) FROM Metric
 *   WHERE metricName LIKE 'techshop.product.views.%'
 *   FACET metricName SINCE 30 minutes ago TIMESERIES 1 minute
 */
@Service
public class CardinalityDemoService {

    private static final Logger log = LoggerFactory.getLogger(CardinalityDemoService.class);

    private final MeterRegistry registry;
    private final Random random = new Random();

    private static final List<String> PRODUCT_IDS  = List.of(
        "PROD-001","PROD-002","PROD-003","PROD-004","PROD-005","PROD-006",
        "PROD-007","PROD-008","PROD-009","PROD-010","PROD-011","PROD-012"
    );
    private static final List<String> CATEGORIES   = List.of("Laptops","Peripherals","Networking","Storage","Accessories");
    private static final List<String> SOURCES      = List.of("web","mobile","api");
    private static final List<String> REGIONS      = List.of("us-west","us-east","eu-central","ap-southeast");

    @Value("${techshop.feature.cardinality-bomb-enabled:false}")
    private boolean bombEnabled;

    public CardinalityDemoService(MeterRegistry registry) {
        this.registry = registry;
    }

    @Trace
    public void recordProductView(String productId, String category, String source) {
        if (bombEnabled) {
            wrongPattern(productId, source);
        } else {
            rightPattern(category, source);
        }
    }

    /** WRONG — DigiCert pattern. Unbounded productId tag = new series per product. */
    private void wrongPattern(String productId, String source) {
        Counter.builder("techshop.product.views.wrong")
            .tag("productId",  productId)                                  // UNBOUNDED ❌
            .tag("source",     source)
            .tag("region",     REGIONS.get(random.nextInt(REGIONS.size())))
            .register(registry).increment();

        registry.timer("techshop.product.latency.wrong",
            "productId", productId)                                        // UNBOUNDED ❌
            .record(Duration.ofMillis(50 + random.nextInt(150)));

        NewRelic.addCustomParameter("cardinality.mode", "WRONG-bomb-active");
    }

    /** RIGHT — bounded category tag. 5 categories x 3 sources = 15 series max, always. */
    private void rightPattern(String category, String source) {
        Counter.builder("techshop.product.views.right")
            .tag("category", category != null ? category : "unknown")      // 5 values ✅
            .tag("source",   source)                                        // 3 values ✅
            .register(registry).increment();

        registry.timer("techshop.product.latency.right",
            "category", category != null ? category : "unknown")           // bounded ✅
            .record(Duration.ofMillis(50 + random.nextInt(150)));

        NewRelic.addCustomParameter("cardinality.mode", "RIGHT-bounded");
    }

    @Trace(dispatcher = true)
    public Map<String, Object> simulateBurst(int count) {
        for (int i = 0; i < count; i++) {
            String productId = PRODUCT_IDS.get(random.nextInt(PRODUCT_IDS.size()));
            String category  = CATEGORIES.get(random.nextInt(CATEGORIES.size()));
            String source    = SOURCES.get(random.nextInt(SOURCES.size()));
            recordProductView(productId, category, source);
        }

        NewRelic.addCustomParameter("cardinality.burst.count",   count);
        NewRelic.addCustomParameter("cardinality.bomb.enabled",  bombEnabled);

        int estimatedSeries = bombEnabled
            ? PRODUCT_IDS.size() * SOURCES.size() * REGIONS.size()
            : CATEGORIES.size()  * SOURCES.size();

        return Map.of(
            "eventsSimulated",        count,
            "cardinalityBombEnabled", bombEnabled,
            "mode",                   bombEnabled ? "WRONG — unbounded productId tag" : "RIGHT — bounded category tag",
            "estimatedMaxSeries",     estimatedSeries,
            "nrqlToCheck",
                "SELECT uniqueCount(dimensions) FROM Metric " +
                "WHERE metricName LIKE 'techshop.product.views.%' " +
                "FACET metricName SINCE 30 minutes ago TIMESERIES 1 minute",
            "digicertLesson",
                "DigiCert hit 1.2M Agent Spans DPM (limit 800K) by tagging counters " +
                "with 'tld' (unbounded) inside a per-DNS-response forEach loop. " +
                "Fix: replace unbounded tags with bounded ones. " +
                "For per-entity detail, use Custom Events (NRDB), not Micrometer series."
        );
    }

    public boolean isBombEnabled() { return bombEnabled; }
}
