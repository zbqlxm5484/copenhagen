FROM docker.io/library/eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew :apps:core-service:bootJar --no-daemon

FROM docker.io/library/eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=builder --chown=app:app /workspace/apps/core-service/build/libs/app.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
