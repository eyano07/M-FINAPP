#!/usr/bin/env bash
# Met l'application a jour avec une NOUVELLE livraison (preparee sur l'ordinateur de
# developpement par deploiement/preparer-livraison.sh --mise-a-jour) copiee dans ce dossier.
# Le .env, la base, les fichiers televerses, les sauvegardes et le JOURNAL D'AUDIT ne sont jamais supprimes.
#
# Usage : ./mettre-a-jour.sh
# Deroulement : sauvegarde complete -> chargement des nouvelles images -> redemarrage de
# l'application (la base est migree automatiquement au demarrage) -> controles.
# Retour arriere : les images precedentes restent sur le serveur (voir le message final).
# Attention : une mise a jour peut modifier la structure de la base ; revenir a l'ancienne version
# apres cela exige de restaurer la sauvegarde prise juste avant (./restaurer-base.sh ... --remplacer).
set -Eeuo pipefail
. "$(dirname "$0")/lib.sh"
trap 'echo "ARRET INATTENDU (ligne $LINENO). Verifiez avec : docker compose ps  et  docker compose logs --tail 50 backend" >&2' ERR

[ "${1:-}" != "--aide" ] || { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }
[ -f images/version.env ] || echec "images/version.env introuvable : copiez ici le dossier images/ de la nouvelle livraison."
nouveau_backend=$(grep -E '^BACKEND_IMAGE=' images/version.env | cut -d= -f2-)
nouveau_frontend=$(grep -E '^FRONTEND_IMAGE=' images/version.env | cut -d= -f2-)
[ -n "$nouveau_backend" ] && [ -n "$nouveau_frontend" ] || echec "images/version.env incomplet."
ancien_backend=$(lire_env BACKEND_IMAGE); ancien_frontend=$(lire_env FRONTEND_IMAGE)
if [ "$nouveau_backend" = "$ancien_backend" ] && [ "$nouveau_frontend" = "$ancien_frontend" ]; then
  echo "Cette version ($nouveau_backend) est deja installee : rien a faire."
  exit 0
fi

titre "Sauvegarde complete avant la mise a jour"
./sauvegarder.sh --avant-mise-a-jour

titre "Journal d'audit : controle"
# Le journal d'audit (audit.log et archives audit-AAAA-MM-JJ.log.gz) ne se supprime JAMAIS : c'est la memoire du systeme. Il vit dans le
# volume « backend_logs » (/app/data/logs), qui survit aux mises a jour. Les installations anciennes ne declaraient pas ce volume : leur
# journal etait dans le conteneur, detruit quand il est recree. Dans ce cas il est copie HORS du conteneur (sauvegardes/, a conserver)
# puis remis dans le volume avant de demarrer la nouvelle version (etape « Journal d'audit : mise a l'abri », plus bas).
JOURNAL_DANS_CONTENEUR=0
id_ancien=$(docker compose ps -aq backend 2>/dev/null | head -1)
if [ -n "$id_ancien" ]; then
  volume_actuel=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/app/data/logs"}}{{.Name}}{{end}}{{end}}' "$id_ancien" 2>/dev/null || true)
  if [ -n "$volume_actuel" ]; then
    info "journal d'audit deja dans le volume $volume_actuel : rien a deplacer."
  else
    JOURNAL_DANS_CONTENEUR=1
    avertir "le journal d'audit est encore dans le conteneur (ancienne configuration) : il sera copie puis remis dans un volume."
  fi
fi

