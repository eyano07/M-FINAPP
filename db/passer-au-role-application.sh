#!/usr/bin/env bash
# Bascule une installation EXISTANTE sur le compte PostgreSQL applicatif non
# super-utilisateur (audit sécurité du 05/10/2026, S-05) :
#   1. crée le rôle DB_APP_USER (mot de passe DB_APP_PASSWORD du .env) s'il manque ;
#   2. lui donne la connexion à la base et la création dans le schéma public ;
#   3. lui transfère la propriété des tables et séquences (Flyway doit pouvoir
#      les modifier).
# Le compte POSTGRES_USER reste super-utilisateur pour les sauvegardes et la
# maintenance. Idempotent. Usage : db/passer-au-role-application.sh [base]
# (base par défaut : POSTGRES_DB). Retour arrière : réaffecter les tables à
# POSTGRES_USER et remettre son compte dans SPRING_DATASOURCE_* .
set -euo pipefail
cd "$(dirname "$0")/.."
lire() { grep -E "^$1=" .env | tail -1 | cut -d= -f2-; }
APP_USER=$(lire DB_APP_USER); APP_USER=${APP_USER:-mbsc_app}
APP_PASS=$(lire DB_APP_PASSWORD)
[ -n "$APP_PASS" ] || { echo "DB_APP_PASSWORD absent du .env" >&2; exit 1; }
PG_USER=$(lire POSTGRES_USER); PG_USER=${PG_USER:-mbsc}
BASE=${1:-$(lire POSTGRES_DB)}; BASE=${BASE:-mbsc}
docker compose exec -T db psql -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$BASE" \
  -v app_user="$APP_USER" -v app_pass="$APP_PASS" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE', :'app_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', :'app_user', :'app_pass') \gexec
SELECT format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I', current_database(), :'app_user') \gexec
SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'app_user') \gexec
SELECT format('ALTER TABLE public.%I OWNER TO %I', tablename, :'app_user')
FROM pg_tables WHERE schemaname = 'public' \gexec
SELECT format('ALTER SEQUENCE public.%I OWNER TO %I', sequencename, :'app_user')
FROM pg_sequences WHERE schemaname = 'public' \gexec
SQL
echo "Base $BASE : tables et séquences confiées à $APP_USER."
