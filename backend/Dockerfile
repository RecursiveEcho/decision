# syntax=docker/dockerfile:1
FROM maven:3.9.14-eclipse-temurin-17@sha256:674de0df5a38cfe793a587829a181835f8af3ea1f556be7172e0aa547274bcc9 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package
FROM eclipse-temurin:17-jre-jammy@sha256:2a9eb226abb654c9d1f91b53cf638d20cb3abb726d1b18a67909c29515545469
WORKDIR /app
RUN groupadd --system decision && useradd --system --gid decision decision
COPY --from=build /build/target/decision-backend-0.1.0.jar /app/app.jar
USER decision
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70.0","-jar","/app/app.jar"]
