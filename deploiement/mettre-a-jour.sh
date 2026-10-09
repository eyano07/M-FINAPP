#!/usr/bin/env bash
# Met l'application a jour avec une NOUVELLE livraison (preparee sur l'ordinateur de
# developpement par deploiement/preparer-livraison.sh --mise-a-jour) copiee dans ce dossier.
# Le .env, la base, les fichiers televerses et les sauvegardes ne sont jamais touches.
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

titre "Chargement des nouvelles images"
shopt -s nullglob
archives=(images/*.tar.gz)
[ "${#archives[@]}" -gt 0 ] || echec "Aucune archive images/*.tar.gz."
for a in "${archives[@]}"; do info "docker load : $a"; docker load < "$a" >/dev/null; done
docker image inspect "$nouveau_backend" >/dev/null 2>&1 || echec "L'image $nouveau_backend n'est pas dans l'archive chargee."
docker image inspect "$nouveau_frontend" >/dev/null 2>&1 || echec "L'image $nouveau_frontend n'est pas dans l'archive chargee."

titre "Redemarrage"
ecrire_env BACKEND_IMAGE_PRECEDENT "$ancien_backend"
ecrire_env FRONTEND_IMAGE_PRECEDENT "$ancien_frontend"
ecrire_env BACKEND_IMAGE "$nouveau_backend"
ecrire_env FRONTEND_IMAGE "$nouveau_frontend"
docker compose up -d
attendre_service backend 600 >/dev/null || echec "Le serveur n'est pas revenu en bonne sante (voir : docker compose logs --tail 80 backend)."
attendre_service nginx 60 >/dev/null || true
docker compose restart nginx >/dev/null   # reprend les adresses des conteneurs recrees

titre "Controles"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "http://127.0.0.1:${PORT_HTTP}/api/health" || true)
[ "$code" = 200 ] || echec "/api/health repond $code au lieu de 200."
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "http://127.0.0.1:${PORT_HTTP}/login" || true)
[ "$code" = 200 ] || echec "La page de connexion repond $code au lieu de 200."
info "migration appliquee : V$(psql_super -c "select max(version::int) from flyway_schema_history where version ~ '^[0-9]+\$'")"
echo
echo "Mise a jour terminee : $nouveau_backend"
echo "Retour arriere vers l'ancienne version (si AUCUNE migration n'a ete appliquee) :"
echo "  mettre dans .env BACKEND_IMAGE=$ancien_backend et FRONTEND_IMAGE=$ancien_frontend, puis docker compose up -d"
echo "Sinon : ./restaurer-base.sh sauvegardes/base_avant-mise-a-jour_*.dump --remplacer (le plus recent), puis les lignes ci-dessus."
