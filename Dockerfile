# ============================================================
# DocMind — Multi-stage Dockerfile
# ============================================================
# Stage 1: Build (Maven + JDK 21)
# Stage 2: Runtime (JRE 21 slim — ~220MB vs ~500MB for full JDK)
#
# Why multi-stage builds?
#   - The build stage has Maven, sources, and all build tools (~800MB).
#   - The final image only contains the JRE and the layered application JAR.
#   - Result: significantly smaller production images, faster pushes.
#
# Why layered JARs?
#   Spring Boot's layered JAR feature splits the JAR into layers (ordered by change frequency):
#     1. dependencies        (rarely changes — big, shared across rebuilds)
#     2. spring-boot-loader  (rarely changes)
#     3. snapshot-dependencies (occasionally changes)
#     4. application         (changes on every code change — smallest layer)
#
#   Docker caches each layer independently. When you rebuild after a code change,
#   only the "application" layer is invalidated — dependencies are reused from cache.
#   This reduces image rebuild time from ~60s to ~5s in CI.
# ============================================================

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
LABEL stage=builder

WORKDIR /workspace

# Copy Maven wrapper and POMs first (to leverage Docker layer caching for deps)
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY docmind-common/pom.xml docmind-common/pom.xml
COPY docmind-ingestion/pom.xml docmind-ingestion/pom.xml
COPY docmind-core/pom.xml docmind-core/pom.xml
COPY docmind-api/pom.xml docmind-api/pom.xml
COPY docmind-eureka-server/pom.xml docmind-eureka-server/pom.xml
COPY docmind-gateway/pom.xml docmind-gateway/pom.xml

# Download all dependencies (cached as a separate layer — only invalidated when POMs change)
RUN ./mvnw dependency:go-offline -B

# Copy source code (invalidates only when source changes)
COPY docmind-common/src docmind-common/src
COPY docmind-ingestion/src docmind-ingestion/src
COPY docmind-core/src docmind-core/src
COPY docmind-api/src docmind-api/src
COPY docmind-eureka-server/src docmind-eureka-server/src
COPY docmind-gateway/src docmind-gateway/src

# Build, skipping tests (tests run in CI before this Docker build step)
RUN ./mvnw package -pl docmind-api -am -DskipTests -B

# Extract layered JAR into separate directories for optimized final image
WORKDIR /workspace/docmind-api/target
RUN java -Djarmode=layertools -jar docmind-api-*.jar extract

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as non-root user (never run containers as root in production)
RUN addgroup --system docmind && adduser --system --ingroup docmind docmind
USER docmind

WORKDIR /app

# Copy layered JAR contents in order of change frequency (most stable → least stable)
# Docker caches each COPY as a separate layer
COPY --from=builder --chown=docmind:docmind /workspace/docmind-api/target/dependencies/ ./
COPY --from=builder --chown=docmind:docmind /workspace/docmind-api/target/spring-boot-loader/ ./
COPY --from=builder --chown=docmind:docmind /workspace/docmind-api/target/snapshot-dependencies/ ./
COPY --from=builder --chown=docmind:docmind /workspace/docmind-api/target/application/ ./

# Expose application port (HTTP) and management port (Actuator)
EXPOSE 8080
EXPOSE 8081

# JVM tuning for containers:
#   - UseContainerSupport: auto-detects CPU/memory limits from cgroup (Docker/k8s)
#   - MaxRAMPercentage: use 75% of container memory for JVM heap
#   - ExitOnOutOfMemoryError: fail fast instead of limping along in OOM state
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Spring Boot layered JARs use JarLauncher (not the app's main class directly)
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

# Health check — Docker will mark the container as unhealthy if this fails 3 times
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1
