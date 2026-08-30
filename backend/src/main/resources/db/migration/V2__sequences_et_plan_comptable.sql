-- ============================================================
-- MBSC Finapp - V2 : sequences de references + plan comptable OHADA
-- ============================================================

-- Sequences pour les references metier lisibles (NF-, TRX-, REC-, BUD-).
-- Utilisees par ReferenceGenerator via nextval().
CREATE SEQUENCE IF NOT EXISTS seq_note_frais        START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS seq_transaction_caisse START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS seq_recu_caisse        START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS seq_budget             START WITH 1 INCREMENT BY 1;

-- ------------------------------------------------------------
-- Plan comptable OHADA minimal (classes 5, 6 et 7)
-- type : ACTIF | PASSIF | CHARGE | PRODUIT
-- ------------------------------------------------------------
INSERT INTO comptes_ohada (numero, libelle, type, classe) VALUES
    -- Tresorerie (classe 5)
    ('571',  'Caisse',                              'ACTIF',   5),
    ('521',  'Banques',                             'ACTIF',   5),
    -- Charges (classe 6)
    ('601',  'Achats de marchandises',              'CHARGE',  6),
    ('604',  'Achats stockes de matieres',          'CHARGE',  6),
    ('605',  'Autres achats',                       'CHARGE',  6),
    ('611',  'Transports',                          'CHARGE',  6),
    ('622',  'Locations et charges locatives',      'CHARGE',  6),
    ('624',  'Entretien, reparations et maintenance','CHARGE', 6),
    ('625',  'Primes d''assurance',                 'CHARGE',  6),
    ('626',  'Etudes, recherches et documentation', 'CHARGE',  6),
    ('627',  'Publicite, relations publiques',      'CHARGE',  6),
    ('628',  'Frais de telecommunications',         'CHARGE',  6),
    ('631',  'Frais bancaires',                     'CHARGE',  6),
    ('641',  'Impots et taxes directs',             'CHARGE',  6),
    ('661',  'Remunerations directes versees',      'CHARGE',  6),
    ('6588', 'Charges diverses',                    'CHARGE',  6),
    -- Produits (classe 7)
    ('701',  'Ventes de marchandises',              'PRODUIT', 7),
    ('706',  'Services vendus',                     'PRODUIT', 7),
    ('707',  'Produits accessoires',                'PRODUIT', 7),
    ('771',  'Revenus financiers',                  'PRODUIT', 7),
    ('758',  'Produits divers',                     'PRODUIT', 7)
ON CONFLICT (numero) DO NOTHING;
