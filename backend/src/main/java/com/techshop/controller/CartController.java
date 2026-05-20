package com.techshop.controller;

import com.newrelic.api.agent.NewRelic;
import com.techshop.config.MetricsConfig;
import com.techshop.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final ProductService productService;
    private final MetricsConfig.TechShopMetrics metrics;

    public CartController(ProductService productService,
                          MetricsConfig.TechShopMetrics metrics) {
        this.productService = productService;
        this.metrics        = metrics;
    }

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> addToCart(@RequestBody Map<String, Object> body) {
        Long productId = Long.valueOf(body.get("productId").toString());
        int qty        = Integer.parseInt(body.getOrDefault("quantity", 1).toString());

        return productService.getProductById(productId).map(product -> {
            double itemValue = product.getPrice().doubleValue() * qty;

            metrics.recordCartAdd(product.getCategory(), itemValue);

            Map<String, Object> event = new HashMap<>();
            event.put("productId",  productId);
            event.put("productSku", product.getSku());
            event.put("category",   product.getCategory());
            event.put("quantity",   qty);
            event.put("itemValue",  itemValue);
            NewRelic.getAgent().getInsights().recordCustomEvent("CartAddEvent", event);

            Map<String, Object> result = new HashMap<>();
            result.put("productId", productId);
            result.put("name",      product.getName());
            result.put("quantity",  qty);
            result.put("unitPrice", product.getPrice());
            result.put("itemTotal", itemValue);
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }
}
