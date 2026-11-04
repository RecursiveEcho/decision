FROM maven:3.9.14-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --system decision && useradd --system --gid decision decision
COPY --from=build /build/target/decision-backend-0.1.0.jar /app/app.jar
USER decision
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70.0","-jar","/app/app.jar"]
