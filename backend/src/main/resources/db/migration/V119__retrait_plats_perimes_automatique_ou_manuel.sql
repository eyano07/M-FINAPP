-- Retrait des plats perimes : automatique, ou manuel avec notification (choix de l'administrateur).
--
-- MANUEL (par defaut, comme avant) : une tache planifiee previent chaque jour le responsable du restaurant et les
-- administrateurs qu'il reste des portions perimees ; c'est le responsable qui les retire (Sorties de plats).
-- AUTOMATIQUE : la meme tache retire elle-meme les portions perimees du stock, avec la meme ecriture qu'un retrait manuel,
-- puis previent. L'ecriture est passee au nom de l'administrateur qui a active le mode (retrait_perimes_auteur_id) : une
-- ecriture comptable a toujours un auteur identifiable.
ALTER TABLE restaurant_parametres
    ADD COLUMN retrait_perimes_mode VARCHAR(12) NOT NULL DEFAULT 'MANUEL'
        CONSTRAINT chk_restaurant_parametres_retrait_perimes_mode
        CHECK (retrait_perimes_mode IN ('MANUEL', 'AUTOMATIQUE'));

-- Administrateur qui a active le retrait automatique, et quand. Efface si l'utilisateur disparait : la tache ne retire alors
-- plus rien d'elle-meme et previent comme en mode manuel, jusqu'a ce qu'un administrateur reconfirme le mode.
ALTER TABLE restaurant_parametres
    ADD COLUMN retrait_perimes_auteur_id BIGINT REFERENCES users (id) ON DELETE SET NULL;
ALTER TABLE restaurant_parametres
    ADD COLUMN retrait_perimes_modifie_le TIMESTAMPTZ;

-- Dernier jour ou la tache a prevenu qu'il y avait des plats perimes : une seule notification par jour, meme si le serveur
-- redemarre entre-temps.
ALTER TABLE restaurant_parametres
    ADD COLUMN perimes_signales_le DATE;
