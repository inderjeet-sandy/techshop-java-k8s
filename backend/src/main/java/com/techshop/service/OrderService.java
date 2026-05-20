package com.techshop.service;

import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Token;
import com.newrelic.api.agent.Trace;
import com.techshop.config.MetricsConfig;
import com.techshop.model.Order;
import com.techshop.model.OrderItem;
import com.techshop.model.Product;
import com.techshop.repository.OrderRepository;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * OrderService — Token API async tracing + DigiCert Micrometer lessons
 *
 * TOKEN API (from NR blog):
 * ─────────────────────────────────────────────────────────────────────
 * HTTP thread:   token = getToken()          → captures current tx context
 *                processOrderAsync(id, token) → hands off to worker thread
 * Worker thread: token.link()                → links worker to parent tx
 *                ... do work ...
 *                token.expire()              → tx can now close and report
 *
 * Without token linking, the async processing disappears from NR traces.
 * With it, you see the full waterfall: HTTP thread → worker thread segments.
 *
 * DIGICERT METRICS LESSON:
 * ─────────────────────────────────────────────────────────────────────
 * Low-cardinality aggregates (counts, latency histograms, value distributions)
 * → Micrometer OTLP → New Relic Metric API
 *
 * High-cardinality per-order detail (orderNumber, email, itemCount)
 * → NewRelic.recordCustomEvent() → NRDB (no cardinality limit)
 *
 * DigiCert's mistake: putting tld (unbounded) into a Micrometer counter.
 * The fix: put it in an event. Events absorb arbitrary dimensions.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ProductService  productService;
    private final MetricsConfig.TechShopMetrics metrics;
    private final Random random = new Random();

    @Value("${techshop.feature.slow-checkout-enabled:false}")
    private boolean slowCheckoutEnabled;

    @Value("${techshop.feature.payment-errors-enabled:false}")
    private boolean paymentErrorsEnabled;

    @Value("${techshop.async.order-processing-delay:500}")
    private long orderProcessingDelayMs;

    public OrderService(OrderRepository orderRepository,
                        ProductService productService,
                        MetricsConfig.TechShopMetrics metrics) {
        this.orderRepository = orderRepository;
        this.productService  = productService;
        this.metrics         = metrics;
    }

    @Trace(dispatcher = true)
    @Transactional
    public Order placeOrder(String customerEmail, List<Map<String, Object>> items) {

        // Start Micrometer checkout latency timer
        // ONE histogram regardless of order volume — DigiCert lesson
        Timer.Sample checkoutSample = metrics.startCheckout();

        NewRelic.addCustomParameter("order.customerEmail", customerEmail);
        NewRelic.addCustomParameter("order.itemCount",     items.size());

        try {
            Order order = buildOrder(customerEmail, items);
            orderRepository.save(order);
            BigDecimal total = order.getTotal();

            // ── Custom Event: per-order detail → NRDB (not Micrometer) ──
            // customerEmail and orderNumber are high-cardinality — fine as events
            Map<String, Object> event = new HashMap<>();
            event.put("orderNumber",   order.getOrderNumber());
            event.put("customerEmail", customerEmail);
            event.put("itemCount",     items.size());
            event.put("orderTotal",    total.doubleValue());
            NewRelic.getAgent().getInsights().recordCustomEvent("OrderPlacedEvent", event);

            // ── Micrometer: low-cardinality aggregates ──
            // counter tag "status" = success/failed (2 values max) — safe
            metrics.recordOrderPlaced(total.doubleValue());

            // ── Token API: link async processing to this HTTP transaction ──
            Token asyncToken = NewRelic.getAgent().getTransaction().getToken();
            processOrderAsync(order.getId(), asyncToken);

            log.info("Order placed: {} total={}", order.getOrderNumber(), total);
            return order;

        } catch (Exception e) {
            metrics.recordOrderFailed();
            NewRelic.noticeError(e);
            throw e;
        } finally {
            metrics.stopCheckout(checkoutSample);
        }
    }

    /**
     * processOrderAsync — runs on "order-proc-N" thread pool.
     *
     * @Trace(dispatcher=true): creates a NEW transaction on this thread.
     * token.link(): bridges it to the parent HTTP transaction in NR.
     * token.expire(): signals NR this transaction segment is complete.
     */
    @Async("orderProcessingExecutor")
    @Trace(dispatcher = true)
    public CompletableFuture<Void> processOrderAsync(Long orderId, Token asyncToken) {
        asyncToken.link();
        try {
            NewRelic.addCustomParameter("async.orderId", orderId);
            NewRelic.addCustomParameter("async.thread",  Thread.currentThread().getName());

            validateInventory(orderId);
            processPayment(orderId);
            updateOrderStatus(orderId, "SHIPPED");

            log.info("Order {} processed on thread {}", orderId, Thread.currentThread().getName());
            return CompletableFuture.completedFuture(null);

        } catch (Exception e) {
            NewRelic.noticeError(e);
            updateOrderStatus(orderId, "FAILED");
            return CompletableFuture.failedFuture(e);
        } finally {
            asyncToken.expire();
        }
    }

    @Trace
    private void validateInventory(Long orderId) {
        NewRelic.addCustomParameter("step", "validateInventory");
        sleep(50 + random.nextInt(50));
    }

    @Trace
    private void processPayment(Long orderId) {
        NewRelic.addCustomParameter("step", "processPayment");

        if (slowCheckoutEnabled) {
            sleep(2000 + random.nextInt(3000)); // 2-5s delay
        } else {
            sleep(100 + random.nextInt(200));
        }

        if (paymentErrorsEnabled && random.nextInt(100) < 5) {
            throw new RuntimeException("Payment processor timeout — orderId=" + orderId);
        }
    }

    @Trace
    private void updateOrderStatus(Long orderId, String status) {
        orderRepository.findById(orderId).ifPresent(o -> {
            o.setStatus(status);
            orderRepository.save(o);
            NewRelic.getAgent().getInsights().recordCustomEvent("OrderStatusChangeEvent",
                Map.of("orderId", orderId, "newStatus", status));
        });
    }

    private Order buildOrder(String customerEmail, List<Map<String, Object>> items) {
        Order order = new Order();
        order.setOrderNumber("TS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        order.setCustomerEmail(customerEmail);
        order.setStatus("PENDING");
        order.setCreatedAt(LocalDateTime.now());
        order.setTotal(BigDecimal.ZERO);

        List<OrderItem> orderItems = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Long productId = Long.valueOf(item.get("productId").toString());
            int qty = Integer.parseInt(item.getOrDefault("quantity", 1).toString());

            productService.getProductById(productId).ifPresent(p -> {
                OrderItem oi = new OrderItem();
                oi.setOrder(order);
                oi.setProductId(p.getId());
                oi.setProductName(p.getName());
                oi.setProductSku(p.getSku());
                oi.setQuantity(qty);
                oi.setUnitPrice(p.getPrice());
                orderItems.add(oi);
            });
        }

        order.setItems(orderItems);
        BigDecimal total = orderItems.stream()
            .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotal(total);
        return order;
    }

    public List<Order> getOrdersByEmail(String email) {
        return orderRepository.findByCustomerEmailOrderByCreatedAtDesc(email);
    }

    public Optional<Order> getOrderByNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
