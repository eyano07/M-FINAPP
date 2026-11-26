-- Le "territoire" (subdivision administrative RDC, distincte de la province)
-- et le superviseur explicite d'une mission collective (texte imprime :
-- "Sous la supervision de ... agent de [societe] ...") — jusqu'ici deduit
-- implicitement du premier agent de la liste, ce qui changeait silencieusement
-- si la liste etait reordonnee.
ALTER TABLE drh_ordres_mission ADD COLUMN territoire VARCHAR(120);
ALTER TABLE drh_ordres_mission ADD COLUMN superviseur_employe_id BIGINT REFERENCES drh_employes(id);
ALTER TABLE drh_ordres_mission ADD COLUMN superviseur_civilite VARCHAR(10);
