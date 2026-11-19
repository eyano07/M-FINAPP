#!/bin/sh
# Prépare la base de M-FINAPP Hôtellerie dans le conteneur de base de données de M-FINAPP (service ponctuel pms-init).
#   - base mbsc_pms, possédée par le rôle pms_app (aucun droit sur la base de M-FINAPP) ;
#   - rôle pms_lecteur : SELECT seulement, sur une liste fermée de tables de référence de M-FINAPP ;
#   - schéma finapp dans mbsc_pms : ces tables, lues par postgres_fdw (aucune écriture possible par ce chemin).
# Rejouable sans risque : chaque étape vérifie l'existant ; les mots de passe sont remis à ceux de l'environnement.
set -eu

: "${PGHOST:=db}"
: "${POSTGRES_DB:=mbsc}"
: "${POSTGRES_USER:=mbsc}"
: "${POSTGRES_PASSWORD:?mot de passe de la base M-FINAPP}"
: "${PMS_DB_PASSWORD:?PMS_DB_PASSWORD manquant dans .env (openssl rand -hex 24)}"
: "${PMS_LECTEUR_PASSWORD:?PMS_LECTEUR_PASSWORD manquant dans .env (openssl rand -hex 24)}"
# Noms des bases (modifiables pour une vérification sur une copie jetable).
: "${FINAPP_DB:=$POSTGRES_DB}"
: "${PMS_DB:=mbsc_pms}"
export PGHOST PGPASSWORD="$POSTGRES_PASSWORD"

ADMIN="psql -v ON_ERROR_STOP=1 -U $POSTGRES_USER"

echo "Attente de la base…"
until pg_isready -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"; do sleep 2; done

# 1. Rôles (mots de passe passés en variables psql, jamais dans le texte SQL).
$ADMIN -d "$FINAPP_DB" -v pw_app="$PMS_DB_PASSWORD" -v pw_lect="$PMS_LECTEUR_PASSWORD" -v base_pms="$PMS_DB" -v base_finapp="$FINAPP_DB" <<'SQL'
SELECT 'CREATE ROLE pms_app LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_app') \gexec
ALTER ROLE pms_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'pw_app';
SELECT 'CREATE ROLE pms_lecteur LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_lecteur') \gexec
ALTER ROLE pms_lecteur LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'pw_lect';
SELECT format('CREATE DATABASE %I OWNER pms_app', :'base_pms') WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'base_pms') \gexec
-- pms_app n'a aucun droit sur les tables de M-FINAPP ; pms_lecteur lit seulement la liste ci-dessous.
SELECT format('GRANT CONNECT ON DATABASE %I TO pms_lecteur', :'base_finapp') \gexec
GRANT USAGE ON SCHEMA public TO pms_lecteur;
GRANT SELECT ON users, roles, user_roles, drh_employes, comptes_ohada, articles, entrepots, clients,
    etablissements_tresorerie, parametres_entreprise, taux_change, taux_tva, stock_niveaux TO pms_lecteur;
SQL

# 2. Lien de lecture dans mbsc_pms (création des objets par l'administrateur, propriété donnée à pms_app).
$ADMIN -d "$PMS_DB" -v pw_lect="$PMS_LECTEUR_PASSWORD" -v hote="$PGHOST" -v base_finapp="$FINAPP_DB" <<'SQL'
CREATE EXTENSION IF NOT EXISTS postgres_fdw;
CREATE EXTENSION IF NOT EXISTS btree_gist;
SELECT format('CREATE SERVER finapp FOREIGN DATA WRAPPER postgres_fdw OPTIONS (host %L, port %L, dbname %L)', :'hote', '5432', :'base_finapp')
WHERE NOT EXISTS (SELECT 1 FROM pg_foreign_server WHERE srvname = 'finapp') \gexec
GRANT USAGE ON FOREIGN SERVER finapp TO pms_app;
CREATE USER MAPPING IF NOT EXISTS FOR pms_app SERVER finapp OPTIONS (user 'pms_lecteur', password 'provisoire');
ALTER USER MAPPING FOR pms_app SERVER finapp OPTIONS (SET password :'pw_lect');
CREATE USER MAPPING IF NOT EXISTS FOR CURRENT_USER SERVER finapp OPTIONS (user 'pms_lecteur', password 'provisoire');
ALTER USER MAPPING FOR CURRENT_USER SERVER finapp OPTIONS (SET password :'pw_lect');
DROP SCHEMA IF EXISTS finapp CASCADE;
CREATE SCHEMA finapp AUTHORIZATION pms_app;
IMPORT FOREIGN SCHEMA public LIMIT TO (users, roles, user_roles, drh_employes, comptes_ohada, articles, entrepots, clients,
    etablissements_tresorerie, parametres_entreprise, taux_change, taux_tva, stock_niveaux)
    FROM SERVER finapp INTO finapp;
SQL
# Les tables étrangères appartiennent à l'administrateur : lecture pour pms_app.
$ADMIN -d "$PMS_DB" -c "GRANT USAGE ON SCHEMA finapp TO pms_app; GRANT SELECT ON ALL TABLES IN SCHEMA finapp TO pms_app;"

echo "Base $PMS_DB prête (lien de lecture vers M-FINAPP : schéma finapp)."
