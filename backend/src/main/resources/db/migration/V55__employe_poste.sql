-- Poste occupe par l'employe (intitule libre : "Chef de chantier",
-- "Comptable"...), distinct de la categorie CNSS et de l'affectation
-- (site/lieu). Absent jusqu'ici du formulaire employe.
ALTER TABLE drh_employes ADD COLUMN poste VARCHAR(100);
