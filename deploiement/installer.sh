#!/usr/bin/env bash
# Installation de M-FINAPP sur le serveur : a lancer UNE FOIS, depuis le dossier livre.
# Relancer ce script est sans danger : chaque etape verifie d'abord son propre etat.
#
#   1. verifications (Docker, .env, ports)
#   2. adresse par laquelle les utilisateurs ouvriront le site (autorisee dans le serveur, CORS)
#   3. chargement des images de l'application (archive images/)
#   4. base de donnees : creation, puis restauration de la base livree (plan comptable + compte ADMIN)
#   5. compte administrateur : adresse e-mail et NOUVEAU mot de passe (le compte livre est verrouille)
#   6. demarrage de l'application (nginx sur le port 9090 par defaut) et controles
#
# Sans clavier (automatisation) : MBSC_ADRESSE, MBSC_ADMIN_EMAIL, MBSC_ADMIN_PASSWORD.
set -Eeuo pipefail
. "$(dirname "$0")/lib.sh"
trap 'echo "ARRET INATTENDU (ligne $LINENO). Relancez ./installer.sh apres avoir lu le message ci-dessus ; rien n est perdu." >&2' ERR

[ "${1:-}" != "--aide" ] || { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

# ---------------------------------------------------------------------------
titre "1/6  Verifications"
command -v docker >/dev/null || echec "Docker n'est pas installe (voir LISEZ-MOI.md, etape « Preparer le serveur »)."
docker compose version >/dev/null 2>&1 || echec "Le plugin « docker compose » (version 2) est absent."
docker info >/dev/null 2>&1 || echec "Docker ne repond pas : l'utilisateur courant n'a pas le droit de l'utiliser (essayez avec sudo, ou : sudo usermod -aG docker \$USER puis se reconnecter)."
[ -f .env ] || echec "Le fichier .env est absent : il est livre avec ce dossier (secrets du serveur)."
for v in POSTGRES_PASSWORD DB_APP_PASSWORD JWT_SECRET BACKEND_IMAGE FRONTEND_IMAGE; do
  [ -n "$(lire_env "$v")" ] || echec "La variable $v est vide dans .env."
done
[ -f db/base_vierge.dump ] || echec "db/base_vierge.dump est absent : la base livree n'a pas ete copiee."
[ -f db/init/01-role-application.sh ] || echec "db/init/01-role-application.sh est absent."
# Integrite des gros fichiers transferes : verifiee a la PREMIERE installation seulement (une relance apres une
# mise a jour, ou apres suppression de l'archive des images devenue inutile, n'a rien a reverifier).
if [ -f SHA256SUMS ] && [ ! -f .admin-defini ]; then
  info "Controle d'integrite des fichiers transferes (images, base)..."
  grep -E ' \./(images|db)/' SHA256SUMS | sha256sum -c --quiet --ignore-missing \
    || echec "Un fichier livre est abime ou incomplet (transfert interrompu ?) : renvoyez-le depuis l'ordinateur de developpement."
fi
info "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo ?) ; port public : $PORT_HTTP"
if [ -z "$(docker compose ps -q nginx 2>/dev/null)" ] && command -v ss >/dev/null; then
  if ss -ltn "( sport = :$PORT_HTTP )" 2>/dev/null | grep -q LISTEN; then
    echec "Le port $PORT_HTTP est deja utilise sur ce serveur (ss -ltnp | grep :$PORT_HTTP). Choisissez un autre port : HTTP_PORT=... dans .env."
  fi
fi

# ---------------------------------------------------------------------------
titre "2/6  Adresse du site"
adresse="${MBSC_ADRESSE:-$(lire_env ADRESSE_PUBLIQUE)}"
if [ -z "$adresse" ]; then
  defaut=$(hostname -I 2>/dev/null | awk '{print $1}')
  [ -t 0 ] || echec "Pas de clavier : definissez MBSC_ADRESSE (adresse IP ou nom de domaine du serveur)."
  echo "Adresse IP (ou nom de domaine) par laquelle les utilisateurs ouvriront le site."
  echo "Plusieurs adresses possibles, separees par des virgules (ex. 203.0.113.10,erp.exemple.cd)."
  read -r -p "Adresse [$defaut] : " adresse
  adresse="${adresse:-$defaut}"
fi
adresse=$(printf '%s' "$adresse" | tr -d ' ')
[ -n "$adresse" ] || echec "Aucune adresse indiquee."
origines=""
IFS=',' read -r -a liste <<< "$adresse"
for h in "${liste[@]}"; do
  [[ "$h" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || echec "Adresse invalide : « $h » (IPv4 ou nom de domaine, sans http:// ni port)."
  if [ "$PORT_HTTP" = 80 ]; then o="http://$h"; else o="http://$h:$PORT_HTTP"; fi
  origines="${origines:+$origines,}$o"
done
ecrire_env ADRESSE_PUBLIQUE "$adresse"
ecrire_env CORS_ORIGINS "$origines"
info "Site : ${origines//,/  ou  }"

# ---------------------------------------------------------------------------
titre "3/6  Images de l'application"
shopt -s nullglob
for var in BACKEND_IMAGE FRONTEND_IMAGE; do
  image=$(lire_env "$var")
  if docker image inspect "$image" >/dev/null 2>&1; then
    info "$image : deja presente"
  else
    archives=(images/*.tar.gz)
    [ "${#archives[@]}" -gt 0 ] || echec "L'image $image est absente et images/*.tar.gz aussi."
    for a in "${archives[@]}"; do info "docker load : $a"; docker load < "$a" >/dev/null; done
    docker image inspect "$image" >/dev/null 2>&1 || echec "L'image $image n'est pas dans l'archive chargee."
  fi
done

# ---------------------------------------------------------------------------
titre "4/6  Base de donnees"
docker compose up -d db >/dev/null
attendre_service db 180 >/dev/null || echec "La base de donnees ne demarre pas (docker compose logs db)."
attendre_base 180 || echec "La base de donnees ne repond pas (docker compose logs db)."
tables=$(nombre_tables) || echec "Impossible de compter les tables de la base (docker compose logs db)."
if [ "$tables" -eq 0 ]; then
  info "Base neuve : restauration de la base livree (plan comptable, roles, compte administrateur)."
  ./restaurer-base.sh db/base_vierge.dump
  rm -f .admin-defini      # la base vient d'etre (re)creee : le compte ADMIN est verrouille
else
  info "La base contient deja des donnees : restauration ignoree."
fi

# ---------------------------------------------------------------------------
titre "5/6  Compte administrateur"
if [ -f .admin-defini ]; then
  info "Deja defini ($(cat .admin-defini)). Pour le modifier : ./definir-admin.sh"
else
  ./definir-admin.sh
  date '+le %d/%m/%Y a %H:%M' > .admin-defini
  chmod 600 .admin-defini
fi

# ---------------------------------------------------------------------------
titre "6/6  Demarrage"
docker compose up -d
attendre_service backend 600 >/dev/null || echec "Le serveur ne devient pas operationnel (docker compose logs --tail 80 backend)."
attendre_service frontend 120 >/dev/null || echec "Le site ne demarre pas (docker compose logs --tail 50 frontend)."
docker compose restart nginx >/dev/null
attendre_service nginx 60 >/dev/null || echec "nginx ne demarre pas (docker compose logs --tail 50 nginx)."

controle() { # libelle code_attendu code_obtenu
  if [ "$3" = "$2" ]; then info "OK  $1"; else echec "$1 : code $3 au lieu de $2."; fi
}
URL="http://127.0.0.1:${PORT_HTTP}"
for essai in 1 2 3 4 5 6 7 8 9 10; do
  [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$URL/api/health" || true)" = 200 ] && break
  sleep 3
done
controle "serveur en bonne sante (/api/health)" 200 "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$URL/api/health" || true)"
controle "page de connexion" 200 "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$URL/login" || true)"
controle "API protegee sans connexion" 401 "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$URL/api/notes-frais" || true)"
controle "documentation de l'API fermee" 401 "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$URL/api/v3/api-docs" || true)"
controle "mauvais identifiants refuses" 401 "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST "$URL/api/auth/login" -H 'Content-Type: application/json' -d '{"email":"inconnu@exemple.test","motDePasse":"mauvais-mot-de-passe"}' || true)"
base_migree=$(psql_super -c "select 'V' || max(version::int) from flyway_schema_history where version ~ '^[0-9]+\$'")
info "OK  base a jour (derniere migration : $base_migree)"

echo
echo "================================================================"
echo " Installation terminee."
echo " Ouvrez : ${origines//,/  ou  }"
echo " Connexion : $(psql_super -c "select u.email from users u join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id where r.nom = 'ADMIN' limit 1")"
echo " Le trafic circule en HTTP simple : voir LISEZ-MOI.md (rubrique Securite) avant d'ouvrir au public."
echo " Pare-feu : autoriser le port $PORT_HTTP (ex. : sudo ufw allow $PORT_HTTP/tcp)."
echo " Sauvegardes : planifier ./sauvegarder.sh (voir LISEZ-MOI.md)."
echo "================================================================"
