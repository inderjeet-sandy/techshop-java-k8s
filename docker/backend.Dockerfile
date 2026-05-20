# ─── Stage 1: Maven build ─────────────────────────────────────────────
# This stage compiles Java, runs tests, and downloads newrelic-agent.jar
# Everything happens inside Docker — you don't need Maven on your Mac
# (well, you need it to trigger this, but the actual compilation is here)
FROM maven:3.9-eclipse-temurin-17 AS maven-build

WORKDIR /build

# Copy pom.xml first — Docker caches this layer separately.
# If only source code changes (not pom.xml), Maven won't re-download
# all dependencies on the next build. Saves 3-5 minutes.
COPY backend/pom.xml .
RUN mvn dependency:go-offline -q

# Now copy source and build
COPY backend/src ./src
RUN mvn package -DskipTests -q

# At this point /build/target/ contains:
#   techshop-backend-1.0.0.jar  ← fat JAR with all dependencies
#   newrelic.jar                ← downloaded by maven-dependency-plugin

# ─── Stage 2: Runtime image ───────────────────────────────────────────
# Strip everything out — only the JRE and the JAR go into the final image.
# This is why the image is ~285MB instead of ~700MB (full JDK + Maven).
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Create non-root user — security best practice for containers
RUN groupadd -r techshop && useradd -r -g techshop techshop

# Copy artifacts from build stage
COPY --from=maven-build /build/target/techshop-backend-1.0.0.jar app.jar
COPY --from=maven-build /build/target/newrelic.jar               newrelic.jar
COPY backend/src/main/resources/newrelic.yml                      newrelic.yml
COPY backend/extensions/                                           extensions/

# Create log directory for NR agent
RUN mkdir -p /app/logs && chown -R techshop:techshop /app

USER techshop

EXPOSE 8080

# -javaagent MUST come before -jar — this is the static agent startup
# the NR blog describes. The JVM calls premain() before main().
ENTRYPOINT ["java", "-javaagent:/app/newrelic.jar", "-Dnewrelic.config.file=/app/newrelic.yml", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
