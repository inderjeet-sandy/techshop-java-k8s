package com.techshop.service;

import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import com.techshop.config.MetricsConfig;
import com.techshop.model.Product;
import com.techshop.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ProductService — @Trace annotations + Micrometer metrics
 *
 * @Trace marks methods as segments in transaction traces.
 * Every method here will appear as a named segment in the NR trace waterfall.
 */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final MetricsConfig.TechShopMetrics metrics;

    @Value("${techshop.feature.n-plus-one-enabled:false}")
    private boolean nPlusOneEnabled;

    public ProductService(ProductRepository productRepository,
                          MetricsConfig.TechShopMetrics metrics) {
        this.productRepository = productRepository;
        this.metrics           = metrics;
    }

    @Trace
    public List<Product> getAllProducts() {
        long start = System.currentTimeMillis();
        NewRelic.addCustomParameter("products.queryType", "all");

        List<Product> products = nPlusOneEnabled
            ? getAllProductsNPlusOne()
            : productRepository.findByStockQuantityGreaterThan(0);

        long ms = System.currentTimeMillis() - start;
        NewRelic.recordMetric("Custom/DB/ProductQuery/getAllInStock", ms);
        NewRelic.addCustomParameter("products.resultCount", products.size());
        metrics.recordProductQuery("all", ms);

        return products;
    }

    @Trace
    public Optional<Product> getProductById(Long id) {
        long start = System.currentTimeMillis();
        NewRelic.addCustomParameter("product.id", id);

        Optional<Product> result = productRepository.findById(id);

        long ms = System.currentTimeMillis() - start;
        NewRelic.recordMetric("Custom/ProductService/getById", ms);

        result.ifPresentOrElse(
            p -> {
                NewRelic.addCustomParameter("product.sku",      p.getSku());
                NewRelic.addCustomParameter("product.category", p.getCategory());
            },
            () -> NewRelic.noticeError("Product not found: id=" + id)
        );
        return result;
    }

    @Trace
    public List<Product> getProductsByCategory(String category) {
        long start = System.currentTimeMillis();
        NewRelic.addCustomParameter("filter.category", category);

        List<Product> products = productRepository.findByCategory(category);

        long ms = System.currentTimeMillis() - start;
        NewRelic.addCustomParameter("products.resultCount", products.size());
        metrics.recordProductQuery("category", ms);

        return products;
    }

    /**
     * N+1 chaos mode — queries each product individually instead of one batch.
     * With this on, a request for 12 products generates 12+1 DB queries.
     * Visible in NR trace as 12 DB segments instead of 1.
     */
    @Trace
    private List<Product> getAllProductsNPlusOne() {
        NewRelic.addCustomParameter("query.pattern", "n_plus_one");
        List<Long> ids = productRepository.findAll().stream().map(Product::getId).toList();
        List<Product> result = new ArrayList<>();
        for (Long id : ids) {
            productRepository.findById(id).ifPresent(result::add); // N individual queries
        }
        return result;
    }

    @Trace
    public List<Product> searchProducts(String query) {
        long start = System.currentTimeMillis();
        NewRelic.addCustomParameter("search.query",       query);
        NewRelic.addCustomParameter("search.queryLength", query.length());

        List<Product> results = productRepository.searchProducts(query);

        long ms = System.currentTimeMillis() - start;
        NewRelic.addCustomParameter("search.resultCount", results.size());
        metrics.recordSearch(results.size());
        metrics.recordProductQuery("search", ms);

        // Per-search detail goes to Custom Event (NRDB), NOT Micrometer
        // DigiCert lesson: query text is unbounded — safe as event, not metric tag
        Map<String, Object> searchEvent = new HashMap<>();
        searchEvent.put("searchQuery",  query);
        searchEvent.put("resultCount",  results.size());
        searchEvent.put("zeroResults",  results.isEmpty());
        NewRelic.getAgent().getInsights().recordCustomEvent("ProductSearchEvent", searchEvent);

        return results;
    }

    @Trace
    public List<String> getAllCategories() {
        return productRepository.findAllCategories();
    }

    @Trace
    public Product save(Product product) {
        return productRepository.save(product);
    }
}
