# ====================================================================
# OmniFlow Multi-Stage Production Dockerfile
# Hardened, non-root user (UID 10001), minimal attack surface
# ====================================================================

# Stage 1: Build stage
FROM eclipse-adoptium:17-jdk-alpine AS builder
WORKDIR /workspace

# Cache dependencies
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Build application
COPY src src
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Hardened Runtime stage
FROM eclipse-adoptium:17-jre-alpine AS runtime

# Create non-root group and user
RUN addgroup -g 10001 -S omniflow && \
    adduser -u 10001 -S omniflow -G omniflow

WORKDIR /app

# Copy packaged jar from builder
COPY --from=builder /workspace/target/omniflow-core-*.jar /app/app.jar

# Enforce non-root ownership and execution
RUN chown -R omniflow:omniflow /app
USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/app/app.jar"]
