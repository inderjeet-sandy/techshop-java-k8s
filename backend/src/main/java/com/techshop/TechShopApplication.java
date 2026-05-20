package com.techshop;

import com.newrelic.api.agent.NewRelic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.Map;

@SpringBootApplication
@EnableAsync
public class TechShopApplication {

    private static final Logger log = LoggerFactory.getLogger(TechShopApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(TechShopApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("TechShop Java Backend started!");
        log.info("New Relic Agent: {}", NewRelic.getAgent().getClass().getName());

        NewRelic.getAgent().getInsights().recordCustomEvent("AppStartupEvent", Map.of(
            "service",     "TechShop-Java-Backend",
            "javaVersion", System.getProperty("java.version"),
            "startTime",   System.currentTimeMillis()
        ));
    }
}
