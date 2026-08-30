-- Lot 2 : fiabilisation du taux de change et mesure des pertes.
--
-- 1) Un seul taux par date d'effet.
--    taux_change n'avait aucune contrainte d'unicite : deux taux contradictoires
--    pouvaient coexister pour le meme jour, la resolution se faisant alors sur
--    created_at DESC — donc silencieusement, sans que personne ne voie le
--    conflit. On supprime d'abord les doublons eventuels en ne conservant que
--    le dernier saisi pour chaque date, puis on impose l'unicite.
DELETE FROM taux_change t
USING taux_change plus_recent
WHERE t.date_effet = plus_recent.date_effet
  AND (t.created_at < plus_recent.created_at
       OR (t.created_at = plus_recent.created_at AND t.id < plus_recent.id));

ALTER TABLE taux_change
    ADD CONSTRAINT uq_taux_change_date_effet UNIQUE (date_effet);

-- 2) Tracabilite de l'auteur, comme sur taux_tva.
ALTER TABLE taux_change
    ADD COLUMN created_by_id BIGINT REFERENCES users(id);

-- 3) Taux d'engagement d'une note de frais.
--    Jusqu'ici une note en USD n'etait convertie qu'au moment du paiement, au
--    taux de ce jour-la : l'ecart entre le taux du jour ou la depense a ete
--    engagee et celui du reglement etait absorbe silencieusement dans le
--    compte de charge. La perte de change existait mais restait invisible.
--    On fige donc le taux au moment ou la note part en tresorerie ; l'ecart
--    constate au paiement est comptabilise en 676 (perte) ou 776 (gain).
ALTER TABLE notes_frais
    ADD COLUMN taux_engagement NUMERIC(15, 6);

COMMENT ON COLUMN notes_frais.taux_engagement IS
    'Taux FC/USD fige lors de la transmission en tresorerie. NULL pour les notes en CDF (aucune conversion) et pour les notes anterieures a cette migration.';
