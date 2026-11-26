#!/bin/sh
# Vide la base de données de M-FINAPP en gardant le plan comptable, les rôles, les utilisateurs et les configurations.
#
#   sh outils/vider-base.sh            (depuis la racine du projet, conteneurs démarrés)
#
# Étapes :
#   1. sauvegarde complète dans sauvegardes/base-avant-vidage-AAAAMMJJ-HHMM.dump (restauration : voir en bas) ;
#   2. confirmation : taper VIDER ;
#   3. arrêt du serveur (backend), vidage en une seule transaction, numérotation remise à 1, redémarrage.
#
# Conservé : plan comptable (comptes_ohada, comptes_anciens_numeros), rôles et droits, utilisateurs (préférences, double
# authentification, téléphones), paramètres (entreprise, comptabilité, sécurité, instance, priorité des notes, IA, mail,
# e-MCF, restaurant, paie), modules activés, taux de TVA et groupes de taxe DGI, caisses et banques, taux de change,
# services budgétaires, emplacements et entrepôts, historique des migrations (flyway).
# Vidé : tout le reste (budgets, notes, pièces, grand livre, transactions, immobilisations, inventaires, stock, ventes,
# restaurant, RH, notifications...). Les compteurs de sous-comptes (banque, mobile money, consommables) ne sont pas remis
# à zéro : ils numérotent des comptes du plan comptable, qui est gardé.
#
# Les fichiers joints (photos, factures, pièces jointes) restent sur le disque du serveur ; seules leurs fiches partent.
# Vérifier que SEED_ENABLED=false dans .env : sinon les données de démonstration reviennent au redémarrage.
#
# Restaurer la sauvegarde (remplace toute la base) :
#   docker compose stop backend
#   docker compose exec -T db pg_restore -U <utilisateur> -d <base> --clean --if-exists < sauvegardes/<fichier>.dump
#   docker compose start backend
set -e
cd "$(dirname "$0")/.."
UTILISATEUR=$(sed -n 's/^POSTGRES_USER=//p' .env | head -1)
BASE=$(sed -n 's/^POSTGRES_DB=//p' .env | head -1)
[ -n "$UTILISATEUR" ] && [ -n "$BASE" ] || { echo "POSTGRES_USER / POSTGRES_DB introuvables dans .env"; exit 1; }
grep -q '^SEED_ENABLED=false' .env || echo "⚠ SEED_ENABLED n'est pas à false dans .env : les données de démonstration reviendront."

mkdir -p sauvegardes
FICHIER="sauvegardes/base-avant-vidage-$(date +%Y%m%d-%H%M).dump"
docker compose exec -T db pg_dump -U "$UTILISATEUR" -d "$BASE" -Fc < /dev/null > "$FICHIER"
echo "Sauvegarde : $FICHIER ($(du -h "$FICHIER" | cut -f1))"

printf "Vider la base %s (irréversible sans la sauvegarde) ? Tapez VIDER pour continuer : " "$BASE"
read -r REPONSE || REPONSE=""
[ "$REPONSE" = "VIDER" ] || { echo "Annulé : rien n'a été supprimé."; exit 0; }

docker compose stop backend < /dev/null
docker compose exec -T db psql -v ON_ERROR_STOP=1 -U "$UTILISATEUR" -d "$BASE" <<'SQL'
BEGIN;
DO $$
DECLARE
  garder text[] := array['comptes_ohada','comptes_anciens_numeros','roles','role_permissions','role_libelles','user_roles',
    'users','preferences_utilisateur','double_authentification','appareils_push','jetons_widget',
    'parametres_comptables','parametres_entreprise','parametres_instance','parametres_priorite_note','parametres_securite',
    'parametres_ia','parametres_mail','parametres_emcf','restaurant_parametres','drh_parametres_paie','modules_config',
    'taux_tva','groupes_taxe_dgi','flyway_schema_history',
    'etablissements_tresorerie','taux_change','services_budgetaires','emplacements','entrepots'];
  liste text;
  s text;
BEGIN
  -- Une table conservée ne doit pas dépendre d'une table vidée (sinon TRUNCATE la viderait aussi ou échouerait).
  IF EXISTS (SELECT 1 FROM pg_constraint c
             WHERE c.contype = 'f' AND c.conrelid::regclass::text = ANY (garder)
               AND NOT (c.confrelid::regclass::text = ANY (garder))) THEN
    RAISE EXCEPTION 'Une table conservée référence une table à vider : script à mettre à jour.';
  END IF;
  SELECT string_agg(format('%I', table_name), ', ') INTO liste
  FROM information_schema.tables
  WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> ALL (garder);
  EXECUTE 'TRUNCATE ' || liste || ' RESTART IDENTITY';
  -- Numéros de référence (NF-, BUD-, pièces, transactions...) : repartent de 1.
  FOREACH s IN ARRAY array['seq_budget','seq_depense_vehicule','seq_immobilisation','seq_inventaire','seq_mouvement_stock',
      'seq_note_frais','seq_piece_brouillon','seq_piece_comptable','seq_production','seq_provision','seq_recu_banque',
      'seq_recu_caisse','seq_recu_mobile_money','seq_retour_vente','seq_trajet','seq_transaction_bancaire',
      'seq_transaction_caisse','seq_transaction_mobile_money','seq_vente'] LOOP
    IF EXISTS (SELECT 1 FROM pg_class WHERE relkind = 'S' AND relname = s) THEN
      EXECUTE format('ALTER SEQUENCE %I RESTART', s);
    END IF;
  END LOOP;
END $$;
COMMIT;
SQL
docker compose start backend
echo "Base vidée. Sauvegarde conservée : $FICHIER"
