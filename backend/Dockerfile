FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw \
    && chmod +x mvnw
COPY src src
RUN ./mvnw -DskipTests package

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl \
    && addgroup -S pulseink \
    && adduser -S -G pulseink pulseink

WORKDIR /app
COPY --from=build /workspace/target/pulseink-backend-*.jar /app/app.jar
RUN mkdir -p /app/data/knowledge /app/evals/reports \
    && chown -R pulseink:pulseink /app

USER pulseink
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --retries=10 \
  CMD curl --fail --silent --show-error http://localhost:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
