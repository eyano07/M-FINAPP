#!/usr/bin/env bash
# Restaure une sauvegarde (format « custom » de pg_dump : fichiers .dump) dans la base de
# l'application. Les tables appartiennent ensuite au compte PostgreSQL de l'application
# (non super-utilisateur), comme l'attend le serveur.
#
# Usage : ./restaurer-base.sh FICHIER.dump             base VIDE seulement (installation)
#         ./restaurer-base.sh FICHIER.dump --remplacer  remplace TOUT le contenu actuel de la base
#                                                       (sauvegarde de securite prise d'abord ;
#                                                       l'application est arretee pendant l'operation)
set -Eeuo pipefail
. "$(dirname "$0")/lib.sh"
trap 'echo "ARRET INATTENDU (ligne $LINENO) : voir le message ci-dessus." >&2' ERR

FICHIER="${1:-}"; REMPLACER=0
[ "${2:-}" != "--remplacer" ] || REMPLACER=1
[ -n "$FICHIER" ] && [ "$FICHIER" != "--aide" ] || { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }
[ -f "$FICHIER" ] || echec "Fichier introuvable : $FICHIER"

nb=$(docker compose exec -T db pg_restore --list < "$FICHIER" 2>/dev/null | grep -c 'TABLE DATA' || true)
[ "$nb" -ge 1 ] || echec "$FICHIER n'est pas une sauvegarde lisible (format custom de pg_dump attendu)."
info "Sauvegarde lisible : $nb tables de donnees."

etat=$(etat_service db)
[ "$etat" = healthy ] || [ "$etat" = running ] || echec "La base de donnees ne tourne pas (docker compose up -d db)."
attendre_base 60 || echec "La base de donnees ne repond pas (docker compose logs db)."
psql_super -c "select 1 from pg_roles where rolname = '$APP_USER'" | grep -q 1 \
  || echec "Le compte PostgreSQL « $APP_USER » n'existe pas (cree par db/init sur un volume de donnees NEUF)."

tables=$(nombre_tables)
if [ "$tables" -gt 0 ]; then
  [ "$REMPLACER" -eq 1 ] || echec "La base contient deja $tables tables : restauration refusee. Pour la REMPLACER : ./restaurer-base.sh $FICHIER --remplacer"
  titre "Remplacement de la base"
  if [ -x ./sauvegarder.sh ]; then ./sauvegarder.sh --avant-restauration; fi
  docker compose stop nginx frontend backend >/dev/null 2>&1 || true
  psql_super -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = '$PG_DB' and pid <> pg_backend_pid()" >/dev/null
  psql_super -c "SET client_min_messages = warning; DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT USAGE, CREATE ON SCHEMA public TO \"$APP_USER\";"
fi

titre "Restauration"
# --role : les objets sont crees (donc possedes) par le compte de l'application. --no-owner / --no-acl :
# la sauvegarde ne depend d'aucun compte du serveur d'origine. Le commentaire de l'extension plpgsql
# (appartient a postgres) est ecarte de la liste : il provoquerait une erreur sans importance.
docker compose exec -T db pg_restore -l < "$FICHIER" | grep -v -E 'COMMENT - EXTENSION' > /tmp/mbsc_liste_restauration.txt
docker compose exec -T db sh -c 'cat > /tmp/liste.txt' < /tmp/mbsc_liste_restauration.txt
rm -f /tmp/mbsc_liste_restauration.txt
docker compose exec -T db sh -c "cat > /tmp/base.dump" < "$FICHIER"
if ! docker compose exec -T db pg_restore -U "$PG_USER" -d "$PG_DB" --no-owner --no-acl --role="$APP_USER" --exit-on-error -L /tmp/liste.txt /tmp/base.dump; then
  docker compose exec -T db rm -f /tmp/base.dump /tmp/liste.txt
  echec "La restauration a echoue : la base est dans un etat incomplet. Relancez avec --remplacer apres avoir corrige la cause."
fi
docker compose exec -T db rm -f /tmp/base.dump /tmp/liste.txt
psql_super -c "ANALYZE"

titre "Controle"
psql_super -c "select 'tables : ' || count(*) from pg_tables where schemaname = 'public'"
psql_super -c "select 'tables appartenant a ' || tableowner || ' : ' || count(*) from pg_tables where schemaname = 'public' group by tableowner"
psql_super -c "select 'derniere migration appliquee : V' || max(version::int) from flyway_schema_history where version ~ '^[0-9]+\$'"
if [ "$REMPLACER" -eq 1 ]; then
  info "Application arretee pendant la restauration : docker compose up -d pour la relancer."
fi
echo "Restauration terminee."
