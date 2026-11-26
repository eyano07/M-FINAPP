# ---- Construction ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Exécution ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S pms && adduser -S pms -G pms
COPY --from=build /app/target/hotellerie-1.0.0.jar app.jar
RUN mkdir -p /app/data/uploads /app/data/logs && chown -R pms:pms /app
USER pms
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
