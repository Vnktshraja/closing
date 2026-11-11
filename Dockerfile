# Multi-stage build for Spring Boot Closing Application
#
# Usage:
#   Development:  docker build -t closing-app:dev .
#                 docker run -p 8089:8089 --env-file dev.env closing-app:dev
#
#   Production:   docker build -t closing-app:prod .
#                 docker run -p 8089:8089 -e SPRING_PROFILES_ACTIVE=prod closing-app:prod
#

# Stage 1: Build stage
FROM gradle:jdk25 AS builder

WORKDIR /app

# Copy gradle configuration files
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

# Ensure gradlew executable permission
RUN chmod +x gradlew

# Download dependencies in a separate layer for Docker cache reuse (quiet mode)
RUN gradle dependencies --no-daemon -q > /dev/null 2>&1 || true

# Copy source code
COPY src src

# Build argument for Gradle build flags (e.g., -Prelease for release builds)
ARG GRADLE_FLAGS=""

# Build the application JAR
RUN gradle bootJar -x test --no-daemon $GRADLE_FLAGS

# Stage 2: Runtime stage
FROM eclipse-temurin:25-jre-alpine

LABEL maintainer="venkatesh"
LABEL description="Closing Spring Boot Application"
LABEL version="1.0"

WORKDIR /app

# Install curl for health checks
RUN apk add --no-cache curl

# Create non-root user for security
RUN addgroup -g 1001 appuser && \
    adduser -D -u 1001 -G appuser appuser

# Copy built JAR from builder stage
COPY --from=builder /app/build/libs/closing-*.jar ./app.jar

# Expose port
EXPOSE 8089

# Set user
USER appuser

# Environment variable for Spring profile (default: dev)
ENV SPRING_PROFILES_ACTIVE=dev
ENV TZ=Asia/Kolkata
ENV SERVER_PORT=8089

# Run application
ENTRYPOINT ["java", "-Duser.timezone=Asia/Kolkata", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-XX:InitialRAMPercentage=25.0", "-XX:MinRAMPercentage=12.5", "-XX:+UseStringDeduplication", "-XX:+DoEscapeAnalysis", "-jar", "app.jar"]
