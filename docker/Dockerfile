# syntax=docker/dockerfile:1
#
# Multi-stage build for the AI-Based Proctored Interview System.
# Build from the REPO ROOT (not this docker/ folder) so both frontend/ and
# src/ are in the build context:
#
#   docker build -f docker/Dockerfile -t proctor-interview:latest .
#
# Stage 1 builds the React exam SPA. Stage 2 builds the Spring Boot JAR,
# which embeds stage 1's output as its static resources (this is how the
# project always ships - one runnable JAR, no separate frontend server).
# Stage 3 is the actual runtime image: just a JRE and the JAR, running as a
# non-root user.

# ------------------------------------------------------------------ stage 1
FROM node:24-alpine AS frontend-build
WORKDIR /workspace

# Install deps first so this layer only rebuilds when package*.json changes.
COPY frontend/package.json frontend/package-lock.json frontend/
RUN cd frontend && npm ci

COPY frontend/ frontend/
# vite.config.js writes build output to ../src/main/resources/static/exam
# (relative to frontend/), so that path must exist under /workspace even
# though only static/exam itself is used - it is overwritten by npm run build.
COPY src/main/resources/static src/main/resources/static
RUN cd frontend && npm run build

# ------------------------------------------------------------------ stage 2
FROM eclipse-temurin:25-jdk-jammy AS backend-build
WORKDIR /workspace

# Resolve dependencies before copying source, so this layer is cached across
# source-only changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Bring in the compiled frontend from stage 1, replacing the placeholder tree.
COPY --from=frontend-build /workspace/src/main/resources/static/exam src/main/resources/static/exam

# Tests are skipped here on purpose: this is a packaging build, not CI. Run
# `mvnw test` / `cd frontend && npx vitest run` separately before building an
# image you intend to ship.
RUN ./mvnw -B -q clean package -DskipTests

# ------------------------------------------------------------------ stage 3
FROM eclipse-temurin:25-jre-jammy AS runtime

# curl only, for the HEALTHCHECK below - kept minimal on purpose.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin appuser
WORKDIR /app
COPY --from=backend-build /workspace/target/*.jar app.jar
RUN chown appuser:appuser app.jar
USER appuser

EXPOSE 8080

# /login is permitAll on the session+CSRF chain (see SecurityConfig), so a
# 200 here means the application context is actually up, not just the JVM.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl -fsS http://localhost:8080/login >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
