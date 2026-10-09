-- ---------------------------------------------------------------------------
-- Notes de frais de paie et notes fiscales sur salaires (module DRH).
--
-- Une note PAIE regle les salaires nets d'un mois (debit 4221.1) ; son
-- paiement ecrit aussi la constatation de la paie (classe 66/64 contre les
-- dettes 4221.1, 431.1, 4472, 4478.x...). Une note IMPOT_PAIE solde ensuite
-- une dette envers un organisme (IPR, CNSS, INPP, ONEM). Voir PaieNoteService.
-- ---------------------------------------------------------------------------
ALTER TABLE notes_frais ADD COLUMN categorie VARCHAR(20) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE notes_frais ADD COLUMN paie_mois INTEGER;
ALTER TABLE notes_frais ADD COLUMN paie_annee INTEGER;
ALTER TABLE notes_frais ADD COLUMN organisme_paie VARCHAR(10);
CREATE INDEX idx_notes_frais_paie ON notes_frais (categorie, paie_annee, paie_mois);

ALTER TABLE drh_bulletins_paie ADD COLUMN note_frais_paie_id BIGINT REFERENCES notes_frais(id);
CREATE INDEX idx_bulletins_note_paie ON drh_bulletins_paie (note_frais_paie_id);

-- Salaires nets a payer : credite par PaieComptabilisationService depuis la
-- V43, mais jamais cree par une migration (une base neuve faisait echouer la
-- comptabilisation de la paie). Sous-compte de 422 « Personnel, remunerations
-- dues », meme convention que 431.1 (V46) et 4478.1/4478.2 (V43).
INSERT INTO comptes_ohada (numero, libelle, type, classe, imputable, actif, manuel) VALUES
    ('4221.1', 'Personnel, rémunérations dues — salaires nets', 'PASSIF', 4, TRUE, TRUE, TRUE)
ON CONFLICT (numero) DO NOTHING;

-- Comptes utilises par la constatation de la paie (SYSCOHADA revise) :
-- 6413 « Taxes sur appointements et salaires » (ONEM, INPP) et 662x
-- (remunerations du personnel non national). Tous des comptes de detail.
UPDATE comptes_ohada SET actif = TRUE, imputable = TRUE
WHERE numero IN ('4221.1', '431.1', '4472', '4478.1', '4478.2', '6413',
                 '6621', '6622', '6623', '6626', '6628');
