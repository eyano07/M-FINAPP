CREATE TABLE notifications (
    id               BIGSERIAL PRIMARY KEY,
    destinataire_id  BIGINT NOT NULL REFERENCES users(id),
    type             VARCHAR(40) NOT NULL,
    titre            VARCHAR(200) NOT NULL,
    message          VARCHAR(500) NOT NULL,
    lien             VARCHAR(200),
    note_frais_id    BIGINT REFERENCES notes_frais(id) ON DELETE CASCADE,
    lue              BOOLEAN NOT NULL DEFAULT FALSE,
    date_creation    TIMESTAMP NOT NULL DEFAULT now()
);

-- Alimente la liste (tri par date) et le badge (comptage des non lues) pour
-- un destinataire donne : c'est l'unique acces de lecture de cette table.
CREATE INDEX idx_notifications_destinataire ON notifications (destinataire_id, date_creation DESC);
CREATE INDEX idx_notifications_non_lues ON notifications (destinataire_id) WHERE lue = FALSE;
