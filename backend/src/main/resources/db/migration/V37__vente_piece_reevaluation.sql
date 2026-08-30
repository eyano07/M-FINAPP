-- Une reevaluation devait jusqu'ici etre reversee ou conservee par PIECE
-- entiere (une piece regroupait toutes les creances revaluees a une meme
-- cloture). Si une seule de ces creances etait reglee avant la cloture
-- suivante, la reevaluation des autres restait "ouverte" dans la meme
-- piece : la reverser au complet aurait re-ouvert un solde fantome sur le
-- compte client d'une vente deja soldee. La reevaluation devient donc une
-- piece par vente, reference directement depuis la vente : ne sont
-- reversees a la cloture suivante que celles dont la creance est encore
-- ouverte (piece_reglement_id IS NULL).
ALTER TABLE ventes ADD COLUMN piece_reevaluation_id BIGINT REFERENCES pieces_comptables(id);
