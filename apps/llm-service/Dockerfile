# Jarvis 2.0 - LLM Service Dockerfile
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Jarvis Team"
LABEL description="Jarvis 2.0 LLM Service"

RUN apk add --no-cache curl bash

WORKDIR /app

RUN addgroup -g 1000 jarvis && \
    adduser -u 1000 -G jarvis -s /bin/sh -D jarvis

COPY target/*.jar app.jar

RUN chown -R jarvis:jarvis /app

USER jarvis

EXPOSE 8091

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8091/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
