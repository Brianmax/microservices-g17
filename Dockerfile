FROM maven:3.9.11-eclipse-temurin-21 AS build

ARG MODULE
WORKDIR /workspace

COPY pom.xml ./
COPY identity-service/pom.xml identity-service/pom.xml
COPY customer-service/pom.xml customer-service/pom.xml
COPY banking-service/pom.xml banking-service/pom.xml
COPY transfer-service/pom.xml transfer-service/pom.xml
COPY exchange-rate-service/pom.xml exchange-rate-service/pom.xml
RUN mvn -B -ntp -pl "${MODULE}" -am dependency:go-offline

COPY . .
RUN mvn -B -ntp -pl "${MODULE}" -am verify

FROM eclipse-temurin:21-jre-alpine

ARG MODULE
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build "/workspace/${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar" app.jar
USER spring:spring
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
