# Quarkus JVM mode (ADR-0002: native image only for the final measurement run).
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/quarkus-app/lib/ lib/
COPY target/quarkus-app/*.jar ./
COPY target/quarkus-app/app/ app/
COPY target/quarkus-app/quarkus/ quarkus/
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