titre "Chargement des nouvelles images"
shopt -s nullglob
archives=(images/*.tar.gz)
[ "${#archives[@]}" -gt 0 ] || echec "Aucune archive images/*.tar.gz."
for a in "${archives[@]}"; do info "docker load : $a"; docker load < "$a" >/dev/null; done
docker image inspect "$nouveau_backend" >/dev/null 2>&1 || echec "L'image $nouveau_backend n'est pas dans l'archive chargee."
docker image inspect "$nouveau_frontend" >/dev/null 2>&1 || echec "L'image $nouveau_frontend n'est pas dans l'archive chargee."

titre "Journal d'audit : mise a l'abri"
JOURNAL_COPIE=""; JOURNAL_NB=0
if [ "$JOURNAL_DANS_CONTENEUR" = 1 ]; then
  docker compose stop backend >/dev/null      # plus aucune ligne n'est ecrite : la copie est complete
  JOURNAL_COPIE="sauvegardes/journal-audit_avant-volume_$(date +%Y%m%d_%H%M%S)"
  mkdir -p "$JOURNAL_COPIE"; chmod 700 sauvegardes "$JOURNAL_COPIE"
  if ! docker cp "$id_ancien:/app/data/logs/." "$JOURNAL_COPIE/"; then
    docker compose start backend >/dev/null || true
    echec "le journal d'audit n'a pas pu etre copie : la mise a jour est ARRETEE avant toute modification (ancienne version relancee)."
  fi
  JOURNAL_NB=$(find "$JOURNAL_COPIE" -type f | wc -l)
  info "$JOURNAL_NB fichier(s) copie(s) ($(du -sh "$JOURNAL_COPIE" | cut -f1)) dans $JOURNAL_COPIE : A CONSERVER, ne jamais supprimer."
else
  info "rien a copier : le journal est deja dans son volume."
fi

titre "Redemarrage"
ecrire_env BACKEND_IMAGE_PRECEDENT "$ancien_backend"
ecrire_env FRONTEND_IMAGE_PRECEDENT "$ancien_frontend"
ecrire_env BACKEND_IMAGE "$nouveau_backend"
ecrire_env FRONTEND_IMAGE "$nouveau_frontend"
if [ "$JOURNAL_NB" -ge 1 ]; then
  # Cree le volume et le nouveau conteneur SANS le demarrer, y remet le journal, puis le rend a l'utilisateur de l'application
  # (docker cp ecrit en root ; l'application doit pouvoir y AJOUTER des lignes).
  docker compose up --no-start backend >/dev/null
  id_nouveau=$(docker compose ps -aq backend | head -1)
  [ -n "$id_nouveau" ] || echec "le nouveau conteneur du serveur n'a pas pu etre cree (le journal d'audit reste dans $JOURNAL_COPIE)."
  docker cp "$JOURNAL_COPIE/." "$id_nouveau:/app/data/logs/"
  docker run --rm --user 0 --volumes-from "$id_nouveau" --entrypoint sh "$nouveau_backend" -c 'chown -R mbsc:mbsc /app/data/logs'
  nb_volume=$(docker run --rm --volumes-from "$id_nouveau" --entrypoint sh "$nouveau_backend" -c 'find /app/data/logs -type f | wc -l')
  [ "$nb_volume" -ge "$JOURNAL_NB" ] || echec "journal d'audit incomplet dans le volume ($nb_volume fichier(s) sur $JOURNAL_NB) : il reste intact dans $JOURNAL_COPIE."
  info "journal d'audit remis dans le volume : $nb_volume fichier(s)."
fi
docker compose up -d
attendre_service backend 600 >/dev/null || echec "Le serveur n'est pas revenu en bonne sante (voir : docker compose logs --tail 80 backend)."
attendre_service nginx 60 >/dev/null || true
docker compose restart nginx >/dev/null   # reprend les adresses des conteneurs recrees

titre "Controles"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "http://127.0.0.1:${PORT_HTTP}/api/health" || true)
[ "$code" = 200 ] || echec "/api/health repond $code au lieu de 200."
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "http://127.0.0.1:${PORT_HTTP}/login" || true)
[ "$code" = 200 ] || echec "La page de connexion repond $code au lieu de 200."
info "journal d'audit : $(docker compose exec -T backend sh -c 'ls /app/data/logs | wc -l' 2>/dev/null || echo '?') fichier(s) dans le volume, audit.log $(docker compose exec -T backend sh -c 'wc -l < /app/data/logs/audit.log' 2>/dev/null || echo '?') ligne(s)"
info "migration appliquee : V$(psql_super -c "select max(version::int) from flyway_schema_history where version ~ '^[0-9]+\$'")"
echo
echo "Mise a jour terminee : $nouveau_backend"
echo "Retour arriere vers l'ancienne version (si AUCUNE migration n'a ete appliquee) :"
echo "  mettre dans .env BACKEND_IMAGE=$ancien_backend et FRONTEND_IMAGE=$ancien_frontend, puis docker compose up -d"
echo "Sinon : ./restaurer-base.sh sauvegardes/base_avant-mise-a-jour_*.dump --remplacer (le plus recent), puis les lignes ci-dessus."
