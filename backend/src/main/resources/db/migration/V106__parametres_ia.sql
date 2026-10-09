-- ---------------------------------------------------------------------------
-- Parametres de l'IA (Administration > Intelligence artificielle) : cles
-- OpenAI et Anthropic saisies par l'administrateur (chiffrees, jamais
-- renvoyees par l'API) et modele choisi pour chacun. Claude (Anthropic) ne
-- sert qu'aux analyses ; OpenAI fait tout le reste et reprend les analyses
-- quand le credit Anthropic est epuise.
-- Ligne unique (id = 1) ; absente, les variables d'environnement
-- APP_OPENAI_* restent le secours d'une installation existante.
-- ---------------------------------------------------------------------------
CREATE TABLE parametres_ia (
    id                        BIGINT PRIMARY KEY,
    openai_cle_chiffree       VARCHAR(1000),
    openai_cle_fin            VARCHAR(8),
    openai_modele             VARCHAR(100) NOT NULL DEFAULT 'o4-mini',
    anthropic_cle_chiffree    VARCHAR(1000),
    anthropic_cle_fin         VARCHAR(8),
    anthropic_modele          VARCHAR(100) NOT NULL DEFAULT 'claude-sonnet-5-5',
    anthropic_epuise_depuis   TIMESTAMP,
    anthropic_refusee_depuis  TIMESTAMP,
    modifie_par_id            BIGINT REFERENCES users(id),
    date_maj                  TIMESTAMP NOT NULL DEFAULT now()
);
