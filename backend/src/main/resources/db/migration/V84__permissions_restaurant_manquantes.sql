-- Complete la grille de permissions du module RESTAURANT, livree incomplete
-- par V49 : le code (RestaurantService.LECTURE / ECRITURE_STATUT_TABLE)
-- reserve explicitement au CAISSIER l'occupation des tables, la consultation
-- des commandes et le reglement de l'addition, et ouvre la lecture au DA et
-- au COMPTABLE — sans qu'aucune ligne ne le leur accorde ici. Toute nouvelle
-- installation (ou reconstruction depuis les migrations) partait donc avec
-- un caissier incapable d'acceder au moindre ecran Restaurant, malgre ce que
-- le code annonce.
INSERT INTO role_permissions (role, module, niveau) VALUES
    ('CAISSIER',  'RESTAURANT', 'ECRITURE'),
    ('DA',        'RESTAURANT', 'LECTURE'),
    ('COMPTABLE', 'RESTAURANT', 'LECTURE')
ON CONFLICT (role, module) DO UPDATE SET niveau = EXCLUDED.niveau
    WHERE role_permissions.niveau = 'AUCUN';
