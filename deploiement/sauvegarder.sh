#!/usr/bin/env bash
# Sauvegarde la base de donnees (pg_dump, format custom, verifiee), les fichiers televerses (pieces
# jointes, logo, photos) et le JOURNAL D'AUDIT (jamais supprime). Retention : quotidienne 30 jours,
# mensuelle 12 mois (premiere sauvegarde du mois), annuelle 10 ans (premiere de l'annee) ; copie hors site si
# MBSC_COPIE_HORS_SITE est defini (dossier monte ou destination rsync utilisateur@hote:/chemin).
#
# Usage : ./sauvegarder.sh [--garder JOURS] [--avant-restauration | --avant-mise-a-jour]
# Dossier : ./sauvegardes (ou MBSC_SAUVEGARDES=/autre/chemin), lisible par son proprietaire seulement.
#
# A planifier chaque nuit (crontab -e), par exemple a 2 h du matin :
#   0 2 * * * cd /opt/mbsc && ./sauvegarder.sh >> sauvegardes/journal.log 2>&1
# Une sauvegarde qui reste SUR le serveur ne protege pas d'une panne du serveur : definissez
# MBSC_COPIE_HORS_SITE (voir LISEZ-MOI.md).
set -Eeuo pipefail
. "$(dirname "$0")/lib.sh"
trap 'echo "ARRET INATTENDU (ligne $LINENO) : la sauvegarde est incomplete." >&2' ERR

# Conservation (audit SYSCOHADA du 10/11/2026, C-13 ; AUDCIF art. 24 : dix ans) : quotidiennes 30 jours, mensuelles
# 12 mois, annuelles 10 ans. Les archives figees des clotures d'exercice sont en plus gardees dans la base elle-meme.
DOSSIER="${MBSC_SAUVEGARDES:-sauvegardes}"; JOURS=30; JOURS_MENSUEL=372; JOURS_ANNUEL=3660; ETIQUETTE=""
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

# Journal d'audit (audit.log et ses archives audit-AAAA-MM-JJ.log.gz) : la memoire du systeme depuis sa creation. Copie a part,
# lue dans le volume du service backend. Ces archives (journal-audit_*) ne sont JAMAIS supprimees : la retention ci-dessous ne les
# concerne pas, et aucune commande de ce script ne touche au journal lui-meme.
if [ -n "$(docker compose ps -q backend 2>/dev/null)" ]; then
  tmp_audit=$(mktemp -d)
  docker compose cp backend:/app/data/logs/. "$tmp_audit" >/dev/null 2>&1 || true
  if [ -n "$(find "$tmp_audit" -type f -print -quit)" ]; then
    AUDIT_F="$DOSSIER/journal-audit_${ETIQUETTE}${H}.tar.gz"
    tar -czf "$AUDIT_F" -C "$tmp_audit" .
    chmod 600 "$AUDIT_F"
    printf "Journal d'audit : %s (%s fichier(s), %s) - a conserver, jamais supprime\n" "$AUDIT_F" "$(find "$tmp_audit" -type f | wc -l)" "$(du -h "$AUDIT_F" | cut -f1)"
  else
    avertir "aucun fichier de journal d'audit lu dans le service backend : rien n'a ete copie."
  fi
  rm -rf "$tmp_audit"
fi

# Sauvegardes mensuelle et annuelle : la premiere sauvegarde ordinaire du mois (de l'annee) en est copiee a part.
if [ -z "$ETIQUETTE" ]; then
  for niveau in mensuel annuel; do
    if [ "$niveau" = mensuel ]; then periode=$(date +%Y%m); else periode=$(date +%Y); fi
    mkdir -p "$DOSSIER/$niveau"; chmod 700 "$DOSSIER/$niveau"
    if [ -z "$(find "$DOSSIER/$niveau" -maxdepth 1 -type f -name "base_${periode}*.dump" -print -quit)" ]; then
      cp -p "$BASE_F" "$DOSSIER/$niveau/"
      [ -n "${FICHIERS_F:-}" ] && cp -p "$FICHIERS_F" "$DOSSIER/$niveau/"
      printf 'Sauvegarde %s : %s\n' "$niveau" "$DOSSIER/$niveau/$(basename "$BASE_F")"
    fi
  done
fi

# Retention : les sauvegardes de base et de fichiers plus anciennes que leur duree de conservation sont supprimees (celles
# prises avant une restauration ou une mise a jour comprises : elles ont servi a leur retour arriere). JAMAIS le journal d'audit.
find "$DOSSIER" -maxdepth 1 -type f \( -name 'base_*.dump' -o -name 'fichiers_*.tar.gz' \) -mtime +"$JOURS" -print -delete | sed 's/^/Supprime (ancienne) : /'
[ -d "$DOSSIER/mensuel" ] && find "$DOSSIER/mensuel" -maxdepth 1 -type f \( -name 'base_*.dump' -o -name 'fichiers_*.tar.gz' \) -mtime +"$JOURS_MENSUEL" -print -delete | sed 's/^/Supprime (mensuelle) : /'
[ -d "$DOSSIER/annuel" ] && find "$DOSSIER/annuel" -maxdepth 1 -type f \( -name 'base_*.dump' -o -name 'fichiers_*.tar.gz' \) -mtime +"$JOURS_ANNUEL" -print -delete | sed 's/^/Supprime (annuelle) : /'

# Copie hors site : sans elle, une panne du serveur emporte aussi ses sauvegardes. Rien n'est jamais supprime de la
# destination ; un echec de la copie est signale sans annuler la sauvegarde locale.
if [ -n "${MBSC_COPIE_HORS_SITE:-}" ]; then
  if rsync -a "$DOSSIER"/ "$MBSC_COPIE_HORS_SITE"/; then
    printf 'Copie hors site : %s\n' "$MBSC_COPIE_HORS_SITE"
  else
    avertir "copie hors site vers $MBSC_COPIE_HORS_SITE impossible : la sauvegarde reste seulement sur ce serveur."
  fi
else
  avertir "MBSC_COPIE_HORS_SITE n'est pas defini : les sauvegardes restent seulement sur ce serveur."
fi
printf '%s sauvegarde(s) de base conservee(s) dans %s ; espace libre sur le disque : %s\n' \
  "$(find "$DOSSIER" -maxdepth 1 -type f -name 'base_*.dump' | wc -l)" "$DOSSIER" "$(df -h --output=avail "$DOSSIER" | tail -1 | tr -d ' ')"
