package com.techshop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ChaosConfig — runtime-mutable chaos feature flags.
 *
 * Fields are initialised from env vars at startup (so the ConfigMap defaults
 * still work), but can be flipped live via POST /api/chaos without a pod
 * restart.  All services inject this bean instead of using @Value directly.
 */
@Component
public class ChaosConfig {

    @Value("${techshop.feature.slow-checkout-enabled:false}")
    private volatile boolean slowCheckoutEnabled;

    @Value("${techshop.feature.n-plus-one-enabled:false}")
    private volatile boolean nPlusOneEnabled;

    @Value("${techshop.feature.payment-errors-enabled:false}")
    private volatile boolean paymentErrorsEnabled;

    @Value("${techshop.feature.cardinality-bomb-enabled:false}")
    private volatile boolean cardinalityBombEnabled;

    // ── Getters ──────────────────────────────────────────────────────────

    public boolean isSlowCheckoutEnabled()    { return slowCheckoutEnabled; }
    public boolean isNPlusOneEnabled()        { return nPlusOneEnabled; }
    public boolean isPaymentErrorsEnabled()   { return paymentErrorsEnabled; }
    public boolean isCardinalityBombEnabled() { return cardinalityBombEnabled; }

    // ── Runtime toggle (called by /api/chaos) ────────────────────────────

    public boolean toggle(String flag, boolean enabled) {
        switch (flag) {
            case "slow-checkout":    slowCheckoutEnabled    = enabled; break;
            case "n-plus-one":       nPlusOneEnabled        = enabled; break;
            case "payment-errors":   paymentErrorsEnabled   = enabled; break;
            case "cardinality-bomb": cardinalityBombEnabled = enabled; break;
            default:
                throw new IllegalArgumentException("Unknown chaos flag: " + flag);
        }
        return enabled;
    }

    // ── State snapshot (for /api/chaos GET) ─────────────────────────────

    public java.util.Map<String, Boolean> snapshot() {
        return java.util.Map.of(
            "slow-checkout",    slowCheckoutEnabled,
            "n-plus-one",       nPlusOneEnabled,
            "payment-errors",   paymentErrorsEnabled,
            "cardinality-bomb", cardinalityBombEnabled
        );
    }
}
