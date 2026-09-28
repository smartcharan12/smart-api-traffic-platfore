# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B package -DskipTests && cp target/demo-service-*.jar /build/app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 demo
USER demo
WORKDIR /app
COPY --from=build /build/app.jar app.jar
# Which service and port this container is: set per container in docker-compose.yml.
ENV DEMO_SERVICE_NAME=orders-service SERVER_PORT=9001 JAVA_OPTS="-Xmx128m -XX:+UseSerialGC"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fs "http://localhost:${SERVER_PORT}/health" > /dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
