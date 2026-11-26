# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Runtime stage ----
# Ubuntu plutot qu'Alpine : LibreOffice Calc y fonctionne sans interface (le paquet Alpine plante au
# demarrage). Il convertit le classeur des etats financiers en PDF (bouton PDF, 15/11/2026).
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      libreoffice-calc-nogui fonts-dejavu-core fonts-liberation fonts-crosextra-carlito \
 && rm -rf /var/lib/apt/lists/*
# Memes numeros que l'ancien utilisateur Alpine (uid 100, gid 101) : les volumes deja en place (journal
# d'audit, pieces jointes) lui appartiennent ; un autre numero empecherait le demarrage (audit.log refuse).
# Sous Ubuntu l'uid 100 est celui de _apt, inutile une fois les paquets installes : il est deplace.
RUN usermod -u 42 _apt \
 && groupadd -r -g 101 mbsc && useradd -r -u 100 -g 101 -m mbsc
COPY --from=build /app/target/finapp-1.0.0.jar app.jar
RUN mkdir -p /app/data/uploads /app/data/logs && chown -R mbsc:mbsc /app
USER mbsc
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
