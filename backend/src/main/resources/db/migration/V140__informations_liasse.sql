-- Renseignements facultatifs de la liasse OHADA (page de garde, fiches R1 à R3) :
-- forme juridique, régime fiscal, dirigeants, membres du conseil, domiciliations
-- bancaires… Conservés en JSON (InformationsLiasse) ; NULL = rien de renseigné.
ALTER TABLE parametres_entreprise ADD COLUMN informations_liasse TEXT;
