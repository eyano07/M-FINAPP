-- Parametres de messagerie (SMTP) saisis par l'administrateur : ligne unique. Le mot de passe est chiffre
-- (AES-GCM) ; en l'absence de ligne, les variables d'environnement APP_MAIL_* servent de secours.
CREATE TABLE parametres_mail (
    id                    BIGINT PRIMARY KEY,
    actif                 BOOLEAN      NOT NULL DEFAULT FALSE,
    hote                  VARCHAR(200),
    port                  INTEGER      NOT NULL DEFAULT 587,
    securite              VARCHAR(10)  NOT NULL DEFAULT 'STARTTLS',
    utilisateur           VARCHAR(200),
    mot_de_passe_chiffre  VARCHAR(1000),
    mot_de_passe_fin      VARCHAR(8),
    expediteur            VARCHAR(200),
    url_publique          VARCHAR(300),
    derniere_erreur       VARCHAR(500),
    derniere_erreur_le    TIMESTAMP WITH TIME ZONE,
    modifie_par_id        BIGINT REFERENCES users (id),
    date_maj              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
