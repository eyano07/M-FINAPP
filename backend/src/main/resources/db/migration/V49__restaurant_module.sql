-- ---------------------------------------------------------------------------
-- Module RESTAURANT : carte (plats, boissons) et parc d'emballages consignes.
--
-- Module PLAT (parent_module NULL), comme PATRIMOINE : l'administrateur
-- l'active ou le coupe d'un seul geste depuis Administration > Modules. Il est
-- livre ACTIF, contrairement au module DRH qui attendait une reprise de
-- donnees a verifier : ici il n'y a rien a reprendre.
--
-- Les plats et les boissons ne creent AUCUNE table : ce sont des `articles`
-- portant les nouveaux types PLAT et BOISSON. La colonne articles.type est un
-- VARCHAR sans contrainte CHECK, donc ces valeurs sont acceptees sans
-- migration de table -- meme raisonnement que V32 pour les CONSOMMABLES,
-- plutot que de dupliquer le moteur de stock (entrepots, CMUP, grand livre,
-- ecritures OHADA).
--
-- Seuls les emballages ont leurs propres tables : un bac contient N
-- bouteilles et une bouteille a un format, structure qu'`articles` ne sait pas
-- porter ; et surtout « en circulation chez le client » n'est ni une ENTREE,
-- ni une SORTIE, ni un TRANSFERT de stock, c'est un etat distinct.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 1. Role
-- ---------------------------------------------------------------------------
INSERT INTO roles (nom) VALUES ('RESP_RESTAURANT') ON CONFLICT (nom) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Module (plat, actif des la livraison)
-- ---------------------------------------------------------------------------
INSERT INTO modules_config (module, actif, parent_module) VALUES
    ('RESTAURANT', TRUE, NULL)
ON CONFLICT (module) DO UPDATE SET parent_module = EXCLUDED.parent_module;

-- ---------------------------------------------------------------------------
-- 3. Grille de permissions par defaut
-- ---------------------------------------------------------------------------
-- Le responsable restaurant tient la carte et le parc d'emballages. DFIN et DG
-- consultent : la carte porte des prix de vente et le stock une valorisation,
-- deux elements qui remontent dans les etats financiers.
INSERT INTO role_permissions (role, module, niveau) VALUES
    ('RESP_RESTAURANT', 'RESTAURANT', 'ECRITURE'),
    ('DFIN',            'RESTAURANT', 'LECTURE'),
    ('DG',              'RESTAURANT', 'LECTURE')
ON CONFLICT (role, module) DO UPDATE SET niveau = EXCLUDED.niveau;

-- ---------------------------------------------------------------------------
-- 4. Parc d'emballages consignes
-- ---------------------------------------------------------------------------
-- Deux compteurs plutot qu'un seul : un emballage remis au client n'est pas
-- « sorti » definitivement, il est attendu en retour. Les distinguer est ce
-- qui permet de connaitre a tout moment le parc reellement dehors.
CREATE TABLE IF NOT EXISTS restaurant_emballages (
    id                    BIGSERIAL PRIMARY KEY,
    code                  VARCHAR(40)  NOT NULL UNIQUE,
    libelle               VARCHAR(200) NOT NULL,
    type                  VARCHAR(20)  NOT NULL,
    -- Format du contenant (33CL, 50CL, 65CL, 1L...) : pertinent pour une
    -- BOUTEILLE, laisse vide pour un BAC.
    format                VARCHAR(20),
    -- Nombre de bouteilles par bac : pertinent pour un BAC, vide sinon.
    contenance            INTEGER,
    -- Montant de consigne unitaire, en devise de base (FC). INDICATIF : le
    -- suivi retenu est simple, aucune ecriture comptable n'est produite.
    montant_consigne      NUMERIC(15,2) NOT NULL DEFAULT 0,
    quantite_stock        NUMERIC(15,3) NOT NULL DEFAULT 0,
    quantite_circulation  NUMERIC(15,3) NOT NULL DEFAULT 0,
    -- Boisson associee (optionnel) : permet de savoir quel emballage sert quel
    -- produit sans l'imposer, un bac pouvant etre generique.
    article_boisson_id    BIGINT REFERENCES articles(id),
    actif                 BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ,
    CONSTRAINT chk_emballage_quantites CHECK (quantite_stock >= 0 AND quantite_circulation >= 0)
);

COMMENT ON TABLE restaurant_emballages IS
    'Parc d''emballages consignes du restaurant (bouteilles, bacs), suivi en quantites uniquement : aucune ecriture comptable de consigne.';
COMMENT ON COLUMN restaurant_emballages.quantite_circulation IS
    'Quantite actuellement chez les clients, en attente de retour.';

-- ---------------------------------------------------------------------------
-- 5. Journal de circulation
-- ---------------------------------------------------------------------------
-- Append-only : les compteurs de restaurant_emballages sont l'etat courant,
-- cette table en est l'historique verifiable.
CREATE TABLE IF NOT EXISTS restaurant_mouvements_emballage (
    id              BIGSERIAL PRIMARY KEY,
    emballage_id    BIGINT NOT NULL REFERENCES restaurant_emballages(id),
    type            VARCHAR(20) NOT NULL,
    quantite        NUMERIC(15,3) NOT NULL CHECK (quantite > 0),
    date_mouvement  DATE NOT NULL,
    -- Client ou fournisseur concerne, en texte libre : le restaurant sert
    -- aussi des clients de passage, qui n'existent pas dans la table clients.
    tiers           VARCHAR(200),
    notes           VARCHAR(500),
    created_by_id   BIGINT REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_mvt_emballage_emballage ON restaurant_mouvements_emballage (emballage_id);
CREATE INDEX IF NOT EXISTS idx_mvt_emballage_date ON restaurant_mouvements_emballage (date_mouvement);

COMMENT ON TABLE restaurant_mouvements_emballage IS
    'Historique des mouvements du parc d''emballages : ENTREE (achat), SORTIE (remise au client), RETOUR, PERTE (casse ou non-retour).';
