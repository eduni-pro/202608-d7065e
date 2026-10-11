# Plain Java, no framework (ADR-0002). Built on the host by `make build` so the image is a
# copy rather than a nine-times-repeated reactor build.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/physics-simulator-1.0.0-SNAPSHOT.jar app.jar
COPY target/lib lib
# The fault control surface, CONTROL_PORT's default; the simulator reaches BuildSim on its own.
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
