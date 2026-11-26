#!/usr/bin/env bash
# Prepare, sur l'ordinateur de developpement, le dossier a envoyer au serveur (VPS) :
#   - les images de l'application (backend + frontend) construites a partir du code actuel,
#   - la base « vierge » : plan comptable et table des roles + le compte ADMIN, dont le mot de
#     passe est VERROUILLE dans la copie livree (il est redefini a l'installation : l'ancien
#     mot de passe par defaut, publie avec le depot, ne doit jamais arriver en production),
#   - la configuration de production et le .env (mots de passe et cle JWT generes au hasard),
#   - les scripts d'installation, de sauvegarde et de mise a jour, et le guide LISEZ-MOI.md.
#
# Usage (a la racine du projet, systeme local demarre) :
#   deploiement/preparer-livraison.sh                    premiere installation -> livraison/
#   deploiement/preparer-livraison.sh --mise-a-jour      nouvelle version seulement -> livraison-maj/
#                                                         (images + scripts : ni .env, ni base)
# Options : --sans-controle-vierge  ne verifie pas que la base locale ne contient aucune donnee
#                                   (A NE FAIRE QUE SI VOUS VOULEZ EXPEDIER CES DONNEES)
#           --dossier CHEMIN        autre dossier de sortie
# Le dossier produit contient des secrets et des donnees : il est exclu de git et ne doit jamais
# etre publie ni commite.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
trap 'echo "ARRET INATTENDU (ligne $LINENO) : la livraison est incomplete, ne l'"'"'envoyez pas." >&2' ERR

MAJ=0; CONTROLE=1; DEST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --mise-a-jour) MAJ=1 ;;
    --sans-controle-vierge) CONTROLE=0 ;;
    --dossier) shift; DEST="${1:?--dossier attend un chemin}" ;;
    --aide|-h|--help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Argument inconnu : $1 (voir --aide)" >&2; exit 2 ;;
  esac
  shift
done
[ -n "$DEST" ] || { if [ "$MAJ" -eq 1 ]; then DEST=livraison-maj; else DEST=livraison; fi; }

lire_env_local() { { grep -E "^$1=" .env 2>/dev/null || true; } | tail -1 | cut -d= -f2-; }
PGUSER_=$(lire_env_local POSTGRES_USER); PGUSER_=${PGUSER_:-mbsc}
BASE=$(lire_env_local POSTGRES_DB);      BASE=${BASE:-mbsc}
titre() { printf '\n== %s\n' "$*"; }
echec() { printf '\nECHEC : %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null || echec "Docker est requis."
command -v openssl >/dev/null || echec "openssl est requis (generation des secrets)."
psql_local() { docker compose exec -T db psql -U "$PGUSER_" -d "${BASE_CIBLE:-$BASE}" -v ON_ERROR_STOP=1 -q -At "$@"; }

# ---------------------------------------------------------------------------
if [ "$MAJ" -eq 0 ]; then
  titre "Controle de la base locale"
  docker compose ps --status running --services 2>/dev/null | grep -qx db \
    || echec "La base locale ne tourne pas (docker compose up -d db)."
  if [ "$CONTROLE" -eq 1 ]; then
    non_vides=$(psql_local -c "select coalesce(string_agg(t || '=' || n, ' ' order by t), '') from (select tablename t, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from public.%I', tablename), false, true, '')))[1]::text::bigint n from pg_tables where schemaname = 'public' and tablename not in ('comptes_ohada', 'users', 'user_roles', 'roles', 'flyway_schema_history')) x where n > 0")
    [ -z "$non_vides" ] || echec "La base locale contient des donnees ($non_vides). Videz-la d'abord (./purge.sh production --oui) : la livraison ne doit contenir que le plan comptable et le compte ADMIN."
    autres=$(psql_local -c "select count(*) from users u where not exists (select 1 from user_roles ur join roles r on r.id = ur.role_id where ur.user_id = u.id and r.nom = 'ADMIN')")
    [ "$autres" = 0 ] || echec "La base locale contient $autres utilisateur(s) qui ne sont pas ADMIN."
    echo "Base locale vierge : $(psql_local -c 'select count(*) from comptes_ohada') comptes comptables, $(psql_local -c 'select count(*) from users') utilisateur(s) (ADMIN)."
  else
    echo "ATTENTION : controle « base vierge » desactive."
  fi
fi

# ---------------------------------------------------------------------------
titre "Version"
COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo sans-git)
MODIFIE=""; [ -z "$(git status --porcelain 2>/dev/null)" ] || MODIFIE="-modifie"
VERSION="$(date +%Y%m%d-%H%M)-${COMMIT}${MODIFIE}"
IMG_BACKEND="mbsc/finapp-backend:${VERSION}"
IMG_FRONTEND="mbsc/finapp-frontend:${VERSION}"
echo "Version : $VERSION"
[ -z "$MODIFIE" ] || echo "ATTENTION : des fichiers du depot ne sont pas commites ; la livraison contient le code ACTUEL de l'arborescence."

