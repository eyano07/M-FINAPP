# M-FINAPP Hôtellerie (PMS)

Gestion d'hôtels, de motels et d'appartements meublés, **reliée à M-FINAPP** : l'hôtellerie gère l'exploitation
(établissements, unités, tarifs, calendrier, réservations, arrivées et départs, folios, ménage) ; M-FINAPP garde la
comptabilité, la caisse, la banque et le mobile money, le stock, les notes de frais, les comptes et les rôles, la RH.

## Ce qui tourne

| Conteneur      | Rôle                                                                                     |
|----------------|------------------------------------------------------------------------------------------|
| `pms-init`     | Ponctuel : crée la base `mbsc_pms` dans le conteneur de base de M-FINAPP, les rôles `pms_app` et `pms_lecteur`, le lien `postgres_fdw` (schéma `finapp`, lecture seule). |
| `pms-backend`  | Serveur de l'hôtellerie (Java 21, Spring Boot), base `mbsc_pms`.                         |
| `pms-frontend` | Site de l'hôtellerie (Nuxt 3, Vuetify).                                                  |
| `pms-nginx`    | Seul port publié : `PMS_PORT` (8090 par défaut).                                         |

Les deux bases vivent dans le même conteneur PostgreSQL. L'hôtellerie **lit** les tables de référence de M-FINAPP par
`postgres_fdw` (comptes, employés, articles, entrepôts, clients, banques, taux) et **n'écrit jamais** dans la base de
M-FINAPP : toute écriture (folio, acompte, caution, client) passe par l'API de M-FINAPP, signée avec la clé
d'appairage et portée par le jeton de l'utilisateur. Les règles de M-FINAPP s'appliquent donc telles quelles
(droits, séparation des tâches, verrou de période, journal d'audit).

## Installation

1. Dans `.env`, renseignez `PMS_DB_PASSWORD`, `PMS_LECTEUR_PASSWORD` et `PMS_SECRETS_KEY` (voir `.env.example`).
2. Démarrez : `docker compose --profile pms up -d` (production : `COMPOSE_FILE=docker-compose.prod.yml`).
3. Dans **M-FINAPP › Administration › Modules**, activez **Hôtellerie (PMS)**.
4. Dans **M-FINAPP › Administration › Hôtellerie**, saisissez l'adresse IP du serveur (ex. `192.0.2.10`) et le port
   (`8090`), puis **Générer la clé**.
5. Ouvrez `http://192.0.2.10:8090` : l'écran de premier lancement demande la clé. Collez-la.
6. Dans M-FINAPP, **Tester la connexion** doit répondre « Connexion établie ».

Les utilisateurs ouvrent ensuite l'hôtellerie depuis M-FINAPP (menu **Hôtellerie › Ouvrir l'hôtellerie**, connexion
unique) ou s'y connectent avec leurs identifiants M-FINAPP.

## Rôles (créés dans M-FINAPP › Utilisateurs)

| Rôle M-FINAPP     | Dans l'hôtellerie                                                                       |
|-------------------|-----------------------------------------------------------------------------------------|
| `RECEPTIONNISTE`  | Réservations, arrivées, extras, acomptes, cautions, départs **et encaissement des folios**. |
| `GOUVERNANTE`     | Ménage : états des unités, tâches.                                                      |
| `TECHNICIEN`      | Mise hors service des unités (maintenance).                                             |
| `DIR_HEBERGEMENT` | Établissements, unités, tarifs ; tout ce que fait la réception.                         |
| `ADMIN`           | Paramétrage et réservations, **jamais d'encaissement** (séparation des tâches).         |
| DG, DFIN, DA, comptable | Consultation.                                                                     |

## Ce que devient un séjour dans M-FINAPP

| Opération              | Écriture dans M-FINAPP                                                                 |
|------------------------|-----------------------------------------------------------------------------------------|
| Acompte                | D trésorerie (571, 52x ou 552x) / C 4191 — journal de caisse, banque ou mobile money.   |
| Caution                | D trésorerie / C 1651 (dépôts reçus).                                                  |
| Départ (folio)         | Vente d'origine HOTELLERIE : D 411x / C 7061 (hébergement) et produits des extras, TVA 443 ; sortie de stock des extras (minibar…) ; analytique **986 Hébergement**. Puis règlement : D 4191 (acompte imputé) + D 1651 (caution retenue) + D trésorerie (reste) / C 411x. |
| Folio facturé (crédit) | La créance reste ouverte au compte du client ; réglée plus tard.                        |
| Restitution de caution | D 1651 / C trésorerie.                                                                 |

Chaque opération porte une **clé d'idempotence** : rejouée après une coupure, elle ne produit jamais deux écritures.
Si M-FINAPP est injoignable au départ du client, le départ est enregistré (unité à nettoyer) et l'encaissement reste
« en attente » ; le bouton **Encaisser** le rejoue quand M-FINAPP répond.

## Journaux et sauvegarde

- `pms_logs` (`audit.log`) : journal d'audit de l'hôtellerie, en ajout seul — à conserver, jamais à supprimer.
- `pms_uploads` : pièces d'identité des clients (accès réception et direction seulement, chaque consultation est
  journalisée).
- La base `mbsc_pms` se sauvegarde avec `pg_dump` comme celle de M-FINAPP.

## Phases suivantes

- Phase 2 : canaux iCal (Booking.com, Airbnb, Expedia/VRBO), moteur de réservation direct, messages automatiques.
- Phase 3 : motels (créneaux, véhicules), appartements (baux, compteurs, inventaires), tickets de maintenance (achats par
  note de frais), rapports (occupation, ADR, RevPAR), interface mobile du ménage.
