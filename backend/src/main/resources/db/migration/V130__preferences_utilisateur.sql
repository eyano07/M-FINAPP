-- ---------------------------------------------------------------------------
-- V130 : preferences d'affichage enregistrees sur le COMPTE de l'utilisateur.
--
-- Une preference (ex. « notes.affichage » = CARTES ou TABLEAU) suit l'utilisateur sur tous ses appareils et survit a la
-- deconnexion : elle est lue a l'ouverture de session (GET /profil/preferences) et ecrite au changement
-- (PUT /profil/preferences). Les cles et les valeurs admises sont fixees par le serveur (ProfilService) : aucune autre n'est stockee.
-- Une ligne par utilisateur et par cle ; supprimee avec l'utilisateur. Absence de ligne = valeur par defaut de l'ecran.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS preferences_utilisateur (
    id        BIGSERIAL    PRIMARY KEY,
    user_id   BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    cle       VARCHAR(60)  NOT NULL,
    valeur    VARCHAR(60)  NOT NULL,
    date_maj  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_preferences_utilisateur UNIQUE (user_id, cle)
);
