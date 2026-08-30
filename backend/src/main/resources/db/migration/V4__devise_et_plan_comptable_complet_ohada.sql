-- =====================================================================
-- V4 : Devise des notes de frais + plan comptable OHADA (SYSCOHADA) complet
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) Devise des notes de frais (CDF par defaut, ou USD)
-- ---------------------------------------------------------------------
ALTER TABLE notes_frais
    ADD COLUMN IF NOT EXISTS devise VARCHAR(3) NOT NULL DEFAULT 'CDF';

-- ---------------------------------------------------------------------
-- 2) Plan comptable OHADA complet
--    type : ACTIF / PASSIF / CHARGE / PRODUIT
--    classe : 1 a 8 (premier chiffre du numero)
--    Les comptes deja presents (V2) sont ignores via ON CONFLICT.
-- ---------------------------------------------------------------------
INSERT INTO comptes_ohada (numero, libelle, type, classe) VALUES
    -- ===== CLASSE 1 : Ressources durables (capitaux) =====
    ('10',  'Capital',                                         'PASSIF', 1),
    ('101', 'Capital social',                                  'PASSIF', 1),
    ('104', 'Primes liees au capital social',                  'PASSIF', 1),
    ('105', 'Ecarts de reevaluation',                          'PASSIF', 1),
    ('106', 'Reserves',                                        'PASSIF', 1),
    ('11',  'Report a nouveau',                                'PASSIF', 1),
    ('12',  'Resultat net de l''exercice',                     'PASSIF', 1),
    ('13',  'Resultat net en instance d''affectation',         'PASSIF', 1),
    ('14',  'Subventions d''investissement',                   'PASSIF', 1),
    ('15',  'Provisions reglementees et fonds assimiles',      'PASSIF', 1),
    ('16',  'Emprunts et dettes assimilees',                   'PASSIF', 1),
    ('161', 'Emprunts obligataires',                           'PASSIF', 1),
    ('162', 'Emprunts aupres des etablissements de credit',    'PASSIF', 1),
    ('165', 'Depots et cautionnements recus',                  'PASSIF', 1),
    ('17',  'Dettes de location acquisition',                  'PASSIF', 1),
    ('18',  'Dettes liees a des participations',               'PASSIF', 1),
    ('19',  'Provisions financieres pour risques et charges',  'PASSIF', 1),

    -- ===== CLASSE 2 : Actif immobilise =====
    ('20',  'Charges immobilisees',                            'ACTIF', 2),
    ('21',  'Immobilisations incorporelles',                   'ACTIF', 2),
    ('211', 'Frais de developpement',                          'ACTIF', 2),
    ('212', 'Brevets, licences, concessions et droits',        'ACTIF', 2),
    ('213', 'Logiciels et sites internet',                     'ACTIF', 2),
    ('215', 'Fonds commercial',                                'ACTIF', 2),
    ('22',  'Terrains',                                        'ACTIF', 2),
    ('23',  'Batiments, installations et agencements',         'ACTIF', 2),
    ('231', 'Batiments industriels et commerciaux',            'ACTIF', 2),
    ('24',  'Materiel, mobilier et actifs biologiques',        'ACTIF', 2),
    ('241', 'Materiel et outillage',                           'ACTIF', 2),
    ('244', 'Materiel et mobilier de bureau',                  'ACTIF', 2),
    ('245', 'Materiel de transport',                           'ACTIF', 2),
    ('2451','Materiel automobile',                             'ACTIF', 2),
    ('2454','Materiel informatique',                           'ACTIF', 2),
    ('25',  'Avances et acomptes sur immobilisations',         'ACTIF', 2),
    ('26',  'Titres de participation',                         'ACTIF', 2),
    ('27',  'Autres immobilisations financieres',              'ACTIF', 2),
    ('28',  'Amortissements des immobilisations',              'ACTIF', 2),
    ('29',  'Depreciations des immobilisations',               'ACTIF', 2),

    -- ===== CLASSE 3 : Stocks =====
    ('31',  'Marchandises',                                    'ACTIF', 3),
    ('32',  'Matieres premieres et fournitures liees',         'ACTIF', 3),
    ('33',  'Autres approvisionnements',                       'ACTIF', 3),
    ('34',  'Produits en cours',                               'ACTIF', 3),
    ('35',  'Services en cours',                               'ACTIF', 3),
    ('36',  'Produits finis',                                  'ACTIF', 3),
    ('37',  'Produits intermediaires et residuels',            'ACTIF', 3),
    ('38',  'Stocks en cours de route, en consignation',       'ACTIF', 3),
    ('39',  'Depreciations des stocks',                        'ACTIF', 3),

    -- ===== CLASSE 4 : Comptes de tiers =====
    ('40',  'Fournisseurs et comptes rattaches',              'PASSIF', 4),
    ('401', 'Fournisseurs, dettes en compte',                 'PASSIF', 4),
    ('408', 'Fournisseurs, factures non parvenues',           'PASSIF', 4),
    ('409', 'Fournisseurs debiteurs',                         'ACTIF',  4),
    ('41',  'Clients et comptes rattaches',                   'ACTIF',  4),
    ('411', 'Clients',                                        'ACTIF',  4),
    ('416', 'Creances clients litigieuses ou douteuses',     'ACTIF',  4),
    ('418', 'Clients, produits non encore factures',         'ACTIF',  4),
    ('42',  'Personnel',                                      'PASSIF', 4),
    ('421', 'Personnel, avances et acomptes',                'ACTIF',  4),
    ('422', 'Personnel, remunerations dues',                 'PASSIF', 4),
    ('43',  'Organismes sociaux',                            'PASSIF', 4),
    ('431', 'Securite sociale',                              'PASSIF', 4),
    ('44',  'Etat et collectivites publiques',              'PASSIF', 4),
    ('441', 'Etat, impot sur les benefices',                'PASSIF', 4),
    ('443', 'Etat, TVA facturee',                           'PASSIF', 4),
    ('445', 'Etat, TVA recuperable',                        'ACTIF',  4),
    ('447', 'Etat, impots retenus a la source',             'PASSIF', 4),
    ('45',  'Organismes internationaux',                    'PASSIF', 4),
    ('46',  'Associes et groupe',                           'PASSIF', 4),
    ('47',  'Debiteurs et crediteurs divers',               'PASSIF', 4),
    ('48',  'Creances et dettes hors activites ordinaires', 'PASSIF', 4),
    ('49',  'Depreciations et provisions (tiers)',          'ACTIF',  4),

    -- ===== CLASSE 5 : Tresorerie =====
    ('50',  'Titres de placement',                          'ACTIF', 5),
    ('52',  'Banques',                                      'ACTIF', 5),
    ('53',  'Etablissements financiers et assimiles',       'ACTIF', 5),
    ('54',  'Instruments de tresorerie',                    'ACTIF', 5),
    ('56',  'Banques, credits de tresorerie et d''escompte','PASSIF', 5),
    ('57',  'Caisse',                                       'ACTIF', 5),
    ('58',  'Regies d''avances, accreditifs et virements internes', 'ACTIF', 5),
    ('59',  'Depreciations et provisions (tresorerie)',     'ACTIF', 5),

    -- ===== CLASSE 6 : Charges des activites ordinaires =====
    ('60',  'Achats et variations de stocks',              'CHARGE', 6),
    ('602', 'Achats de matieres premieres et fournitures', 'CHARGE', 6),
    ('608', 'Achats d''emballages',                        'CHARGE', 6),
    ('61',  'Transports',                                  'CHARGE', 6),
    ('612', 'Transports sur ventes',                       'CHARGE', 6),
    ('62',  'Services exterieurs A',                       'CHARGE', 6),
    ('63',  'Services exterieurs B',                       'CHARGE', 6),
    ('632', 'Remunerations d''intermediaires et de conseils','CHARGE', 6),
    ('633', 'Frais de formation du personnel',             'CHARGE', 6),
    ('638', 'Autres charges externes',                     'CHARGE', 6),
    ('64',  'Impots et taxes',                             'CHARGE', 6),
    ('645', 'Impots et taxes indirects',                   'CHARGE', 6),
    ('646', 'Droits d''enregistrement',                    'CHARGE', 6),
    ('647', 'Penalites et amendes fiscales',               'CHARGE', 6),
    ('65',  'Autres charges',                              'CHARGE', 6),
    ('658', 'Charges diverses',                            'CHARGE', 6),
    ('66',  'Charges de personnel',                        'CHARGE', 6),
    ('663', 'Indemnites et primes du personnel',           'CHARGE', 6),
    ('664', 'Charges sociales',                            'CHARGE', 6),
    ('67',  'Frais financiers et charges assimilees',      'CHARGE', 6),
    ('671', 'Interets des emprunts',                       'CHARGE', 6),
    ('68',  'Dotations aux amortissements',               'CHARGE', 6),
    ('681', 'Dotations aux amortissements d''exploitation','CHARGE', 6),
    ('69',  'Dotations aux provisions et depreciations',   'CHARGE', 6),

    -- ===== CLASSE 7 : Produits des activites ordinaires =====
    ('70',  'Ventes',                                      'PRODUIT', 7),
    ('702', 'Ventes de produits finis',                    'PRODUIT', 7),
    ('704', 'Travaux factures',                            'PRODUIT', 7),
    ('71',  'Subventions d''exploitation',                 'PRODUIT', 7),
    ('72',  'Production immobilisee',                       'PRODUIT', 7),
    ('73',  'Variations des stocks de biens et services produits', 'PRODUIT', 7),
    ('75',  'Autres produits',                             'PRODUIT', 7),
    ('77',  'Revenus financiers et produits assimiles',    'PRODUIT', 7),
    ('78',  'Transferts de charges',                       'PRODUIT', 7),
    ('79',  'Reprises de provisions et depreciations',     'PRODUIT', 7),

    -- ===== CLASSE 8 : Autres charges et produits =====
    ('81',  'Valeurs comptables des cessions d''immobilisations', 'CHARGE',  8),
    ('82',  'Produits des cessions d''immobilisations',          'PRODUIT', 8),
    ('83',  'Charges hors activites ordinaires (H.A.O.)',        'CHARGE',  8),
    ('84',  'Produits hors activites ordinaires (H.A.O.)',       'PRODUIT', 8),
    ('85',  'Dotations hors activites ordinaires',               'CHARGE',  8),
    ('86',  'Reprises hors activites ordinaires',                'PRODUIT', 8),
    ('87',  'Participation des travailleurs',                    'CHARGE',  8),
    ('88',  'Subventions d''equilibre',                          'PRODUIT', 8),
    ('89',  'Impots sur le resultat',                            'CHARGE',  8)
ON CONFLICT (numero) DO NOTHING;
