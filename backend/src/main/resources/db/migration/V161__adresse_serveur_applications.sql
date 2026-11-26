-- Adresse du serveur pour les applications Android (26/11/2026) : URL ou IP saisie par l'administrateur
-- (ex. http://192.168.1.10 ou https://erp.exemple.com). La page « À propos » en fait un QR, scanné au premier lancement
-- de MBSC Finapp et de MBSC Logistique.
ALTER TABLE parametres_entreprise ADD COLUMN adresse_serveur VARCHAR(255);
