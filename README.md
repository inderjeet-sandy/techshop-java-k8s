# ☕ TechShop Java — New Relic Full-Stack Demo App

A 3-tier Java demo application deployed on Kubernetes, built to demonstrate every major New Relic Java instrumentation concept end-to-end. Companion to [techshop-k8s-nodeapp](https://github.com/inderjeet-sandy/techshop-k8s-nodeapp) — same app, different stack.

**Live on:** EKS (us-west-2) | Spring Boot 3.2 | Java 17 | H2 + Flyway | Micrometer OTLP

---

## Architecture

```
Browser (New Relic Browser Agent)
    │
    ▼
AWS NodePort / LoadBalancer
    │
    ▼
┌─────────────────────────────────┐
│  Frontend Pods (Nginx)          │
│  - Serves HTML/JS/CSS           │
│  - Proxies /api/* to backend    │
│  - Passes W3C traceparent header│
└────────────┬────────────────────┘
             │ ClusterIP (techshop-java-backend-svc:8080)
             ▼
┌─────────────────────────────────────────────────────────┐
│  Backend Pods (Spring Boot 3.2 + Java 17)               │
│                                                         │
│  JVM startup:  -javaagent:/app/newrelic.jar             │
│                 └─ premain() runs before main()         │
│                 └─ class transformers registered        │
│                 └─ NR agent connects to collector       │
│                                                         │
│  Instrumentation layers:                                │
│  ├── @Trace annotations      → APM transaction segments │
│  ├── Token API               → async thread linking     │
│  ├── Custom Events           → NRDB (OrderPlacedEvent)  │
│  ├── Micrometer OTLP         → NR Metric API            │
│  ├── Logs in Context         → trace.id on every line   │
│  └── XML instrumentation     → Flyway / Hibernate       │
│                                                         │
│  H2 in-memory DB (Flyway migrated on startup)           │
│  12 products, 5 categories seeded                       │
└─────────────────────────────────────────────────────────┘
             │
             ▼
    New Relic (APM + Browser + Logs + Metrics + NRDB)
```

### Kubernetes Resources

```
namespace: techshop-java
├── Deployments
│   ├── techshop-java-backend   (2 replicas, Spring Boot)
│   └── techshop-java-frontend  (2 replicas, Nginx)
├── Services
│   ├── techshop-java-backend-svc  (ClusterIP — internal)
│   └── techshop-java-frontend-svc (NodePort / LoadBalancer — public)
├── ConfigMaps
│   ├── techshop-java-config    (app config + chaos flags)
│   ├── nr-extensions-config    (XML instrumentation, hot-reloadable)
│   └── nginx-config            (proxy + DT header passthrough)
├── Secret
│   └── newrelic-secret         (license key — never in Git)
└── HorizontalPodAutoscaler
    └── techshop-java-backend-hpa (min 2, max 6 pods)
```

---

## New Relic Instrumentation — What Each Piece Does

### 1. Static Agent Startup (`-javaagent`)
```dockerfile
ENTRYPOINT ["java", "-javaagent:/app/newrelic.jar", "-jar", "/app/app.jar"]
```
The JVM calls `premain()` before `main()`. The NR agent registers bytecode transformers for every framework it recognises (Spring MVC, JDBC, Tomcat). You see this in pod logs:
```
New Relic Agent: Loading configuration file "/app/newrelic.yml"
New Relic Agent: com.newrelic.agent.AgentImpl
```

### 2. `@Trace` Annotations — Transaction Segments
```java
@Trace
public List<Product> getAllProducts() { ... }

@Trace
private List<Product> getAllProductsNPlusOne() { ... }  // N+1 chaos mode
```
Every `@Trace` method appears as a named segment in the APM trace waterfall. Find them at:
`APM → TechShop-Java-Backend → Transactions → [click any trace] → Trace details`

### 3. Token API — Async Thread Linking
The most powerful pattern. Without it, async processing disappears from NR traces entirely.

```java
// HTTP thread: capture token before handing off
Token asyncToken = NewRelic.getAgent().getTransaction().getToken();
processOrderAsync(order.getId(), asyncToken);

// Worker thread (order-proc-N):
@Async("orderProcessingExecutor")
@Trace(dispatcher = true)
public CompletableFuture<Void> processOrderAsync(Long orderId, Token asyncToken) {
    asyncToken.link();    // bridges this thread to the parent HTTP transaction
    try {
        validateInventory(orderId);   // @Trace sub-segments
        processPayment(orderId);
    } finally {
        asyncToken.expire();          // transaction can now close and report
    }
}
```
Find the linked traces at:
`APM → Distributed Tracing → filter by POST /api/orders/checkout → see HTTP + worker thread linked`

### 4. Custom Events → NRDB
High-cardinality, per-transaction detail goes here — not into Micrometer metrics.
```java
NewRelic.getAgent().getInsights().recordCustomEvent("OrderPlacedEvent", Map.of(
    "orderNumber",   order.getOrderNumber(),
    "customerEmail", customerEmail,           // high cardinality — fine as event
    "orderTotal",    total.doubleValue()
));
```
Query with NRQL:
```sql
SELECT * FROM OrderPlacedEvent SINCE 1 hour ago
SELECT sum(orderTotal) FROM OrderPlacedEvent SINCE 1 day ago
```

### 5. Micrometer OTLP — The DigiCert Lesson

> **Context:** During a real-world engagement, DigiCert removed `micrometer-registry-otlp`
> and replaced it with `micrometer-registry-new-relic`. NR engineer jack-berg (Jan 2024):
> *"We will advise users against using it — fitting a square peg in a round hole."*
> Their `MetricService.java` also tagged counters with `tld` (every domain validated) — an
> unbounded dimension that combined with other tags to produce millions of time series,
> pushing them to 1.2M Agent Spans DPM vs the 800K limit.

**What we do instead:**

`pom.xml` — the correct dependency:
```xml
<!-- CORRECT: sends to otlp.nr-data.net directly -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-otlp</artifactId>
</dependency>

<!-- DO NOT add micrometer-registry-new-relic — deprecated Insights API -->
```

`application.properties` — two lines, no proprietary config:
```properties
management.otlp.metrics.export.url=https://otlp.nr-data.net:4318/v1/metrics
management.otlp.metrics.export.headers.api-key=${NEW_RELIC_LICENSE_KEY}
```

`MetricsConfig.java` — cardinality guardrails:
```java
// WRONG (DigiCert pattern): unbounded "tld" tag
counter("mpic.dns.status", "tld", tld, "agent_id", agent)  // millions of series

// RIGHT (what we do): bounded "category" tag
Counter.builder("techshop.cart.add")
    .tag("category", category)   // 5 values max — always bounded
    .register(registry);

// HIGH-CARDINALITY DETAIL → Custom Events, not Micrometer
NewRelic.getAgent().getInsights().recordCustomEvent("CartAddEvent",
    Map.of("productSku", sku));  // unbounded — fine as event, not metric tag
```

### 6. Cardinality Demo Endpoint
Live recreation of the DigiCert mistake — toggleable at runtime:
```bash
# Enable the cardinality bomb
kubectl set env deployment/techshop-java-backend \
  TECHSHOP_FEATURE_CARDINALITY_BOMB_ENABLED=true -n techshop-java

# Fire a burst of events
curl -X POST "http://YOUR_HOST/api/cardinality-demo/burst?count=200"
```
Watch in NR:
```sql
SELECT uniqueCount(dimensions) FROM Metric
WHERE metricName LIKE 'techshop.product.views.%'
FACET metricName SINCE 30 minutes ago TIMESERIES 1 minute
```
Wrong mode climbs to 144+ series. Right mode stays flat at 15.

### 7. Pod Metadata on Every Transaction
```yaml
# In K8s deployment spec
- name: JAVA_TOOL_OPTIONS
  value: >-
    -Dnewrelic.config.labels.pod_name=$(MY_POD_NAME)
    -Dnewrelic.config.labels.node_name=$(MY_NODE_NAME)
```
Query by pod in NR:
```sql
SELECT * FROM Transaction WHERE pod_name = 'techshop-java-backend-xyz' SINCE 1 hour ago
```

### 8. XML Instrumentation (Hot-Reloadable)
Mounted from a K8s ConfigMap into `/app/extensions`. Instruments third-party code (Flyway, Hibernate) without touching source. Reloads without pod restart because `extensions.reload_modified: true` in `newrelic.yml`.

---

## Project Structure

```
techshop-java/
├── backend/
│   ├── pom.xml                                          # Spring Boot 3.2, NR API 8.10.0, Micrometer OTLP
│   ├── extensions/
│   │   └── techshop-instrumentation.xml                # XML instrumentation (Flyway, Hibernate)
│   └── src/main/
│       ├── java/com/techshop/
│       │   ├── TechShopApplication.java                # @EnableAsync, AppStartupEvent
│       │   ├── config/
│       │   │   ├── AppConfig.java                      # CORS, async executor, NR request filter
│       │   │   └── MetricsConfig.java                  # Micrometer OTLP + cardinality guardrails
│       │   ├── controller/
│       │   │   ├── ProductController.java              # GET /api/products
│       │   │   ├── CartController.java                 # POST /api/cart/add
│       │   │   ├── OrderController.java                # POST /api/orders/checkout
│       │   │   ├── HealthController.java               # GET /api/health
│       │   │   └── CardinalityDemoController.java      # DigiCert demo endpoints
│       │   ├── service/
│       │   │   ├── ProductService.java                 # @Trace, N+1 chaos mode
│       │   │   ├── OrderService.java                   # Token API async tracing
│       │   │   └── CardinalityDemoService.java         # Live cardinality bomb demo
│       │   ├── model/                                  # Product, Order, OrderItem (JPA)
│       │   └── repository/                             # Spring Data repositories
│       └── resources/
│           ├── application.properties                  # OTLP endpoint, feature flags
│           ├── newrelic.yml                            # Agent config (DT, traces, JFR, logs)
│           ├── logback-newrelic.xml                    # Logs in Context encoder
│           └── db/migration/V1__initial_schema.sql     # Schema + 12 products seed
├── frontend/
│   ├── index.html                                      # NR Browser snippet placeholder + SPA
│   ├── app.js                                          # addPageAction, noticeError, setCurrentRouteName
│   └── styles.css
├── docker/
│   ├── backend.Dockerfile                              # Multi-stage Maven build + JRE runtime
│   ├── frontend.Dockerfile                             # Nginx
│   └── nginx.conf                                      # Proxy + W3C traceparent passthrough
├── k8s/
│   └── manifests.yaml                                  # All K8s resources
└── loadgen/
    └── load-generator.js                               # Node.js weighted scenario generator
```

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| kubectl | any | already configured for EKS |
| docker | 24+ | Docker Desktop |
| aws CLI | 2.x | `brew install awscli` |
| maven | 3.6+ | `brew install maven` |
| Java JDK | 17 | `brew install openjdk@17` |
| Node.js | 18+ | for load generator only |

> **Apple Silicon (M1/M2):** Always build with `--platform linux/amd64` for EKS nodes.

---

## Deployment — Step by Step

### 1. Set variables
```bash
export AWS_ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
export AWS_REGION=us-west-2
```

### 2. Create ECR repositories
```bash
aws ecr create-repository --repository-name techshop-java-backend --region $AWS_REGION
aws ecr create-repository --repository-name techshop-java-frontend --region $AWS_REGION
```

### 3. Authenticate Docker to ECR
```bash
aws ecr get-login-password --region $AWS_REGION | \
  docker login --username AWS --password-stdin \
  ${AWS_ACCOUNT}.dkr.ecr.${AWS_REGION}.amazonaws.com
```

### 4. Build images (AMD64 for EKS)
```bash
docker build --platform linux/amd64 \
  -f docker/backend.Dockerfile \
  -t ${AWS_ACCOUNT}.dkr.ecr.${AWS_REGION}.amazonaws.com/techshop-java-backend:latest .

docker build --platform linux/amd64 \
  -f docker/frontend.Dockerfile \
  -t ${AWS_ACCOUNT}.dkr.ecr.${AWS_REGION}.amazonaws.com/techshop-java-frontend:latest .
```

> First backend build takes 5-8 minutes (Maven downloads dependencies + newrelic-agent.jar).
> Subsequent builds use Docker layer cache and complete in ~60 seconds.

### 5. Push to ECR
```bash
docker push ${AWS_ACCOUNT}.dkr.ecr.${AWS_REGION}.amazonaws.com/techshop-java-backend:latest
docker push ${AWS_ACCOUNT}.dkr.ecr.${AWS_REGION}.amazonaws.com/techshop-java-frontend:latest
```

### 6. Create namespace and secret
```bash
kubectl create namespace techshop-java

kubectl create secret generic newrelic-secret \
  --from-literal=license-key=YOUR_NR_LICENSE_KEY \
  --namespace=techshop-java \
  --dry-run=client -o yaml | kubectl apply -f -
```

> The `--dry-run=client -o yaml | kubectl apply -f -` pattern generates the secret in memory
> and pipes it directly to apply. The key is never written to disk and is safe to re-run
> (unlike `kubectl create` which errors if the secret already exists).

### 7. Deploy
```bash
kubectl apply -f k8s/manifests.yaml
```

### 8. Watch pods start
```bash
kubectl get pods -n techshop-java -w
```
Backend pods take 60-90 seconds — JVM + Spring Boot + NR agent all initialise before the readiness probe passes.

### 9. Get the URL
```bash
# If LoadBalancer Controller is installed:
kubectl get svc techshop-java-frontend-svc -n techshop-java

# If not (NodePort fallback):
kubectl patch svc techshop-java-frontend-svc -n techshop-java \
  -p '{"spec": {"type": "NodePort"}}'
kubectl get svc techshop-java-frontend-svc -n techshop-java
# Access at http://NODE_EXTERNAL_IP:NODE_PORT
```

---

## Chaos Engineering

Toggle feature flags live on the running deployment — K8s does a rolling restart with zero downtime.

```bash
# Slow checkout — watch transaction times spike in APM
kubectl set env deployment/techshop-java-backend \
  TECHSHOP_FEATURE_SLOW_CHECKOUT_ENABLED=true -n techshop-java

# N+1 queries — watch DB segment count explode in traces
kubectl set env deployment/techshop-java-backend \
  TECHSHOP_FEATURE_N_PLUS_ONE_ENABLED=true -n techshop-java

# Payment errors — watch error rate climb in Error Analytics
kubectl set env deployment/techshop-java-backend \
  TECHSHOP_FEATURE_PAYMENT_ERRORS_ENABLED=true -n techshop-java

# Cardinality bomb — recreate DigiCert's mistake live
kubectl set env deployment/techshop-java-backend \
  TECHSHOP_FEATURE_CARDINALITY_BOMB_ENABLED=true -n techshop-java
```

Turn anything off by setting the same flag to `false`.

---

## Load Generator

```bash
BACKEND_URL=http://YOUR_HOST:PORT RATE=5 node loadgen/load-generator.js
```

Weighted scenarios: product browsing (30%), category filter (15%), search (20%), add to cart (15%), checkout (12%), 404 errors (8%).

---

## Key NRQL Queries

```sql
-- APM: Order throughput
SELECT count(*) FROM Transaction
WHERE name LIKE '%checkout%' SINCE 1 hour ago TIMESERIES 5 minutes

-- Custom Events: Revenue
SELECT sum(orderTotal) FROM OrderPlacedEvent SINCE 1 day ago

-- Metrics: Checkout latency SLO
SELECT percentile(techshop.checkout.latency, 50, 95, 99)
FROM Metric SINCE 1 hour ago TIMESERIES 5 minutes

-- DigiCert lesson: cardinality comparison
SELECT uniqueCount(dimensions) FROM Metric
WHERE metricName LIKE 'techshop.product.views.%'
FACET metricName SINCE 30 minutes ago TIMESERIES 1 minute

-- K8s: Find which pod handled a slow transaction
SELECT * FROM Transaction
WHERE duration > 2 FACET pod_name SINCE 1 hour ago
```

---

## Useful Commands

```bash
# Pod status
kubectl get pods -n techshop-java -o wide

# Stream backend logs
kubectl logs -f deployment/techshop-java-backend -n techshop-java

# Read NR agent log from inside pod
kubectl exec -it deployment/techshop-java-backend -n techshop-java -- \
  cat /app/logs/newrelic_agent.log | tail -50

# Resource usage (requires metrics-server)
kubectl top pods -n techshop-java

# Rollback
kubectl rollout undo deployment/techshop-java-backend -n techshop-java

# Teardown
kubectl delete namespace techshop-java
```

---

## New Relic Browser Agent

The Browser snippet placeholder in `frontend/index.html` needs to be replaced with your actual snippet:

1. New Relic → Add Data → Browser → Create application → `TechShop-Frontend`
2. Copy the JavaScript snippet
3. Paste it inside the `<head>` tag in `frontend/index.html`
4. Rebuild and push the frontend image

Once added, Browser links to APM distributed traces through the W3C `traceparent` header that Nginx passes through to the backend.
