# syntax=docker/dockerfile:1
# Multi-stage build: a JDK image compiles the jar; the runtime image contains only a JRE and the jar.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
# Dependencies first: this layer is cached until pom.xml changes, so code edits rebuild fast.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src/ src/
# Tests run in CI (they need PostgreSQL/Redis); the image build only packages.
RUN ./mvnw -q -B package -DskipTests && cp target/gateway-*.jar /build/app.jar

FROM eclipse-temurin:21-jre
# curl for the container health check; nothing else.
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
# Never run as root inside the container.
RUN useradd --system --uid 10001 gateway
USER gateway
WORKDIR /app
COPY --from=build /build/app.jar app.jar
EXPOSE 8080
# MaxRAMPercentage: size the heap from the container's memory limit, not the host's.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
  CMD curl -fs http://localhost:8080/actuator/health > /dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
