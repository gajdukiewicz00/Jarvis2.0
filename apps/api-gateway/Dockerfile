# =============================================================================
# Jarvis 2.0 - API Gateway Dockerfile
# =============================================================================
# Использует предварительно собранный JAR файл
# Сначала запусти: mvn clean package -DskipTests
# =============================================================================

FROM eclipse-temurin:21-jre-alpine

# Метаданные
LABEL maintainer="Jarvis Team"
LABEL description="Jarvis 2.0 API Gateway"
LABEL version="2.0.0"

# Добавляем curl для healthcheck
RUN apk add --no-cache curl

WORKDIR /app

# Создаём non-root пользователя
RUN addgroup -g 1000 jarvis && \
  adduser -u 1000 -G jarvis -s /bin/sh -D jarvis

# Копируем уже собранный JAR
COPY target/*.jar app.jar

# Меняем владельца
RUN chown -R jarvis:jarvis /app

USER jarvis

# Expose port
EXPOSE 8080

# JVM options для контейнера
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# Healthcheck
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
