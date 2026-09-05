-- Marchandise de type "minerais" : suivie camion par camion.
--
-- Un minerais s'achete par camions successifs, tous au meme prix d'achat et
-- sur les memes comptes (achat, stock, variation, produit), mais chacun se
-- revend a son propre prix — la teneur et le cours varient d'un chargement a
-- l'autre. Le stock ne peut donc plus se resumer a une quantite : il faut
-- identifier chaque camion (plaque + date d'achat) pour lui attacher son prix
-- de vente au moment de la cession.
--
-- Le CMP reste juste sans amenagement : tous les camions partageant le meme
-- prix d'achat, le cout moyen pondere egale ce prix, et le destockage sur
-- vente (D 6031 / C 311) sort donc chaque camion a son cout reel. Seul le
-- produit (D 571 / C 701) varie d'un camion a l'autre.
ALTER TABLE articles ADD COLUMN minerais BOOLEAN NOT NULL DEFAULT FALSE;

-- Un camion = une unite de stock (choix fonctionnel : l'unite de mesure de
-- l'article devient "camion"). La reception est portee par la logistique et
-- constate la dette fournisseur ; le reglement passe ensuite par la caisse.
CREATE TABLE camions_minerai (
    id                    BIGSERIAL PRIMARY KEY,
    article_id            BIGINT       NOT NULL REFERENCES articles(id),
    entrepot_id           BIGINT       NOT NULL REFERENCES entrepots(id),
    plaque                VARCHAR(40)  NOT NULL,
    date_achat            DATE         NOT NULL,
    prix_achat            NUMERIC(15, 2) NOT NULL CHECK (prix_achat > 0),
    -- EN_STOCK -> VENDU. Un camion se vend entier : pas de reliquat a suivre.
    statut                VARCHAR(20)  NOT NULL DEFAULT 'EN_STOCK',
    -- Regle au fournisseur (decaissement caisse) ou non : la reception cree la
    -- dette, le reglement la solde. Independant du statut de vente.
    regle                 BOOLEAN      NOT NULL DEFAULT FALSE,
    mouvement_id          BIGINT       REFERENCES mouvements_stock(id),
    piece_reception_id    BIGINT       REFERENCES pieces_comptables(id),
    transaction_reglement_id BIGINT    REFERENCES transactions_caisse(id),
    date_creation         TIMESTAMP,
    date_maj              TIMESTAMP
);

-- Une meme plaque peut revenir un autre jour, mais pas deux fois le meme jour
-- pour le meme minerais : c'est ce couple qui identifie le chargement.
CREATE UNIQUE INDEX uq_camion_article_plaque_date
    ON camions_minerai (article_id, plaque, date_achat);
CREATE INDEX idx_camion_article_statut ON camions_minerai (article_id, statut);

-- Ligne de vente rattachee au camion cede : c'est elle qui porte le prix de
-- vente propre au chargement.
ALTER TABLE lignes_vente ADD COLUMN camion_id BIGINT REFERENCES camions_minerai(id);
CREATE UNIQUE INDEX uq_ligne_vente_camion ON lignes_vente (camion_id) WHERE camion_id IS NOT NULL;
