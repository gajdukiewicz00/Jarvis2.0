#!/bin/bash
set -e

echo "=== Jarvis PostgreSQL Initialization ==="

# Create databases if they don't exist
for dbname in jarvis_db jarvis_assistant_core jarvis_user_profile jarvis_security jarvis_memory; do
    if ! psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL | grep -q 1
SELECT 1 FROM pg_database WHERE datname = '$dbname';
EOSQL
    then
        echo "Creating database: $dbname"
        psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
CREATE DATABASE $dbname;
GRANT ALL PRIVILEGES ON DATABASE $dbname TO jarvis;
EOSQL
    else
        echo "Database $dbname already exists"
    fi
done

echo "=== Database initialization complete ==="
