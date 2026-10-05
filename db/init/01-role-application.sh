#!/bin/sh
# Compte PostgreSQL de l'application, SANS privilège de super-utilisateur
# (audit sécurité du 05/10/2026, S-05) : une faille dans l'application ne doit
# pas donner le contrôle du serveur de base (COPY ... PROGRAM, création de rôles).
#
# Exécuté UNE SEULE FOIS par l'image postgres, à l'initialisation d'un volume de
# données vide. Pour une installation existante : db/passer-au-role-application.sh.
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v app_user="${DB_APP_USER:-mbsc_app}" -v app_pass="$DB_APP_PASSWORD" <<'SQL'
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_pass' NOSUPERUSER NOCREATEDB NOCREATEROLE;
SELECT format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I', current_database(), :'app_user') \gexec
-- Flyway crée les tables : l'application en est propriétaire, rien de plus.
GRANT USAGE, CREATE ON SCHEMA public TO :"app_user";
SQL
