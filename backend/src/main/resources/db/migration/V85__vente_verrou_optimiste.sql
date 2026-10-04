-- Verrou optimiste sur les ventes (meme principe que stock_niveaux.version,
-- V13) : sans lui, un double-clic ou deux requetes concurrentes sur le
-- reglement de la meme creance pouvaient toutes deux lire pieceReglement
-- IS NULL avant que l'une ou l'autre n'ait sauvegarde, et generer chacune
-- leur propre piece de reglement — caisse debitee deux fois pour une seule
-- creance, compte client passe crediteur.
ALTER TABLE ventes
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
