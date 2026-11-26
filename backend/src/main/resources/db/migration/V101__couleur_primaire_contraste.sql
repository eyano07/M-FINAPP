-- Le vert d'origine (#16A34A) ne donne que 3,3:1 de contraste avec du texte blanc (minimum WCAG AA : 4,5:1).
-- Nouveau defaut : #15803D (5,0:1). Les entreprises qui avaient garde le vert d'origine passent au nouveau ;
-- une couleur choisie par l'administrateur n'est pas touchee.
ALTER TABLE parametres_entreprise ALTER COLUMN couleur_primaire SET DEFAULT '#15803D';
UPDATE parametres_entreprise SET couleur_primaire = '#15803D' WHERE UPPER(couleur_primaire) = '#16A34A';
