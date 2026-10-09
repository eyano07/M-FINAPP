#!/usr/bin/env bash
# Sauvegarde la base de donnees (pg_dump, format custom, verifiee) et les fichiers televerses
# (pieces jointes, logo, photos), puis supprime les sauvegardes de plus de 14 jours.
#
# Usage : ./sauvegarder.sh [--garder JOURS] [--avant-restauration | --avant-mise-a-jour]
# Dossier : ./sauvegardes (ou MBSC_SAUVEGARDES=/autre/chemin), lisible par son proprietaire seulement.
#
# A planifier chaque nuit (crontab -e), par exemple a 2 h du matin :
#   0 2 * * * cd /opt/mbsc && ./sauvegarder.sh >> sauvegardes/journal.log 2>&1
# Une sauvegarde qui reste SUR le serveur ne protege pas d'une panne du serveur : copiez
# regulierement le dossier sauvegardes/ ailleurs (voir LISEZ-MOI.md).
set -Eeuo pipefail
. "$(dirname "$0")/lib.sh"
trap 'echo "ARRET INATTENDU (ligne $LINENO) : la sauvegarde est incomplete." >&2' ERR

DOSSIER="${MBSC_SAUVEGARDES:-sauvegardes}"; JOURS=14; ETIQUETTE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --garder) shift; JOURS="${1:?--garder attend un nombre de jours}" ;;
    --avant-restauration) ETIQUETTE="avant-restauration_" ;;
    --avant-mise-a-jour)  ETIQUETTE="avant-mise-a-jour_" ;;
    --aide|-h) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echec "Argument inconnu : $1 (voir --aide)" ;;
  esac
  shift
done
case "$JOURS" in ''|*[!0-9]*) echec "--garder attend un nombre entier de jours." ;; esac

etat=$(etat_service db)
[ "$etat" = healthy ] || [ "$etat" = running ] || echec "La base de donnees ne tourne pas : rien n'a ete sauvegarde."
attendre_base 60 || echec "La base de donnees ne repond pas (docker compose logs db)."

mkdir -p "$DOSSIER"; chmod 700 "$DOSSIER"
H=$(date +%Y%m%d_%H%M%S)
BASE_F="$DOSSIER/base_${ETIQUETTE}${H}.dump"
docker compose exec -T db pg_dump -U "$PG_USER" -d "$PG_DB" -Fc > "$BASE_F"
chmod 600 "$BASE_F"
n=$(docker compose exec -T db pg_restore --list < "$BASE_F" | grep -c 'TABLE DATA' || true)
if [ "$n" -lt 1 ]; then rm -f "$BASE_F"; echec "Sauvegarde de la base illisible : supprimee, rien n'est sauvegarde."; fi
printf 'Base : %s (%s tables, %s)\n' "$BASE_F" "$n" "$(du -h "$BASE_F" | cut -f1)"

# Fichiers televerses : lus dans le volume du service backend (copie temporaire, puis archive).
if [ -n "$(docker compose ps -q backend 2>/dev/null)" ]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  docker compose cp backend:/app/data/uploads/. "$tmp" >/dev/null 2>&1 || true
  FICHIERS_F="$DOSSIER/fichiers_${ETIQUETTE}${H}.tar.gz"
  tar -czf "$FICHIERS_F" -C "$tmp" .
  chmod 600 "$FICHIERS_F"
  printf 'Fichiers : %s (%s)\n' "$FICHIERS_F" "$(du -h "$FICHIERS_F" | cut -f1)"
else
  avertir "le service backend n'existe pas : fichiers televerses non sauvegardes."
fi

# Retention : les sauvegardes de plus de JOURS jours sont supprimees (celles prises avant une
# restauration ou une mise a jour comprises : elles ont servi a leur retour arriere).
find "$DOSSIER" -maxdepth 1 -type f \( -name 'base_*.dump' -o -name 'fichiers_*.tar.gz' \) -mtime +"$JOURS" -print -delete | sed 's/^/Supprime (ancienne) : /'
printf '%s sauvegarde(s) de base conservee(s) dans %s ; espace libre sur le disque : %s\n' \
  "$(find "$DOSSIER" -maxdepth 1 -type f -name 'base_*.dump' | wc -l)" "$DOSSIER" "$(df -h --output=avail "$DOSSIER" | tail -1 | tr -d ' ')"
