-- Champs de profil geres par l'utilisateur lui-meme (page "Mon profil") :
-- telephone et photo. Meme patron que parametres_entreprise pour la photo
-- (chemin de stockage + type MIME separes, fichier sur disque via
-- StorageService).
ALTER TABLE users ADD COLUMN telephone VARCHAR(30);
ALTER TABLE users ADD COLUMN photo_chemin_stockage VARCHAR(255);
ALTER TABLE users ADD COLUMN photo_type_mime VARCHAR(100);
