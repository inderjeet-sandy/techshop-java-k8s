package com.techshop.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.newrelic.api.agent.NewRelic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

@Configuration
public class AppConfig {

    // ── CORS ──────────────────────────────────────────────────────────
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    // ── Async Executor for Order Processing (Token API demo) ──────────
    // Named "orderProcessingExecutor" — this name appears in NR thread traces
    @Bean(name = "orderProcessingExecutor")
    public Executor orderProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("order-proc-");
        executor.initialize();
        return executor;
    }

    // ── New Relic Request Filter ──────────────────────────────────────
    // Adds session/user context to every transaction as custom attributes.
    // These appear in NR APM transaction traces and can be queried:
    //   SELECT * FROM Transaction WHERE session.id = 'abc123'
    @Bean
    public Filter newRelicContextFilter() {
        return new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                HttpServletRequest httpReq = (HttpServletRequest) req;

                String sessionId = httpReq.getHeader("X-Session-Id");
                String userId    = httpReq.getHeader("X-User-Id");

                if (sessionId != null) NewRelic.addCustomParameter("session.id", sessionId);
                if (userId    != null) NewRelic.addCustomParameter("user.id",    userId);

                // Tag every transaction with the request path for easier filtering
                NewRelic.addCustomParameter("request.path", httpReq.getRequestURI());

                chain.doFilter(req, res);
            }

            @Override public void init(FilterConfig f) {}
            @Override public void destroy() {}
        };
    }
}
