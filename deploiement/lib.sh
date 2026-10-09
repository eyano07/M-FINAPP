# Fonctions communes aux scripts de la livraison (installer.sh, definir-admin.sh, ...).
# A « sourcer » depuis un script (. "$(dirname "$0")/lib.sh"), pas a lancer.

RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$RACINE"

[ -f docker-compose.prod.yml ] || {
  echo "Ce script se lance depuis le dossier livre : docker-compose.prod.yml est introuvable ici ($RACINE)." >&2
  exit 1
}

# Valeur d'une variable du fichier .env (derniere occurrence), vide si absente.
lire_env() { [ -f .env ] || return 0; { grep -E "^$1=" .env || true; } | tail -1 | cut -d= -f2-; }

# Remplace (ou ajoute) une variable du fichier .env, sans jamais l'afficher.
ecrire_env() {
  local cle="$1" valeur="$2" tmp
  tmp=$(mktemp)
  { [ -f .env ] && grep -v -E "^${cle}=" .env || true; } > "$tmp"
  printf '%s=%s\n' "$cle" "$valeur" >> "$tmp"
  cat "$tmp" > .env
  rm -f "$tmp"
  chmod 600 .env
}

# Docker Compose lit COMPOSE_FILE dans le .env ; a defaut, le fichier de production.
if [ -z "${COMPOSE_FILE:-}" ]; then
  COMPOSE_FILE="$(lire_env COMPOSE_FILE)"
  export COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
fi

PG_USER=$(lire_env POSTGRES_USER); PG_USER=${PG_USER:-mbsc}
PG_DB=$(lire_env POSTGRES_DB);     PG_DB=${PG_DB:-mbsc}
APP_USER=$(lire_env DB_APP_USER);  APP_USER=${APP_USER:-mbsc_app}
HTTP_PORT=$(lire_env HTTP_PORT);   HTTP_PORT=${HTTP_PORT:-9090}
# HTTP_PORT peut valoir « 127.0.0.1:9090 » (publie seulement sur le serveur lui-meme, acces par tunnel SSH) : PORT_HTTP est la partie numerique.
PORT_HTTP=${HTTP_PORT##*:}

titre()   { printf '\n== %s\n' "$*"; }
info()    { printf '   %s\n' "$*"; }
avertir() { printf '   ATTENTION : %s\n' "$*" >&2; }
echec()   { printf '\nECHEC : %s\n' "$*" >&2; exit 1; }

# psql en super-utilisateur dans le conteneur de la base (base par defaut : celle de l'application).
psql_super() {
  local base="$PG_DB"
  if [ "${1:-}" = "--base" ]; then base="$2"; shift 2; fi
  docker compose exec -T db psql -U "$PG_USER" -d "$base" -v ON_ERROR_STOP=1 -q -At "$@"
}

# Etat de sante d'un service (« healthy », « unhealthy », « starting », « running », « absent »).
etat_service() {
  local id
  id=$(docker compose ps -q "$1" 2>/dev/null | head -1)
  [ -n "$id" ] || { echo absent; return 0; }
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo absent
}

# attendre_service SERVICE [SECONDES] : attend « healthy » (ou « running » sans controle de sante).
attendre_service() {
  local service="$1" delai="${2:-300}" debut etat
  debut=$(date +%s)
  while :; do
    etat=$(etat_service "$service")
    case "$etat" in healthy|running) return 0 ;; esac
    [ $(( $(date +%s) - debut )) -lt "$delai" ] || { echo "$etat"; return 1; }
    sleep 3
  done
}

# attendre_base [SECONDES] : attend que la base reponde par TCP a une vraie requete. L'etat « healthy » de
# Docker ne suffit pas juste apres la creation du volume : l'image PostgreSQL demarre d'abord un serveur
# temporaire (socket seulement) pour l'initialisation, l'arrete, puis demarre le serveur definitif.
attendre_base() {
  local delai="${1:-180}" debut
  debut=$(date +%s)
  until docker compose exec -T db psql -h 127.0.0.1 -U "$PG_USER" -d "$PG_DB" -At -c 'select 1' >/dev/null 2>&1; do
    [ $(( $(date +%s) - debut )) -lt "$delai" ] || return 1
    sleep 2
  done
}

# Nombre de tables de l'application dans la base (0 = base neuve). Echoue (au lieu de repondre vide) si la
# base ne repond pas : une reponse vide ne doit jamais etre prise pour « base non vide ».
nombre_tables() {
  local n
  n=$(psql_super -c "select count(*) from pg_tables where schemaname = 'public'") || return 1
  [[ "$n" =~ ^[0-9]+$ ]] || return 1
  echo "$n"
}
