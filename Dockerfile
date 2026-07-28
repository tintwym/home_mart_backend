# Multi-stage build for Render / Railway / Fly.
# Platform injects PORT; Spring reads it via application.properties (${PORT:8080}).

FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package \
    && cp target/home_mart_backend-0.0.1-SNAPSHOT.jar /app/app.jar

FROM eclipse-temurin:21.0.11_10-jre-jammy
WORKDIR /app

RUN groupadd --system homemart \
    && useradd --system --gid homemart --home-dir /app --shell /usr/sbin/nologin homemart \
    && mkdir -p /app/storage/listings \
    && chown -R homemart:homemart /app

COPY --from=build --chown=homemart:homemart /app/app.jar /app/app.jar

USER homemart

ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
