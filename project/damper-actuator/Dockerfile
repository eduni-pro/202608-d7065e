# Plain Java, no framework (ADR-0002). Built on the host by `make build` so the image is a
# copy rather than a nine-times-repeated reactor build.
#
# 25-jre, not 21-jre: the reactor compiles with maven.compiler.release 25 (root pom), so the jar
# is class file 69 and a Java 21 runtime cannot load it.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/damper-actuator-1.0.0-SNAPSHOT.jar app.jar
COPY target/lib lib
ENTRYPOINT ["java", "-jar", "app.jar"]
