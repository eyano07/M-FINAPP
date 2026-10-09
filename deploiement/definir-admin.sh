#!/usr/bin/env bash
# Definit l'adresse e-mail et le mot de passe du compte ADMIN de l'application.
#
# Sert a l'installation (la base livree contient un compte administrateur au mot de passe
# VERROUILLE : personne ne peut s'y connecter tant que ce script n'a pas ete lance), et plus
# tard si le mot de passe est perdu. Le mot de passe n'est ni affiche, ni ecrit dans un
# fichier : il est transmis par l'environnement du processus psql, haché en bcrypt par
# PostgreSQL (extension pgcrypto, activee dans la base « postgres » de maintenance seulement),
# puis seul le hache est enregistre. Tous les jetons de connexion deja emis sont revoques.
#
# Usage : ./definir-admin.sh
#   Questions posees au clavier. Sans clavier (automatisation) :
#   MBSC_ADMIN_EMAIL=... MBSC_ADMIN_PASSWORD=... [MBSC_ADMIN_CIBLE=ancien@email] ./definir-admin.sh
#   MBSC_ADMIN_CIBLE n'est utile que s'il existe plusieurs comptes ADMIN.
set -Eeuo pipefail
. "$(dirname "$0")/lib.sh"
trap 'echo "ARRET INATTENDU (ligne $LINENO) : le mot de passe n a pas ete modifie." >&2' ERR

[ "${1:-}" != "--aide" ] || { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

etat=$(etat_service db)
[ "$etat" = healthy ] || [ "$etat" = running ] || echec "La base de donnees ne tourne pas (docker compose up -d db)."
attendre_base 60 || echec "La base de donnees ne repond pas (docker compose logs db)."

cible="${MBSC_ADMIN_CIBLE:-}"
nb_admins=$(psql_super -c "select count(*) from users u join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id where r.nom = 'ADMIN'")
[ "$nb_admins" -ge 1 ] || echec "Aucun compte ADMIN dans la base."
if [ "$nb_admins" -gt 1 ] && [ -z "$cible" ]; then
  echo "Plusieurs comptes ADMIN existent :" >&2
  psql_super -c "select ' - ' || u.email from users u join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id where r.nom = 'ADMIN' order by 1" >&2
  echec "Indiquez lequel avec MBSC_ADMIN_CIBLE=adresse@exemple (voir --aide)."
fi

# (les variables psql ne sont pas interpolees dans -c : le SQL passe par l'entree standard)
actuel=$(psql_super -v c="$cible" <<'SQL'
select u.email from users u join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id
where r.nom = 'ADMIN' and (:'c' = '' or lower(u.email) = lower(:'c')) limit 1
SQL
)
[ -n "$actuel" ] || echec "Aucun compte ADMIN ne correspond a « $cible »."

email="${MBSC_ADMIN_EMAIL:-}"
mdp="${MBSC_ADMIN_PASSWORD:-}"
if [ -z "$email" ]; then
  [ -t 0 ] || echec "Pas de clavier : definissez MBSC_ADMIN_EMAIL et MBSC_ADMIN_PASSWORD."
  read -r -p "Adresse e-mail de connexion de l'administrateur [$actuel] : " email
  email="${email:-$actuel}"
fi
if [ -z "$mdp" ]; then
  [ -t 0 ] || echec "Pas de clavier : definissez MBSC_ADMIN_PASSWORD."
  read -r -s -p "Nouveau mot de passe (12 caracteres au moins) : " mdp; echo
  read -r -s -p "Confirmez le mot de passe : " mdp2; echo
  [ "$mdp" = "$mdp2" ] || echec "Les deux mots de passe different : rien n'a ete modifie."
fi

# Regles : 12 a 72 octets (BCrypt ne lit pas au-dela de 72), une adresse e-mail plausible.
longueur=$(printf '%s' "$mdp" | LC_ALL=C wc -c)
[ "$longueur" -ge 12 ] || echec "Mot de passe trop court (12 caracteres au moins) : rien n'a ete modifie."
[ "$longueur" -le 72 ] || echec "Mot de passe trop long (72 octets au plus, limite de BCrypt) : rien n'a ete modifie."
case "$mdp" in *$'\n'*|*$'\r'*) echec "Le mot de passe ne doit pas contenir de saut de ligne." ;; esac
printf '%s' "$email" | grep -Eq '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' || echec "Adresse e-mail invalide : « $email »."

docker compose exec -T -e ADMIN_PW="$mdp" -e ADMIN_EMAIL="$email" -e ADMIN_CIBLE="$cible" db \
  psql -U "$PG_USER" -d postgres -v ON_ERROR_STOP=1 -q -v base="$PG_DB" <<'SQL'
\getenv pw ADMIN_PW
\getenv mail ADMIN_EMAIL
\getenv cible ADMIN_CIBLE
SET client_min_messages = warning;
-- Le hache est calcule dans la base de maintenance : aucune extension n'est ajoutee a la base de l'application.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
SELECT crypt(:'pw', gen_salt('bf', 10)) AS h \gset
\c :"base"
BEGIN;
CREATE TEMP TABLE _cible ON COMMIT DROP AS
  SELECT u.id FROM users u
  JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id
  WHERE r.nom = 'ADMIN' AND (:'cible' = '' OR lower(u.email) = lower(:'cible'));
SELECT count(*) = 1 AS une_seule FROM _cible \gset
\if :une_seule
  UPDATE users
     SET mot_de_passe = :'h', email = :'mail', actif = true, version_jetons = version_jetons + 1
   WHERE id IN (SELECT id FROM _cible);
  COMMIT;
\else
  DO $$ BEGIN RAISE EXCEPTION 'Le compte ADMIN a modifier n''est pas unique : rien n''a ete modifie.'; END $$;
\endif
SQL

verif=$(psql_super -v m="$email" <<'SQL'
select count(*) from users u join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id
where r.nom = 'ADMIN' and lower(u.email) = lower(:'m') and u.actif
SQL
)
[ "$verif" = 1 ] || echec "Verification impossible : le compte « $email » n'est pas actif apres la mise a jour."
echo "Compte administrateur defini : $email (mot de passe enregistre sous forme hachee, anciennes sessions revoquees)."
