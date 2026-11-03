#!/bin/sh
# Runs once on first start of the postgres volume; creates the application schema
# (Hibernate ddl-auto does not create schemas).
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
    CREATE SCHEMA IF NOT EXISTS "${DB_SCHEMA:-reactive}" AUTHORIZATION "$POSTGRES_USER";
SQL
