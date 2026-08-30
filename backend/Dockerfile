# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S mbsc && adduser -S mbsc -G mbsc
COPY --from=build /app/target/finapp-1.0.0.jar app.jar
RUN mkdir -p /app/data/uploads && chown -R mbsc:mbsc /app
USER mbsc
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
