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
COPY --from=build /workspace/target/pulseink-sandbox-*.jar /app/app.jar
RUN chown -R pulseink:pulseink /app

USER pulseink
EXPOSE 8090

HEALTHCHECK --interval=15s --timeout=5s --retries=10 \
  CMD curl --silent --output /dev/null --max-time 3 http://localhost:8090/channel-api/v1/posts/by-idempotency-key/00000000-0000-0000-0000-000000000000 || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
