# HikariCP Connection Pool Configuration

This document explains the shared HikariCP configuration used across all Jarvis microservices that connect to PostgreSQL.

## Overview

All database-using services (`assistant-core`, `planner-service`, `life-tracker`, `user-profile`) import a shared HikariCP configuration file to ensure consistent connection pool settings and prevent common issues like:

- Connection thread starvation
- Connection timeouts
- Stale/closed connections
- Validation failures
- "This connection has been closed" errors

## Configuration File

**Location**: `/apps/shared-config/application-hikari.yml`

Services import this configuration using:
```yaml
spring:
  config:
    import: optional:file:../shared-config/application-hikari.yml
```

## Settings Explained

### Pool Size

```yaml
maximum-pool-size: 5
minimum-idle: 2
```

- **Why small?** With 6 microservices × 5 connections = ~30 total connections, well within PostgreSQL's `max_connections: 100` limit
- **Why not larger?** Prevents overwhelming PostgreSQL and reduces resource usage
- **When to increase**: If you see "Connection is not available, request timed out" errors under heavy load, increase to 8-10 per service

### Connection Timeouts

```yaml
connection-timeout: 30000  # 30 seconds
```

- Maximum time to wait for a connection from the pool before throwing exception
- 30 seconds is reasonable for most operations
- **Increase** if you have long-running transactions

### Idle Connection Management

```yaml
idle-timeout: 600000  # 10 minutes
```

- Connections idle for 10 minutes are closed to free resources
- Helps prevent connection leaks
- **Reduce** to 5 minutes (300000) if you want more aggressive cleanup

### Connection Lifetime

```yaml
max-lifetime: 1200000  # 20 minutes
```

- **Critical setting!** Forces connection refresh before PostgreSQL closes it
- PostgreSQL default idle connection timeout is typically 30+ minutes
- Setting to 20 minutes ensures we reconnect before server-side timeout
- **This prevents "This connection has been closed" errors**

### Keepalive

```yaml
keepalive-time: 300000  # 5 minutes
```

- Test idle connections every 5 minutes to keep them alive
- Prevents silent connection closure by PostgreSQL
- Works in conjunction with `max-lifetime`

### Connection Validation

```yaml
validation-timeout: 5000  # 5 seconds
connection-test-query: SELECT 1
```

- Quick health check to ensure connection is alive before use
- 5 seconds is sufficient for simple queries
- `SELECT 1` is lightweight and works on all PostgreSQL versions

### Leak Detection

```yaml
leak-detection-threshold: 60000  # 60 seconds
```

- Warns if a connection is held longer than 60 seconds
- Helps identify connection leaks in code
- Logged at WARN level

### Initialization

```yaml
initialization-fail-timeout: -1
```

- Allow service to start even if database is temporarily unavailable
- Critical for Docker Compose startup where services may start before PostgreSQL is ready
- Set to positive value (e.g., 30000) in production if you want startup to fail fast on database issues

## Troubleshooting

### Still Seeing Connection Timeouts?

1. **Check active connections**:
   ```bash
   docker exec -it jarvis20-postgres-1 psql -U jarvis -d jarvis -c \
     "SELECT count(*) FROM pg_stat_activity;"
   ```

2. **If near 100**: Increase PostgreSQL `max_connections` in `postgres.conf`
3. **If low (<30)**: Your services might have blocking queries - check for long-running transactions

### "Thread starvation or clock leap detected"

This warning means HikariCP detected the thread pool is blocked. Causes:
1. **Database too slow**: Check PostgreSQL logs for slow queries
2. **Not enough connections**: Increase `maximum-pool-size`
3. **Application holding connections**: Add proper `@Transactional` boundaries

### "Connection is not available"

Means all connections in pool are busy. Solutions:
1. **Increase pool size**: From 5 to 8-10 per service
2. **Reduce connection hold time**: Ensure transactions are short
3. **Increase connection timeout**: From 30s to 60s

### Autovacuum Warnings

If you see "autovacuum worker took too long to start":
1. Check `postgres.conf` settings (already optimized)
2. Reduce connection pool sizes to free resources for autovacuum
3. Adjust `autovacuum_naptime` to run more/less frequently

## Monitoring

### View Current Connections

```bash
# Total connections
docker exec -it jarvis20-postgres-1 psql -U jarvis -d jarvis -c \
  "SELECT count(*), state FROM pg_stat_activity GROUP BY state;"

# Connections by database
docker exec -it jarvis20-postgres-1 psql -U jarvis -d jarvis -c \
  "SELECT datname, count(*) FROM pg_stat_activity GROUP BY datname;"

# Long-running queries
docker exec -it jarvis20-postgres-1 psql -U jarvis -d jarvis -c \
  "SELECT pid, now() - pg_stat_activity.query_start AS duration, query 
   FROM pg_stat_activity 
   WHERE state != 'idle' 
   ORDER BY duration DESC;"
```

### HikariCP Metrics

Add to your service's `application.yml`:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics
  metrics:
    export:
      simple:
        enabled: true
```

Then access: `http://localhost:<port>/actuator/metrics/hikaricp.*`

## Best Practices

1. **Keep transactions short**: Long transactions hold connections
2. **Use proper @Transactional boundaries**: Don't span HTTP calls or file I/O in transactions
3. **Close resources**: Always close JDBC `Statement`, `ResultSet` in try-with-resources
4. **Monitor pool usage**: Watch for "connection not available" warnings
5. **Test under load**: Simulate concurrent users to find optimal pool size

## Related Files

- [docker/postgres/postgres.conf](file:///home/kwaqa/IdeaProjects/Jarvis2.0/docker/postgres/postgres.conf) - PostgreSQL configuration
- [docker-compose.yml](file:///home/kwaqa/IdeaProjects/Jarvis2.0/docker-compose.yml) - PostgreSQL resource limits
- [apps/shared-config/application-hikari.yml](file:///home/kwaqa/IdeaProjects/Jarvis2.0/apps/shared-config/application-hikari.yml) - The shared configuration file

##PostgreSQL Configuration

See `docker/postgres/postgres.conf` for PostgreSQL-side settings that complement HikariCP configuration:

- `max_connections: 100` - Total connection limit
- `autovacuum_max_workers: 4` - Handles 6 databases efficiently
- `autovacuum_naptime: 30s` - More frequent vacuum runs
- `work_mem: 8MB` - Reduced to handle more concurrent queries
- `statement_timeout: 300000` - Prevents runaway queries (5 minutes)

## Further Reading

- [HikariCP GitHub](https://github.com/brettwooldridge/HikariCP)
- [PostgreSQL Connection Pooling Best Practices](https://www.postgresql.org/docs/current/runtime-config-connection.html)
- [About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
