-- Module DRH — pointage, fiche de presence manuelle et sorties de
-- travailleurs (module DRH_PRESENCES).

CREATE TABLE drh_presences (
    id          BIGSERIAL PRIMARY KEY,
    employe_id  BIGINT      NOT NULL REFERENCES drh_employes(id),
    date        DATE        NOT NULL,
    statut      VARCHAR(20) NOT NULL DEFAULT 'PRESENT',
    motif       VARCHAR(200),
    CONSTRAINT uq_drh_presence_employe_date UNIQUE (employe_id, date),
    CONSTRAINT chk_drh_presence_statut
        CHECK (statut IN ('PRESENT', 'ABSENT', 'CONGE', 'MALADIE', 'MISSION', 'FERIE'))
);

CREATE INDEX idx_drh_presences_date ON drh_presences (date);

CREATE TABLE drh_agents_presence_manuelle (
    id              BIGSERIAL PRIMARY KEY,
    nom_complet     VARCHAR(160) NOT NULL,
    fonction        VARCHAR(120),
    ordre_affichage INTEGER      NOT NULL DEFAULT 0
);

CREATE TABLE drh_sorties_travailleurs (
    id           BIGSERIAL PRIMARY KEY,
    employe_id   BIGINT       NOT NULL REFERENCES drh_employes(id),
    date_sortie  DATE         NOT NULL,
    heure_sortie TIME         NOT NULL,
    heure_retour TIME,
    motif        VARCHAR(300) NOT NULL,
    notes        VARCHAR(500)
);

CREATE INDEX idx_drh_sorties_date ON drh_sorties_travailleurs (date_sortie);
CREATE INDEX idx_drh_sorties_employe ON drh_sorties_travailleurs (employe_id);

COMMENT ON TABLE drh_presences IS 'Pointage quotidien par employe. L''absence de ligne pour un jour vaut le statut par defaut (ferie le week-end, present sinon), non materialise en base.';
COMMENT ON TABLE drh_agents_presence_manuelle IS 'Liste libre d''agents externes/temporaires pour la fiche de presence manuelle imprimable, independante de drh_employes.';
COMMENT ON TABLE drh_sorties_travailleurs IS 'Sorties ponctuelles d''un employe pendant la journee de travail (registre, pas lie a la paie).';
