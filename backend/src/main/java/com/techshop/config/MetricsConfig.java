package com.techshop.config;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * MetricsConfig — Micrometer OTLP with DigiCert Cardinality Lessons
 * ═══════════════════════════════════════════════════════════════════
 *
 * DIGICERT LESSON #1 — USE THE RIGHT REGISTRY
 * DigiCert removed micrometer-registry-otlp and replaced it with
 * micrometer-registry-new-relic. NR's own engineer jack-berg (Jan 2024):
 * "We will advise users against using it — fitting a square peg in a round hole."
 *
 * We use micrometer-registry-otlp. Configured in application.properties:
 *   management.otlp.metrics.export.url=https://otlp.nr-data.net:4318/v1/metrics
 *
 * DIGICERT LESSON #2 — CARDINALITY BOMB
 * DigiCert's MetricService.java tagged counters with "tld" (unbounded).
 * agents(N) x tlds(∞) x dns_type(3) x status(4) = millions of series.
 * Result: 1.2M Agent Spans DPM vs 800K limit.
 *
 * DIGICERT LESSON #3 — EVENTS vs METRICS
 * High-cardinality detail (orderNumber, email, productId) belongs in
 * Custom Events (NRDB). Low-cardinality aggregates belong in Micrometer.
 */
@Configuration
public class MetricsConfig {

    private static final Logger log = LoggerFactory.getLogger(MetricsConfig.class);

    /**
     * Common tags on every metric — safe because both have very few values.
     * "service" = 1 value. "env" = 3-4 values. Never unbounded.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config()
            .commonTags(
                "service", "techshop-backend",
                "env",     System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "dev")
            );
    }

    /**
     * MeterFilter — cardinality guardrails.
     * Runs at instrumentation time. Data is dropped before leaving the JVM.
     *
     * DigiCert had NO filters. Every tag combination went straight to NR.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> meterFilters() {
        return registry -> registry.config()

            // Block any metric accidentally tagged with unbounded keys
            .meterFilter(MeterFilter.deny(id ->
                id.getTag("productId") != null || id.getTag("sessionId") != null
            ))

            // SLO-based histogram buckets for latency metrics
            .meterFilter(new MeterFilter() {
                @Override
                public DistributionStatisticConfig configure(
                        Meter.Id id, DistributionStatisticConfig config) {
                    if (id.getName().startsWith("techshop.")) {
                        return DistributionStatisticConfig.builder()
                            .serviceLevelObjectives(50, 100, 200, 500, 1000, 2000, 5000)
                            .percentiles(0.5, 0.95, 0.99)
                            .build()
                            .merge(config);
                    }
                    return config;
                }
            });
    }

    @Bean
    public TechShopMetrics techShopMetrics(MeterRegistry registry) {
        return new TechShopMetrics(registry);
    }

    /**
     * TechShopMetrics — centralised, governed metric instruments.
     *
     * All meters defined here. Services inject this bean instead of
     * calling meterRegistry.counter() ad-hoc throughout the codebase.
     * Makes cardinality audits trivial — one file to review.
     */
    public static class TechShopMetrics {

        private final MeterRegistry registry;

        // Counters — all tags have bounded value sets
        private final Counter orderPlacedCounter;   // status: success (2 values max)
        private final Counter orderFailedCounter;   // status: failed
        private final Counter searchCounter;
        private final Counter zeroResultsCounter;

        // Timers — histograms, NOT per-entity series
        private final Timer checkoutTimer;
        private final Timer productQueryTimer;

        // Distribution summaries
        private final DistributionSummary orderValueSummary;
        private final DistributionSummary cartValueSummary;

        public TechShopMetrics(MeterRegistry registry) {
            this.registry = registry;
            log.info("Initializing TechShop metrics (Micrometer OTLP registry)");

            orderPlacedCounter = Counter.builder("techshop.orders.placed")
                .description("Orders successfully placed")
                .tag("status", "success")
                .register(registry);

            orderFailedCounter = Counter.builder("techshop.orders.placed")
                .description("Orders that failed")
                .tag("status", "failed")
                .register(registry);

            // No query-text tag — that would be unbounded (DigiCert lesson)
            searchCounter = Counter.builder("techshop.search.requests")
                .description("Total product searches")
                .register(registry);

            zeroResultsCounter = Counter.builder("techshop.search.zero_results")
                .description("Searches returning no results")
                .register(registry);

            // ONE histogram for checkout latency — not per-order timers
            checkoutTimer = Timer.builder("techshop.checkout.latency")
                .description("End-to-end checkout latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .serviceLevelObjectives(
                    Duration.ofMillis(200), Duration.ofMillis(500),
                    Duration.ofSeconds(1),  Duration.ofSeconds(2),
                    Duration.ofSeconds(5)
                )
                .register(registry);

            // queryType tag: "all", "category", "search" — 3 values, safe
            productQueryTimer = Timer.builder("techshop.product.query.latency")
                .description("Product query latency")
                .publishPercentiles(0.5, 0.95)
                .register(registry);

            orderValueSummary = DistributionSummary.builder("techshop.order.value")
                .description("Order value distribution in dollars")
                .baseUnit("dollars")
                .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                .register(registry);

            cartValueSummary = DistributionSummary.builder("techshop.cart.value")
                .description("Cart value distribution in dollars")
                .baseUnit("dollars")
                .publishPercentiles(0.5, 0.95)
                .register(registry);

            log.info("TechShop metrics registered with OTLP registry");
        }

        public void recordOrderPlaced(double value) {
            orderPlacedCounter.increment();
            orderValueSummary.record(value);
        }

        public void recordOrderFailed() {
            orderFailedCounter.increment();
        }

        public void recordSearch(int resultCount) {
            searchCounter.increment();
            if (resultCount == 0) zeroResultsCounter.increment();
        }

        // category tag: 5 values max — SAFE. Do NOT pass productId here.
        public void recordCartAdd(String category, double itemValue) {
            Counter.builder("techshop.cart.add")
                .tag("category", category != null ? category : "unknown")
                .register(registry)
                .increment();
            cartValueSummary.record(itemValue);
        }

        public Timer.Sample startCheckout() { return Timer.start(); }
        public void stopCheckout(Timer.Sample s) { s.stop(checkoutTimer); }

        public void recordProductQuery(String queryType, long ms) {
            productQueryTimer.record(ms, TimeUnit.MILLISECONDS);
        }
    }
}
