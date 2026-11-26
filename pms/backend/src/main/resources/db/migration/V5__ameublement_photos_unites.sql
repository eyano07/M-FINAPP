-- ---------------------------------------------------------------------------
-- Appartements meublés ou non meublés (le locataire d'un non meublé apporte ses meubles et son électroménager) et
-- photos des unités (chambres, unités de motel, appartements), montrées aux clients sur la réservation en ligne :
-- un type d'unité n'y est proposé qu'avec au moins 3 photos (20/11/2026).
-- ---------------------------------------------------------------------------

ALTER TABLE types_unite ADD COLUMN IF NOT EXISTS ameublement VARCHAR(12)
    CHECK (ameublement IS NULL OR ameublement IN ('MEUBLE', 'NON_MEUBLE'));

ALTER TABLE photos DROP CONSTRAINT IF EXISTS photos_objet_check;
ALTER TABLE photos ADD CONSTRAINT photos_objet_check CHECK (objet IN ('ETAT_LIEUX', 'TICKET', 'RELEVE', 'UNITE'));
ALTER TABLE photos ADD COLUMN IF NOT EXISTS ordre INTEGER NOT NULL DEFAULT 0;
