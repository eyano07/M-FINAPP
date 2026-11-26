-- Charges connexes d'un camion de minerais (frais accessoires d'achat).
--
-- Le prix paye au fournisseur n'est pas le cout du chargement : s'y ajoutent
-- le transport, le pont bascule, le peage routier, les documents de
-- chargement/dechargement, la manutention... Le SYSCOHADA revise range ces
-- frais accessoires dans le COUT D'ACQUISITION de la marchandise : ils
-- doivent donc etre incorpores au stock, pas laisses en charges de la
-- periode, faute de quoi la marge du camion serait surevaluee tant qu'il
-- n'est pas vendu.
--
-- Chaque charge produit deux ecritures, exactement comme la reception :
--   D 61x/62x charge par nature / C 4011 Fournisseurs   (journal ACHATS)
--   D 311x Stock              / C 6031 Variation        (journal STOCK)
-- La premiere constate la nature de la depense au compte de resultat, la
-- seconde l'incorpore au stock. Leur difference se solde en cout d'achat des
-- marchandises vendues a la sortie, comme pour le prix d'achat lui-meme.

-- Cout d'acquisition = prix d'achat + charges connexes incorporees. C'est ce
-- montant, et non le CMP, qui sort du stock a la vente du camion : deux
-- chargements de minerais n'ont ni la meme teneur ni les memes frais de
-- route, ils ne sont pas interchangeables. Le SYSCOHADA admet dans ce cas
-- l'identification specifique, plus fidele ici que le cout moyen pondere.
ALTER TABLE camions_minerai ADD COLUMN cout_acquisition NUMERIC(15, 2);
UPDATE camions_minerai SET cout_acquisition = prix_achat WHERE cout_acquisition IS NULL;
ALTER TABLE camions_minerai ALTER COLUMN cout_acquisition SET NOT NULL;

CREATE TABLE charges_camion_minerai (
    id                       BIGSERIAL PRIMARY KEY,
    camion_id                BIGINT       NOT NULL REFERENCES camions_minerai(id),
    libelle                  VARCHAR(200) NOT NULL,
    compte_charge_id         BIGINT       NOT NULL REFERENCES comptes_ohada(id),
    montant                  NUMERIC(15, 2) NOT NULL CHECK (montant > 0),
    date_charge              DATE         NOT NULL,
    -- Dette envers le prestataire (transporteur, pont bascule...), soldee par
    -- la caisse comme celle du fournisseur du minerais.
    regle                    BOOLEAN      NOT NULL DEFAULT FALSE,
    piece_id                 BIGINT       REFERENCES pieces_comptables(id),
    piece_incorporation_id   BIGINT       REFERENCES pieces_comptables(id),
    transaction_reglement_id BIGINT       REFERENCES transactions_caisse(id),
    date_creation            TIMESTAMP,
    date_maj                 TIMESTAMP
);

CREATE INDEX idx_charge_camion ON charges_camion_minerai (camion_id);
CREATE INDEX idx_charge_camion_regle ON charges_camion_minerai (regle);