mkdir -p "$DEST/images" "$DEST/db" "$DEST/nginx"
chmod 700 "$DEST"

# ---------------------------------------------------------------------------
titre "Images (construction a partir du code actuel)"
docker build -q -t "$IMG_BACKEND" backend >/dev/null
echo "construite : $IMG_BACKEND"
docker build -q --network host -t "$IMG_FRONTEND" frontend-web >/dev/null
echo "construite : $IMG_FRONTEND"
rm -f "$DEST"/images/*.tar.gz
docker save "$IMG_BACKEND" "$IMG_FRONTEND" | gzip > "$DEST/images/finapp-${VERSION}.tar.gz"
printf 'BACKEND_IMAGE=%s\nFRONTEND_IMAGE=%s\n' "$IMG_BACKEND" "$IMG_FRONTEND" > "$DEST/images/version.env"
echo "archive : $DEST/images/finapp-${VERSION}.tar.gz ($(du -h "$DEST/images/finapp-${VERSION}.tar.gz" | cut -f1))"

# ---------------------------------------------------------------------------
if [ "$MAJ" -eq 0 ]; then
  titre "Base livree (copie jetable, mot de passe de l'administrateur verrouille)"
  COPIE="mbsc_livraison_tmp"
  docker compose exec -T db sh -c "dropdb -U '$PGUSER_' --if-exists '$COPIE' && createdb -U '$PGUSER_' '$COPIE' && pg_dump -U '$PGUSER_' -d '$BASE' --no-owner --no-acl | psql -q -v ON_ERROR_STOP=1 -U '$PGUSER_' -d '$COPIE' > /dev/null"
  BASE_CIBLE="$COPIE" psql_local <<'SQL'
-- Le mot de passe actuel n'est jamais livre : remplace par le hache bcrypt d'un secret aleatoire
-- que personne ne connait (l'installation en definit un nouveau). L'extension ne sert que le
-- temps de ce calcul, elle est retiree avant la sauvegarde.
CREATE EXTENSION pgcrypto;
UPDATE users SET mot_de_passe = crypt(encode(gen_random_bytes(32), 'hex'), gen_salt('bf', 10)),
                 version_jetons = version_jetons + 1,
                 photo_chemin_stockage = NULL, photo_type_mime = NULL;
DROP EXTENSION pgcrypto;
SQL
  docker compose exec -T db pg_dump -U "$PGUSER_" -d "$COPIE" -Fc --no-owner --no-acl > "$DEST/db/base_vierge.dump"
  chmod 600 "$DEST/db/base_vierge.dump"
  docker compose exec -T db dropdb -U "$PGUSER_" --if-exists "$COPIE"
  n=$(docker compose exec -T db pg_restore --list < "$DEST/db/base_vierge.dump" | grep -c 'TABLE DATA' || true)
  [ "$n" -ge 1 ] || echec "La base livree est illisible."
  if docker compose exec -T db pg_restore --list < "$DEST/db/base_vierge.dump" | grep -qi 'pgcrypto'; then
    echec "La base livree contient encore l'extension pgcrypto."
  fi
  echo "base livree : $DEST/db/base_vierge.dump ($(du -h "$DEST/db/base_vierge.dump" | cut -f1), $n tables)"
fi

# ---------------------------------------------------------------------------
titre "Configuration, scripts et guide"
cp docker-compose.prod.yml docker-compose.tls.yml docker-compose.limites.yml "$DEST/"
cp nginx/nginx.conf nginx/nginx.prod.conf "$DEST/nginx/"
if [ "$MAJ" -eq 0 ]; then
  mkdir -p "$DEST/db/init"
  cp db/init/01-role-application.sh "$DEST/db/init/"
fi
cp deploiement/lib.sh deploiement/installer.sh deploiement/definir-admin.sh deploiement/restaurer-base.sh \
   deploiement/sauvegarder.sh deploiement/mettre-a-jour.sh deploiement/LISEZ-MOI.md "$DEST/"
chmod +x "$DEST"/*.sh "$DEST"/db/init/*.sh 2>/dev/null || true

if [ "$MAJ" -eq 0 ]; then
  if [ -f "$DEST/.env" ]; then
    echo ".env conserve (deja present : ses secrets sont ceux du serveur, ils ne sont pas regeneres)."
    ecrire() { :; }
  else
    alea() { openssl rand -base64 96 | tr -dc 'A-Za-z0-9' | cut -c1-40; }
    umask 077
    cat > "$DEST/.env" <<EOF
# M-FINAPP - configuration du serveur (genere le $(date '+%d/%m/%Y a %H:%M')). CONFIDENTIEL :
# ne jamais publier, ne jamais commiter. Conservez-en une copie en lieu sur (gestionnaire de mots
# de passe) : elle contient les acces a la base de donnees et la cle des sessions.
COMPOSE_FILE=docker-compose.prod.yml
COMPOSE_PROJECT_NAME=mbsc
# Prefixe des noms de conteneurs. Pour une SECONDE instance sur un serveur qui en heberge deja une
# (instance de test a cote de la production) : changer COMPOSE_PROJECT_NAME et CONTENEURS (ex. mfinapp-test)
# et plafonner ses ressources : COMPOSE_FILE=docker-compose.prod.yml:docker-compose.limites.yml
CONTENEURS=mbsc

# ---- Base de donnees ----
POSTGRES_DB=mbsc
POSTGRES_USER=mbsc
POSTGRES_PASSWORD=$(alea)
DB_APP_USER=mbsc_app
DB_APP_PASSWORD=$(alea)

# ---- Sessions (cle Base64 de 384 bits) ----
JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
JWT_EXPIRATION=3600000

# ---- Acces ----
# Port public de nginx (HTTP). L'installateur renseigne ensuite l'adresse du serveur (ADRESSE_PUBLIQUE)
# et en deduit CORS_ORIGINS.
HTTP_PORT=9090
ADRESSE_PUBLIQUE=
CORS_ORIGINS=

# ---- Images de l'application (archive images/) ----
BACKEND_IMAGE=${IMG_BACKEND}
FRONTEND_IMAGE=${IMG_FRONTEND}

# ---- Agent IA (facultatif) ----
# Sans cle OpenAI, la suggestion de comptes et la reformulation des libelles fonctionnent en mode
# degrade (regles locales). A renseigner ici, sur le serveur, si vous souhaitez l'IA.
IA_ENABLED=true
OPENAI_API_KEY=
OPENAI_MODEL=gpt-5
EOF
    chmod 600 "$DEST/.env"
    echo ".env genere (secrets aleatoires, droits 600)."
  fi
fi

# Empreintes : permettent a l'installateur de detecter un fichier abime pendant le transfert.
(cd "$DEST" && find . -type f ! -name SHA256SUMS ! -name .env -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
cat > "$DEST/INFO-LIVRAISON.txt" <<EOF
Livraison M-FINAPP
  version   : $VERSION
  code      : commit $COMMIT${MODIFIE:+ (avec des modifications non commitees)}
  nature    : $( [ "$MAJ" -eq 1 ] && echo "mise a jour (images et scripts seulement)" || echo "premiere installation (base vierge + .env)" )
  preparee  : $(date '+%d/%m/%Y a %H:%M')
EOF

titre "Livraison prete : $DEST/"
du -sh "$DEST" | awk '{print "taille totale : " $1}'
if [ "$MAJ" -eq 0 ]; then
  echo "Envoyer au serveur :  scp -r $DEST/. utilisateur@ADRESSE:/opt/mbsc/    puis, sur le serveur :  cd /opt/mbsc && ./installer.sh"
else
  echo "Envoyer au serveur :  scp -r $DEST/. utilisateur@ADRESSE:/opt/mbsc/    puis, sur le serveur :  cd /opt/mbsc && ./mettre-a-jour.sh"
fi
echo "Lire d'abord : $DEST/LISEZ-MOI.md"
