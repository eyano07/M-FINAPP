-- ============================================================
-- MBSC Finapp - V18 : etablissements de tresorerie
--   Les banques et les operateurs mobile money deviennent des
--   donnees gerees par l'administrateur (et non plus une liste
--   figee dans le code). Chaque etablissement possede son propre
--   sous-compte OHADA, ce qui donne un solde distinct calcule par
--   le grand livre :
--     - banques          : 5215, 5216, ... (sous 521 Banques)
--     - mobile money     : 5821, 5822, ... (sous 582 Mobile Money)
-- ============================================================

-- ── 1. Comptes OHADA dedies aux etablissements par defaut ────
INSERT INTO comptes_ohada (numero, libelle, type, classe) VALUES
    ('5215', 'Banque — Equity Bank',        'ACTIF', 5),
    ('5216', 'Banque — Rawbank',            'ACTIF', 5),
    ('5821', 'Mobile Money — Airtel',       'ACTIF', 5),
    ('5822', 'Mobile Money — Orange',       'ACTIF', 5),
    ('5823', 'Mobile Money — Vodacom',      'ACTIF', 5),
    ('5824', 'Mobile Money — Africell',     'ACTIF', 5)
ON CONFLICT (numero) DO NOTHING;

-- ── 2. Table des etablissements ──────────────────────────────
CREATE TABLE etablissements_tresorerie (
    id            BIGSERIAL PRIMARY KEY,
    nom           VARCHAR(100) NOT NULL,
    type          VARCHAR(20)  NOT NULL,   -- BANQUE | MOBILE_MONEY
    compte_id     BIGINT       NOT NULL REFERENCES comptes_ohada(id),
    actif         BOOLEAN      NOT NULL DEFAULT TRUE,
    date_creation TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_etablissement_nom_type UNIQUE (nom, type),
    CONSTRAINT uq_etablissement_compte   UNIQUE (compte_id)
);

INSERT INTO etablissements_tresorerie (nom, type, compte_id)
SELECT v.nom, v.type, c.id
FROM (VALUES
        ('Equity Bank', 'BANQUE',       '5215'),
        ('Rawbank',     'BANQUE',       '5216'),
        ('Airtel',      'MOBILE_MONEY', '5821'),
        ('Orange',      'MOBILE_MONEY', '5822'),
        ('Vodacom',     'MOBILE_MONEY', '5823'),
        ('Africell',    'MOBILE_MONEY', '5824')
     ) AS v(nom, type, numero)
JOIN comptes_ohada c ON c.numero = v.numero;

-- ── 3. Rattachement des transactions ─────────────────────────
ALTER TABLE transactions_bancaires
    ADD COLUMN etablissement_id BIGINT REFERENCES etablissements_tresorerie(id);
ALTER TABLE transactions_mobile_money
    ADD COLUMN etablissement_id BIGINT REFERENCES etablissements_tresorerie(id);

-- Reprise de l'historique mobile money : l'operateur etait stocke
-- en enum sur la transaction, on le relie a son etablissement.
UPDATE transactions_mobile_money t
SET etablissement_id = e.id
FROM etablissements_tresorerie e
WHERE e.type = 'MOBILE_MONEY'
  AND upper(e.nom) = t.operateur_telecom;

-- Les operations bancaires anterieures n'avaient pas de banque :
-- elles sont rattachees a la premiere banque par defaut.
UPDATE transactions_bancaires
SET etablissement_id = (SELECT id FROM etablissements_tresorerie WHERE nom = 'Equity Bank')
WHERE etablissement_id IS NULL;

-- ── 4. Reprise des ecritures du grand livre ──────────────────
-- Les ecritures historiques pointent sur les comptes generiques
-- (5211 banque, 582 mobile money). On les bascule sur le compte
-- de l'etablissement concerne pour que les soldes par
-- etablissement soient exacts ; les totaux globaux (prefixes 521
-- et 582) restent inchanges.
UPDATE grand_livre g
SET compte_id = e.compte_id
FROM transactions_mobile_money t
JOIN etablissements_tresorerie e ON e.id = t.etablissement_id
WHERE g.transaction_mobile_money_id = t.id
  AND g.compte_id = (SELECT id FROM comptes_ohada WHERE numero = '582');

UPDATE grand_livre g
SET compte_id = e.compte_id
FROM transactions_bancaires t
JOIN etablissements_tresorerie e ON e.id = t.etablissement_id
WHERE g.transaction_bancaire_id = t.id
  AND g.compte_id = (SELECT id FROM comptes_ohada WHERE numero = '5211');

-- L'operateur est desormais porte par l'etablissement.
ALTER TABLE transactions_mobile_money DROP COLUMN operateur_telecom;

-- ── 5. Sequences des numeros de compte des nouveaux etablissements ──
-- Le service alloue le prochain numero libre a partir de ces bornes.
CREATE SEQUENCE IF NOT EXISTS seq_compte_banque       START WITH 17 INCREMENT BY 1;  -- 5217, 5218...
CREATE SEQUENCE IF NOT EXISTS seq_compte_mobile_money START WITH 25 INCREMENT BY 1;  -- 5825, 5826...
