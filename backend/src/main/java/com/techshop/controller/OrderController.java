package com.techshop.controller;

import com.newrelic.api.agent.NewRelic;
import com.techshop.model.Order;
import com.techshop.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/checkout")
    public ResponseEntity<Order> checkout(@RequestBody Map<String, Object> body) {
        String email = (String) body.get("customerEmail");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");

        if (email == null || items == null || items.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        Order order = orderService.placeOrder(email, items);
        return ResponseEntity.ok(order);
    }

    @GetMapping
    public List<Order> getOrders(@RequestParam String email) {
        return orderService.getOrdersByEmail(email);
    }

    @GetMapping("/{orderNumber}")
    public ResponseEntity<Order> getOrder(@PathVariable String orderNumber) {
        return orderService.getOrderByNumber(orderNumber)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
