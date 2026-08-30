-- ============================================================
-- MBSC Finapp - V15 : modules Banque et Mobile Money
--   Paiement des notes de frais et operations manuelles via un
--   second et troisieme canal de tresorerie (en plus de la caisse).
-- ============================================================

-- ── Compte OHADA "Mobile Money" ──────────────────────────────
-- Aucun compte standard OHADA n'existe pour la monnaie electronique ;
-- logé sous la classe 5 (tresorerie), a cote de 581 Virements internes.
-- Compte de saisie direct (pas de sous-comptes), imputable par defaut.
INSERT INTO comptes_ohada (numero, libelle, type, classe) VALUES
    ('582', 'Mobile Money', 'ACTIF', 5)
ON CONFLICT (numero) DO NOTHING;

-- ── Sequences de reference ────────────────────────────────────
CREATE SEQUENCE IF NOT EXISTS seq_transaction_bancaire     START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS seq_recu_banque              START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS seq_transaction_mobile_money START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS seq_recu_mobile_money        START WITH 1 INCREMENT BY 1;

-- ── Transactions bancaires (miroir de transactions_caisse) ───
CREATE TABLE transactions_bancaires (
    id                  BIGSERIAL PRIMARY KEY,
    uuid                UUID NOT NULL UNIQUE,
    reference           VARCHAR(30) NOT NULL UNIQUE,
    note_id             BIGINT REFERENCES notes_frais(id),
    montant             NUMERIC(15,2) NOT NULL,
    sens                VARCHAR(20) NOT NULL,
    operateur_id        BIGINT NOT NULL REFERENCES users(id),
    numero_recu         VARCHAR(30),
    libelle             VARCHAR(255),
    date_operation      TIMESTAMP,
    date_enregistrement TIMESTAMP
);

-- ── Transactions mobile money (miroir de transactions_caisse) ─
CREATE TABLE transactions_mobile_money (
    id                  BIGSERIAL PRIMARY KEY,
    uuid                UUID NOT NULL UNIQUE,
    reference           VARCHAR(30) NOT NULL UNIQUE,
    note_id             BIGINT REFERENCES notes_frais(id),
    montant             NUMERIC(15,2) NOT NULL,
    sens                VARCHAR(20) NOT NULL,
    operateur_id        BIGINT NOT NULL REFERENCES users(id),
    numero_recu         VARCHAR(30),
    libelle             VARCHAR(255),
    date_operation      TIMESTAMP,
    date_enregistrement TIMESTAMP
);

-- ── Lien depuis le grand livre ────────────────────────────────
-- Une ecriture appartient a au plus une transaction de tresorerie,
-- quel que soit le canal (transaction_id = caisse, deja existant).
ALTER TABLE grand_livre
    ADD COLUMN transaction_bancaire_id BIGINT REFERENCES transactions_bancaires(id) ON DELETE CASCADE,
    ADD COLUMN transaction_mobile_money_id BIGINT REFERENCES transactions_mobile_money(id) ON DELETE CASCADE;
