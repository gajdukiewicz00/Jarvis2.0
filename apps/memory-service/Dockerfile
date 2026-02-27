FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S jarvis && adduser -S -u 10001 -G jarvis jarvis

WORKDIR /app

COPY target/memory-service-*.jar app.jar

RUN chown -R 10001:10001 /app

EXPOSE 8093

ENV JAVA_OPTS="-Xms256m -Xmx512m"

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8093/memory/health || exit 1

USER 10001:10001

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
