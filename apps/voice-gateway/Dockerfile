# Jarvis 2.0 - Voice Gateway Dockerfile
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Jarvis Team"
LABEL description="Jarvis 2.0 Voice Gateway"

RUN apk add --no-cache curl espeak-ng

WORKDIR /app

RUN addgroup -g 1000 jarvis && \
    adduser -u 1000 -G jarvis -s /bin/sh -D jarvis

COPY apps/voice-gateway/target/*.jar app.jar

# Copy repo-local speech models if present
COPY --chown=jarvis:jarvis models/ /app/models/

RUN chown -R jarvis:jarvis /app

USER jarvis

EXPOSE 8081

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8081/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
