-- =====================================================================
-- Plan comptable SYSCOHADA revise (AUDCIF 2017) - referentiel officiel
--
-- Remplace le plan comptable approximatif seme par les migrations V1/V13
-- par le referentiel officiel commente, extrait du PDF fourni par
-- l'entreprise (« Plan Comptable SYSCOHADA - Referentiel commente,
-- classes 1 a 9 », 166 pages, 1300 comptes).
--
-- Trois apports :
--   1. le referentiel complet (1300 comptes contre 330 auparavant) ;
--   2. les rubriques commentees de chaque compte principal (Contenu,
--      Commentaires, Fonctionnement, Exclusions, Elements de controle),
--      affichees dans la page Plan comptable et fournies a l'assistant IA
--      pour fiabiliser ses suggestions de compte ;
--   3. la correction des comptes que l'application utilisait a contresens.
--
-- Aucun compte n'est supprime : ils sont tous references par le grand
-- livre, les notes de frais, les budgets ou les articles.
-- =====================================================================

-- 1. Rubriques commentees du referentiel -------------------------------
ALTER TABLE comptes_ohada
    ADD COLUMN IF NOT EXISTS contenu        TEXT,
    ADD COLUMN IF NOT EXISTS commentaires   TEXT,
    ADD COLUMN IF NOT EXISTS fonctionnement TEXT,
    ADD COLUMN IF NOT EXISTS exclusions     TEXT,
    ADD COLUMN IF NOT EXISTS controle       TEXT;

COMMENT ON COLUMN comptes_ohada.fonctionnement IS
    'Regles de debit/credit du referentiel SYSCOHADA commente';

-- Photographie des comptes deja mouvementes AVANT toute reprise. C'est
-- elle qui protege les libelles historiques : sans photographie prealable,
-- un compte recevant des ecritures reaffectees plus bas serait considere
-- comme « historique » et conserverait a tort son ancien intitule.
CREATE TEMP TABLE comptes_historiques AS
SELECT DISTINCT compte_id AS id FROM grand_livre;

-- Les comptes pris a contresens (corriges en section 3) sont exclus de
-- cette protection : leurs ecritures partent vers le compte officiellement
-- correct, ils doivent donc recuperer leur intitule du referentiel.
-- Sans cela, 6384 garderait l'intitule « Carburant » tout en accueillant
-- les ecritures de missions qui lui reviennent.
DELETE FROM comptes_historiques
WHERE id IN (SELECT id FROM comptes_ohada
             WHERE numero IN ('6012', '6384', '6323', '611', '6112', '6212'));


-- 2. Referentiel officiel ---------------------------------------------
-- Insertion des comptes manquants et mise a jour des libelles. Le libelle
-- d'un compte deja mouvemente n'est pas ecrase : l'intitule sous lequel une
-- ecriture a ete passee doit rester lisible tel quel.

INSERT INTO comptes_ohada (numero, libelle, type, classe, imputable, actif, manuel) VALUES
    ('101', 'Capital social', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1011', 'Capital souscrit, non appelé', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1012', 'Capital souscrit, appelé, non versé', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1013', 'Capital souscrit, appelé, versé, non amorti', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1014', 'Capital souscrit, appelé, versé, amorti', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1018', 'Capital souscrit, soumis à des conditions particulières', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('102', 'Capital par dotation', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1021', 'Dotation initiale', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1022', 'Dotations complémentaires', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1028', 'Autres dotations', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('103', 'Capital personnel', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('104', 'Compte de l''exploitant', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1041', 'Apports temporaires', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1042', 'Opérations courantes', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1043', 'Rémunérations, impôts, et autres charges personnelles', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1047', 'Prélèvements d''autoconsommation', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1048', 'Autres prélèvements', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('105', 'Primes liées aux capitaux propres', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1051', 'Primes d''émission', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1052', 'Primes d''apport', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1053', 'Primes de fusion', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1054', 'Primes de conversion', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1058', 'Autres primes', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('106', 'Ecarts de réévaluation', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1061', 'Ecarts de réévaluation légale', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1062', 'Ecarts de réévaluation libre', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('109', 'Apporteurs, capital souscrit, non appelé', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('11', 'Réserves', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('111', 'Reserve legale', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('112', 'Reserves statutaires ou contractuelles', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('113', 'Reserves reglementees', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1131', 'Réserves de plus - values nettes à long terme', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1132', 'Réserves d''attribution gratuite d''actions au personnel salarié et aux dirigeants', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1133', 'Réserves consécutives à l''octroi de subventions d''investissement', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1134', 'Réserves de valeurs mobilières donnant accès au capital', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1138', 'Autres réserves réglementées', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('118', 'Autres reserves', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1181', 'Réserves facultatives', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1188', 'Réserves diverses', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('12', 'Report à nouveau', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('121', 'Report a nouveau crediteur', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('129', 'Report a nouveau debiteur', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1291', 'Perte nette à reporter', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1292', 'Perte - Amortissements réputés différés', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('13', 'Résultat net de l''exercice', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('130', 'Resultat en instance d''affectation', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1301', 'Résultat en instance d''affectation : Bénéfice', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1309', 'Résultat en instance d''affectation : Perte', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('131', 'Resultat net : benefice', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('132', 'Marge commerciale (m.c.)', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('133', 'Valeur ajoutee (v.a.)', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('134', 'Excedent brut d''exploitation (e.b.e.)', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('135', 'Resultat d''exploitation (r.e.)', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('136', 'Resultat financier (r.f.)', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('137', 'Resultat des activites ordinaires (r.a.o.)', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('138', 'Resultat hors activites ordinaires (r.h.a.o.)', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1381', 'Résultat de fusion', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1382', 'Résultat d''apport partiel d''actif', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1383', 'Résultat de scission', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1384', 'Résultat de liquidation', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('139', 'Resultat net : perte', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('14', 'Subventions d''investissement', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('141', 'Subventions d''equipement', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1411', 'Etat', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1412', 'Régions', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1413', 'Départements', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1414', 'Communes et collectivités publiques décentralisées', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1415', 'Entité publiques ou mixtes', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1416', 'Entreprises et organismes privés', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1417', 'Organismes internationaux', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1418', 'Autres', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('148', 'Autres subventions d''investissement', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('15', 'Provisions réglementées et fonds assimilés', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('151', 'Amortissements derogatoires', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('152', 'Plus - values de cession a reinvestir', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('153', 'Fonds reglementes', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1531', 'Fonds national', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1532', 'Prélèvement pour le Budget', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('154', 'Provision speciale de reevaluation', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('155', 'Provisions reglementees relatives aux immobilisations', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1551', 'Reconstitution des gisements miniers et pétroliers', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('156', 'Provisions reglementees relatives aux stocks', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1561', 'Hausse de prix', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1562', 'Fluctuation des cours', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('157', 'Provisions pour investissement', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('158', 'Autres provisions et fonds reglementes', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('16', 'Emprunts et dettes assimilées', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('161', 'Emprunts obligataires', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1611', 'Emprunts obligataires ordinaires', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1612', 'Emprunts obligataires convertibles en actions', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1613', 'Emprunts obligataires remboursables en actions', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1618', 'Autres emprunts obligataires', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('162', 'Emprunts et dettes aupres des etablissements de credit', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('163', 'Avances reçues de l''etat', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('164', 'Avances reçues et comptes courants bloques', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('165', 'Depôts et cautionnements reçus', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1651', 'Dépôts', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1652', 'Cautionnements', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('166', 'Interêts courus', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1661', 'Interêts courus : sur emprunts obligataires', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1662', 'Interêts courus : sur emprunts et dettes auprès des établissements de crédit', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1663', 'Interêts courus : sur avances reçues de l''Etat', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1664', 'Interêts courus : sur avances reçues et comptes courants bloqués', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1665', 'Interêts courus : sur dépôts et cautionnements reçus', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1667', 'Interêts courus : sur avances assorties de conditions particulières', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1668', 'Interêts courus : sur autres emprunts et dettes', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('167', 'Avances assorties de conditions particulieres', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1671', 'Avances bloquées pour augmentation du capital', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1672', 'Avances conditionnées par l''Etat', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1673', 'Avances conditionnées par les autres organismes africains', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1674', 'Avances conditionnées par les organismes internationaux', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1676', 'Droits du concédant exigibles en nature', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('168', 'Autres emprunts et dettes', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1681', 'Rentes viagères capitalisées', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1682', 'Billets de fonds', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1683', 'Dettes consécutives à des titres empruntés', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1685', 'Emprunts participatifs', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1686', 'Participation des travailleurs aux bénéfices', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('17', 'Dettes de location acquisition', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('172', 'Dettes de location acquisition/credit - bail immobilier', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('173', 'Dettes de location acquisition/ credit - bail mobilier', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('174', 'Dettes de location acquisition /location vente', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('176', 'Interêts courus', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1762', 'Interêts courus : sur dettes de location acquisition/crédit - bail immobilier', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1763', 'Interêts courus : sur dettes de location acquisition/crédit - bail mobilier', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1764', 'Interêts courus : sur dettes de location acquisition/location vente', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1768', 'Interêts courus : sur autres dettes de location acquisition', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('178', 'Dettes d''autres contrats de location acquisition', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('181', 'Dettes liees a des participations', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1811', 'Dettes liées à des participations (groupe)', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1812', 'Dettes liées à des participations (hors groupe)', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('182', 'Dettes liees a des societes en participation', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('183', 'Interêts courus sur dettes liees a des participations', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('184', 'Comptes permanents bloques des etablissements et succursales', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('185', 'Comptes permanents non bloques des etablissements et succursales', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('186', 'Comptes de liaison charges', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('187', 'Comptes de liaison produits', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('188', 'Comptes de liaison des societes en participation', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('19', 'Provisions pour risques et charges', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('191', 'Provisions pour litiges', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('192', 'Provisions pour garanties donnees aux clients', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('193', 'Provisions pour pertes sur marches a achevement futur', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('194', 'Provisions pour pertes de change', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('195', 'Provisions pour impôts', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('196', 'Provisions pour pensions et obligations similaires', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1961', 'Provisions pour pensions et obligations similaires – engagement de retraite', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1962', 'Actif du régime de retraite', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('197', 'Provisions pour restructuration', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('198', 'Autres provisions pour risques et charges', 'PASSIF', 1, FALSE, TRUE, FALSE),
    ('1981', 'Provisions pour amendes et pénalités', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1983', 'Provisions de propre assureur', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1984', 'Provisions pour démantèlement et remise en état', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1985', 'Provisions de droits à déduction (Chèques cadeau, cartes de fidélité…)', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('1988', 'Autres provisions pour risques et charges', 'PASSIF', 1, TRUE, TRUE, FALSE),
    ('21', 'Immobilisations incorporelles', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('211', 'Frais de developpement', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('212', 'Brevets, licences, concessions et droits similaires', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2121', 'Brevets', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2122', 'Licences', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2123', 'Concessions de service public', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2128', 'Autres concessions et droits similaires', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('213', 'Logiciels et sites internet', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2131', 'Logiciels', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2132', 'Sites internet', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('214', 'Marques', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('215', 'Fonds commercial', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('216', 'Droit au bail', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('217', 'Investissements de creation', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('218', 'Autres droits et valeurs incorporels', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2181', 'Frais de prospection et d''évaluation de ressources minérales', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2182', 'Coûts d''obtention du contrat', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2183', 'Fichiers clients, notices, titres de journaux et magazines', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2188', 'Divers droits et valeurs incorporelles', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('219', 'Immobilisations incorporelles en cours', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2191', 'Frais de développement', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2193', 'Logiciels', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2198', 'Autres droits et valeurs incorporels', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('22', 'Terrains', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('221', 'Terrains agricoles et forestiers', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2211', 'Terrains d''exploitation agricole', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2212', 'Terrains d''exploitation forestière', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2218', 'Autres terrains', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('222', 'Terrains nus', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2221', 'Terrains à bâtir', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2228', 'Autres terrains nus', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('223', 'Terrains bâtis', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2231', 'Terrains bâtis : pour bâtiments industriels et agricoles', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2232', 'Terrains bâtis : pour bâtiments administratifs et commerciaux', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2234', 'Terrains bâtis : pour bâtiments affectés aux autres opérations professionnelles', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2235', 'Terrains bâtis : pour bâtiments affectés aux autres opérations non professionnelles', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2238', 'Autres terrains bâtis', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('224', 'Travaux de mise en valeur des terrains', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2241', 'Plantations d''arbres et d''arbustes', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2245', 'Améliorations du fonds', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2248', 'Autres travaux', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('225', 'Terrains carrieres trefonds', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2251', 'Carrières', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('226', 'Terrains amenages', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2261', 'Parkings', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('227', 'Terrains mis en concession', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('228', 'Autres terrains', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2281', 'Terrains des immeubles de placement', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2285', 'Terrains des logements affectés au personnel', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2286', 'Terrain location - acquisition', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2288', 'Autres', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('229', 'Amenagements de terrains en cours', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2291', 'Terrains agricoles et forestiers', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2292', 'Terrains nus', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2295', 'Terrains de carrières – tréfonds', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2298', 'Autres terrains', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('23', 'Bâtiments, installations techniques et agencements', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('231', 'Bâtiments industriels, agricoles, administratifs et commerciaux sur sol propre', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2311', 'Bâtiments industriels', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2312', 'Bâtiments agricoles', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2313', 'Bâtiments administratifs et commerciaux', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2314', 'Bâtiments affectés au logement du personnel', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2315', 'Immeubles de placement', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2316', 'Bâtiments de location acquisition', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('232', 'Bâtiments industriels agricoles, administratifs et commerciaux sur sol d''autrui', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2321', 'Bâtiments industriels', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2322', 'Bâtiments agricoles', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2323', 'Bâtiments administratifs et commerciaux', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2324', 'Bâtiments affectés au logement du personnel', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2325', 'Immeubles de placement', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2326', 'Bâtiment de location - acquisition', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('233', 'Ouvrages d''infrastructure', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2331', 'Voies de terre', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2332', 'Voies de fer', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2333', 'Voies d''eau', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2334', 'Barrages, Digues', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2335', 'Pistes d''aérodrome', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2338', 'Autres', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('234', 'Installations techniques', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2341', 'Installations complexes spécialisées sur sol propre', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2342', 'Installations complexes spécialisées sur sol d''autrui', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2343', 'Installations à caractère spécifique sur sol propre', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2344', 'Installations à caractère spécifique sur sol d''autrui', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2345', 'Aménagements et agencements des bâtiments', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('235', 'Amenagements de bureaux', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2351', 'Installations générales', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2358', 'Autres', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('237', 'Bâtiments industriels, agricoles et commerciaux mis en concession', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('238', 'Autres installations et agencements', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('239', 'Bâtiments et installations en cours', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2391', 'Bâtiments en cours', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2392', 'Installations en cours', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2393', 'Aménagements et agencements', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('24', 'Matériel, Mobilier et Actifs biologiques', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('241', 'Materiel et outillage industriel et commercial', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2411', 'Matériel industriel', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2412', 'Outillage industriel', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2413', 'Matériel commercial', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2414', 'Outillage commercial', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2416', 'Matériel et outillage de location acquisition', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('242', 'Materiel et outillage agricole', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2421', 'Matériel agricole', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2422', 'Outillage agricole', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2426', 'Matériel et outillage agricole de location – acquisition', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('243', 'Materiel d''emballage recuperable et identifiable', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('244', 'Materiel et mobilier', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2441', 'Matériel de bureau', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2442', 'Matériel informatique', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2443', 'Matériel bureautique', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2444', 'Mobilier de bureau', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2446', 'Matériel et mobilier de location - acquisition', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2447', 'Matériel et mobilier des logements affectés au personnel', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('245', 'Materiel de transport', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2451', 'Matériel automobile', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2452', 'Matériel ferroviaire', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2453', 'Matériel fluvial, lagunaire', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2454', 'Matériel naval', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2455', 'Matériel aérien', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2456', 'Matériel de transport de location - acquisition', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2457', 'Matériel hippomobile', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2458', 'Autres (vélo, mobylette, moto)', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('246', 'Actifs biologiques', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2461', 'Cheptel, animaux de trait', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2462', 'Cheptel, animaux reproducteurs', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2463', 'Animaux de garde', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2465', 'Plantations agricoles', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2468', 'Autres', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('247', 'Agencements, amenagements du materiel et actifs biologiques', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2471', 'Agencements et aménagements du matériel', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2472', 'Agencements et aménagements des actifs biologiques', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('248', 'Autres materiels et mobiliers', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2481', 'Collections et œuvres d''art', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('249', 'Materiel et actifs biologiques en cours', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2491', 'Matériel et outillage industriel et commercial', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2492', 'Matériel et outillage agricole', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2493', 'Matériel d''emballage récupérable et identifiable', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2494', 'Matériel et mobilier de bureau', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2495', 'Matériel de transport', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2496', 'Actifs biologiques', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2497', 'Agencements et aménagements du matériel et des actifs biologiques', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2498', 'Autres matériels', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('25', 'Avances et acomptes versés sur immobilisations', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('251', 'Avances et acomptes versés sur immobilisations incorporelles', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('252', 'Avances et acomptes versés sur immobilisations corporelles', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('26', 'Titres de participation', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('261', 'Titres de participation dans des societes sous contrôle exclusif', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('262', 'Titres de participation dans des societes sous contrôle conjoint', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('263', 'Titres de participation dans des societes conferant une influence notable', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('265', 'Participations dans des organismes professionnels', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('266', 'Parts dans des groupements d''interêt economique (G.I.E.)', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('268', 'Autres titres de participation', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('27', 'Autres immobilisations financières', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('271', 'Prêts et creances', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2711', 'Prêts participatifs', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2712', 'Prêts aux associés', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2713', 'Billets de fonds', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2714', 'Créances de location financement', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2715', 'Titres prêtés', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('272', 'Prêts au personnel', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2721', 'Prêts immobiliers', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2722', 'Prêts mobiliers et d''installation', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2728', 'Autres prêts (frais d''études)', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('273', 'Creances sur l''etat', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2731', 'Retenues de garantie', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2733', 'Fonds réglementé', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2734', 'Créances sur le concédant', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2738', 'Autres', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('274', 'Titres immobilises', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2741', 'Titres immobilisés de l''activité de portefeuille (T.I.A.P.)', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2742', 'Titres participatifs', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2743', 'Certificats d''investissement', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2744', 'Parts de fonds commun de placement (F.C.P.)', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2745', 'Obligations', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2746', 'Actions ou parts propres', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2748', 'Autres titres immobilisés', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('275', 'Depôts et cautionnements verses', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2751', 'Dépôts pour loyers d''avance', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2752', 'Dépôts pour l''électricité', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2753', 'Dépôts pour l''eau', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2754', 'Dépôts pour le gaz', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2755', 'Dépôts pour le téléphone, le télex, la télécopie', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2756', 'Cautionnements sur marchés publics', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2757', 'Cautionnements sur autres opérations', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2758', 'Autres dépôts et cautionnements', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('276', 'Interêts courus', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2761', 'Prêts et créances non commerciales', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2762', 'Prêts au personnel', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2763', 'Créances sur l''Etat', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2764', 'Titres immobilisés', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2765', 'Dépôts et cautionnements versés', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2766', 'Créances de location financement', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2767', 'Créances rattachées à des participations', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2768', 'Immobilisations financières diverses', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('277', 'Creances rattachees a des participations et avances a des G.I.E', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2771', 'Créances rattachées à des participations (groupe)', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2772', 'Créances rattachées à des participations (hors groupe)', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2773', 'Créances rattachées à des sociétés en participation', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2774', 'Avances à des groupements d''intérêt économique (G.I.E.)', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('278', 'Immobilisations financieres diverses', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2781', 'Créances diverses groupe', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2782', 'Créances diverses hors groupe', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2784', 'Banques dépôt à terme', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2785', 'Or et métaux précieux', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('28', 'Amortissements', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('281', 'Amortissements des immobilisations incorporelles', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2811', 'Amortissements des frais de développement', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2812', 'Amortissements des brevets, licences, concessions et droits similaires', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2813', 'Amortissements des logiciels et sites internet', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2814', 'Amortissements des marques', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2815', 'Amortissements du fonds commercial', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2816', 'Amortissements du droit au bail', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2817', 'Amortissements des investissements de création', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2818', 'Amortissements des autres droits et valeurs incorporels', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('282', 'Amortissements des terrains', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2821', 'Amortissements des terrains agricoles et forestiers', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2824', 'Amortissements des travaux de mise en valeur des terrains', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2825', 'Amortissements des terrains de gisement', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('283', 'Amortissements des bâtiments, installations techniques et agencements', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2831', 'Amortissements des bâtiments industriels, agricoles, administratifs et commerciaux sur sol propre', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2832', 'Amortissements des bâtiments industriels, agricoles, administratifs et commerciaux sur sol d''autrui', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2833', 'Amortissements des ouvrages d''infrastructure', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2834', 'Amortissements des aménagements, agencements et installations techniques', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2835', 'Amortissements des aménagements de bureaux', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2837', 'Amortissements de bâtiments industriels, agricoles et commerciaux mis en concession', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2838', 'Amortissements des autres installations et agencements', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('284', 'Amortissements du materiel', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2841', 'Amortissements du matériel et outillage industriel et commercial', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2842', 'Amortissements du matériel et outillage agricole', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2843', 'Amortissements du matériel d''emballage récupérable et identifiable', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2844', 'Amortissements du matériel et mobilier', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2845', 'Amortissements du matériel de transport', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2846', 'Amortissements des actifs biologiques', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2847', 'Amortissements des agencements et aménagements du matériel et des actifs biologiques', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2848', 'Amortissements des autres matériels', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('29', 'Dépréciations', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('291', 'Depreciations des immobilisations ncorporelles', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2911', 'Dépréciations des frais de développement', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2912', 'Dépréciations des brevets, licences, marques concessions et droits similaires', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2913', 'Dépréciations des logiciels et sites internet', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2914', 'Dépréciations des marques', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2915', 'Dépréciations du fonds commercial', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2916', 'Dépréciations du droit au bail', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2917', 'Dépréciations des investissements de création', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2918', 'Dépréciations des autres droits et valeurs incorporels', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2919', 'Dépréciations des immobilisations incorporelles en cours', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('292', 'Depreciations des terrains', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2921', 'Dépréciations des terrains agricoles et forestiers', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2922', 'Dépréciations des terrains nus', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2923', 'Dépréciations des terrains bâtis', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2924', 'Dépréciations des travaux de mise en valeur des terrains', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2925', 'Dépréciations des terrains de gisement', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2926', 'Dépréciations des terrains aménagés', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2927', 'Dépréciations des terrains mis en concession', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2928', 'Dépréciations des autres terrains', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2929', 'Dépréciations des aménagements de terrains en cours', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('293', 'Depreciations des bâtiments, installations techniques et agencements', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2931', 'Dépréciations des bâtiments industriels, agricoles, administratifs et commerciaux sur sol propre', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2932', 'Dépréciations des bâtiments industriels, agricoles, administratifs et commerciaux sur sol d''autrui', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2933', 'Dépréciations des ouvrages d''infrastructures', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2934', 'Dépréciations des aménagements, agencements et installations techniques', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2935', 'Dépréciations des aménagements de bureaux', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2937', 'Dépréciations des bâtiments industriels, agricoles et commerciaux mis en concession', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2938', 'Dépréciations des autres installations et agencements', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2939', 'Dépréciations des bâtiments et installations en cours', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('294', 'Depreciations du materiel, du mobilier et de l''actif biologique', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2941', 'Dépréciations du matériel et outillage industriel et commercial', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2942', 'Dépréciations du matériel et outillage agricole', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2943', 'Dépréciations du matériel d''emballage récupérable et identifiable', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2944', 'Dépréciations du matériel et mobilier', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2945', 'Dépréciations du matériel de transport', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2946', 'Dépréciations des actifs biologiques', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2947', 'Dépréciations des agencements et aménagements du matériel et des actifs biologiques', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2948', 'Dépréciations des autres matériels', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2949', 'Dépréciations du matériel en cours', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('295', 'Depreciations des avances et acomptes verses sur immobilisations', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2951', 'Dépréciations des avances et acomptes versés sur immobilisations incorporelles', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2952', 'Dépréciations des avances et acomptes versés sur immobilisations corporelles', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('296', 'Depreciations des titres de participation', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2961', 'Dépréciations des titres de participation dans des sociétés sous contrôle exclusif', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2962', 'Dépréciations des titres de participation dans les sociétés sous contrôle conjoint', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2963', 'Dépréciations des titres de participation dans les sociétés conférant une influence notable', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2965', 'Dépréciations des participations dans des organismes professionnels', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2966', 'Dépréciations des parts dans des G.I.E', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2968', 'Dépréciations des autres titres de participation', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('297', 'Depreciations des autres immobilisations financieres', 'ACTIF', 2, FALSE, TRUE, FALSE),
    ('2971', 'Dépréciations des prêts et créances', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2972', 'Dépréciations des prêts au personnel', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2973', 'Dépréciations des créances sur l''Etat', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2974', 'Dépréciations des titres immobilisés', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2975', 'Dépréciations des dépôts et cautionnements versés', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2977', 'Dépréciations des créances rattachées à des participations et avances à des G.I.E', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('2978', 'Dépréciations des créances financières diverses', 'ACTIF', 2, TRUE, TRUE, FALSE),
    ('31', 'Marchandises', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('311', 'Marchandises A', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('3111', 'Marchandises A1', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3112', 'Marchandises A2', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('312', 'Marchandises B', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('3121', 'Marchandises B1', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3122', 'Marchandises B2', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('318', 'Marchandises hors activites ordinaires (H.A.O.)', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('32', 'Matières premières et fournitures liées', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('321', 'Matières premières et fournitures liées : Matieres A', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('322', 'Matières premières et fournitures liées : Matieres B', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('323', 'Fournitures (a, B)', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('33', 'Autres approvisionnements', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('331', 'Matieres consommables', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('333', 'Fournitures de magasin', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('334', 'Fournitures de bureau', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('335', 'Emballages', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('3351', 'Emballages perdus', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3352', 'Emballages récupérables non identifiables', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3353', 'Emballages à usage mixte', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3358', 'Autres emballages', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('338', 'Autres matieres', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('34', 'Produits en cours', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('341', 'Produits en cours', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('3411', 'Produits en cours P1', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3412', 'Produits en cours P2', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('342', 'Travaux en cours', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('3421', 'Travaux en cours T1', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3422', 'Travaux en cours T2', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('343', 'Produits intermediaires en cours', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('3431', 'Produits intermédiaires A', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3432', 'Produits intermédiaires B', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('344', 'Produits residuels en cours', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('3441', 'Produits résiduels A', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3442', 'Produits résiduels B', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('35', 'Services en cours', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('351', 'Etudes en cours', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('3511', 'Etudes en cours E1', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3512', 'Etudes en cours E2', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('352', 'Prestations de services en cours', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('3521', 'Prestations de services S1', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3522', 'Prestations de services S2', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('36', 'Produits finis', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('361', 'Produits finis A', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('362', 'Produits finis B', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('37', 'Produits intermédiaires et résiduels', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('371', 'Produits intermediaires', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('3711', 'Produits intermédiaires A', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3712', 'Produits intermédiaires B', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('372', 'Produits residuels', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('3721', 'Déchets', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3722', 'Rebuts', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3723', 'Matières de récupération', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('38', 'Stocks en cours de route, en consignation ou en dépôt', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('381', 'Marchandises en cours de route', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('382', 'Matieres premieres et fournitures liees en cours de route', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('383', 'Autres approvisionnements en cours de route', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('386', 'Produits finis en cours de route', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('387', 'Stock en consignation ou en depôt', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('3871', 'Stock en consignation', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('3872', 'Stock en dépôt', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('388', 'Stock provenant d''immobilisations mises hors service ou au rebut', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('39', 'Dépréciations des stocks et encours de production', 'ACTIF', 3, FALSE, TRUE, FALSE),
    ('391', 'Depreciations des stocks de marchandises', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('392', 'Depreciations des stocks de matieres premieres et fournitures liees', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('393', 'Depreciations des stocks d''autres approvisionnements', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('394', 'Depreciations des produits en cours', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('395', 'Depreciations des services en cours', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('396', 'Depreciations des stocks de produits finis', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('397', 'Depreciations des stocks de produits intermediaires et residuels', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('398', 'Depreciations des stocks en cours de route, en consignation ou en depôt', 'ACTIF', 3, TRUE, TRUE, FALSE),
    ('40', 'Fournisseurs et comptes rattachés', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('401', 'Fournisseurs, dettes en compte', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4011', 'Fournisseurs', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4012', 'Fournisseurs - Groupe', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4013', 'Fournisseurs sous - traitants', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4017', 'Fournisseurs, retenues de garantie', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('402', 'Fournisseurs, effets a payer', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4021', 'Fournisseurs, Effets à payer', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4022', 'Fournisseurs - Groupe, Effets à payer', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4023', 'Fournisseurs sous - traitants, Effets à payer', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('408', 'Fournisseurs, factures non parvenues', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4081', 'Fournisseurs', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4082', 'Fournisseurs - Groupe', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4083', 'Fournisseurs sous - traitants', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4086', 'Fournisseurs, intérêts courus', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('409', 'Fournisseurs debiteurs', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4091', 'Fournisseurs, avances et acomptes versés', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4092', 'Fournisseurs - Groupe, avances et acomptes versés', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4093', 'Fournisseurs sous - traitants, avances et acomptes versés', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4094', 'Fournisseurs, créances pour emballages et matériels à rendre', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4098', 'Rabais, Remises, Ristournes et autres avoirs à obtenir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('41', 'Clients et comptes rattachés', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('411', 'Clients', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4111', 'Clients', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4112', 'Clients - Groupe', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4114', 'Clients, Etat et collectivités publiques', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4115', 'Clients, organismes internationaux', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4116', 'Clients ventes avec réserves de propriété', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4117', 'Clients, retenues de garantie', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4118', 'Clients, dégrèvements de Taxes sur la Valeur Ajoutée (T.V.A.)', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('412', 'Clients, effets a recevoir en portefeuille', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4121', 'Clients, Effets à recevoir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4122', 'Clients - Groupe, Effets à recevoir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4124', 'Etat et collectivités publiques, Effets à recevoir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4125', 'Organismes internationaux, Effets à recevoir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4126', 'Clients, effets a recevoir en portefeuille : clients ventes avec réserves de propriété, effets à recevoir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('414', 'Creances sur cessions courantes d''immobilisations', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4141', 'Créances en compte', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4142', 'Effets à recevoir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('415', 'Clients, effets escomptes non echus', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('416', 'Creances clients litigieuses ou douteuses', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4161', 'Créances litigieuses', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4162', 'Créances douteuses', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('418', 'Clients, produits a recevoir', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4181', 'Clients, factures à établir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4186', 'Clients, intérêts courus', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('419', 'Clients crediteurs', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4191', 'Clients, avances et acomptes reçus', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4192', 'Clients - Groupe, avances et acomptes reçus', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4194', 'Clients, dettes pour emballages et matériels consignés', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4198', 'Rabais, Remises, Ristournes et autres avoirs à accorder', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('42', 'Personnel', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('421', 'Personnel, avances et acomptes', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4211', 'Personnel, avances', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4212', 'Personnel, acomptes', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4213', 'Frais avancés et fournitures au personnel', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('422', 'Personnel, remunerations dues', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('423', 'Personnel, oppositions, saisies - arrêts', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4231', 'Personnel, oppositions', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4232', 'Personnel, saisies - arrêts', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4233', 'Personnel, avis à tiers détenteur', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('424', 'Personnel,œuvres sociales internes', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4241', 'Assistance médicale', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4242', 'Allocations familiales', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4245', 'Organismes sociaux rattachés à l''entité', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4248', 'Autres œuvres sociales internes', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('425', 'Representants du personnel', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4251', 'Délégués du personnel', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4252', 'Syndicats et Comités d''entreprise, d''établissement', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4258', 'Autres représentants du personnel', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('426', 'Personnel, participation aux benefices et au capital', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4261', 'Participation aux bénéfices', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4264', 'Participation au capital', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('427', 'Personnel - depôts', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('428', 'Personnel, charges a payer et produits a recevoir', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4281', 'Dettes provisionnées pour congés à payer', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4286', 'Autres charges à payer', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4287', 'Produits à recevoir', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('43', 'Organismes sociaux', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('431', 'Securite sociale', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4311', 'Prestations familiales', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4312', 'Accidents du travail', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4313', 'Caisse de retraite obligatoire', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4314', 'Caisse de retraite facultative', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4318', 'Autres cotisations sociales', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('432', 'Caisses de retraite complementaire', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('433', 'Autres organismes sociaux', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4331', 'Mutuelle', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4332', 'Assurances retraite', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4333', 'Assurances et organismes de santé', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('438', 'Organismes sociaux, charges a payer et produits a recevoir', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4381', 'Charges sociales sur gratifications à payer', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4382', 'Charges sociales sur congés à payer', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4386', 'Autres charges à payer', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4387', 'Produits à recevoir', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('44', 'Etat et Collectivités publiques', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('441', 'Etat, impôt sur les benefices', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('442', 'Etat, autres impôts et taxes', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4421', 'Impôts et taxes d''Etat', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4422', 'Impôts et taxes pour les collectivités publiques', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4423', 'Impôts et taxes recouvrables sur des obligataires', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4424', 'Impôts et taxes recouvrables sur des associés', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4426', 'Droits de douane', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4428', 'Autres impôts et taxes', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('443', 'Etat, T.V.A. facturee', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4431', 'T.V.A. facturée sur ventes', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4432', 'T.V.A. facturée sur prestations de services', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4433', 'T.V.A. facturée sur travaux', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4434', 'T.V.A. facturée sur production livrée à soi - même', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4435', 'T.V.A. sur factures à établir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('444', 'Etat, T.V.A. due ou credit de T.V.A', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4441', 'Etat, T.V.A. due', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4449', 'Etat, crédit de T.V.A. à reporter', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('445', 'Etat, T.V.A. recuperable', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4451', 'T.V.A. récupérable sur immobilisations', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4452', 'T.V.A. récupérable sur achats', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4453', 'T.V.A. récupérable sur transport', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4454', 'T.V.A. récupérable sur services extérieurs et autres charges', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4455', 'T.V.A. récupérable sur factures non parvenues', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4456', 'T.V.A. transférée par d''autres entités', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('446', 'Etat, autres taxes sur le chiffre d''affaires', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('447', 'Etat, impôts retenus a la source', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4471', 'Impôt général sur le revenu', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4472', 'Impôts sur salaires', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4473', 'Contribution nationale', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4474', 'Contribution nationale de solidarité', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4478', 'Autres impôts et contributions', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('448', 'Etat, charges a payer et produits a recevoir', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4486', 'Charges à payer', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4487', 'Produits à recevoir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('449', 'Etat, creances et dettes diverses', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4491', 'Etat, obligations cautionnées', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4492', 'Etat, avances et acomptes versés sur impôts', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4493', 'Etat, fonds de dotation à recevoir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4494', 'Etat, subventions d''équipement à recevoir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4495', 'Etat, subventions d''exploitation à recevoir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4496', 'Etat, subventions d''équilibre à recevoir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4497', 'Etat avances sur subventions', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4499', 'Etat, fonds réglementé provisionné', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('45', 'Organismes internationaux', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('451', 'Operations avec les organismes africains', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('452', 'Operations avec les autres organismes internationaux', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('458', 'Organismes internationaux, fonds de dotation et subventions a recevoir', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4581', 'Organismes internationaux, fonds de dotation à recevoir', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4582', 'Organismes internationaux, subventions à recevoir', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('46', 'Apporteurs, Associés et Groupe', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('461', 'Apporteurs, operations sur le capital', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4611', 'Apporteurs, apports en nature', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4612', 'Apporteurs, apports en numéraire', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4613', 'Apporteurs, capital souscrit appelé non versé', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4614', 'Apporteurs, capital appelé non versé', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4615', 'Apporteurs, versements reçus sur augmentation de capital', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4616', 'Apporteurs, versements anticipés', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4617', 'Apporteurs défaillants', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4618', 'Apporteurs, autres apports', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4619', 'Apporteurs, capital à rembourser', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('462', 'Associes(1), comptes courants', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('4621', 'Principal', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4626', 'Intérêts courus', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('463', 'Associes(1), operations faites en commun', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('465', 'Associes(1), dividendes a payer', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('466', 'Groupe, comptes courants', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('467', 'Apporteurs, restant DÛ sur capital appele', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('47', 'Débiteurs et créditeurs divers', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('471', 'Debiteurs et crediteurs divers', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4711', 'Débiteurs divers', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4712', 'Créditeurs divers', 'PASSIF', 4, TRUE, TRUE, FALSE),
    ('4713', 'Obligataires', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4714', 'Créances sur cessions de titres de placement', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4715', 'Rémunérations d''administrateurs', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4716', 'Compte du factor', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4717', 'Débiteurs divers - retenues de garantie', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4718', 'Apport, compte de restructuration', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4719', 'Bons de souscription d''actions et d''obligations', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('472', 'Versements restant a effectuer sur titres de placement non liberes', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('473', 'Intermediaires – operations faites pour le compte de tiers', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4731', 'Mandants', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4732', 'Mandataires', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4733', 'Commettants', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4734', 'Commissionnaires', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('474', 'Repartition periodique des charges et des produits', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4746', 'Charges', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4747', 'Produits', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('475', 'Compte transitoire, ajustement special lie a la revision SYSCOHADA', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4751', 'Compte actif', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4752', 'Compte passif', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('476', 'Charges constatees d''avance', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('477', 'Produits constates d''avance', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('478', 'Ecarts de conversion - actif', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4781', 'Diminution des créances', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4782', 'Augmentation des dettes', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4788', 'Différences compensées par couverture de change', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('479', 'Ecarts de conversion - passif', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4791', 'Augmentation des créances', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4792', 'Diminution des dettes', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4798', 'Différences compensées par couverture de change', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('48', 'Créances et dettes hors activités ordinaires', 'PASSIF', 4, FALSE, TRUE, FALSE),
    ('481', 'Fournisseurs d''investissements', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4811', 'Immobilisations incorporelles', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4812', 'Immobilisations corporelles', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4813', 'Versements restant à effectuer sur titres de participation et titres immobilisés non libérés', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4817', 'Retenues de garantie', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4818', 'Factures non parvenues', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('482', 'Fournisseurs d''investissements effets a payer', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('484', 'Autres dettes hors activites ordinaires (H.A.O.)', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('485', 'Creances sur cessions d''immobilisations', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4851', 'Creances sur cessions d''immobilisations : en compte', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4852', 'Effets à recevoir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4857', 'Retenues de garantie', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4858', 'Factures à établir', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('488', 'Autres creances hors activites ordinaires (H.A.O.)', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('49', 'Dépréciations et provisions pour risques à court terme (Tiers)', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('490', 'Dépréciations des comptes fournisseurs', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('491', 'Depreciations des comptes clients', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4911', 'Créances litigieuses', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4912', 'Créances douteuses', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('492', 'Depreciations des comptes personnel', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('493', 'Depreciations des comptes organismes sociaux', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('494', 'Dépréciations des comptes état et collectivités publiques', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('495', 'Depreciations des comptes organismes internationaux', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('496', 'Depreciations des comptes apporteurs associes et groupe', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4962', 'Associés, comptes courants', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4963', 'Associés, opérations faites en commun', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4966', 'Groupe, comptes courants', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('497', 'Depreciations des comptes debiteurs divers', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('498', 'Depreciations des comptes de creances H.A.O', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4981', 'Créances sur cessions d''immobilisations', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4982', 'Créances sur cessions de titres de placement', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4983', 'Autres créances H.A.O', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('499', 'Provisions pour risques a court terme', 'ACTIF', 4, FALSE, TRUE, FALSE),
    ('4991', 'Provisions pour risques a court terme : sur opérations d''exploitation', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('4998', 'Provisions pour risques a court terme : sur opérations H.A.O', 'ACTIF', 4, TRUE, TRUE, FALSE),
    ('50', 'Titres de placement', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('501', 'Titres du tresor et bons de caisse a court terme', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('5011', 'Titres du Trésor à court terme', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5012', 'Titres d''organismes financiers', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5013', 'Bons de caisse à court terme', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5016', 'Frais d''acquisition des titres du trésor et bons de caisse', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('502', 'Actions', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('5021', 'Actions ou parts propres', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5022', 'Actions cotées', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5023', 'Actions non cotées', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5024', 'Actions démembrées (certificats d''investissement ; droits de vote)', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5025', 'Autres actions', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5026', 'Frais d''acquisition des actions', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('503', 'Obligations', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('5031', 'Obligations émises par la société et rachetées par elle', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5032', 'Obligations cotées', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5033', 'Obligations non cotées', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5035', 'Autres obligations', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5036', 'Frais d''acquisition des obligations', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('504', 'Bons de souscription', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('5042', 'Bons de souscription d''actions', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5043', 'Bons de souscription d''obligations', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('505', 'Titres negociables hors region', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('506', 'Interêts courus', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('5061', 'Titres du Trésor et bons de caisse à court terme', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5062', 'Actions', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5063', 'Obligations', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('508', 'Autres valeurs assimilees', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('51', 'Valeurs à encaisser', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('511', 'Effets a encaisser', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('512', 'Effets a l''encaissement', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('513', 'Cheques a encaisser', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('514', 'Cheques a l''encaissement', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('515', 'Cartes de credit a encaisser', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('518', 'Autres valeurs a l''encaissement', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('5181', 'Warrants', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5182', 'Billets de fonds', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5185', 'Chèques de voyage', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5186', 'Coupons échus', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5187', 'Intérêts échus des obligations', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('52', 'Banques', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('521', 'Banques locales', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('5211', 'Banque X', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5212', 'Banque Y', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('522', 'Banques autres etats region', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('523', 'Banques autres etats zone monetaire', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('524', 'Banques hors zone monetaire', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('525', 'Banques depot a terme', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('526', 'Banques, interet courus', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('53', 'Etablissements financiers et assimilés', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('531', 'Cheques postaux', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('532', 'Tresor', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('533', 'Societes de gestion et d''intermediation (s.g.i.)', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('536', 'Etablissements financiers, interets courus', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('5361', 'Etablissements financiers, intérêts courus, charges à payer', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5367', 'Etablissements financiers, intérêts courus produits à recevoir', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('538', 'Autres organismes financiers', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('54', 'Instruments de trésorerie', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('541', 'Options de taux d''interêt', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('542', 'Options de taux de change', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('543', 'Options de taux boursiers', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('544', 'Instruments de marches a terme', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('545', 'Avoirs d''or et autres metaux precieux', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('55', 'Instruments de monnaie électronique', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('551', 'Monnaie electronique - carte carburant', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('552', 'Monnaie electronique - telephone portable', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('553', 'Monnaie electronique - carte peage', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('554', 'Porte - monnaie electronique', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5561', 'Banques, intérêts courus, charges à payer', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('558', 'Autres instruments de monnaie electronique', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('56', 'Banques, crédits de trésorerie et d''escompte', 'PASSIF', 5, FALSE, TRUE, FALSE),
    ('561', 'Credits de tresorerie', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('564', 'Escompte de credits de campagne', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('565', 'Escompte de credits ordinaires', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('566', 'Credits de tresorerie, interets courus', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('5667', 'Banques, intérêts courus, produits à recevoir', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('57', 'Caisse', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('571', 'Caisse siege social', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('5711', 'Caisse siege social : dans l''unité monétaires légales des pays (UML)', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5712', 'Caisse siege social : en devises', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('572', 'Caisse succursale A', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('5721', 'Caisse succursale A : en UML', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5722', 'Caisse succursale A : en devises', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('573', 'Caisse succursale B', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('5731', 'Caisse succursale B : en UML', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('5732', 'Caisse succursale B : en devises', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('58', 'Régies d''avances, accréditifs et virements internes', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('581', 'Regies d''avance', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('582', 'Accreditifs', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('585', 'Virements de fonds', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('588', 'Autres virements internes', 'ACTIF', 5, TRUE, TRUE, FALSE),
    ('59', 'Dépréciations et provisions pour risques à court terme (Trésorerie)', 'ACTIF', 5, FALSE, TRUE, FALSE),
    ('590', 'Dépréciations des titres de placement', 'PASSIF', 5, TRUE, TRUE, FALSE),
    ('591', 'Depreciations des titres et valeurs a encaisser', 'PASSIF', 5, TRUE, TRUE, FALSE),
    ('592', 'Depreciations des comptes banques', 'PASSIF', 5, TRUE, TRUE, FALSE),
    ('593', 'Depreciations des comptes etablissements financiers et assimiles', 'PASSIF', 5, TRUE, TRUE, FALSE),
    ('594', 'Depreciations des comptes d''instruments de tresorerie', 'PASSIF', 5, TRUE, TRUE, FALSE),
    ('599', 'Provisions pour risrques a court terme a caractere financier', 'PASSIF', 5, TRUE, TRUE, FALSE),
    ('60', 'Achats', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('601', 'Achats de marchandises', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6011', 'Achats de marchandises : dans la Région', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6012', 'Achats de marchandises : hors Région', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6013', 'Achats de marchandises : aux entités du groupe dans la Région', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6014', 'Achats de marchandises : aux entités du groupe hors Région', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6015', 'Frais sur achats', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6019', 'Rabais, Remises et Ristournes obtenus (non ventilés)', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('602', 'Achats de matieres premieres et fournitures liees', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6021', 'Achats de matieres premieres et fournitures liees : dans la Région', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6022', 'Achats de matieres premieres et fournitures liees : hors Région', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6023', 'Achats de matieres premieres et fournitures liees : aux entités du groupe dans la Région', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6024', 'Achats de matieres premieres et fournitures liees : aux entités du groupe hors Région', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6025', 'Frais sur Achats', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6029', 'Rabais, Remises et Ristournes obtenus (non ventilés)', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('603', 'Variations des stocks de biens achetés', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6031', 'Variations des stocks de marchandises', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6032', 'Variations des stocks de matières premières et fournitures liées', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6033', 'Variations des stocks d''autres approvisionnements', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('604', 'Achats stockes de matieres et fournitures consommables', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6041', 'Matières consommables', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6042', 'Matières combustibles', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6043', 'Produits d''entretien', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6044', 'Fournitures d''atelier et d''usine', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6045', 'Frais sur Achats', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6046', 'Fournitures de magasin', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6047', 'Fournitures de bureau', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6049', 'Rabais, Remises et Ristournes obtenus (non ventilés)', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('605', 'Autres achats', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6051', 'Fournitures non stockables - Eau', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6052', 'Fournitures non stockables - Electricité', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6053', 'Fournitures non stockables - Autres énergies', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6054', 'Fournitures d''entretien non stockables', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6055', 'Fournitures de bureau non stockables', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6056', 'Achats de petit matériel et outillage', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6057', 'Achats d''études et prestations de service', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6058', 'Achats de travaux, matériels et équipements', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6059', 'Rabais, Remises et Ristournes obtenus (non ventilés)', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('608', 'Achats d''emballages', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6081', 'Emballages perdus', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6082', 'Emballages récupérables non identifiables', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6083', 'Emballages à usage mixte', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6085', 'Frais sur achats', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6089', 'Rabais, Remises et Ristournes obtenus (non ventilés)', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('61', 'Transports', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('612', 'Transports sur ventes', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('613', 'Transports pour le compte de tiers', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('614', 'Transports du personnel', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('616', 'Transport de plis', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('618', 'Autres frais de transport', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6181', 'Voyages et déplacements', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6182', 'Transports entre établissements ou chantiers', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6183', 'Transports entre établissements ou chantiers', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('621', 'Sous - traitance generale', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('622', 'Locations et charges locatives', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6221', 'Locations de terrains', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6222', 'Locations de bâtiments', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6223', 'Locations de matériels et outillages', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6224', 'Malis sur emballages', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6225', 'Locations d''emballages', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6226', 'Fermages et loyers du foncier', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6228', 'Locations et charges locatives diverses', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('623', 'Redevances de location acquisition', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6232', 'Crédit - bail immobilier', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6233', 'Crédit - bail mobilier', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6234', 'Redevances de location acquisition : location vente', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6235', 'Autres contrats de location acquisition', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('624', 'Entretien, reparations et maintenance', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6241', 'Entretien et réparations des biens immobiliers', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6242', 'Entretien et réparations des biens mobiliers', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6243', 'Maintenance', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6248', 'Autres entretiens et réparations', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('625', 'Primes d''assurance', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6251', 'Assurances multirisques', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6252', 'Assurances matériel de transport', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6253', 'Assurances risques d''exploitation', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6254', 'Assurances responsabilité du producteur', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6255', 'Assurances insolvabilité clients', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6257', 'Assurances transports sur ventes', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6258', 'Autres primes d''assurances', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('626', 'Etudes, recherches et documentation', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6261', 'Etudes et recherches', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6265', 'Documentation générale', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6266', 'Documentation technique', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('627', 'Publicite, publications, relations publiques', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6271', 'Annonces, insertions', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6272', 'Catalogues, imprimés publicitaires', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6273', 'Echantillons', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6274', 'Foires et expositions', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6275', 'Publications', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6276', 'Cadeaux à la clientèle', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6277', 'Frais de colloques, séminaires, conférences', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6278', 'Autres charges de publicité et relations publiques', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('628', 'Frais de telecommunications', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6281', 'Frais de téléphone', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6282', 'Frais de télex', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6283', 'Frais de télécopie', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6288', 'Autres frais de télécommunications', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('631', 'Frais bancaires', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6311', 'Frais sur titres (, vente, garde)', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6312', 'Frais sur effets', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6313', 'Location de coffres', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6314', 'Commissions d'' affacturage', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6315', 'Commissions sur cartes de crédit', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6316', 'Frais d''émission d''emprunts', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6317', 'Frais sur instruments monétaires électroniques', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6318', 'Autres frais bancaires', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('632', 'Remunerations d''intermediaires et de conseils', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6322', 'Commissions et courtages sur ventes', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6324', 'Honoraires des professions règlementées', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6325', 'Frais d''actes et de contentieux', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6327', 'Rémunérations des autres prestataires de services', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6328', 'Divers frais', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('633', 'Frais de formation du personnel', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('634', 'Redevances pour brevets, licences, marques, logiciels, sites, concessions et droits et', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6342', 'Redevances pour brevets, licences', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6343', 'Redevances pour logiciels', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6344', 'Redevances pour marques', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6345', 'Redevances pour sites', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6346', 'Redevances pour concessions, droits et valeurs similaires', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('635', 'Cotisations', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6351', 'Cotisations', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6358', 'Concours divers', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('637', 'Remunerations de personnel exterieur a l''entite', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6371', 'Personnel intérimaire', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6372', 'Personnel détaché ou prêté à l''entité', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('638', 'Autres charges externes', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6381', 'Frais de recrutement du personnel', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6382', 'Frais de déménagement', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6383', 'Réceptions', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6384', 'Missions', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('64', 'Impôts et taxes', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('641', 'Impôts et taxes directs', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6411', 'Impôts fonciers et taxes annexes', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6412', 'Patentes, licences et taxes annexes', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6413', 'Taxes sur appointements et salaires', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6414', 'Taxes d''apprentissage', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6415', 'Formation professionnelle continue', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6418', 'Autres impôts et taxes directs', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('645', 'Impôts et taxes indirects', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('646', 'Droits d''enregistrement', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6461', 'Droits de mutation', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6462', 'Droits de timbre', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6463', 'Taxes sur les véhicules de société', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6464', 'Vignettes', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6468', 'Autres droits', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('647', 'Penalites et amendes fiscales', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6471', 'Pénalités d''assiette, impôts directs', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6472', 'Pénalités d''assiette, impôts indirects', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6473', 'Pénalités de recouvrement, impôts directs', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6474', 'Pénalités de recouvrement, impôts indirects', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6478', 'Autres amendes pénales et fiscales', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('648', 'Autres impôts et taxes', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('65', 'Autres charges (sauf 659)', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('651', 'Pertes sur creances clients et autres debiteurs', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6511', 'Clients', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6515', 'Autres débiteurs', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('652', 'Quote - part de resultat sur operations faites en commun', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6521', 'Quote - part transférée de bénéfices (comptabilité du gérant)', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6525', 'Pertes imputées par transfert (comptabilité des associés non gérants)', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('654', 'Valeurs comptables des cessions courantes d''immobilisations', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('658', 'Charges diverses', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6581', 'Indemnités de fonction et autres rémunérations d''administrateurs', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6582', 'Dons', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6583', 'Mécénat', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6588', 'Autres charges diverses', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('659', 'Charges pour dépréciations et provisions pour risques a court', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6591', 'Charges pour dépréciations et provisions pour risques a court : sur risques à court terme', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6593', 'Charges pour dépréciations et provisions pour risques a court : sur stocks', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6594', 'Charges pour dépréciations et provisions pour risques a court : sur créances', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6598', 'Autres charges pour dépréciations et provisions pour risques à court terme', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('66', 'Charges de personnel', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('661', 'Remunerations directes versees au personnel national', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6611', 'Appointements, salaires et commissions', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6612', 'Primes et gratifications', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6613', 'Congés payés', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6614', 'Indemnités de préavis, de licenciement et de recherche d''embauche', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6615', 'Indemnités de maladie versées aux travailleurs', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6616', 'Supplément familial', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6617', 'Avantages en nature', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6618', 'Autres rémunérations directes', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('662', 'Remunerations directes versees au personnel non national', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6621', 'Appointements, salaires et commissions', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6622', 'Primes et gratifications', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6623', 'Congés payés', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6624', 'Indemnités de préavis, de licenciement et de recherche d''embauche', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6625', 'Indemnités de maladie versées aux travailleurs', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6626', 'Supplément familial', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6627', 'Avantages en nature', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6628', 'Autres rémunérations directes', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('663', 'Indemnites forfaitaires versees au personnel', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6631', 'Indemnités de logement', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6632', 'Indemnités de représentation', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6633', 'Indemnités d''expatriation', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6638', 'Autres indemnités et avantages divers', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('664', 'Charges sociales', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6641', 'Charges sociales sur rémunération du personnel national', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6642', 'Charges sociales sur rémunération du personnel non national', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('666', 'Rémunération et charges sociales de l''exploitant individuel', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6661', 'Rémunération du travail de l''exploitant', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6662', 'Charges sociales', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('667', 'Rémunération transférée de personnel extérieur', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6671', 'Personnel intérimaire', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6672', 'Personnel détaché ou prêté à l''entité', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('668', 'Autres charges sociales', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6681', 'Versements aux Syndicats et Comités d''entreprise, d''établissement', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6682', 'Versements aux Comités d''hygiène et de sécurité', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6683', 'Versements aux autres œuvres sociales', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6684', 'Médecine du travail et pharmacie', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6685', 'Assurances et organismes de santé', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6686', 'Assurances retraite et fonds de pension', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('67', 'Frais financiers et charges assimilées', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('671', 'Interêts des emprunts', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6711', 'Emprunts obligataires', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6712', 'Emprunts auprès des établissements de crédit', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6713', 'Dettes liées à des participations', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6714', 'Primes de remboursement des obligations', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('672', 'Interêts dans loyers de location acquisition', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6721', 'Intérêts dans loyers de location acquisition / crédit - bail immobilier', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6722', 'Intérêts dans loyers de location acquisition / crédit - bail mobilier', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6723', 'Intérêts dans loyers de location acquisition / location vente', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6724', 'Intérêts dans loyers des autres contrats', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('673', 'Escomptes accordes', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('674', 'Autres interêts', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6741', 'Avances reçues et dépôts créditeurs', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6742', 'Comptes courants bloqués', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6743', 'Intérêts sur obligations cautionnées', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6744', 'Intérêts sur dettes commerciales', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6745', 'Intérêts bancaires et sur opérations de trésorerie et d''escompte', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6748', 'Intérêts sur dettes diverses', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('675', 'Escomptes des effets de commerce', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('676', 'Pertes de change', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('677', 'Pertes sur titres de placement', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6771', 'Pertes sur cessions de titres de placement', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6772', 'Malis provenant d''attribution gratuite d''actions au personnel salarié et aux dirigeants', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('678', 'Pertes sur risques financiers', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6781', 'Pertes sur risques financiers : sur rentes viagères', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6782', 'Pertes sur risques financiers : sur opérations financières', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6784', 'Pertes sur risques financiers : sur instruments de trésorerie', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('679', 'Charges pour depreciations et provisions pour risques a court terme financieres', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6791', 'charges pour dépréciation sur risques financiers et le crédit du compte 594 dépréciations des comptes d''instruments', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6795', 'Charges pour depreciations et provisions pour risques a court terme financieres : sur titres de placement', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6798', 'Autres charges pour dépréciations et provisions pour risques à court terme financières', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('68', 'Dotations aux amortissements', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('681', 'Dotations aux amortissements d''exploitation', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6812', 'Dotations aux amortissements des immobilisations incorporelles', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6813', 'Dotations aux amortissements des immobilisations corporelles', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('687', 'Dotations aux amortissements a caractere financier', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('69', 'Dotations aux provisions et aux dépréciations', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('691', 'Dotations aux provisions et aux depreciations d''exploitation', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6911', 'Dotations aux provisions et aux depreciations d''exploitation : pour risques et charges', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6913', 'Dotations aux provisions et aux depreciations d''exploitation : des immobilisations incorporelles', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6914', 'Dotations aux provisions et aux depreciations d''exploitation : des immobilisations corporelles', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('697', 'Dotations aux provisions et aux depreciations financieres', 'CHARGE', 6, FALSE, TRUE, FALSE),
    ('6971', 'Dotations aux provisions et aux depreciations financieres : pour risques et charges', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6972', 'Dotations aux provisions et aux depreciations financieres : des immobilisations financières', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('70', 'Ventes', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('701', 'Ventes de marchandises', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7011', 'Ventes de marchandises : dans la Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7012', 'Ventes de marchandises : hors Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7013', 'Ventes de marchandises : aux entités du groupe dans la Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7014', 'Ventes de marchandises : aux entités du groupe hors Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7015', 'Ventes de marchandises : sur internet', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('702', 'Ventes de produits finis', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7021', 'Ventes de produits finis : dans la Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7022', 'Ventes de produits finis : hors Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7023', 'Ventes de produits finis : aux entités du groupe dans la Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7024', 'Ventes de produits finis : aux entités du groupe hors Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7025', 'Ventes de produits finis : sur internet', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('703', 'Ventes de produits intermediaires', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7031', 'Ventes de produits intermediaires : dans la Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7032', 'Ventes de produits intermediaires : hors Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7033', 'Ventes de produits intermediaires : aux entités du groupe dans la Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7034', 'Ventes de produits intermediaires : aux entités du groupe hors Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7035', 'Ventes de produits intermediaires : sur internet', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('704', 'Ventes de produits residuels', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7041', 'Ventes de produits residuels : dans la Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7042', 'Ventes de produits residuels : hors Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7043', 'Ventes de produits residuels : aux entités du groupe dans la Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7044', 'Ventes de produits residuels : aux entités du groupe hors Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7045', 'Ventes de produits residuels : sur internet', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('705', 'Travaux factures', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7051', 'Travaux factures : dans la Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7052', 'Travaux factures : hors Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7053', 'Travaux factures : aux entités du groupe dans la Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7054', 'Travaux factures : aux entités du groupe hors Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7055', 'Travaux factures : sur internet', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('706', 'Services vendus', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7061', 'Services vendus : dans la Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7062', 'Services vendus : hors Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7063', 'Services vendus : aux entités du groupe dans la Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7064', 'Services vendus : aux entités du groupe hors Région', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7065', 'Services vendus : sur internet', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('707', 'Produits accessoires', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7071', 'Ports, emballages perdus et autres frais facturés', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7072', 'Commissions et courtages', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7073', 'Locations', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7074', 'Bonis sur reprises et cessions d''emballage', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7075', 'Mise à disposition de personnel', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7076', 'Redevances pour brevets, logiciels, marques et droits similaires', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7077', 'Services exploités dans l''intérêt du personnel', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7078', 'Autres produits accessoires', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('71', 'Subventions d''exploitation', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('711', 'Sur produits a l''exportation', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('712', 'Sur produits a l''importation', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('713', 'Sur produits de perequation', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('718', 'Autres subventions d''exploitation', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7181', 'Versées par l''Etat et les collectivités publiques', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7182', 'Versées par les organismes internationaux', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7183', 'Versées par des tiers', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('72', 'Production immobilisée', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('721', 'Immobilisations incorporelles', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('722', 'Immobilisations corporelles', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('724', 'Production auto - consommée', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('726', 'Immobilisations financières', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('73', 'Variations des stocks de biens et services produits', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('734', 'Variations des stocks de produits en cours', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7341', 'Produits en cours', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7342', 'Travaux en cours', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('735', 'Variations des en - cours de services', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7351', 'Etudes en cours', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7352', 'Prestations de services en cours', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('736', 'Variations des stocks de produits finis', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('737', 'Variations des stocks de produits intermediaires et residuels', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7371', 'Produits intermédiaires', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7372', 'Produits résiduels', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('75', 'Autres produits (sauf compte 759)', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('752', 'Quote - part de resultat sur operations faites en commun', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7521', 'Quote - part transférée de pertes (comptabilité du gérant)', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7525', 'Bénéfices attribués par transfert (comptabilité des associés non gérants)', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('754', 'Produits des cessions courantes d''immobilisations', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('758', 'Produits divers', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7581', 'Indemnités de fonction et autres rémunérations', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7582', 'Indemnités d''assurances reçues', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7588', 'Autres produits divers', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('759', 'Reprises de charges pour depreciations et provisions a court terme d''exploitation', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7591', 'Reprises de charges pour depreciations et provisions a court terme d''exploitation : sur risques à court terme', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7593', 'Reprises de charges pour depreciations et provisions a court terme d''exploitation : sur les stocks', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7594', 'Reprises de charges pour depreciations et provisions a court terme d''exploitation : sur les créances', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7598', 'Reprises de charges pour depreciations et provisions a court terme d''exploitation : sur autres charges pour dépréciations et provisions pour risques à court terme', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('77', 'Revenus financiers et produits assimilés', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('771', 'Interets de prêts et creances diverses', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7712', 'Intérêts de prêts', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7713', 'Intérêts sur créances diverses', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('772', 'Revenus de participations et autres creances immobilises', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7721', 'Revenus des titres de participation', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7722', 'Revenus des autres titres immobilisés', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('773', 'Escomptes obtenus', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('774', 'Revenus de placement', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7745', 'Revenus des obligations', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7746', 'Revenus des titres de placement', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('775', 'Interets dans loyers de location acquisition', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('776', 'Gains de change', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('777', 'Gains sur cessions de titres de placement', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('778', 'Gains sur risques financiers', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7781', 'Gains sur risques financiers : sur rentes viagères', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7782', 'Gains sur risques financiers : sur opérations financières', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7784', 'Gains sur risques financiers : sur instruments de trésorerie', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('779', 'Reprises de charges pour dépréciations et provisions pour risques À court terme', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7791', 'Reprises de charges pour dépréciations et provisions pour risques À court terme : sur risques financiers', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7795', 'Reprises de charges pour dépréciations et provisions pour risques À court terme : sur titres de placement', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7798', 'Reprises de charges pour dépréciations et provisions pour risques À court terme : sur Autres charges pour dépréciations et provisions pour risques à court terme financières', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('78', 'Transferts de charges', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('781', 'Transferts de charges d''exploitation', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('787', 'Transferts de charges financieres', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('79', 'Reprises de provisions, dépréciations et autres', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('791', 'Reprises de provisions et de depreciations d''exploitation', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7911', 'Reprises de provisions et de depreciations d''exploitation : pour risques et charges', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7913', 'Reprises de provisions et de depreciations d''exploitation : des immobilisations incorporelles', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7914', 'Reprises de provisions et de depreciations d''exploitation : des immobilisations corporelles __________________', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('797', 'Reprises de provisions et', 'PRODUIT', 7, FALSE, TRUE, FALSE),
    ('7971', 'Reprises de provisions et : pour risques et charges', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('7972', 'Reprises de provisions et : des immobilisations financières', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('798', 'Reprises d''amortissements', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('799', 'Reprises de subventions d''investissement', 'PRODUIT', 7, TRUE, TRUE, FALSE),
    ('81', 'Valeurs comptables des cessions d''immobilisations', 'CHARGE', 8, FALSE, TRUE, FALSE),
    ('811', 'Immobilisations incorporelles', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('812', 'Immobilisations corporelles', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('816', 'Immobilisations financieres', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('82', 'Produits des cessions d''immobilisations', 'PRODUIT', 8, FALSE, TRUE, FALSE),
    ('821', 'Immobilisations incorporelles', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('822', 'Immobilisations corporelles', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('826', 'Immobilisations financieres', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('83', 'Charges hors activités ordinaires', 'CHARGE', 8, FALSE, TRUE, FALSE),
    ('831', 'Charges H.A.O. constatees', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('833', 'Charges liees aux operations de restructuration', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('834', 'Pertes sur créances H.A.O', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('835', 'Dons et libéralités accordés', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('836', 'Abandons de creances consentis', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('837', 'Charges liees aux operations de liquidation', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('839', 'Charges pour depreciations et provisions pour risques a court terme H.A.O', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('84', 'Produits hors activités ordinaires', 'PRODUIT', 8, FALSE, TRUE, FALSE),
    ('841', 'Produits H.A.O. constates', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('843', 'Produits lies aux operations de restructuration', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('845', 'Dons et libéralités obtenus', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('846', 'Abandons de creances obtenus', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('847', 'Produits lies aux operations de liquidation', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('848', 'Transferts de charges H.A.O', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('849', 'Reprises de charges pour depreciations et provisions pour risques a court terme H.A.O', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('85', 'Dotations hors activités ordinaires', 'CHARGE', 8, FALSE, TRUE, FALSE),
    ('851', 'Dotations aux provisions reglementees', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('852', 'Dotations aux amortissements H.A.O', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('853', 'Dotations aux depreciations H.A.O', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('854', 'Dotations aux provisions pour risques et charges H.A.O', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('858', 'Autres dotations H.A.O', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('86', 'Reprises hors activités ordinaires', 'PRODUIT', 8, FALSE, TRUE, FALSE),
    ('861', 'Reprises de provisions reglementees', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('862', 'Reprises d''amortissements H.A.O', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('863', 'Reprises de depreciations H.A.O', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('864', 'Reprises de provisions pour risques et charges H.A.O', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('868', 'Autres reprises H.A.O', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('87', 'Participation des travailleurs', 'CHARGE', 8, FALSE, TRUE, FALSE),
    ('871', 'Participation légale aux bénéfices', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('872', 'Participation contractuelle aux bénéfices', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('878', 'Autres participations', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('88', 'Subventions d''équilibre', 'PRODUIT', 8, FALSE, TRUE, FALSE),
    ('881', 'Etat', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('884', 'Collectivités publiques', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('886', 'Groupe', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('888', 'Autres', 'PRODUIT', 8, TRUE, TRUE, FALSE),
    ('89', 'Impôts sur le résultat', 'CHARGE', 8, FALSE, TRUE, FALSE),
    ('891', 'Impôts sur les benefices de l''exercice', 'CHARGE', 8, FALSE, TRUE, FALSE),
    ('8911', 'Activités exercées dans l''Etat', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('8912', 'Activités exercées dans les autres Etats de la Région', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('8913', 'Activités exercées hors Région', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('892', 'Rappels d''impôts sur resultats anterieurs', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('895', 'Impôt minimum forfaitaire (i.m.f.)', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('899', 'Dégrevements et annulations d''impôts sur résultats antérieurs', 'CHARGE', 8, FALSE, TRUE, FALSE),
    ('8991', 'Dégrèvements', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('8994', 'Annulations pour pertes rétroactives', 'CHARGE', 8, TRUE, TRUE, FALSE),
    ('9011', 'Crédits confirmés obtenus', NULL, 9, TRUE, FALSE, FALSE),
    ('9012', 'Emprunts restant à encaisser', NULL, 9, TRUE, FALSE, FALSE),
    ('9021', 'Avals obtenus', NULL, 9, TRUE, FALSE, FALSE),
    ('9022', 'Cautions, garanties obtenues', NULL, 9, TRUE, FALSE, FALSE),
    ('9023', 'Hypothèques obtenues', NULL, 9, TRUE, FALSE, FALSE),
    ('9024', 'Effets endossés par des tiers', NULL, 9, TRUE, FALSE, FALSE),
    ('9028', 'Autres garanties obtenues', NULL, 9, TRUE, FALSE, FALSE),
    ('9031', 'Achats de marchandises à terme', NULL, 9, TRUE, FALSE, FALSE),
    ('9032', 'Achats à terme de devises', NULL, 9, TRUE, FALSE, FALSE),
    ('9038', 'AUTRES ENGAGEMENTS RÉCIPROQUES peuvent être contrôlés à partir des bons de commande, des bons de', NULL, 9, TRUE, FALSE, FALSE),
    ('9041', 'Abandons de créances conditionnels', NULL, 9, TRUE, FALSE, FALSE),
    ('9061', 'Avals accordés', NULL, 9, TRUE, FALSE, FALSE),
    ('9062', 'Cautions, garanties accordées', NULL, 9, TRUE, FALSE, FALSE),
    ('9063', 'Hypothèques accordées', NULL, 9, TRUE, FALSE, FALSE),
    ('9064', 'Effets endossés par l''entité', NULL, 9, TRUE, FALSE, FALSE),
    ('9068', 'Autres garanties accordées', NULL, 9, TRUE, FALSE, FALSE),
    ('9071', 'Ventes de marchandises à terme', NULL, 9, TRUE, FALSE, FALSE),
    ('9072', 'Ventes à terme de devises', NULL, 9, TRUE, FALSE, FALSE),
    ('9073', 'Commandes fermes aux fournisseurs', NULL, 9, TRUE, FALSE, FALSE),
    ('9078', 'Autres engagements réciproques', NULL, 9, TRUE, FALSE, FALSE),
    ('9081', 'Annulations conditionnelles de dettes', NULL, 9, TRUE, FALSE, FALSE),
    ('9151', 'et 9158', NULL, 9, TRUE, FALSE, FALSE),
    ('9183', 'et 9188', NULL, 9, TRUE, FALSE, FALSE)
ON CONFLICT (numero) DO UPDATE SET
    libelle   = CASE WHEN comptes_ohada.id IN (SELECT id FROM comptes_historiques)
                     THEN comptes_ohada.libelle ELSE EXCLUDED.libelle END,
    type      = COALESCE(comptes_ohada.type, EXCLUDED.type),
    classe    = EXCLUDED.classe,
    imputable = EXCLUDED.imputable;


-- 3. Correction des comptes utilises a contresens ----------------------
-- Le referentiel officiel donne un autre sens a ces numeros. Les ecritures
-- deja passees sont reaffectees au compte officiellement correct, afin que
-- le plan ET l'historique soient conformes.
--
--   6012 « Variation de stocks »   -> 6031 Variations des stocks de marchandises
--        (6012 designe en realite les achats de marchandises hors Region)
--   6384 « Carburant »             -> 6042 Matieres combustibles
--        (6384 designe en realite les missions)
--   6323 « Deplacements/missions » -> 6384 Missions
--   611  « Transports »            -> 6181 Voyages et deplacements
--        (le referentiel ne comporte pas de compte 611)
--   6112 « Transport marchandises »-> 612  Transports sur ventes
--   6212 « Loyers »                -> 6222 Locations de batiments
--
-- Les comptes de banque (521x) et de monnaie electronique restent des
-- extensions legitimes : le referentiel prevoit explicitement de les
-- personnaliser (« Banque X », « Banque Y »).

-- Les comptes cibles doivent exister avant la reaffectation.
INSERT INTO comptes_ohada (numero, libelle, type, classe, imputable, actif, manuel)
VALUES
    ('6031', 'Variations des stocks de marchandises', 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6042', 'Matieres combustibles',                 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6181', 'Voyages et deplacements',               'CHARGE', 6, TRUE, TRUE, FALSE),
    ('612',  'Transports sur ventes',                 'CHARGE', 6, TRUE, TRUE, FALSE),
    ('6222', 'Locations de batiments',                'CHARGE', 6, TRUE, TRUE, FALSE)
ON CONFLICT (numero) DO NOTHING;

-- Reaffectation des ecritures et des imputations de configuration.
-- L'ordre importe : 6323 -> 6384 doit suivre 6384 -> 6042.
DO $$
DECLARE
    r RECORD;
    paires TEXT[][] := ARRAY[
        ['6012','6031'], ['6384','6042'], ['6323','6384'],
        ['611','6181'],  ['6112','612'],  ['6212','6222']
    ];
    src BIGINT; dst BIGINT; i INT;
BEGIN
    FOR i IN 1 .. array_length(paires, 1) LOOP
        SELECT id INTO src FROM comptes_ohada WHERE numero = paires[i][1];
        SELECT id INTO dst FROM comptes_ohada WHERE numero = paires[i][2];
        IF src IS NULL OR dst IS NULL OR src = dst THEN CONTINUE; END IF;
        UPDATE grand_livre           SET compte_id = dst            WHERE compte_id = src;
        UPDATE lignes_note_frais     SET compte_imputation_id = dst WHERE compte_imputation_id = src;
        UPDATE lignes_budget         SET compte_id = dst            WHERE compte_id = src;
        UPDATE articles              SET compte_charge_id = dst     WHERE compte_charge_id = src;
        UPDATE articles              SET compte_stock_id = dst      WHERE compte_stock_id = src;
        UPDATE articles              SET compte_produit_id = dst    WHERE compte_produit_id = src;
        UPDATE depenses_vehicule     SET compte_charge_id = dst     WHERE compte_charge_id = src;
        UPDATE mouvements_stock      SET compte_contrepartie_id = dst WHERE compte_contrepartie_id = src;
    END LOOP;
END $$;

-- La monnaie electronique quitte 582 (Accreditifs) pour 552, son compte
-- officiel. Renumeroter suffit : les cles etrangeres pointent sur l'id.
UPDATE comptes_ohada
SET numero = '55' || substr(numero, 3)
WHERE numero LIKE '582_' AND NOT EXISTS (
    SELECT 1 FROM comptes_ohada c2 WHERE c2.numero = '55' || substr(comptes_ohada.numero, 3));


-- 4. Rubriques commentees des comptes principaux ----------------------

UPDATE comptes_ohada SET contenu = 'Le Capital social traduit le montant des valeurs apportées par les associés.
Dans les sociétés, le capital initial correspond à la valeur des apports (nature ou espèces) effectués par les associés à la
création de l''entité tels qu''ils figurent dans les statuts.
Il est divisé en actions ou parts d''une même valeur nominale.
Au cours de la vie sociale, le capital peut, sur décision des organes compétents, être augmenté ou diminué pour diverses
raisons, notamment : apports et/ou retraits de capital, affectation de résultats et incorporation de réserves.
Pour certaines sociétés, la loi prévoit la limitation de la responsabilité des associés à l''égard des créanciers sociaux en
fixant le montant minimum du capital social.', commentaires = '• le capital social représente la valeur nominale des actions ou parts sociales.
• le compte 1011 – Capital souscrit, non appelé enregistre à son crédit les promesses d''apport en espèces ou en nature,
faites par les associés, par le débit du compte 109 – Apporteurs capital souscrit, non appelé.
• au moment de l''appel d''une nouvelle fraction du capital le compte 1011 est débité par le crédit du compte 1012 à
concurrence du montant appelé.
Corrélativement, le compte 467 – Apporteurs, restant dû sur capital appelé est débité du même montant par le crédit du
compte 109 – Apporteurs, capital souscrit, non appelé.
• le compte 1012 – Capital souscrit, appelé, non versé enregistre à son crédit la fraction de capital en instance d''être
effectivement libérée par les apporteurs.
En cas de libération effective par les apporteurs de la fraction de capital appelé, le compte 1012 – Capital souscrit, appelé,
non versé est viré au compte 1013 – Capital souscrit appelé, versé, non amorti.
• lesorganes compétents peuvent décider de rembourser aux associés tout ou partie du montant nominal de leurs actions
à titre d''avances sur le produit de la liquidation future de la société. Le capital demeure inchangé, les actions amorties
devenant des actions de jouissance. La contre-valeur des actions de jouissance est isolée dans le compte 1014 –
Capital souscrit, appelé, versé, amorti. Les actions dont le capital est partiellement ou totalement amorti donnent les
mêmes droits que les actions non amorties à l''exception du premier dividende (Intérêt statutaire).
• L''attribution gratuite d''actions au personnel salarié de la société et aux dirigeants est traitée au titre VII opérations
spécifiques chapitre 18.
• le compte 1018 – Capital souscrit, soumis à des conditions particulières enregistre à son crédit le montant du capital
provenant d''opérations particulières telles que :
• l''incorporation de plus-values nettes à long terme (P.V.N.L.T.), lorsque les dispositions législatives et réglementaires le
prévoient ;
• l''émission de certificats d''investissement, d''actions de préférence et d''actions à dividendes prioritaires, sans droit de
vote.', fonctionnement = 'Le compte 101 – CAPITAL SOCIAL est crédité du montant : – des apports initiaux ; – des augmentations de capital en
espèces, en nature ou en réserves (déduction faite des primes liées au capital social)
• par le débit du compte 46 – Apporteurs Associés et Groupe, pour les apports en espèces ou en nature, pour les
sociétés de personnes et GIE ;
• ou par le débit du compte 109 Apporteurs capital souscrit, non appelé, pour les sociétés de capitaux ;
• ou par le débit du compte 11 – Réserves, pour l''incorporation de ce poste au capital ;
• ou par le débit du compte 12 – Report à nouveau, pour l''incorporation de ce poste au capital
• ou par le débit du compte 13 – Résultat net de l''exercice, pour l''incorporation de ce poste au capital.
Le compte 101 – CAPITAL SOCIAL est débité des réductions de capital décidées par les Assemblées générales
d''associés
• par le crédit du compte 12 – Report à nouveau, pour l''absorption des pertes antérieures reportées ;
• ou par le crédit du compte 109 Apporteurs, capital souscrit non appelé dans le cas de renonciation d''une partie du
capital non libéré
• ou par le crédit du compte 46 – Apporteurs, Associés et Groupe, dans le cas du remboursement d''une partie du
capital.', exclusions = 'Le compte 101 – CAPITAL SOCIAL ne doit pas servir à enregistrer :
• les versements et/ou retraits temporaires de fonds effectués par les associés
• les apports effectués par l''exploitant individuel
• les apports non remboursables effectués par la puissance publique
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 46 – Apporteurs, Associés et Groupe
• 103 – Capital personnel
• 102 – Capital par dotation', controle = 'Le compte 101 – CAPITAL SOCIAL peut être contrôlé à partir de recoupements issus :
• des statuts de la société ;
• de la déclaration notariée de souscription et de versement ;
• des virements bancaires et relevés de banque ;
• du procès-verbal de l''Assemblée des associés.' WHERE numero = '101';
UPDATE comptes_ohada SET contenu = 'Le Capital par dotation représente la contrepartie de l''intégration au patrimoine des entités publiques, des immobilisations
et fonds affectés, sur décision de l''Autorité publique, au fonctionnement de ces entités.
Cette dotation peut aussi se réaliser par transformation de dettes.', commentaires = '• le compte 102 – Capital par dotation ne saurait être utilisé que dans les entités publiques. Il reçoit en effet les fonds de
dotation des collectivités publiques. Il enregistre la contre-valeur des biens affectés de manière irrévocable à ces
entités.
• il n''en demeure pas moins vrai que certaines subventions d''investissement, accordées par les collectivités auxquelles
les entités sont rattachées, peuvent être considérées comme étant des fonds de dotation. Ce sera notamment le cas
d''espèce d''organismes subventionneurs et d''entité subventionnée émanant de la même personne morale publique.
Dans ce cas, il faut se référer à la décision d''octroi pour leur qualification.', fonctionnement = 'Le compte 102 – CAPITAL PAR DOTATION est crédité des dotations en numéraire et en nature accordées par une
collectivité publique
• par le débit du compte 4493 – Etat, fonds de dotation à recevoir ;
• ou par le débit du compte 45 – Organismes internationaux ;
• ou par le débit du compte 47 – Débiteurs et créditeurs divers ;
• ou encore par le débit des comptes d''actifs concernés, immobilisations, stocks, créances.
Le compte 102 – CAPITAL PAR DOTATION est débité, en cas de reprise contractuelle de dettes
• par le crédit des comptes de passif concernés.', exclusions = 'Le compte 102 – CAPITAL PAR DOTATION ne doit pas servir à enregistrer :
• les sommes reçues à titre de prêts ou d''avances remboursables par les entités publiques
• les sommes reçues à titre de prêts ou d''avances remboursables assorties de conditions particulières
• les sommes reçues à titre de subventions d''investissement dans la mesure où elles ne sont pas transformées en
capital par dotation
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 163 – Avances reçues de l''Etat
• 167 – Avances assorties de conditions particulières
• 14 – Subventions d''investissement', controle = 'Le compte 102 – CAPITAL PAR DOTATION peut être contrôlé à partir de recoupements issus :
• de décret, arrêté ou lettre officielle d''octroi ou de déblocage des fonds ;
• de procès-verbal de remise d''un bien cédé en guise d''apport en nature ;
• de pièces justificatives de virements correspondants' WHERE numero = '102';
UPDATE comptes_ohada SET contenu = 'A la création de l''entité exploitée sous la forme individuelle, le capital initial représente le montant des apports en nature ou
en espèces effectués par l''entrepreneur à titre définitif et des dettes qu''il décide d''inscrire au bilan.
Ce capital initial est modifié ultérieurement par les apports et les retraits de capital ainsi que par l''affectation des résultats.', commentaires = 'Ce compte ne doit pas être confondu avec le compte de l''exploitant. Lorsque le solde de ce compte est débiteur, il reste au
passif, mais précédé du signe moins.', fonctionnement = 'Le compte 103 – CAPITAL PERSONNEL est crédité des apports effectués par l''exploitant à titre définitif, en début ou
en cours d''activité
• par le débit des comptes d''actifs concernés : immobilisations, stocks, trésorerie ; à la clôture de l''exercice, de
l''apport net issu du solde du Compte de l''exploitant
• par le débit du compte 104 – Compte de l''exploitant.
Le compte 103 – CAPITAL PERSONNEL est crédité, à l''ouverture de l''exercice, du montant de l''affectation du résultat
de l''exercice précédent
• par le débit du compte 131 – Résultat net : Bénéfice.
Le compte 103 – CAPITAL PERSONNEL est débité, à l''ouverture de l''exercice, du montant de l''affectation du résultat
de l''exercice précédent
• par le crédit du compte 139 – Résultat net : Perte.
Le compte 103 – CAPITAL PERSONNEL est débité, à la clôture de l''exercice, du solde du compte de l''exploitant
(retraits nets)
• par le crédit du compte 104 – Compte de l''exploitant.', exclusions = 'Le compte 103 – CAPITAL PERSONNEL ne doit pas servir à enregistrer :
• les prélèvements et versements effectués dans les entités non individuelles
• les prélèvements et apports effectués par l''exploitant à titre temporaire
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 462 – Apporteurs, Associés, comptes courants
• 104 – Compte de l''exploitant', controle = 'Le compte 103 – CAPITAL PERSONNEL peut être contrôlé à partir de recoupements issus :
• du compte de résultat de l''exercice précédent ;
• des virements ;
• des fiches de caisse ;
• des relevés de banque.' WHERE numero = '103';
UPDATE comptes_ohada SET contenu = 'Ce compte sert à établir la situation de l''entrepreneur en ce qui concerne :
• les apports et compléments d''apports financiers et/ou de biens et services effectués à titre temporaire en cours
d''exercice. Ces apports et compléments d''apports financiers peuvent consister en des versements dans la caisse ou sur
un compte bancaire de l''entité ou en des règlements de dépenses de l''entité sur la trésorerie privée de l''exploitant ;
• les retraits effectués au cours de l''exercice pour son usage personnel ou celui de sa famille et dans le cadre de
l''exploitation. Ceux-ci consistent en des :', commentaires = 'Le compte 104 – Compte de l''exploitant est en fait un démembrement du compte 103 – Capital personnel. A ce titre, il est
systématiquement soldé à la clôture de l''exercice.', fonctionnement = 'Le compte 104 – COMPTE DE L''EXPLOITANT est crédité, en cours d''exercice, des apports et compléments d''apports
financiers et/ou de biens et services effectués par l''exploitant à titre temporaire
• par le débit d''un compte de trésorerie ou des comptes d''actifs correspondants.
Le compte 104 – COMPTE DE L''EXPLOITANT est crédité, à la clôture de l''exercice, du montant débiteur de son solde
• par le débit du compte 103 – Capital personnel
Le compte 104 – COMPTE DE L''EXPLOITANT est débité, en cours d''exercice, des retraits de fonds ou des
prélèvements de biens et services effectués par l''exploitant, pour son usage personnel ou celui de sa famille et de
l''exploitation
• par le crédit des comptes d''actifs correspondants.
Le compte 104 – COMPTE DE L''EXPLOITANT est débité, à la clôture de l''exercice, du montant de son solde créditeur
• par le crédit du compte 103 – Capital personnel.', exclusions = 'Le compte 104 – COMPTE DE L''EXPLOITANT ne doit pas servir à enregistrer :
• les prélèvements et versements effectués dans des entités non individuelles
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 462 – Apporteurs, Associés, comptes courants', controle = 'Le compte 104 – COMPTE DE L''EXPLOITANT peut être contrôlé à partir de recoupements issus :
• des virements ;
• des fiches de caisse ;
• des relevés de banque.' WHERE numero = '104';
UPDATE comptes_ohada SET contenu = 'La prime peut être analysée comme étant un droit d''entrée demandé au nouvel actionnaire ou associé d''autant que l''action
ou la part sociale vaut, avant augmentation du capital, beaucoup plus que sa valeur nominale. Elle représente une partie
des apports purs et simples non comprise dans le capital social.
Les primes liées aux capitaux propres doivent figurer distinctement au passif du bilan dans les rubriques appropriées. Selon
la nature des opérations d''augmentation de capital, en nature ou en espèces, il y a lieu de distinguer quatre (4) catégories
de primes, d''émission, de fusion, d''apport et de conversion.', commentaires = '• la prime d''émission est égale à l''excédent du prix d''émission en numéraire (c''est-à-dire le prix payé par le souscripteur)
sur la valeur nominale des actions ou parts sociales.
• la prime de fusion représente la différence entre la valeur réelle de l''entité absorbée et la valeur nominale des actions ou
parts sociales rémunérant l''apport.
• la prime d''apport représente la différence entre la valeur du ou des biens apportés et la valeur nominale des actions ou
des parts sociales rémunérant l''apport.
• la prime de conversion représente la différence entre la valeur de conversion du ou des titres de créances et la valeur
nominale des actions ou des parts sociales rémunérant l''apport.', fonctionnement = 'Le compte 105 – PRIMES LIEES AUX CAPITAUX PROPRES est crédité lors des augmentations de capital
• par le débit des comptes d''associés, de comptes de tiers ou de comptes de trésorerie.
Le compte 105 – PRIMES LIEES AUX CAPITAUX PROPRES est débité en cas d''incorporation des primes au capital
• par le crédit du compte 101 – Capital social
Le compte 105 – PRIMES LIEES AUX CAPITAUX PROPRES est débité en cas d''absorption de pertes
• par le crédit du compte 12 – Report à nouveau ou 139 – Résultat net : pertes.
Le compte 105 – PRIMES LIEES AUX CAPITAUX PROPRES est débité en cas de remboursement du capital
• par le crédit du compte 462 – Apporteurs, Associés, comptes courants.
Le compte 105 – PRIMES LIEES AUX CAPITAUX PROPRES est débité, en cas d''augmentation du capital, du
montant des frais de cette augmentation
• par le crédit du compte 78 – Transferts de charges, en cas d''imputation des frais d''augmentation du capital.', exclusions = 'Le compte 105 – PRIMES LIEES AUX CAPITAUX PROPRES ne doit pas servir à enregistrer certaines sommes
qualifiées de primes, exemples : primes de remboursement des obligations, primes d''assurance, primes de création
d''emplois, primes de développement
Il convient dans les cas d''espèce d''utiliser des comptes tels que :
• 6714 – Primes de remboursement des obligations
• 625 – Primes d''assurance
• 7078 – Autres produits accessoires
• etc.', controle = 'Le compte 105 – PRIMES LIEES AUX CAPITAUX PROPRES peut être contrôlé à partir de recoupements issus :
• des décisions de l''Assemblée des associés portant augmentation du capital social ;
• des textes relatifs au protocole de fusion ;
• des textes relatifs au protocole d''apport ;
• des factures de frais ou du calcul analytique des frais d''augmentation de capital.' WHERE numero = '105';
UPDATE comptes_ohada SET contenu = 'L''écart de réévaluation représente la contrepartie au passif du bilan des augmentations de valeur d''éléments actifs soit
dans le cadre d''une réévaluation légale, soit dans celui d''une réévaluation libre.
La différence entre les valeurs réévaluées et les valeurs nettes précédemment comptabilisées constitue l''écart de
réévaluation.
L''écart de réévaluation s''inscrit distinctement au passif du bilan dans les capitaux propres.', commentaires = 'L''écart de réévaluation n''a pas la nature d''un résultat et ne peut être utilisé à compenser les pertes de l''exercice de
réévaluation. Il n''est pas distribuable ; il peut être incorporé en tout ou partie au capital.', fonctionnement = 'Le compte 106 – ECARTS DE REEVALUATION est crédité du montant de la réévaluation des éléments d''actif
réévalués
• par le débit des comptes d''actifs concernés.
Le compte 106 – ECARTS DE REEVALUATION est débité des incorporations directes au capital
• par le crédit du compte 10 – Capital.', exclusions = NULL, controle = 'Le compte 106 – ECARTS DE REEVALUATION peut être contrôlé à partir de recoupements issus :
• des décisions des organes de gestion;
• des décisions de l''Assemblée générale des actionnaires portant augmentation de capital par incorporation de tout
ou partie de l''écart de réévaluation.
• de la comparaison des actifs de l''exercice précèdent avec ceux de l''exercice en cours.' WHERE numero = '106';
UPDATE comptes_ohada SET contenu = 'Ce compte retrace la créance de la société sur les actionnaires ou les associés, pour la fraction du capital non encore
appelé par les organes compétents en cas de libération partielle. Celle-ci peut être consécutive aux opérations de
constitution d''une société ou d''augmentation de capital.', commentaires = 'Le montant inscrit au compte 109 représente en fait la créance globale de la société sur les actionnaires et les associés.
Elle devra être personnalisée pour chacun d''eux au moment des appels effectifs de fonds et portée au débit du compte 467
– Apporteurs, restant dû sur capital appelé.
Le compte 109 figure en seconde ligne au passif du bilan, en moins parmi les capitaux propres.', fonctionnement = 'Le compte 109 – APPORTEURS, CAPITAL SOUSCRIT NON APPELE est débité, lors de la création d''une société ou
lors d''une augmentation de capital, du montant non appelé immédiatement
• par le crédit du compte 101 – Capital social.
Le compte 109 – APPORTEURS, CAPITAL SOUSCRIT NON APPELE est crédité lors des appels successifs du
capital
• par le débit du compte 467 – Apporteurs, restant dû sur capital appelé.', exclusions = NULL, controle = 'Le compte 109 – Apporteurs, capital souscrit, non appelé peut être contrôlé à partir de recoupements issus :
• des statuts ;
• des décisions des Assemblées générales ordinaires et extraordinaires ;
• des décisions du conseil d''administration ou de la gérance pour les appels de fonds ;
• du compte 1011 – Capital souscrit, non appelé, de solde opposé et de montant identique.' WHERE numero = '109';
UPDATE comptes_ohada SET contenu = 'Les réserves correspondent à des bénéfices laissés à la disposition de l''entité et non incorporés au capital.
L''obligation de constituer des réserves résulte des dispositions statutaires ou réglementaires et des décisions des organes
compétents.', commentaires = 'Les réserves accroissent les capitaux propres et comprennent les réserves indisponibles : légales, réglementées et
statutaires ainsi que les réserves libres ou facultatives.
• le compte 111 – Réserve légale est destiné à constater l''obligation annuelle d''alimentation ou de constitution d''un fonds
de réserves, en application de dispositions juridiques régissant certains types de sociétés (SA et SARL, notamment) : la
dotation est égale à 10% au moins prélevée sur le bénéfice de l''exercice diminué le cas échéant des pertes antérieures.
La réserve légale, qui peut également être constituée par prélèvement sur toute réserve disponible (notamment primes
liées au capital), cesse d''être obligatoire lorsque son montant atteint 20 % du montant du capital.
• le compte 113 – Réserves réglementées comprend des subdivisions telles que :
Ce compte est ouvert lorsque la convention de subvention prévoit :
• la constitution par l''entité subventionnée d''une réserve de montant déterminé eu égard à la subvention ;
• le maintien d''une telle réserve au passif du bilan pendant une période déterminée.', fonctionnement = 'Le compte 11 – RESERVES est crédité du montant affecté aux réserves
• par le débit du compte 131 – Résultat net : Bénéfice ou le débit du compte 1301 – Résultat en instance
d''affectation : Bénéfice.
Le compte 11 – RÉSERVES est débité des incorporations directes au capital
• par le crédit du compte 101 – Capital social.
Le compte 11 – RÉSERVES est débité des distributions aux associés
• par le crédit du compte 465 – Associés, dividendes à payer.
Le compte 11 – RÉSERVES est débité des prélèvements pour l''amortissement des pertes
• par le crédit des comptes 129 – Report à nouveau débiteur ou 139 – Résultat net : Perte.', exclusions = 'Le compte 11 – RESERVES ne doit pas servir à enregistrer :
• les provisions pour pertes et charges
• les dépréciations des immobilisations
• les dépréciations des comptes de stocks
• les dépréciations des comptes clients
• les dépréciations des comptes de trésorerie
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 19 – Provisions financières pour risques et charges
• 29 –Dépréciations
• 39 – Dépréciations des stocks
• 49 – Dépréciations et provisions pour risques à court terme (Tiers)
• 59–Dépréciations et provisions pour risques à court terme (Trésorerie)', controle = 'Le compte 11 – RESERVES peut être contrôlé à partir de recoupements issus :
• de dispositions législatives, statutaires ou contractuelles obligatoires concernant la répartition des résultats ;
• des décisions de l''Assemblée générale des actionnaires portant répartition des résultats.' WHERE numero = '11';
UPDATE comptes_ohada SET contenu = 'Le report à nouveau correspond au montant soit des bénéfices d''exercices antérieurs dont l''affectation a été reportée sur
les exercices ultérieurs, soit des pertes constatées à la clôture d''exercices antérieurs qui n''ont pas été compensées par des
prélèvements opérés sur les bénéfices, les réserves ou le capital.
Le report à nouveau est inscrit au passif du bilan où il doit figurer sur une ligne distincte : en moins si son solde est débiteur,
et en plus si son solde est créditeur. Il constitue un élément des capitaux propres.', commentaires = 'Le report à nouveau est constitué par :
• les sommes non affectées et laissées à la disposition de l''entité;
• les pertes non compensées par des réserves ou par une diminution du capital ;
• les sommes venant des arrondis des dividendes distribués.
Le fonctionnement de ce compte est subordonné à la décision de l''Assemblée générale statuant sur l''affectation du
bénéfice de l''exercice précédent ou sur le sort des pertes constatées à la clôture de l''exercice précédent.', fonctionnement = 'Le compte 12 – REPORT A NOUVEAU est crédité lors de la répartition des bénéfices
• par le débit du compte 131 – Résultat net : Bénéfice, pour la partie non distribuée, ou non affectée à un compte de
réserves.
Le compte 12 – REPORT A NOUVEAU est débité lors de l''affectation du résultat
• par le crédit du compte 139 – Résultat net : Perte, pour le montant des pertes non compensées par des
prélèvements opérés sur des réserves ou sur le capital ;
• ou par le crédit du compte 465 – Associés, dividendes à payer, pour le report à nouveau mis en distribution.
Lorsque la législation fiscale prévoit un traitement des amortissements différés, différent de celui des pertes
ordinaires, l''entité substituera les sous-comptes 1291 et 1292 au compte 129 – Report à nouveau débiteur.', exclusions = 'Le compte 12 – REPORT A NOUVEAU ne doit pas servir à enregistrer :
• les sommes à porter en réserves par décision de l''Assemblée générale ordinaire
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 11 – Réserves', controle = 'Le compte 12 – REPORT À NOUVEAU peut être contrôlé à partir de recoupements issus des décisions des
assemblées sur la répartition des résultats.' WHERE numero = '12';
UPDATE comptes_ohada SET contenu = 'Le résultat net de l''exercice peut être défini de deux façons : 1. différence entre les produits (reçus ou à recevoir) et les
charges (payées ou à payer) de la période ; 2. variation des capitaux propres entre le début et la clôture de l''exercice, hors
nouveaux apports et retraits d''apports et hors réévaluation.
Quel que soit son signe, le résultat net de l''exercice est inscrit au passif du bilan sur la ligne correspondante, parmi les
capitaux propres.', commentaires = 'Le compte 13 – Résultat net de l''exercice permet de calculer, à la clôture de l''exercice, le résultat net à affecter, après
déduction de l''impôt sur les bénéfices et autres prélèvements obligatoires.
Le solde du compte 13 représente un bénéfice si les produits l''emportent sur les charges (solde créditeur) ou une perte si
les charges l''emportent sur les produits (solde débiteur).
L''affectation du résultat d''un exercice est décidée par les organes compétents au cours de l''exercice suivant. Le compte 13
est donc soldé lors de la comptabilisation de cette affectation.
En fin d''exercice, le résultat de l''exercice précédent non affecté à un compte de réserves et non distribué sera viré au
compte de report à nouveau.
A la réouverture des comptes de l''exercice suivant, les entités ont la possibilité d''utiliser un compte spécial "Résultat en
instance d''affectation".
Dans les entités individuelles, le solde du compte 13 – Résultat net de l''exercice est viré au compte 103 – Capital
personnel.', fonctionnement = 'Le compte 13 – RESULTAT NET DE L''EXERCICE est crédité, à la clôture de l''exercice
• par le débit des comptes de la classe 7 et des comptes créditeurs de la classe 8 pour solde.
Le compte 13 – RESULTAT NET DE L''EXERCICE est crédité, après la clôture de l''exercice et décision d''imputation
des pertes, du montant du résultat déficitaire
• par le débit des comptes : 12 – Report à nouveau, ou 11 – Réserves, ou 101 – Capital social, ou 103 – Capital
personnel.
Le compte 13 – RESULTAT NET DE L''EXERCICE est débité à la clôture de l''exercice du montant des charges de
l''exercice
• par le crédit des comptes de la classe 6 et des comptes débiteurs de la classe 8 pour solde.
Le compte 13 – RESULTAT NET DE L''EXERCICE est débité après la clôture de l''exercice et décision d''affectation des
résultats du montant du résultat déficitaire
• par le crédit des comptes 12 – Report à nouveau ou 11 – Réserves ou 101 – Capital social ou 103 – Capital
personnel ou 465 – Associés, dividendes à payer.', exclusions = 'Le compte 13 – RESULTAT NET DE L''EXERCICE ne doit pas servir à enregistrer :
• les charges ou produits qui n''auraient pas au préalable transité par les comptes de gestion
Il convient dans le cas d''espèce d''utiliser les comptes ci-après :
• classes 6, 7 et 8', controle = 'Le compte 13 – RESULTAT NET DE L''EXERCICE peut être contrôlé à partir de recoupements issus des soldes des
comptes de gestion.' WHERE numero = '13';
UPDATE comptes_ohada SET contenu = 'Les subventions d''investissement sont des aides financières non remboursables accordées aux entités (publiques ou
privées), pour différentes raisons : acquisition, création de valeurs immobilisées (subventions d''équipement) ou financement
d''activités à long terme, afin de pourvoir au remplacement ou à la remise en état des immobilisations. Elles peuvent
également consister en l''octroi de biens et services (voir Titre VII Opérations spécifiques, Chapitre 16).', commentaires = 'Les subventions d''investissement sont accordées par l''Etat, les collectivités publiques, les organismes internationaux ou les
tiers, éventuellement, en vue d''acquérir ou de créer des immobilisations et de financer des activités à long terme.
Dans certains cas, l''entité reçoit ladite subvention d''investissement sous la forme d''un transfert direct d''immobilisations, à
titre gratuit.
Les subventions d''investissement figurent pour leur montant net au passif du bilan, parmi les capitaux propres, jusqu''à ce
qu''elles aient rempli leur objet.
Le compte 14 permet aux entités subventionnées d''échelonner sur plusieurs exercices l''enrichissement provenant de ces
subventions.
La quote-part de subvention reprise dans le résultat de l''exercice est égale :
• soit au montant de la dotation de l''exercice aux comptes d''amortissements des immobilisations amortissables acquises
ou créées au moyen de la subvention ;
• soit à un montant déterminé en fonction du nombre d''années pendant lesquelles les immobilisations non amortissables
acquises ou créées au moyen de la subvention sont inaliénables aux termes du contrat, ou à défaut d''une clause
d''inaliénabilité dans le contrat, d''une somme égale au dixième du montant de la subvention.
Des dérogations à ces règles générales pourront être admises lorsqu''une telle mesure sera justifiée par des circonstances
particulières, notamment par le régime juridique des entités, l''objet de leur activité, les conditions posées ou les
engagements demandés par les autorités ou organismes ayant alloué ces subventions.', fonctionnement = 'Le compte 14 – SUBVENTIONS D''INVESTISSEMENT est crédité du montant de l''aide obtenue
• par le débit du compte approprié de la classe 2, sur la base de l''évaluation des immobilisations transférées
gratuitement à l''entité.
Le compte 14 – SUBVENTIONS D''INVESTISSEMENT est crédité du montant de la subvention
• par le débit du compte approprié de la classe 4 tel que 4494 – Etat, subventions d''équipement à recevoir ou 4582
– Organismes internationaux, subventions à recevoir.
Le compte 14 – SUBVENTIONS D''INVESTISSEMENT est débité à la clôture de l''exercice
• par le crédit des comptes 799– Reprises de subventions d''investissement, pour la partie de la subvention
rapportée au résultat de la période.
Le compte 14 – SUBVENTIONS D''INVESTISSEMENT est débité à la date de cession de l''actif acquis à l''aide de la
subvention
• par le crédit du compte 799 – Reprises de subventions d''investissement, pour la partie de la subvention non
encore rapportée au résultat.', exclusions = 'Le compte 14 – SUBVENTIONS D''INVESTISSEMENT ne doit pas servir à enregistrer :
• les subventions d''exploitation reçues
• les subventions d''équilibre reçues
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 71 – Subventions d''exploitation
• 88 – Subventions d''équilibre', controle = 'Le compte 14 – SUBVENTIONS D''INVESTISSEMENT peut être contrôlé à partir de recoupements issus :
• des décisions d''octroi de la subvention ou d''affectation à l''entité d''un bien de façon définitive et à titre gratuit ;
• du tableau d''amortissement des biens acquis ou créés à l''aide de la subvention pour vérification de la reprise au
résultat de la subvention selon le même rythme que les amortissements. Pour les biens non amortissables, l''entité
a la faculté de décider en l''absence d''instruction du pourvoyeur de la subvention, du rythme de reprise de la
subvention au résultat.' WHERE numero = '14';
UPDATE comptes_ohada SET contenu = 'Les provisions réglementées sont des provisions à caractère purement fiscal ou réglementaire, comptabilisées non pas en
application de principes comptables, mais suivant des dispositions légales et réglementaires (lois de finances, par
exemple).
Peuvent être classées dans cette catégorie, les provisions :
• autorisées spécialement pour certaines professions (reconstitution de gisements miniers et pétroliers) ;
• pour hausse des prix et fluctuation des cours ;
• pour investissement.
Ont notamment le caractère de fonds assimilés, lorsqu''ils sont prévus par la législation fiscale :
• les amortissements dérogatoires ;
• les plus-values de cession à réinvestir ;
• les fonds réglementés ;
• la provision spéciale de réévaluation, lorsque la législation fiscale n''autorisant pas la déductibilité du supplément
d''amortissement (concept dit de "neutralité fiscale") impose la comptabilisation sous cette forme.', commentaires = 'Du fait de leur caractère de réserves non libérées d''impôt sur lesquelles pèsent une charge latente ou différée d''impôt qui
n''est pas comptabilisée, les provisions réglementées et fonds assimilés sont inscrits au passif du bilan parmi les capitaux
propres.
Elles sont créées ou augmentées exclusivement par "Dotations H.A.O.", et sont réduites ou annulées exclusivement par
"Reprises H.A.O.".
Exemple : Schéma de comptabilisation des plus-values à réinvestir
Ce mécanisme comptable a pour objet de répondre aux exigences fiscales dans les pays où s''applique le système des
plus-values à réinvestir :
• les plus-values de cession sur des éléments de l''actif immobilisé de l''exercice sont constatées par différence entre : les
comptes 81 – Valeurs comptables des cessions d''immobilisations et les comptes 82 – Produits des cessions
d''immobilisations ;
• à la clôture de l''exercice, l''engagement de réemploi de la plus-value, dans les limites autorisées par la législation fiscale,
est constaté :
Débit 851 – Dotations aux provisions réglementées
Crédit 152 – Plus-values de cession à réinvestir
En l''absence de réinvestissement au cours de l''exercice suivant, la provision doit être reprise intégralement :
Débit 152 – Plus-values de cession à réinvestir
Crédit 861 – Reprises de provisions réglementées
En cas d''utilisation de la plus-value conformément à son objet, le bien donnera lieu à un amortissement calculé dans les
conditions de droit commun. En revanche, annuellement, la différence entre l''amortissement calculé globalement sur la
valeur d''entrée du bien dans le patrimoine et l''amortissement calculé sur la base de son "coût de revient", diminué de la
plus-value, donne lieu à reprise partielle pour ce montant de la plus-value à réinvestir :
Débit 152 – Plus-values de cession à réinvestir
Crédit 861 – Reprises de provisions réglementées', fonctionnement = 'Le compte 15 – PROVISIONS REGLEMENTEES ET FONDS ASSIMILES est crédité de la création ou de la variation
en augmentation des provisions réglementées
• par le débit du compte 85 – Dotations H.A.O.
Le compte 15 – PROVISIONS REGLEMENTEES ET FONDS ASSIMILES est débité de l''annulation ou de la variation
en diminution des provisions réglementées
• par le crédit du compte 86 – Reprises H.A.O.', exclusions = 'Le compte 15 – PROVISIONS REGLEMENTEES ET FONDS ASSIMILES ne doit pas servir à enregistrer :
• les provisions destinées à couvrir des risques et des charges futurs (à plus d''un an)
• les dépréciations de l''actif immobilisé
• les dépréciations de l''actif circulant
• les dépréciations des comptes de trésorerie
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 19 – Provisions financières pour risques et charges
• 29 –Dépréciations
• 39 – Dépréciations des stocks
• 49 – Dépréciations et provisions pour risques à court terme (Tiers)
• 59 – Dépréciations et provisions pour risques à court terme (Trésorerie)', controle = 'Le compte 15 – PROVISIONS REGLEMENTEES ET FONDS ASSIMILES peut être contrôlé à partir de recoupements
issus :
• des écritures à la clôture de l''exercice ;
• des tableaux d''amortissements comptables et fiscaux ;
• des factures de cession d''immobilisations et des opérations faisant ressortir la plus ou moins-value ;
• des décisions des assemblées sur la répartition du résultat et la législation concernant cette affectation.' WHERE numero = '15';
UPDATE comptes_ohada SET contenu = 'Les emprunts et les dettes assimilées sont des ressources financières externes, contractées auprès d''établissements de
crédit et/ou de tiers divers, affectées de façon durable au financement des moyens d''exploitation ou de production.
Les emprunts obligataires sont traités au titre VII Opérations spécifiques, chapitre 19.
Remboursables à terme, ils participent concurremment avec les capitaux propres à la couverture des besoins durables de
l''entité.', commentaires = 'Les emprunts et dettes assimilées ne sont pas distingués en fonction du terme d''exigibilité. Toutefois, à la clôture de
l''exercice, les fractions devenues exigibles à un an au plus, à deux ans au plus, et à plus de deux ans sont isolées afin
d''être portées distinctement dans le tableau des créances et dettes.
S''agissant de leur position au passif du bilan, les comptes 161 – Emprunts obligataires et 162 – Emprunts et dettes auprès
des établissements de crédit doivent être regroupés sur une ligne distincte "Emprunts". Les comptes 163 à 168 figurent en
dettes financières diverses.
Pour les emprunts assortis d''une caution, d''une garantie ou de gage, le montant et la portée de la caution, de la garantie ou
du gage doivent être indiqués dans les Notes annexes.
Schéma de comptabilisation des emprunts obligations (voir titre VIII Opérations spécifiques, chapitre 20).', fonctionnement = 'Le compte 16 – EMPRUNTS ET DETTES ASSIMILEES est crédité du montant net des emprunts et avances diverses
accordés
• par le débit des comptes de trésorerie concernés
Le compte 16 – EMPRUNTS ET DETTES ASSIMILEES est crédité, à la clôture de l''exercice, des intérêts courus
jusqu''au jour de la clôture
• par le débit du compte 671 – Intérêts des emprunts.
Le compte 16 – EMPRUNTS ET DETTES ASSIMILEES est crédité du montant des dépôts et cautionnements reçus
• par le débit des comptes de trésorerie intéressés.
Le compte 16 – EMPRUNTS ET DETTES ASSIMILEES est débité, à la date d''échéance de remboursement, du
montant du principal remboursé
• par le crédit d''un compte de tiers ou d''un compte de trésorerie.
Le compte 16 – EMPRUNTS ET DETTES ASSIMILEES est débité, à l''ouverture de l''exercice, du montant des intérêts
courus pris en compte à la clôture de l''exercice précédent
• par le crédit du compte 671 – Intérêts des emprunts.
Le compte 16 – EMPRUNTS ET DETTES ASSIMILEES est débité du montant des dépôts et cautionnements restitués
• par le crédit des comptes de trésorerie concernés.', exclusions = 'Le compte 16 – EMPRUNTS ET DETTES ASSIMILEES ne doit pas servir à enregistrer :
• les emprunts et dettes liées à des participations
• les emprunts équivalents de crédit-bail et contrats assimilés
• les primes de remboursement des obligations
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 181 – Dettes liées à des participations
• 17 – Dettes de crédit-bail et contrats assimilés
• 6714 – Primes de remboursement des obligations', controle = 'Le compte 16 – EMPRUNTS ET DETTES ASSIMILEES peut être contrôlé à partir de recoupements issus :
• des contrats de prêts signés par l''entité;
• des virements (réception et remboursements) ;
• du tableau d''amortissement des emprunts ;
• du calcul des intérêts courus ;
• des contrats de dépôts et cautionnements ;
• des contrats d''avances-engagements de l''Etat et des organismes internationaux.' WHERE numero = '16';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre le montant correspondant à la valeur d''entrée du bien acquis par contrats de location acquisition.
Cette valeur est celle figurant dans le contrat ou la somme actualisée des redevances de location acquisition (voir Titre VIII
Opérations spécifiques, Chapitre 8).
Les dettes liées à des participations sont des emprunts contractés auprès d''entités liées ou avec lesquelles elles ont un lien
de participation.
Les dettes liées à des participations figurent au passif du bilan parmi les dettes financières diverses.
Le compte de liaison des établissements et succursales est un compte de bilan ouvert au nom de l''établissement. Il
fonctionne comme un compte courant, de sorte que toutes les opérations réalisées entre le siège et l''établissement y soient
enregistrées comme s''il s''agissait d''un tiers. En conséquence, il conviendra :
• de créer, au siège, un compte de liaison au nom de chaque établissement ou succursale ;
• de créer, dans l''établissement ou la succursale, un compte réfléchi au nom du siège.
Les opérations entre le siège et l''établissement ou la succursale sont à enregistrer de manière symétrique, dans la même
période comptable et sur la base des mêmes pièces justificatives. Il en résulte que les comptes de liaison sont égaux et de
sens contraire dans la comptabilité du siège et dans celle de l''établissement ou la succursale.', commentaires = 'Ne sont visés par ce compte que les contrats de location acquisition (voir Titre VIII Opérations spécifiques, Chapitre 8).
L''utilisation des comptes 181, 182, 183 et
Celle des comptes 184, 185, 186 et 187 est réservée aux opérations entre établissements d''une même entité.
Le terme établissement s''applique à toute division de l''entité disposant d''une comptabilité autonome (succursales, usines,
ateliers).
Il convient d''entendre par comptabilité autonome, toute comptabilité distincte rattachée à la comptabilité du siège par un
compte de liaison.', fonctionnement = 'Le compte 17 – DETTES DE LOCATION ACQUISITION est crédité à l''entrée du bien sous le contrôle de l''entité du
montant stipulé au contrat ou de la somme actualisée des redevances
• par le débit du compte d''immobilisation concerné.
Le compte 17 – DETTES DE LOCATION ACQUISITION est crédité, à la clôture de l''exercice, des intérêts courus de
la dette
• par le débit du compte 672 – Intérêts dans loyers de location acquisition
Le compte 17 – DETTES DE LOCATION ACQUISITION est débité à la clôture de l''exercice de la fraction des
redevances payées, durant l''exercice correspondant au remboursement de la dette
• par le crédit du compte 623 – Redevances de location acquisition
Le compte 17 – DETTES DE LOCATION ACQUISITION est débité, à l''ouverture de l''exercice, du montant des intérêts
courus pris en compte à la clôture de l''exercice précédent
• par le crédit du compte 672 – Intérêts dans loyers de location acquisition
Les comptes 181, 182 – DETTES LIEES A DES PARTICIPATIONS ET DETTES LIEES A DES SOCIETES EN
PARTICIPATION sont crédités de la valeur à rembourser des emprunts contractés
• par le débit des comptes de trésorerie ou des comptes de tiers concernés.
Le compte 183 –INTERÊTS COURUS SUR DETTES LIEES A DES PARTICIPATIONS est crédité, à la clôture de
l''exercice, du montant des intérêts courus depuis la dernière échéance
• par le débit du compte 671 – Intérêts des emprunts.
Les comptes 181, 182 – DETTES LIEES A DES PARTICIPATIONS et DETTES LIEES A DES SOCIETES EN
PARTICIPATION sont débités à la date d''échéance des dettes
• par le crédit des comptes de trésorerie concernés.
Les comptes 184 à 187 – COMPTES DE LIAISON DES ETABLISSEMENTS ET SUCCURSALES sont crédités des
opérations effectuées entre le siège d''une entité entreprise et ses établissements ou succursales
• par le débit des comptes concernés.
Les comptes 184 à 187 – COMPTES DE LIAISON DES ETABLISSEMENTS ET SUCCURSALES sont débités des
opérations effectuées entre le siège d''une entité entreprise et ses établissements ou succursales (y compris le
montant antérieurement apporté à titre permanent)
• par le crédit des comptes concernés.', exclusions = 'Le compte 17 – DETTES DE LOCATION ACQUISITION ne doit pas servir à enregistrer :
• les dettes autres que celles relatives aux contrats de location acquisition (répondant au critère d''inscription à l''actif
du bilan)
• les redevances non retraitées
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 16 ou 18 – Selon le cas
• 622 – Locations et charges locatives
Le compte 18 – DETTES LIEES A DES PARTICIPATIONS ET COMPTES DE LIAISON DES ETABLISSEMENTS ET
SOCIETES EN PARTICIPATION ne doit pas servir à enregistrer :
• les dettes résultant d''opérations commerciales courantes entre les sociétés du groupe
• les dettes financières à l''égard de tiers non liés à l''entité par des liens de participation ou celles contractées auprès
d''autres établissements de crédit mais à des conditions de droit commun
• les comptes bloqués d''associés
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 40 – Fournisseurs et comptes rattachés
• 16 – Emprunts et dettes assimilées
• 164 – Avances reçues et comptes courants bloqués', controle = 'Le compte 17 – DETTES DE LOCATION ACQUISITION peut être contrôlé à partir :
• des factures de redevances ;
• des contrats de crédit-bail et assimilés ;
• des contrats de location vente et assimilés ;
• des échéanciers de remboursement.
18
Dettes liées à des participations et comptes de liaison des établissements et sociétés
en participation
Le compte 18 – DETTES LIEES A DES PARTICIPATIONS ET COMPTES DE LIAISON DES ETABLISSEMENTS ET
SOCIETES EN PARTICIPATION peut être contrôlé à partir des recoupements issus :
• de la vérification du lien de participation ;
• du contrat de prêt ;
• du tableau de remboursement ou d''amortissement de l''emprunt ;
• du calcul des intérêts courus ;
• de la vérification des conditions d''octroi lorsque l''entité liée est un établissement de crédit ;
• des virements.' WHERE numero = '17';
UPDATE comptes_ohada SET contenu = 'Une provision est un passif externe (dette) dont l''échéance ou le montant est incertain.
Les provisions pour risques et charges sont des provisions destinées à couvrir des charges, des risques et pertes
nettement précisés quant à leur objet que des événements survenus ou en cours rendent probables, mais comportant un
élément d''incertitude quant à leur montant ou leur réalisation prévisible à plus d''un an.
Les provisions pour risques et charges sont inscrites au passif du bilan dans les dettes financières et ressources
assimilées.
Les provisions et passifs et actifs éventuels sont traités au titre VIII Opérations spécifiques, chapitre 18.', commentaires = 'Toutefois, en cas d''imposition fractionnée et pour des montants significatifs, il convient de doter le compte de provision pour
impôts (exemple : étalement des plus-values nettes à long terme).
Dans la plupart des Etats de l''espace OHADA, il est institué une obligation légale ou conventionnelle, à la charge des
entités, de verser au profit de l''employé une indemnité spéciale dite de fin de carrière, le jour du départ à la retraite d''un
montant proportionnel à l''ancienneté et à la rémunération atteinte à cette date.
Pour respecter cet engagement, deux solutions s''offrent aux entités :
• verser à un organisme tiers (compagnie d''assurances) ou à un fonds de pension des cotisations mensuelles,
trimestrielles ou annuelles pendant toute la période d''activité du salarié.
L''organisme prend en charge le versement des prestations à l''échéance.
• verser elles-mêmes à l''échéance les prestations acquises par les salariés. Cet engagement devrait être constaté sous
forme de provision (par le biais du compte 6911Dotations aux provisions pour risques et charges) et réajustée (en cas
de reprises par le biais du compte 7911 Reprises de provisions pour risques et charges).
Les indemnités de fin de carrière sont considérées comme des avantages à prestations définies, ressortant de la catégorie
des avantages postérieurs à l''emploi dont la prise en charge s''effectue de façon linéaire pendant toute la durée d''acquisition
conditionnelle des droits conférés aux bénéficiaires, tout en probabilisant les risques que le salarié quitte l''entreprise avant
son départ en retraite.
La comptabilisation des régimes à prestations définies est complexe parce que des hypothèses actuarielles sont
nécessaires pour évaluer l''obligation et la charge et que des écarts actuariels peuvent exister. De plus, les obligations sont
évaluées sur une base actualisée car elles peuvent être réglées de nombreuses années après que les membres du
personnel aient effectué les services correspondants.
Par dérogation, les entités non inscrites à une bourse de valeur peuvent soit opter pour la méthode actuarielle, soit définir
leurs propres modalités d''évaluation des engagements de retraite et avantages similaires (avec ou sans actualisation).
En revanche les sociétés inscrites à une bourse de valeur sont tenues d''appliquer la méthode d''évaluation actuarielle et
faire des hypothèses actuarielles (voir Titre VIII Opérations spécifiques, Chapitre 21).
La première année d''application de la comptabilisation des indemnités de fin de carrière, (à inscrire au crédit du compte
« 196 : provisions pour pensions et obligations similaires ») doit être considérée comme un changement de méthode
comptable. Le retraitement doit être en principe rétrospectif par une imputation sur les réserves. Les engagements
antérieurs non comptabilisés (montant net de l''effet d''impôt) sont affectés directement aux postes de réserves ou report à
nouveau. La mise en œuvre de cette possibilité est plus délicate. En effet, elle nécessite :
• l''existence de réserves suffisantes ;
• l''approbation de l''Assemblée générale des actionnaires.
Toutefois, l''entité peut recourir à deux autres méthodes de comptabilisation qui sont les suivantes :
• comptabilisation de la totalité de la charge à la fin du premier exercice d''application ;
• étalement de la partie de l''indemnité relative aux engagements antérieurs non comptabilisés de façon linéaire, sur une
durée maximum de cinq ans ;
En ce qui concerne la revente d''une branche d''activité, l''obligation de restructuration n''existe pas jusqu''à un accord de
vente irrévocable. Si ce fait générateur n''existe pas à la date de clôture, aucune provision ne sera comptabilisée.
(voir Titre VIII Opérations spécifiques chapitre 18).
Lors de la vente initiale d''un produit ou d''un service, l''entité peut s''engager, de manière explicite ou implicite, à accorder à
ses clients des droits se traduisant par une réduction monétaire ou par la remise d''avantages en nature ou de prestations.
Le droit à réduction ou l''avantage peut être mobilisable immédiatement ou à terme, avec des conditions de délai ou de
seuils, le cas échéant. (voir Titre VIII Opérations spécifiques, Chapitre 18).
En application du principe de prudence, même en cas d''absence ou d''insuffisance de bénéfice, il doit être procédé
obligatoirement aux provisions.
Le compte 19 est réajusté à la clôture de chaque exercice soit par dotations supplémentaires, soit par reprises des
provisions antérieures.', fonctionnement = 'Le compte 19 – PROVISIONS POUR RISQUES ET CHARGES est crédité, à la clôture de l''exercice, des charges et
pertes prévisibles
• par le débit du compte 69 (compte 691 – Dotations aux provisions et aux dépréciations d''exploitation, ou 697 –
Dotations aux provisions et aux dépréciations financières) ;
• ou par le débit du compte 85 – Dotations H.A.O.
Le compte 19 – PROVISIONS POUR RISQUES ET CHARGES est débité, à la clôture de l''exercice, de la reprise des
provisions pour charges et pertes constatées à la clôture d''un exercice antérieur dont les raisons qui les ont motivées
ont cessé d''exister
• par le crédit du compte 79 – Reprises de provisions, de dépréciations et autres;
• ou par le crédit du compte 86 – Reprises H.A.O.', exclusions = 'Le compte 19 – PROVISIONS POUR RISQUES ET CHARGES ne doit pas servir à enregistrer :
• les charges certaines d''un montant déterminé, qui sont à comptabiliser dans les comptes de charges par nature
avec contrepartie dans les comptes de tiers ou de trésorerie concernés
• les provisions qui ont pour origine une réglementation particulière, souvent d''ordre fiscal, sans charges ou pertes
réellement prévisibles
• les provisions correspondant à des risques à moins d''un an
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• classes 6 et 8 de Charges
• 15 – Provisions réglementées et fonds assimilés
• 499 – Provisions pour risques à court terme', controle = 'Le compte 19 – PROVISIONS POUR RISQUES ET CHARGES peut être contrôlé à partir des éléments ci-après :
• vérification du calcul des provisions ;
• recherche de la réalité du risque ou de l''éventualité de la charge ; appréciation de l''échéance du risque ou de la
charge.
COMPTES D''ACTIF IMMOBILISÉ
L''actif immobilisé correspond aux emplois durables rendus nécessaires par l''objet économique et financier de l''entité
que constituent ses « activités ordinaires ».
Les immobilisations représentent les biens et valeurs destinés à rester durablement dans l''entité: les immobilisations
incorporelles, corporelles et financières.
L''entité dresse à la clôture de l''exercice un inventaire détaillé de ses immobilisations.
Les comptes de l''actif immobilisé doivent comprendre toutes les immobilisations, corporelles ou incorporelles, existant
dans l''entité, qu''elles soient affectées ou non à l''exploitation. Les immobilisations louées par l''entité et qui concourent à
son exploitation sont également inscrites au bilan.
Le montant d''une immobilisation corporelle d''une entité peut sur option être ventilée en ses parties significatives dès
lors que :
• les éléments d''actif sont dissociables ;
• les éléments d''actif ont une utilisation différente ;
• la durée d''utilité de chaque élément est différente.
Chaque élément de cette immobilisation peut être comptabilisé séparement dès son acquisition ou son remplacement,
si son coût peut être évalué de façon fiable et qu''il est significatif par rapport au coût total de l''immobilisation.
Ainsi, peuvent être décomposés selon le SYSCOHADA : les immeubles, les avions, les bateaux, des camions des
autocars, des bus, des plates formes pétrolières, certaines machines des entités industrielles, minières, et pétrolières
dès lors que l''entité dispose de statistiques lui permettant de bien appréhender la durée d''utilité de chaque élément.
Les immobilisations entièrement amorties demeurent inscrites au bilan aussi longtemps qu''elles subsistent dans l''entité.
Les comptes d''actif immobilisé peuvent être assortis de comptes d''amortissements ou de provisions pour dépréciation.
La dépréciation des immobilisations, qu''elle résulte de l''usure, du changement des techniques ou de toute autre cause,
doit être constatée par des amortissements.
Les moins-values sur les immobilisations consécutives à des événements jugés non irréversibles doivent faire l''objet de
provisions pour dépréciation. Toutefois, les moins-values sur immobilisations amortissables ne concernent que des
dépréciations exceptionnelles qui ne peuvent raisonnablement être inscrites au compte d''amortissement en raison de
leur caractère non définitif.
En tout état de cause, même en cas d''absence ou d''insuffisance de bénéfices, l''entité procède aux amortissements et
aux provisions nécessaires pour que le bilan donne une image fidèle du patrimoine, de la situation financière et du
résultat de l''exercice.
Lors de son entrée dans le patrimoine de l''entité, la valeur de l''immobilisation est ainsi déterminée :
• le bien acquis à titre onéreux est comptabilisé à son coût d''acquisition.
Ce coût d''acquisition est déterminé par l''addition des éléments suivants :
Compte tenu de la simultanéité des deux écritures le SYSCOHADA autorise que le sous compte composant
démantèlement soit débité par le crédit du compte de provisions 1984.
Pour la première d''année application les coûts doivent être estimés et comptabilisés en tenant compte de la provision
constituée antérieurement par le biais du compte provision pour charges à répartir (voir titre VIII Opérations spécifiques,
Chapitre 6).
• le bien produit par l''entité est comptabilisé à son coût de production.
Ce coût de production est déterminé par l''addition des éléments suivants :
• le bien acquis à titre gratuit est comptabilisé à sa valeur vénale ;
• le bien acquis par voie d''échange doit être évalué à la valeur vénale ou à la valeur comptable de l''actif donné en
échange, à défaut de marché.
• Le bien acquis contre versement de rentes viagères est comptabilisé pour le montant stipulé dans le contrat ou à
défaut la valeur actuelle à la date du contrat voir Titre VIII Opérations spécifiques, Chapitre 11).
• le bien reçu à titre d''apport en nature est comptabilisé à la valeur figurant dans l''acte d''apport.
• Le bien acquis en monnaies étrangères est comptabilisé en unité monétaire légale au cours du jour de l''acquisition
(voir Titre VIII Opérations spécifiques, Chapitre 22).
• Les pièces de rechange principales et le stock de pièces de sécurité constituent des immobilisations corporelles si
l''entité compte les utiliser sur plus d''un exercice.
Pour les pièces de sécurité, l''amortissement doit démarrer dès l''acquisition de l''immobilisation principale.
Pour les pièces de rechange destinées à remplacer totalement ou partiellement un composant, l''amortissement ne
débute qu''à la date d''utilisation de la pièce, c''est-à-dire au moment où elle est intégrée dans l''immobilisation principale.
• Lorsqu''une inspection majeure est réalisée, son coût est comptabilisé dans un sous compte composant de
l''immobilisation principale appelée
Révisions majeures. Cette révision est amortie sur la durée séparant deux révisions. Lorsque la révision est réalisée
toute valeur comptable résiduelle du coût de la précédente inspection est décomptabilisée (voir Titre VIII Opérations
spécifiques, Chapitre 5).
Les immobilisations cédées, disparues ou détruites cessent de figurer au bilan.
Les immobilisations mises hors service ou au rebut, sont à amortir intégralement.
• La concession est le contrat par lequel une personne publique, le concédant, confie à un concessionnaire, entité du
secteur privé,
Le SYSCOHADA a retenu le mode comptabilisation suivant :
COMPTES DE LA CLASSE 2
21    Immobilisations incorporelles                        29   26   Titres de participation                        41
22    Terrains                                             33   27   Autres immobilisations financières             43
23    Bâtiments, installations techniques et agencements   35   28   Amortissements                                 46
24    Matériel, Mobilier et Actifs biologiques             38   29   Dépréciations                                  48
25    Avances et acomptes versés sur immobilisations       40' WHERE numero = '19';
UPDATE comptes_ohada SET contenu = 'Les immobilisations incorporelles sont des actifs non monétaires identifiables sans substance physique, contrôlés par
l''entité qui a le pouvoir d''obtenir des avantages économiques futurs et peut restreindre l''accès des tiers à ces avantages.
Un actif est identifiable s''il
• est séparable, c''est-à-dire qu''il peut être séparé de l''entité et être vendu, transféré, concédé par licence, loué ou
échangé, soit de façon individuelle, soit dans le cadre d''un contrat, avec un actif ou un passif liés; ou
• résulte de droits contractuels ou d''autres droits légaux, que ces droits soient ou non cessibles ou séparables de l''entité
ou d''autres droits et obligations.
Une entité contrôle ses avantages si ces connaissances sont protégées par des droits légaux.
Les avantages économiques futurs résultent des produits découlant de la vente de biens ou services, des économies de
coûts ou d''autres avantages résultant de l''utilisation de l''actif par l''entité.
Elles ont la nature de biens acquis ou créés par l''entité, non pour être vendus ou transformés, mais pour être utilisés de
manière durable, directement ou indirectement, pour la réalisation des opérations professionnelles ou non.', commentaires = 'Le compte 211 enregistre les dépenses résultant du développement (ou de la phase de développement d''un projet interne)
si et seulement si l''entité peut démontrer qu''elle satisfait aux 6 critères suivants :
• la faisabilité technique nécessaire à l''achèvement de l''immobilisation incorporelle en vue de sa mise en service ou de sa
vente : faisabilité technique démontrée ;
• son intention d''achever l''immobilisation incorporelle et de la mettre en service ou de la vendre : intention de produire et
de commercialiser ;
• sa capacité à mettre en service ou à vendre l''immobilisation incorporelle : capacité de produire et de commercialiser ;
• la façon dont l''immobilisation incorporelle générera des avantages économiques futurs probables.
L''entité doit démontrer, entre autres choses, l''existence d''un marché pour la production issue de l''immobilisation
incorporelle ou pour l''immobilisation incorporelle elle- même ou, si celle-ci doit être utilisée en interne, son utilité : existence
d''un marché potentiel;
• la disponibilité de ressources techniques, financières et autres, appropriées pour achever le développement et mettre
en service ou vendre l''immobilisation incorporelle : ressources suffisantes pour terminer le projet;
• sa capacité à évaluer de façon fiable les dépenses attribuables à l''immobilisation incorporelle au cours de son
développement : capacité d''évaluation fiable des dépenses à immobiliser.
Cette immobilisation incorporelle est amortie en fonction de sa durée d''utilité.
Les dépenses pour la recherche sont comptabilisées en charge lorsqu''elles sont encourues et non en immobilisations
incorporelles.
Si la distinction n''est pas possible entre la phase de recherche et la phase de développement, toutes les dépenses seront
considérés comme de la recherche, donc inscrites dans les charges (voir Titre VIII Opérations spécifiques, Chapitre 1).
Compte 212 Brevets, licences, concessions et droits assimilés (voir titre VIII Opérations spécifiques, chapitre 2). Il
enregistre les dépenses engagées pour obtenir la protection accordée sous certaines conditions aux inventeurs, auteurs ou
bénéficiaires du droit d''exploitation des brevets, modèles, dessins, procédés, propriétés littéraire et artistique sous forme
directe ou sous forme de licences ou de concessions.
Les éléments du compte 212 sont amortissables sur leur durée de vie économique au maximum égale à la durée de la
protection juridique.
Les Marques enregistrent le coût d''acquisition des marques commerciales ou industrielles. Dans le cas où ces marques ne
semblent pas avoir une valeur pérenne, elles sont à amortir.
Compte 213 logiciels et sites internet (voir Titre VIII Opérations spécifiques, Chapitre 2). Ce compte Logiciels enregistre les
dépenses faites en vue d''acquérir le droit d''usage, d''adaptation, ou encore de reproduction d''un logiciel acquis, de même
que le coût de production d''un logiciel créé ou développé pour les besoins internes de l''entité.
Le logiciel est un ensemble de programmes, procédés, et règles assortis ou non de documentation, acquis ou créés par
l''entité en vue du traitement automatique des données.
Compte 215 – Fonds commercial (voir titre VIII Opérations spécifiques, Chapitre 2). Ce compte est constitué par les
éléments incorporels qui ne font pas l''objet d''une évaluation et d''une comptabilisation séparées au bilan et qui concourent
au maintien ou au développement du potentiel d''activité de l''entité, de la clientèle, de l''achalandage, du droit au bail, du
nom commercial et de l''enseigne.
La clientèle et l''achalandage correspondent au potentiel de bénéfice représenté par l''existence d''une clientèle déterminée
ou justifié par l''emplacement de l''entité.
Les éléments composant le fonds commercial ne bénéficient pas toujours d''une protection juridique leur donnant une valeur
pérenne. Seul est inscrit à ce compte le fonds commercial acquis.
Compte 216 – Droit au bail et pas de porte (voir titre VIII Opérations spécifiques, chapitre 2). Ce compte est constitué par le
montant versé ou dû au locataire précédent en considération du transfert des droits résultant tant des conventions que de la
législation sur la propriété commerciale.
Le compte 217 – Investissements de création se rapporte aux fabricants, producteurs, éditeurs et distributeurs de
phonogrammes, aux entités de spectacle, aux établissements exerçant des activités culturelles et aux industries textiles
(créateurs de mode).
Sont donc portés au compte 217 les dépenses particulièrement élevées que les éditeurs engagent pour l''étude et la
production de certains ouvrages et de certaines éditions (ouvrages de grandes collections, ouvrages d''art et encyclopédies)
ainsi que les frais de collection exposés dans l''industrie textile.
Compte 2181 Frais de prospection et d''évaluation de ressources minérales (voir titre VIII Opérations spécifiques, chapitre
3). Ce compte enregistre des dépenses d''exploration et d''évaluation des ressources minérales après obtention des droits
d''exploitation. Ces dépenses peuvent être enregistrées soit en charges, soit en immobilisations incorporelles.
La méthode retenue doit être cohérente et permanente pour tous les exercices et les activités ou éléments similaires.
L''entité peut prendre en compte toutes les dépenses qui peuvent être associées à la découverte de ressources minérales
spécifiques par exemple :
Les actifs de prospection et d''évaluation doivent être soumis à un test de dépréciation.
Compte 2182 Coûts d''obtention du contrat (voir titre VIII Opérations spécifiques, chapitre 2). Le SYSCOHADA préconise
l''activation sur option des coûts d''obtention du contrat lorsque les conditions suivantes sont remplies :
• ces coûts sont marginaux, c''est-à-dire que l''entité ne les aurait pas encourus si elle n''avait pas obtenu le contrat ;
• l''entité s''attend à les recouvrer ;
• les coûts d''obtention du contrat sont significatifs ;
• en cas d''activation, la durée d''amortissement de ces coûts d''obtention aurait été supérieure à 12 mois.
Les coûts d''obtention du contrat qui auraient été supportés en tout état de cause sont enregistrés en charge lorsqu''ils sont
encourus ; toutefois, même si le contrat n''est pas obtenu, ces coûts sont inscrits à l''actif lorsqu''ils peuvent être facturés au
client.
Par contre, les coûts suivants sont exclus du coût d''obtention du contrat :
• les frais encourus pour répondre à des appels d''offre ;
• les frais marketing que l''entité encourt, qu''elle obtienne ou non le contrat.
Compte 2183 Fichiers clients, notices, titres de journaux et magazines (voir titre VIII Opérations spécifiques, chapitre 2).
Ce compte enregistre lorsqu''ils sont acquis, les fichiers clients (listes et autres bases de données reflétant des relations
contractuelles antérieures, listes de clients ou d''abonnés fréquemment concédées par licence), notices, titres de journaux,
et magazines.
Les dépenses pour générer en interne les marques, les notices, les titres de journaux et de magazines, les listes de clients
et autres éléments similaires en substance ne peuvent pas être distinguées du coût de développement de l''activité dans
son ensemble. Par conséquent, ces éléments ne sont pas comptabilisés en tant qu''immobilisations incorporelles!
Immobilisations incorporelles en cours : le compte 219 enregistre le coût de production des brevets, investissements de
création et logiciels élaborés par l''entité elle-même, dont les éléments transitent pour la plupart par le compte 211 – Frais de
développement.', fonctionnement = 'Le compte 21 – IMMOBILISATIONS INCORPORELLES est débité de la valeur d''apport, d''acquisition ou de création
par l''entité de l''immobilisation incorporelle
• par le crédit du compte 10 – Capital ;
• ou par le crédit du compte 46 – Apporteurs, Associés et
Groupe ;
• ou par le crédit des comptes de tiers ;
• ou par le crédit des comptes de trésorerie ;
• ou par le crédit du compte 72 – Production immobilisée.
Le compte 21 – IMMOBILISATIONS INCORPORELLES est crédité en cas de cession, disparition, destruction ou mise
au rebut
• par le débit du compte 81 – Valeurs comptables des cessions d''immobilisations (ou du compte 654 – Valeurs
comptables des cessions courantes d''immobilisations) ; et/ou par le débit du compte 281 – Amortissements des
immobilisations incorporelles (pour solde de ce compte).', exclusions = 'Le compte 21 – IMMOBILISATIONS INCORPORELLES ne doit pas servir à enregistrer :
• les frais d''établissement
• les frais de recherche
• les frais de pré exploitation portés en classe 6
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• charges de la classe 6', controle = 'Le compte 21 – IMMOBILISATIONS INCORPORELLES peut être contrôlé à partir :
• des factures ;
• des promesses d''apport ;
• des actes d''acquisition ;
• des récépissés de dépôt de brevets, de marques ;
• des contrats de concession ...' WHERE numero = '21';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre la valeur des terrains dont l''entité est propriétaire et de ceux qui sont mis à sa disposition par des
tiers.', commentaires = 'Les terrains nus sont des terrains pouvant constituer le sol de bâtiments ou d''ouvrages. Ils sont par conséquent sans
construction.
Les terrains bâtis sont ceux sur lesquels des constructions sont édifiées ; la valeur d''entrée de ces terrains doit toujours être
distinguée de celle du bâtiment correspondant. A défaut de pièces justificatives indiquant séparément la valeur des terrains
et celle des constructions, la ventilation du prix global d''acquisition peut être effectuée par tous moyens à la disposition de
l''entité.
Le compte 2288 – Autres correspond aux terrains non évoqués dans les rubriques précédentes, tels que, notamment, les
sous- sols et les sur-sols au cas où l''entité ne serait pas propriétaire des trois éléments rattachés à une même parcelle de
terrain, à savoir le sous-sol, le sol et le sur-sol.
Les terrains de gisement sont des terrains d''extraction de matières destinées soit aux besoins de l''entité, soit à être
revendues en l''état ou après transformation.
Les travaux de mise en valeur des terrains, dont la valeur peut être enregistrée par le compte 224 (voir titre VIII Opérations
spécifiques, chapitre 37),sont essentiellement des travaux de défrichage, drainage, irrigation, nivellement, défonçage,
plantation d''arbres et d''arbustes, à l''exclusion de tout travail de construction et de fondation qui feraient partie intégrante du
coût des bâtiments.
Ces travaux ne peuvent être isolés dans un compte et donner lieu à amortissement que s''ils ont été effectués par l''entité ou
sous ses ordres et, en aucun cas, pour les terrains acquis.', fonctionnement = 'Le compte 22 – TERRAINS est débité de la valeur d''apport ou d''acquisition des terrains
• par le crédit du compte 10 – Capital ;
• par le crédit du compte 46 – Apporteurs, Associés et Groupe ;
• ou par le crédit des comptes de tiers et des comptes de trésorerie concernés.
Le compte 22 – TERRAINS est crédité en cas de cession
• par le débit du compte 81 – Valeurs comptables des cessions d''immobilisations ; et le débit du compte 282 –
Amortissements des terrains, pour le montant des amortissements pratiqués sur les terrains agricoles ou forestiers
et sur les travaux de mise en valeur des terrains.', exclusions = 'Le compte 22 – TERRAINS ne doit pas servir à enregistrer :
• les dépenses de construction qui constituent des composantes du coût des bâtiments
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 23 – Bâtiments, installations techniques et agencements', controle = 'Le compte 22 – TERRAINS peut être contrôlé à partir :
• des actes d''acquisition ;
• des titres de propriété.' WHERE numero = '22';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre le montant des opérations ayant trait aux apports effectués par les associés ou à l''acquisition et à la
création par l''entité de bâtiments, installations et agencements, de même que leur cession, disparition et mise au rebut.', commentaires = 'La valeur des terrains n''est pas comprise dans celle des bâtiments. Les terrains et les bâtiments doivent faire l''objet
d''évaluations distinctes.
Il faut en revanche inclure dans la valeur des bâtiments les éléments ci-après qui peuvent être ventilés dans des sous
comptes spécifiques du bâtiment concerné et amortis sur leur durée d''utilité et non celle du bâtiment :
• le coût de la peinture extérieure et intérieure des constructions neuves ;
• le coût de tous les aménagements permanents tels que appareils de chauffage, de conditionnement d''air et de
climatisation, conduites d''eau, de gaz, d''électricité, de réception d''images ;
• le coût du matériel normalement installé avant que le bâtiment soit occupé.
• Toute construction sur un terrain d''autrui, bien qu''elle revienne en fin de bail au bailleur, doit être constatée dans les
livres du locataire par le compte 232 en tenant compte de toutes les dépenses engagées pour réaliser la construction.
En revanche, Le bailleur ne doit faire figurer dans ses livres que la valeur des terrains pendant toute la durée du bail.
Le locataire est tenu d''amortir les constructions sur la durée la plus courte entre la durée d''utilité du bien et la durée du bail.
En fin de bail, le locataire rétrocède les constructions en soldant le compte 232 et le compte 2832 par le compte 812. Si le
transfert se fait contre indemnité du bailleur, celle-ci sera constatée par le compte 822.
Chez le bailleur, la construction reçue gratuitement sera constatée en retenant la valeur actuelle du jour de transfert en
contrepartie du compte 841 produits HAO constatés. L''indemnité d''éviction est comptabilisée au débit du compte
Les agencements et les installations permanents effectués par le locataire ayant une durée d''utilité supérieure à un an
peuvent être immobilisés et amortis sur la durée du bail. Toutefois si le bail prévoit une remise en état à la fin du contrat une
estimation doit être effectuée et enregistrée comme un composant des agencements et des installations.
Les bâtiments et installations en cours sont ceux qui ne sont pas encore terminés à la clôture de l''exercice, mais qui
appartiennent cependant à l''entité. Après achèvement, ces derniers seront portés au débit des comptes 231 à 238 par le
crédit du compte 239 – Bâtiments et installations en cours. En principe, l''amortissement des bâtiments ou installations en
cours ne peut avoir lieu qu''à partir de leur mise en service effective.', fonctionnement = 'Le compte 23 – BÂTIMENTS, INSTALLATIONS TECHNIQUES ET AGENCEMENTS est débité de la valeur d''apport,
d''acquisition ou de création par l''entité des bâtiments, installations et agencements
• par le crédit du compte 10 – Capital ;
• par le crédit du compte 46 – Apporteurs, Associés et Groupe ;
• ou par le crédit des comptes de tiers et des comptes de trésorerie concernés ;
• par le crédit du compte 72 – Production immobilisée ;
• ou par le crédit du compte 239, lorsque les éléments des comptes 231 à 238 ayant trait à des travaux en cours
sont terminés.
Le compte 23 – BÂTIMENTS, INSTALLATIONS TECHNIQUES ET AGENCEMENTS est crédité en cas de cession,
disparition, mise au rebut
• par le débit du compte 81 – Valeurs comptables des cessions d''immobilisations ;
• ou par le débit du compte 283 – Amortissements des bâtiments, installations techniques et agencements, à
concurrence du montant des amortissements pratiqués sur les éléments cédés ou disparus.', exclusions = 'Le compte 23 – BÂTIMENTS, INSTALLATIONS TECHNIQUES ET AGENCEMENTS ne doit pas servir à enregistrer :
• les biens corporels disparaissant par le premier usage ou dont la durée d''utilisation est inférieure à un an (petit
outillage)
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• compte approprié de la classe 6', controle = 'Le compte 23 – BÂTIMENTS, INSTALLATIONS TECHNIQUES ET AGENCEMENTS peut être contrôlé à partir des :
• actes d''acquisition,
• titres de propriété (titres fonciers),
• factures ...' WHERE numero = '23';
UPDATE comptes_ohada SET contenu = 'Le matériel est constitué par l''ensemble des objets et instruments avec (et ou par) lesquels :
• sont extraits, transformés ou façonnés les matières ou fournitures ;
• sont fournis les services qui sont l''objet même de la profession exercée.
Le mobilier est constitué de meubles et objets utilisés sur une période supérieure à un an dans l''entreprise, comme les
tables, les chaises, les classeurs.
L''actif biologique est constitué d''animaux vivants, de plantes vivantes et secondairement, des améliorations foncières, de
l''autoconsommation prélevée, de certains contrats ou partenariats spécifiques.', commentaires = 'Les matériels d''emballages récupérables sont destinés à être utilisés d''une manière durable, comme instrument de travail.
La remise à neuf et les transformations importantes des matériels sont comptabilisées avec les matériels eux- mêmes, pour
peu que ces travaux entraînent une augmentation de leur durée de vie initiale, ou une meilleure adaptation aux exigences
de la production de biens et de services par l''entité.
Le compte 245 – Matériel de transport enregistre les véhicules et appareils servant au transport des biens et des
personnes.
Sont rattachés au compte Matériel de transport les opérations de transformation et les améliorations apportées à ces
matériels ainsi que les frais annexes entraînés par l''achat de ces matériels d''occasion.
Les Actifs biologiques sont traités au titre VIII Opérations spécifiques, chapitre 37
Le matériel bureautique est constitué notamment par tout le matériel :
• de substitution au support papier tels les ardoises électroniques, les écrans et progiciels ;
• utilisé pour rationaliser le support vocal, en vue de téléconférences, messagerie vocale, reconnaissance de la parole ;
• servant à regrouper des informations sous la forme de chronos, échéanciers, dossiers électroniques ;
• de télétransmission, notamment à l''aide de modems de communication.', fonctionnement = 'Le compte 24 – MATERIEL, MOBILIER ET ACTIFS BIOLOGIQUES est débité de la valeur d''apport, d''acquisition ou
de création par l''entité des matériels
• par le crédit du compte 10 – Capital ;
• par le crédit du compte 46 – Apporteurs, Associés et Groupe ;
• ou par le crédit des comptes de tiers et des comptes de trésorerie concernés ;
• par le crédit du compte 72 – Production immobilisée ;
• ou par le crédit du compte 249 – Matériel en cours, lorsqu''ils ont été achevés.
Le compte 24 – MATERIEL, MOBILIER ET ACTIFS BIOLOGIQUES est crédité en cas de cession, disparition, mise au
rebut
• par le débit du compte 284 – Amortissements du matériel, à concurrence du montant des amortissements
pratiqués jusqu''à la date de cession, de disparition, de mise au rebut ;
• ou par le débit du compte 81 – Valeurs comptables des cessions d''immobilisations, pour le solde (ou 654 –
Valeurs comptables des cessions courantes d''immobilisations).', exclusions = 'Le compte 24 – MATERIEL, MOBILIER ET ACTIFS BIOLOGIQUES ne doit pas servir à enregistrer :
• les biens corporels disparaissant par le premier usage ou d''une durée de vie inférieure à un an ou de très faible
valeur
Il convient dans le cas d''espèce d''utiliser les comptes ci-après :
• comptes de la classe 6', controle = 'Le compte 24 – MATERIEL, MOBILIER ET ACTIFS BIOLOGIQUES peut être contrôlé à partir :
• des factures ;
• des inventaires ;
• des documents nécessaires à la circulation (cartes grises, livrets de bord...) ;
• de recoupements avec les assurances payées et les taxes sur les matériels roulants.' WHERE numero = '24';
UPDATE comptes_ohada SET contenu = 'Sommes versées par l''entité à des tiers pour des commandes en cours d''immobilisations. Le solde de ce compte
représente la créance de l''entité sur ses fournisseurs d''immobilisations.', commentaires = 'Les avances et acomptes versés par l''entité à des tiers pour les opérations en cours sont des versements effectués au
profit des fournisseurs d''immobilisations au moment des commandes ou au cours de l''exécution des contrats. Selon que
ces sommes ont pour objet l''acquisition d''une immobilisation incorporelle ou corporelle, elles sont portées dans les comptes
appropriés.', fonctionnement = 'Le compte 25 – AVANCES ET ACOMPTES VERSÉS SUR IMMOBILISATIONS est débité du montant des sommes
versées aux fournisseurs d''immobilisations à la commande ou en cours d''exécution des contrats
• par le crédit des comptes de trésorerie.
Le compte 25 – AVANCES ET ACOMPTES VERSÉS SUR IMMOBILISATIONS est crédité pour solde à la réception
de la facture définitive du fournisseur de l''immobilisation
• par le débit du compte d''immobilisation concerné.', exclusions = 'Le compte 25 – AVANCES ET ACOMPTES VERSÉS SUR IMMOBILISATIONS ne doit pas servir à enregistrer :
• les en-cours d''immobilisation
• les avances et acomptes versés sur d''autres biens que les immobilisations
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• comptes appropriés de la classe 2
• 48 – Créances et dettes H.A.O.
• 40 – Fournisseurs et comptes rattachés', controle = 'Le compte 25 – AVANCES ET ACOMPTES VERSÉS SUR IMMOBILISATIONS peut être contrôlé à partir :
• des chèques,
• des relevés bancaires,
• des factures,
• des versements effectués.' WHERE numero = '25';
UPDATE comptes_ohada SET contenu = 'Les titres de participation sont constitués par les droits dans le capital d''autres entités, matérialisés ou non par des titres,
afin de créer un lien durable avec celles-ci et de contribuer à l''activité et au développement de la société détentrice.', commentaires = 'Les titres de participation sont ceux dont l''acquisition et la possession durable permettent d''exercer une certaine influence
sur la société qui les a émis (voir titre VIII Opérations spécifiques, chapitre 13).
Sont présumés être des titres de participation, les titres acquis en tout ou partie par offre publique d''achat (O.P.A.) ou par
offre publique d''échange (O.P.E.) et les titres représentant au moins 10 % du capital social d''une entité.
Cette influence peut être de degrés divers allant d''une simple prise de participation, en vue d''établir des relations
commerciales privilégiées, à une véritable prise de contrôle impliquant une influence déterminante sur sa gestion.
Une société est considérée comme étant sous contrôle exclusif, lorsqu''elle est détenue directement ou indirectement par
une entité possédant une fraction du capital lui conférant la majorité des droits de vote dans les
Assemblées générales.
Pour les comptes consolidés le contrôle est ainsi définit : Un investisseur contrôle une unité faisant l''objet d''un
investissement lorsqu''il est exposé ou qu''il a droit à des rendements variables en raison de ses liens avec l''entité faisant
l''objet d''un investissement et qu''il a la capacité d''influer sur ces rendements du fait du pouvoir qu''il détient sur celle-ci.
Une société est présumée exercer ce contrôle lorsqu''elle dispose, directement ou indirectement, d''une fraction des droits
de vote supérieure à 40 % et qu''aucun autre associé ou actionnaire ne détient directement ou indirectement une fraction
supérieure à la sienne.
Le contrôle conjoint est le partage du contrôle d''une entité exploitée en commun par un nombre limité d''associés ou
d''actionnaires, de sorte que les décisions résultent de leur commun accord.
Est présumée conférer une influence notable dans une société la détention de titres, directe ou indirecte, donnant à l''entité
détentrice une fraction au moins égale au cinquième (20 %) des droits de vote dans ladite société.
Les autres titres de participation sont les titres d''une société n''entraînant pour leur propriétaire aucun contrôle déterminant
sur les décisions de l''entité selon la définition donnée ci-dessus, mais lui permettant, néanmoins, d''exercer une influence
notable.
En cas de libération partielle, la part non libérée des titres de participation constitue une dette inscrite au compte 472 –
Versements restant à effectuer sur titres non libérés et dont il devra être fait mention, distinctement, dans les Notes
annexes.
Lorsque le type de contrôle (exclusif, sous contrôle, conjoint, influence notable) vient à changer, il est opéré les transferts
correspondants entre les comptes concernés.
Le compte 266 – Parts dans des G.I.E. enregistre les prises et les cessions de "parts sociales" dans les groupements
d''intérêt économique, à l''exclusion des avances aux
G.I.E. non réalisables à court terme et susceptibles d''être consolidées par incorporation au capital social. Ces avances sont
suivies dans le compte 2774 – Avances à des G.I.E.
Les apports à un G.I.E., non évalués, sont à mentionner dans les engagements donnés.
Les autres opérations effectuées avec un GIE doivent être portées au compte 463 – Associés, opérations faites en commun
qui, pour le cas d''espèce, pourrait donner lieu à ouverture d''un compte divisionnaire.
La valeur d''entrée est le prix d''acquisition majoré des frais accessoires d''achat ; les titres de participation figurent de ce fait
à l''actif (montant brut) pour leur coût d''acquisition.', fonctionnement = 'Le compte 26 – TITRES DE PARTICIPATION est débité de la valeur d''apport ou d''acquisition
• par le crédit du compte 10 – Capital ;
• ou par le crédit des comptes de tiers et des comptes de trésorerie concernés ;
• par le crédit du compte 4813 – Versements restant à effectuer sur titres de participation et titres immobilisés non
libérés, pour la partie non libérée des titres.
Le compte 26 – TITRES DE PARTICIPATION est crédité en cas de cession de titres
• par le débit du compte 81 – Valeurs comptables des cessions d''immobilisations.', exclusions = 'Le compte 26 – TITRES DE PARTICIPATION ne doit pas servir à enregistrer :
• les titres de placement
• les titres immobilisés
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 50 – Titres de placement
• 274 – Titres immobilisés', controle = 'Le compte 26 – TITRES DE PARTICIPATION peut être contrôlé à partir :
• des bons de souscription ;
• des ordres d''achat et de vente en bourse.' WHERE numero = '26';
UPDATE comptes_ohada SET contenu = 'Les autres immobilisations financières comprennent :
• les titres autres que les titres de participation, que l''entité n''a ni l''intention, ni la possibilité de revendre dans un bref
délai ;
• les prêts nés en vertu de dispositions contractuelles ;
• les créances non commerciales assimilées à des prêts (dépôts et cautionnements).', commentaires = 'Les prêts sont ceux qui répondent aux conditions juridiques en matière de contrat.
Les billets de fonds à recevoir sont assimilés aux prêts.
Les titres immobilisés (voir titre VIII Opérations spécifiques, Chapitre 13) sont des titres autres que des titres de
participation que l''entité a l''intention de conserver durablement. Ils sont représentatifs de placements à long terme.
En cas de libération partielle, la part non libérée des titres constitue une dette inscrite au compte 4813 – Versements restant
à effectuer sur titres de participation et titres immobilisés non libérés et devra faire l''objet d''information dans les Notes
annexes. Les frais accessoires d''achat de titres (impôts, courtages, commissions, honoraires) sont inclus dans le prix
d''achat des titres.
Les dépôts sont des sommes versées à certains fournisseurs (gaz, eau, électricité) ou prestataires de services (téléphone,
bailleur) pour leur garantir le paiement des redevances ou des loyers.
Les cautionnements sont les sommes déposées en vue de garantir la bonne fin de l''exécution d''un marché ou d''une
opération.
Elles sont remboursées lors du dénouement du marché ou de l''opération.
Les créances rattachées à des participations sont des prêts ou des avances consentis à une société qui est une
participation de l''entité.
Les prêts et créances ne sont pas distingués en fonction du terme d''exigibilité de leur remboursement. Toutefois, lorsque le
délai d''exigibilité est inférieur ou égal à un an, à la clôture de l''exercice, la partie ainsi devenue exigible est isolée afin d''être
portée distinctement dans le tableau d''échéances des créances et des dettes. De même, les prêts et créances devront être
distingués selon le terme à un an au plus, à deux ans au plus, et à plus de deux ans.
Les prêts assortis d''une garantie font l''objet d''une mention dans les Notes annexes (nantissement, hypothèque, dépôt de
titres, caution bancaire, gages divers).', fonctionnement = 'Le compte 27 – AUTRES IMMOBILISATIONS FINANCIERES est débité de la valeur d''apport ou d''acquisition des
titres, du montant des prêts accordés, des créances nées ou des dépôts et cautionnements versés et de la partie non
libérée des titres immobilisés
• par le crédit des comptes de tiers et des comptes de trésorerie concernés ;
• par le crédit du compte 4813 – Versements restant à effectuer sur titres de participation et titres immobilisés non
libérés.
Le compte 27 – AUTRES IMMOBILISATIONS FINANCIERES est crédité lors du règlement ou en cas de cession de
titres
• par le débit des comptes de trésorerie ou de tiers intéressés.', exclusions = 'Le compte 27 – AUTRES IMMOBILISATIONS FINANCIERES ne doit pas servir à enregistrer :
• les titres de participation
• les titres de placement
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 26 – Titres de participation
• 50 – Titres de placement', controle = 'Le compte 27 – AUTRES IMMOBILISATIONS FINANCIERES peut être contrôlé à partir des recoupements provenant
des :
• contrats de prêts, reçus des dépôts et cautionnement ;
• souscriptions de titres, certificats de propriété de titres ;
• reconnaissances de dettes de la part de tiers, virements bancaires et mouvements financiers.' WHERE numero = '27';
UPDATE comptes_ohada SET contenu = 'L''amortissement consiste pour l''entité à répartir le montant amortissable du bien sur sa durée d''utilité selon un plan
prédéfini.
Le montant du bien amortissable s''entend de la différence entre le coût d''entrée d''un actif et sa valeur résiduelle
prévisionnelle.
La valeur résiduelle d''un actif est le montant estimé qu''une entité obtiendrait actuellement de la sortie de l''actif, après
déduction des coûts de sortie estimés, si l''actif avait déjà l''âge et se trouvait déjà dans l''état prévu à la fin de sa durée
d''utilité.
Différents modes d''amortissement peuvent être utilisés pour répartir de façon systématique le montant amortissable d''un
actif sur sa durée d''utilité. Ces modes incluent :
• le mode linéaire ;
• le mode dégressif ;
• le mode des unités de production ;
• et tout autre mode mieux adapté.
Un mode d''amortissement basé sur les revenus générés par l''utilisation de l''actif est interdit pour les immobilisations
corporelles.
Le mode d''amortissement retenu est appliqué de manière cohérente d''une période à l''autre, sauf en cas de changement du
rythme attendu de consommation de l''actif.
La date de début d''amortissement est la date à laquelle l''actif immobilisé est en l''état et en lieu d''utilisation prévue par
l''entité.', commentaires = 'L''amortissement est en principe calculé selon les usages de la profession, de façon à amortir chaque catégorie
d''immobilisations sur la durée normale d''utilisation prévue. Toutefois, les annuités d''amortissement peuvent être adaptées
aux conditions d''exploitation (calcul sur la base d''unités de mesure de l''utilisation : tonnage, cubage, heures de
fonctionnement, etc.).
Les annuités d''amortissement peuvent être modifiées si les perspectives d''avenir justifient une telle mesure. Dans ce cas,
la correction effectuée sur les taux d''amortissement doit être révélée et quantifiée, de même que les raisons de cette
modification. En revanche, si des prévisions devaient conduire à des prix plus élevés que les premières estimations,
aucune correction ne devrait être pratiquée.
Si la durée d''utilité du bien dans l''entité devait être nettement inférieure à sa durée probable de vie, il doit être tenu compte
d''une valeur résiduelle raisonnablement appréciée au moment de l''établissement du plan d''amortissement. Dans le cas
d''espèce, le calcul de l''amortissement doit être effectué sur la différence entre la valeur d''entrée et la valeur résiduelle,
déduction faite des frais estimés de la revente.
Pour fixer le taux d''amortissement, il est tenu compte de l''usure correspondant aux conditions d''utilisation prévisibles,
notamment :
• du travail en fonction du nombre d''équipes tournantes (double ou triple équipes) ;
• de la désuétude potentielle due aux changements technologiques, c''est-à- dire, des circonstances qui peuvent rendre
prématurément caduques certaines immobilisations ;
• de l''obsolescence potentielle due aux variations de la demande affectant les articles produits ou les services fournis par
l''utilisation.
Les amortissements doivent être pratiqués à la clôture de chaque exercice, même en cas d''absence ou d''insuffisance de
bénéfice', fonctionnement = 'Le compte 28 – AMORTISSEMENTS est crédité, en fin d''exercice de l''annuité d''amortissement ou en cas de cession
de la dotation complémentaire aux amortissements
• par le débit du compte 681 – Dotations aux amortissements d''exploitation ;
• ou par le débit du compte 85 – Dotations H.A.O.
Le compte 28 – AMORTISSEMENTS est débité, en cas de cession d''immobilisation, de l''annulation des
amortissements relatifs à l''immobilisation cédée
• par le crédit du compte d''immobilisation concerné (classe 2).
Le compte 28 – AMORTISSEMENTS est débité de la reprise des amortissements
• par le crédit du compte 798 – Reprises d''amortissements, en cas de révision du plan d''amortissement ;
• ou par le crédit du compte 862 – Reprises d''amortissements H.A.O.', exclusions = NULL, controle = 'Le compte 28 – AMORTISSEMENTS peut être contrôlé à partir des recoupements provenant des tableaux
d''amortissement.' WHERE numero = '28';
UPDATE comptes_ohada SET contenu = 'A la clôture de chaque exercice une entité doit apprécier, s''il existe un quelconque indice qu''un actif a subi une perte de
valeur. S''il existe un tel indice, l''entité doit estimer la valeur actuelle et la comparer avec la valeur nette comptable.
L''actif doit être déprécié lorsque la valeur nette comptable est supérieure à la valeur actuelle.
Pour évaluer s''il existe un indice quelconque de dépréciation d''actif, une entité doit tenir compte, au minimum, des
indications suivantes :
• Sources externes :
• durant l''exercice, la valeur de marché d''un actif a diminué de façon plus importante que du seul effet attendu du
passage du temps ou de l''utilisation normale de l''actif;
• d''importants changements, ayant un effet négatif sur l''entité, sont survenus au cours de l''exercice ou surviendront dans
un proche avenir, dans l''environnement technologique, économique ou juridique ou du marché dans lequel l''entité opère
ou dans le marché auquel l''actif est dévolu ;
• les taux d''intérêt du marché ou d''autres taux de rendement du marché ont augmenté durant l''exercice et il est probable
que ces augmentations affecteront le taux d''actualisation utilisé dans le calcul de la valeur d''utilité d''un actif et
diminueront de façon significative la valeur actuelle de l''actif;
• Sources internes :
• il existe un indice d''obsolescence ou de dégradation physique d''un actif;
• des changements importants, ayant un effet négatif sur l''entité, sont survenus au cours de l''exercice ou sont
susceptibles de survenir dans un proche avenir, dans le degré ou le mode d''utilisation d''un actif tel qu''il est utilisé ou
que l''on s''attend à l''utiliser. Ces changements incluent la mise hors service de l''actif, les plans d''abandon ou de
restructuration du secteur d''activité auquel un actif appartient et les plans de sortie d''un actif avant la date
antérieurement prévue, et la réestimation de la durée d''utilité d''un actif comme déterminée plutôt qu''indéterminée;
• un élément probant provenant du système d''information interne montre que la performance économique d''un actif est
ou sera moins bonne que celle attendue.
Les dépréciations sont inscrites distinctement à l''actif, en diminution de la valeur brute des biens correspondants pour
donner leur valeur comptable nette (V.C.N.).
Même en cas d''absence ou d''insuffisance de bénéfice au cours de l''exercice, il doit être procédé aux dotations nécessaires
pour couvrir les dépréciations.', commentaires = 'L''actif doit être déprécié lorsque sa valeur nette comptable est supérieure à sa valeur actuelle. Cette dépréciation est
constatée par une dotation aux dépréciations (voir Titre VIII Opérations spécifiques, Chapitre 12).
Elles peuvent également concerner les dépréciations exceptionnelles subies par les immobilisations amortissables, lorsque
ces dépréciations ne peuvent raisonnablement être inscrites aux comptes d''amortissement, en raison de leur caractère
définitif.
A la différence des provisions pour pertes et charges, elles expriment des corrections d''actif de sens négatif.
Les dépréciations dépendent des conditions d''exploitation de chaque entité ou de circonstances économiques particulières.
En ce qui concerne les titres, la dépréciation est déterminée à la fin de chaque période, conformément aux règles
suivantes :
• les titres cotés sont évalués au cours moyen boursier du dernier mois ;
• les titres non cotés sont estimés à leur valeur probable de négociation.
Les plus-values apparaissant à la suite de cette estimation ne sont pas comptabilisées. En revanche, les moins- values
sont inscrites au compte de dépréciation. La dépréciation fait donc apparaître, à la clôture de chaque exercice, la totalité
des moins-values constatées à cette date sur les titres en baisse, aucune compensation n''étant établie avec les plus-values
des titres en hausse.
Une dépréciation supplémentaire peut être constituée lorsqu''il s''est produit un événement d''une importance exceptionnelle
qui la justifie (cas de faillite, par exemple).
La dépréciation éventuelle doit en outre être calculée sur la base de la valeur libérée des titres.
Les dépréciations doivent être pratiquées à la clôture de l''exercice, même en l''absence de bénéfice, aussi bien sur les
immobilisations acquises que sur celles en cours de fabrication.', fonctionnement = 'Le compte 29 – DÉPRÉCIATIONS est crédité de la dotation aux provisions
• par le débit du compte 691 – Dotations aux dépréciations d''exploitation ;
• ou par le débit du compte 697 – Dotations aux dépréciations financières ;
• ou par le débit du compte 853 – Dotations aux dépréciations
H.A.O.
Le compte 29 – DÉPRÉCIATIONS est débité de la reprise de provision
• par le crédit du compte 791 – Reprises de provisions et de dépréciations d''exploitation ;
• par le crédit du compte 797 – Reprises de provisions et de dépréciations financières ;
• ou par le crédit du compte 863 – Reprises de dépréciations
H.A.O.', exclusions = 'Le compte 29 – DÉPRÉCIATIONS ne doit pas servir à enregistrer :
• les dépréciations des comptes de stocks
• les dépréciations des comptes de tiers
• les dépréciations des comptes de trésorerie
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 39 – Dépréciations des stocks
• 49 – Dépréciations et provisions pour risques à court terme (Tiers)
• 59 – Dépréciations et provisions pour risques à court terme (Trésorerie)', controle = 'Le compte 29 – DÉPRÉCIATIONS peut être contrôlé à partir :
• des rapprochements effectués entre la valeur d''entrée des actifs dans le patrimoine de l''entité et la valeur à la date
de clôture de l''exercice ;
• de factures ;
• de l''argus ;
• du livre d''inventaire.
COMPTES DE STOCKS
Les stocks ( voir titre VIII opérations spécifiques, chapitre 14) sont des actifs:
Les comptes de stocks peuvent être assortis de comptes de dépréciation.
La comptabilisation des stocks repose sur la tenue soit d''un inventaire permanent, soit d''un inventaire intermittent.
Toutefois, les entités qui n''ont pas les moyens de tenir l''inventaire permanent peuvent recourir au système de
l''inventaire intermittent. Dans ce cas, en fin de période, elles doivent passer les écritures faisant apparaître les
variations de stocks de cette période, pour retrouver le schéma comptable demandé.
L''inventaire physique est un inventaire extra-comptable c''est-à-dire un récolement matériel des existants effectué au
moins une fois pendant l''exercice. Il comporte deux opérations :
• l''établissement de la liste complète des divers éléments composant les stocks par groupe de marchandises,
matières et produits correspondant à la classification des comptes ;
• l''évaluation des existants réels constatés par l''opération précédente.
L''inventaire comptable permanent permet à l''entité de connaître à chaque instant :
• le montant de ses stocks ;
• le coût d''achat des marchandises vendues ;
• le coût d''achat des matières et fournitures engagées dans le processus de fabrication.
L''inventaire intermittent ne permet de connaître le montant des existants qu''à la clôture de l''exercice, au moment de
l''inventaire extra-comptable.
Le coût des stocks doit comprendre tous les coûts d''acquisition, coûts de transformation et autres coûts engagés pour
amener les stocks à l''endroit et dans l''état où ils se trouvent.
Les coûts d''acquisition des stocks comprennent le prix d''achat, les droits de douane et autres taxes non récupérables
par l''entité auprès des administrations fiscales), ainsi que les frais de transport, de manutention et autres coûts
directement attribuables à l''acquisition des produits finis, des matières premières et des services. Les rabais
commerciaux, remises et autres éléments similaires sont déduits pour déterminer les coûts d''acquisition.
Les coûts de transformation des stocks comprennent les coûts directement liés aux unités produites, tels que la main-
d''œuvre directe. Ils comprennent également l''affectation systématique des frais généraux de production fixes et
variables qui sont encourus pour transformer les matières premières en produits finis. Les frais généraux de production
fixes sont les coûts indirects de production qui demeurent relativement constants indépendamment du volume de
production, tels que l''amortissement et l''entretien des bâtiments et de l''équipement industriels, et les frais de gestion et
d''administration de l''usine. Les frais généraux de production variables sont les coûts indirects de production qui varient
directement, ou presque directement, en fonction du volume de production, tels que les matières premières indirectes et
la main-d''œuvre indirecte.
Les autres coûts ne sont inclus dans le coût des stocks que dans la mesure où ils sont encourus pour amener les
stocks à l''endroit et dans l''état où ils se trouvent. Par exemple, il peut être approprié d''inclure dans le coût des stocks
des frais généraux autres que ceux de production ou les coûts de conception de produits à l''usage de clients
spécifiques.
Les prestataires de services évaluent leur stock au coût de production. Ces coûts se composent essentiellement de la
main- d''œuvre et des autres frais de personnel directement engagés pour fournir le service, y compris le personnel
d''encadrement, et les frais généraux attribuables. La main-d''œuvre et les autres coûts relatifs aux ventes et au
personnel administratif général ne sont pas inclus mais sont comptabilisés en charges de la période au cours de
laquelle ils sont encourus. Le coût des stocks d''un prestataire de services ne comprend pas les marges bénéficiaires ou
les frais généraux non attribuables qui sont souvent incorporés dans les prix facturés par les prestataires de services.
Les stocks comprenant la production agricole, récoltés par une entité à partir de ses actifs biologiques, sont évalués
lors de la comptabilisation initiale à la valeur actuelle, moins les coûts des points de vente estimés au moment de la
récolte (voir titre VIII Opérations spécifiques, Chapitre 37).
Les techniques d''évaluation du coût des stocks, telles que la méthode du coût standard ou la méthode du prix de détail,
peuvent être utilisées pour des raisons pratiques si ces méthodes donnent des résultats proches du coût.
Les coûts standard retiennent les niveaux normaux d''utilisation de matières premières et de fournitures, de main-
d''œuvre, d''efficience et de capacité. Ils sont régulièrement réexaminés et, le cas échéant, révisés à la lumière des
conditions actuelles.
La méthode du prix de détail est souvent utilisée dans l''activité de la distribution au détail pour évaluer les stocks de
grandes quantités d''articles à rotation rapide, qui ont des marges similaires et pour lesquels il n''est pas possible
d''utiliser d''autres méthodes de coûts. Le coût des stocks est déterminé en déduisant de la valeur de vente des stocks le
pourcentage de marge brute approprié. Le pourcentage utilisé prend en considération les stocks qui ont été démarqués
au-dessous de leur prix de vente initial. Un pourcentage moyen pour chaque rayon est souvent utilisé.
A la sortie de magasin ou à l''inventaire, les stocks sont ainsi évalués :
• les biens matériellement identifiés et individualisés ainsi que ceux qui ne sont pas interchangeables, sont évalués
article par article à leur coût d''entrée ;
• les biens interchangeables (fongibles), non identifiables après leur entrée en magasin sont évalués soit selon la
technique du coût moyen pondéré (C.M.P.), soit selon la méthode du premier entré premier sorti (P.E.P.S.).
A la clôture de l''exercice les stocks doivent être évalués au plus faible de la valeur nette comptable et de la valeur nette
de réalisation.
La valeur nette de réalisation est le prix de vente estimé dans le cours normal de l''activité, diminué des coûts estimés
pour l''achèvement et des coûts estimés nécessaires pour réaliser la vente.
Les estimations de la valeur nette de réalisation sont fondées sur les éléments probants les plus fiables disponibles à la
date à laquelle elles sont faites, du montant que l''on s''attend à réaliser des stocks. Ces estimations tiennent compte des
fluctuations de prix ou de coût directement liées aux événements survenant après la fin de la période, dans la mesure
où de tels événements confirment les conditions existant à la fin de la période.
Le stock doit être déprécié si la valeur nette comptable est supérieure à la valeur nette de réalisation, élément par
élément.
Dans certains cas, il peut être approprié de regrouper des éléments similaires ou ayant des rapports entre eux.
COMPTES DE LA CLASSE 3
31      Marchandises                                     53   36   Produits finis                                      60
32      Matières premières et fournitures liées          55   37   Produits intermédiaires et résiduels                61
33      Autres approvisionnements                        56   38   Stocks en cours de route, en consignation ou en dépôt    63
34      Produits en cours                                58   39   Dépréciations des stocks et encours de production   65
35      Services en cours                                59' WHERE numero = '29';
UPDATE comptes_ohada SET contenu = 'Les marchandises sont les objets, matières et fournitures, acquis par l''entité et destinés à être revendus en l''état.', commentaires = 'Le compte 31 est subdivisé selon les besoins de l''entité.
Les marchandises hors activités ordinaires (H.A.O.) ne seront distinguées que si leur montant est supérieur à 5 % du total
de l''actif circulant.', fonctionnement = 'En cas d''inventaire intermittent, à la clôture de l''exercice :
Le compte 31 – MARCHANDISES est débité du montant du stock final (1), déterminé par inventaire extra-comptable
et évalué conformément aux règles précisées dans l''évaluation des stocks : les biens matériellement identifiés et
individualisés au coût d''entrée ; les biens interchangeables non identifiables suivant la (méthode P.E.P.S. ou du coût
moyen pondéré)
• par le crédit du compte 6031 – Variations des stocks de marchandises.
Le compte 31 – MARCHANDISES est crédité du montant du stock initial (2), pour solde
(1) ou du montant de l''augmentation de l''exercice (stock final moins stock initial)
(2) ou du montant de la diminution de l''exercice (stock initial moins stock final)
• par le débit du compte 6031 – Variations des stocks de marchandises
En cas d''inventaire permanent :
Le compte 31 – MARCHANDISES est débité, à chaque entrée en stock, du coût des marchandises achetées (prix
d''achat et frais accessoires d''achat)
• par le crédit du compte 6031 – Variations des stocks de marchandises.
Le compte 31 – MARCHANDISES est débité en fin d''exercice, après inventaire physique, pour régularisation du stock
des marchandises, des différences constatées en plus, par rapport à l''inventaire permanent
• par le crédit du compte 6031 – Variations des stocks de marchandises.
Le compte 31 – MARCHANDISES est crédité, à chaque sortie de stock, les biens matériellement identifiés et
individualisés au coût d''entrée ; les biens interchangeables non identifiables suivant la méthode du premier entré,
premier sorti (P.E.P.S.) ou du coût moyen pondéré (C.M.P.)
• par le débit du compte 6031 – Variations des stocks de marchandises.
Le compte 31 – MARCHANDISES est crédité à la clôture de l''exercice, après inventaire physique, pour régularisation
du stock des marchandises, des différences constatées en moins, par rapport à l''inventaire permanent
• par le débit du compte 6031 – Variations des stocks de marchandises.', exclusions = 'Le compte 31 – MARCHANDISES ne doit pas servir à enregistrer :
• les achats de matières premières et fournitures non destinées à être revendues en l''état
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 32 – Matières premières et fournitures liées', controle = 'Le compte 31 – MARCHANDISES peut être contrôlé à partir de l''inventaire extra- comptable et des factures (achats et
frais).' WHERE numero = '31';
UPDATE comptes_ohada SET contenu = 'Les matières premières et fournitures liées sont les objets, matières et fournitures achetés pour être incorporés aux produits
fabriqués.', commentaires = 'Les matières dites consommables ne font pas partie des fournitures liées et sont classées dans le compte 33 – Autres
approvisionnements.', fonctionnement = 'En cas d''inventaire intermittent, à la clôture de l''exercice :
Le compte 32 – MATIERES PREMIERES ET FOURNITURES LIEES FOURNITURES LIEES est débité du montant
du stock final(1), déterminé par inventaire extra-comptable et évalué conformément aux règles précisées dans
l''évaluation des stocks : les biens matériellement identifiés et individualisés au coût d''entrée ; les biens
interchangeables non identifiables suivant la (méthode P.E.P.S. ou du coût moyen pondéré),
• par le crédit du compte 6032 – Variations des stocks de matières premières et fournitures liées.
Le compte 32 – MATIERES PREMIERES ET FOURNITURES LIEES est crédité du montant du stock initial(2), pour
solde
(1) ou du montant de l''augmentation de l''exercice (stock final moins stock initial)
(2) ou du montant de la diminution de l''exercice (stock initial moins stock final)
• par le débit du compte 6032 – Variations des stocks de matières premières et fournitures liées.
En cas d''inventaire permanent :
Le compte 32 – MATIERES PREMIERES ET FOURNITURES LIEES est débité, à chaque entrée en stock, du coût
des matières et fournitures achetées (prix d''achat et frais accessoires d''achat)
• par le crédit du compte 6032 – Variations des stocks de matières premières et fournitures liées
Le compte 32 – MATIERES PREMIERES ET FOURNITURES LIEES est débité, à la clôture de l''exercice, après
inventaire physique, pour régularisation du stock des matières premières et fournitures liées, des différences
constatées en plus, par rapport à l''inventaire permanent
• par le crédit du compte 6032 – Variations des stocks de matières premières et fournitures liées.
Le compte 32 – MATIERES PREMIERES ET FOURNITURES LIEES est crédité à chaque sortie de stock, les biens
matériellement identifiés et individualisés au coût d''entrée ; les biens interchangeables non identifiables suivant la
méthode du premier entré, premier sorti (P.E.P.S.) ou du coût moyen pondéré
• par le débit du compte 6032 – Variations des stocks de matières premières et fournitures liées.
Le compte 32 – MATIERES PREMIERES ET FOURNITURES LIEES est crédité, à la clôture de l''exercice, après
inventaire physique, pour régularisation du stock des matières premières et fournitures liées, des différences
constatées en moins, par rapport à l''inventaire permanent
• par le débit du compte 6032 – Variations des stocks de matières premières et fournitures liées.', exclusions = 'Le compte 32 – MATIERES PREMIERES ET FOURNITURES LIEES ne doit pas servir à enregistrer :
• le matériel de remplacement ou de réserve qui n''est pas encore en service
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 24 – Matériel, mobilier et actifs biologiques', controle = 'Le compte 32 – MATIERES PREMIERES ET FOURNITURES LIEES peut être contrôlé à partir de l''inventaire extra-
comptable et des factures (achats et frais).' WHERE numero = '32';
UPDATE comptes_ohada SET contenu = 'Les autres approvisionnements sont des matières, des fournitures acquises par l''entité et qui concourent à la fabrication ou
à l''exploitation, sans entrer dans la composition des produits fabriqués ou traités.', commentaires = 'Le compte 33 peut comprendre des pièces de rechange, du petit outillage et, le cas échéant, du matériel mobile, dont la
destination définitive (immobilisation ou entretien) n''est pas exactement connue.', fonctionnement = 'En cas d''inventaire intermittent, à la clôture de l''exercice
Le compte 33 – AUTRES APPROVISIONNEMENTS est débité du montant du stock final(1), déterminé par inventaire
extra-comptable et évalué conformément aux règles précisées dans l''évaluation des stocks : les biens matériellement
identifiés et individualisés au coût d''entrée ; les biens interchangeables non identifiables suivant la (méthode P.E.P.S.
ou du coût moyen pondéré),
• par le crédit du compte 6033 – Variations des stocks d''autres approvisionnements.
Le compte 33 – AUTRES APPROVISIONNEMENTS est crédité du montant du stock initial(2), pour solde
(1) ou du montant de l''augmentation de l''exercice (stock final moins stock initial).
(2) ou du montant de la diminution de l''exercice (stock initial moins stock final)
• par le débit du compte 6033 – Variations des stocks d''autres approvisionnements.
En cas d''inventaire permanent :
Le compte 33 – AUTRES APPROVISIONNEMENTS est débité, à chaque entrée en stocks, du coût des autres
approvisionnements achetés (prix d''achat et frais accessoires d''achat)
• par le crédit du compte 6033 – Variations des stocks d''autres approvisionnements.
Le compte 33 – AUTRES APPROVISIONNEMENTS est débité, à la clôture de l''exercice, après inventaire physique,
pour régularisation du stock d''autres approvisionnements, des différences constatées en plus, par rapport à
l''inventaire permanent
• par le crédit du compte 6033 – Variations des stocks d''autres approvisionnements.
Le compte 33 – AUTRES APPROVISIONNEMENTS est crédité, à chaque sortie de stock, les biens matériellement
identifiés et individualisés au coût d''entrée ; les biens interchangeables non identifiables suivant la méthode du
premier entré, premier sorti (P.E.P.S.) ou du coût moyen pondéré,
• par le débit du compte 6033 – Variations des stocks d''autres approvisionnements.
Le compte 33 – AUTRES APPROVISIONNEMENTS est crédité, en fin d''exercice, après inventaire physique, pour
régularisation du stock d''autres approvisionnements, des différences constatées en moins, par rapport à l''inventaire
permanent
• par le débit du compte 6033 – Variations des stocks d''autres approvisionnements.', exclusions = 'Le compte 33 – AUTRES APPROVISIONNEMENTS ne doit pas servir à enregistrer :
• le matériel de remplacement ou de réserve qui n''est pas encore en service
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 24 – Matériel, Mobilier et Actifs biologiques', controle = 'Le compte 33 – AUTRES APPROVISIONNE-MENTS peut être contrôlé à partir de l''inventaire extra- comptable et des
factures (achats et frais).' WHERE numero = '33';
UPDATE comptes_ohada SET contenu = 'Les produits en cours sont des biens et services en voie de formation ou de transformation à la clôture de l''exercice.', commentaires = 'Les produits en cours ne sont pas inscrits à un compte de magasin.
Les travaux en cours concernent des biens d''équipement lourd, immeubles, constructions, dont les délais de fabrication
sont relativement longs et dont la propriété n''est pas encore transférée à l''acheteur.', fonctionnement = 'En cas d''inventaire intermittent :
Le compte 34 – PRODUITS EN COURS est débité, en fin d''exercice, du montant constaté des en-cours(1) déterminé
en comptabilité analytique de gestion ou par voie extra-comptable
• par le crédit du compte 734 – Variations des stocks de produits en cours
Le compte 34 – PRODUITS EN COURS est crédité, en fin d''exercice, du montant initial des en-cours(2), pour solde
• par le débit du compte 734 – Variations des stocks de produits en cours.
En cas d''inventaire permanent :
(1) ou de l''augmentation de l''exercice (montant final moins montant initial).
(2) ou de la diminution de l''exercice (montant initial moins montant final)
Le compte 34 – PRODUITS EN COURS est débité, à chaque incorporation des frais dans les en-cours, du montant
déterminé en comptabilité analytique de gestion
• par le crédit du compte 734 – Variations des stocks de produits en cours.
Le compte 34 – PRODUITS EN COURS est crédité, à chaque sortie des en-cours achevés et transférés en produits
finis ou intermédiaires au coût de production
• par le débit du compte 734 – Variations des stocks de produits en cours.', exclusions = 'Le compte 34 – PRODUITS EN COURS ne doit pas servir à enregistrer :
• les services en cours
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 35 – Services en cours', controle = 'Le compte 34 – PRODUITS EN COURS peut être contrôlé à partir de l''inventaire extra-comptable et de l''évaluation
des coûts de production.' WHERE numero = '34';
UPDATE comptes_ohada SET contenu = 'Les services en cours sont des études et prestations en cours d''exécution, dont la remise définitive à l''acheteur ou au
passeur d''ordre n''est pas encore intervenue.', commentaires = 'Les montants d''études et de prestations déjà engagées et non encore facturées (cas de prestations d''une certaine durée ;
exemples : étude d''organisation, transport international ...) peuvent, en fonction de l''organisation, être suivis en inventaire
permanent ou seulement constatés en inventaire intermittent.', fonctionnement = 'En cas d''inventaire intermittent :
Le compte 35 – SERVICES EN COURS est débité, en fin d''exercice, du montant constaté des en-cours(1) déterminé
en comptabilité analytique de gestion ou par voie extra-comptable
• par le crédit du compte 735 – Variations des en-cours de services.
Le compte 35 – SERVICES EN COURS est crédité en fin d''exercice du montant des en-cours existant au début de
l''exercice(2), pour solde
• par le débit du compte 735 – Variations des en-cours de services.
(1) ou de l''augmentation de l''exercice (montant final moins montant initial).
(2) ou de la diminution de l''exercice (montant initial moins montant final)
En cas d''inventaire permanent :
Le compte 35 – SERVICES EN COURS est débité, à chaque incorporation des frais dans les en-cours, du montant
des travaux en cours déterminé en comptabilité analytique intégrée
• par le crédit du compte 735 – Variations des en-cours de services.
Le compte 35 – SERVICES EN COURS est crédité à chaque sortie en coût de production des "en-cours" achevés et
vendus
• par le débit du compte 735 – Variations des en-cours de services.', exclusions = 'Le compte 35 – SERVICES EN COURS ne doit pas servir à enregistrer :
• les produits en cours
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 34 – Produits en cours', controle = 'Le compte 35 – SERVICES EN COURS peut être contrôlé à partir de l''inventaire extra-comptable et de l''évaluation
des coûts de production.' WHERE numero = '35';
UPDATE comptes_ohada SET contenu = 'Les produits finis sont les produits fabriqués par l''entité qui ont atteint le stade final de production. Ils sont destinés à être
vendus, loués ou fournis.', commentaires = 'Lorsque l''entité vend concurremment et indistinctement des produits achetés à l''extérieur ou des produits fabriqués par elle-
même, en tous points semblables et ne se distinguant que par leur origine, elle peut n''ouvrir qu''un seul compte pour cette
marchandise et ce produit, évalués respectivement selon le coût d''achat et le coût de production. Les sorties de stocks sont
créditées par le débit du compte 6031 – Variations des stocks de marchandises et du compte 736 – Variations des stocks
de produits finis, selon un prorata qu''elle détermine sous sa propre responsabilité.', fonctionnement = 'En cas d''inventaire intermittent :
Le compte 36 – PRODUITS FINIS est débité, à la fin de l''exercice, du montant du stock final (1), évalué : pour les
corps certains, au coût réel de production ; pour les biens interchangeables, au coût de production déterminé en
présumant que le premier élément sorti est le premier entré (P.E.P.S.) ou au coût moyen pondéré
• par le crédit du compte 736 – Variations des stocks de produits finis.
Le compte 36 – PRODUITS FINIS est crédité, à la fin de l''exercice, du montant du stock initial(2), pour solde
(1) ou de l''augmentation de l''exercice (stock final moins stock initial).
(2) ou de la diminution de l''exercice (stock initial moins stock final).
• par le débit du compte 736 – Variations des stocks de produits finis.
En cas d''inventaire permanent :
Le compte 36 – PRODUITS FINIS est débité, à chaque entrée en stock, du coût de production des produits finis,
déterminé par la comptabilité analytique de gestion ou autonome
• par le crédit du compte 736 – Variations des stocks de produits finis.
Le compte 36 – PRODUITS FINIS est débité, à la clôture de l''exercice, après inventaire physique, pour régularisation
du stock de produits finis, des différences constatées, en plus, par rapport à l''inventaire permanent
• par le crédit du compte 736 – Variations des stocks de produits finis.
Le compte 36 – PRODUITS FINIS est crédité, à chaque sortie des stocks : pour les corps certains, du coût réel de
production ; pour les biens interchangeables, du coût de production déterminé en présumant que le premier élément
sorti est le premier entré (P.E.P.S.) ou du coût moyen pondéré
• par le débit du compte 736 – Variations des stocks de produits finis.
Le compte 36 – PRODUITS FINIS est crédité, à la clôture de l''exercice, après inventaire physique, pour régularisation
du stock de produits finis, des différences constatées, en moins, par rapport à l''inventaire permanent
• par le débit du compte 736 – Variations des stocks de produits finis.', exclusions = 'Le compte 36 – PRODUITS FINIS ne doit pas servir à enregistrer :
• les produits intermédiaires fabriqués
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 37 – Produits intermédiaires et résiduels', controle = 'Le compte 36 – PRODUITS FINIS peut être contrôlé à partir de l''inventaire extra- comptable et de l''évaluation des
coûts de production.' WHERE numero = '36';
UPDATE comptes_ohada SET contenu = 'Les produits intermédiaires sont des produits ayant atteint un stade déterminé de fabrication et disponibles pour des
fabrications ultérieures.', commentaires = 'Lorsque l''entité utilise concurremment et indistinctement un produit intermédiaire fabriqué par elle et une matière ou
fourniture liée achetée à l''extérieur, mais en tous points semblables et ne se distinguant que par leur origine, elle peut
n''ouvrir qu''un seul compte pour cette matière et ce produit, mais, dans ce cas, elle crédite les sorties de stocks par le débit
du compte 7371 – Variations des stocks de produits intermédiaires et par celui du compte 6032 – Variations des stocks de
matières premières et fournitures liées, suivant un prorata qu''elle détermine sous sa propre responsabilité.
Les produits résiduels sont constitués par :
• les déchets et rebuts : résidus de toutes natures (produits ouvrés ou semi- ouvrés) impropres à une utilisation ou à un
écoulement normal ;
• les produits de la récupération : matières récupérées à la suite de la mise hors service de certaines immobilisations.
Le compte 372 n''est ouvert que si les déchets et rebuts ne peuvent être normalement introduits dans la nomenclature des
biens et services de l''entité.', fonctionnement = 'En cas d''inventaire intermittent, à la clôture de l''exercice :
Le compte 371 – PRODUITS INTERMEDIAIRES est débité du montant du stock final(1), évalué : pour les corps
certains, au coût réel de production ; pour les biens interchangeables, au coût de production déterminé en présumant
que le premier élément sorti est le premier entré (P.E.P.S.) ou au coût moyen pondéré
• par le crédit du compte 7371 – Variations des stocks de produits intermédiaires.
Le compte 371 – PRODUITS INTERMEDIAIRES est crédité du montant du stock initial(2), pour solde
• par le débit du compte 7371 – Variations des stocks de produits intermédiaires
Le compte 372 – PRODUITS RESIDUELS est débité de la valeur estimée du stock final de produits résiduels(3),
conformément aux règles d''évaluation
• par le crédit du compte 7372 – Variations des stocks de produits résiduels.
Le compte 372 – PRODUITS RESIDUELS est débité de la valeur des produits de la récupération (matières et
matériaux) provenant de la fabrication ou de la mise hors service d''immobilisations, dans la mesure où ils ne sont pas
affectables aux comptes 31, 32 ou 33
• par le crédit du compte 7372 – Variations des stocks de produits résiduels.
Le compte 372 – PRODUITS RESIDUELS est crédité du montant du stock initial(1), pour solde
(1) ou de l''augmentation de l''exercice (stock final moins stock initial)
(2) ou de l''augmentation de l''exercice (stock final moins stock initial)
(3) ou de la diminution de l''exercice (stock initial moins stock final)
• par le débit du compte 7372 – Variations des stocks de produits résiduels.
En cas d''inventaire permanent :
Le compte 37 – PRODUITS INTERMEDIAIRES ET RESIDUELS est débité, à chaque entrée en stocks, du coût de
production des produits intermédiaires et résiduels, déterminé par la comptabilité analytique intégrée ou autonome
• par le crédit du compte 737 – Variations des stocks de produits intermédiaires et résiduels.
Le compte 37 – PRODUITS INTERMEDIAIRES ET RESIDUELS est débité, à la clôture de l''exercice, après inventaire
physique, pour régularisation des stocks de produits intermédiaires et de produits résiduels, des différences
constatées en plus, par rapport à l''inventaire permanent
• par le crédit du compte 737 – Variations des stocks de produits intermédiaires et résiduels.
Le compte 37 – PRODUITS INTERMEDIAIRES ET RESIDUELS est crédité, à chaque sortie des stocks : pour les
corps certains, du coût réel de production ; pour les biens interchangeables, du coût de production déterminé en
présumant que le premier élément sorti est le premier entré (P.E.P.S.) ou au coût moyen pondéré
• par le débit du compte 737 – Variations des stocks de produits intermédiaires et résiduels.
Le compte 37 – PRODUITS INTERMEDIAIRES ET RESIDUELS est crédité à la clôture de l''exercice, après inventaire
physique, pour régularisation des stocks, des différences constatées en moins, par rapport à l''inventaire permanent
• par le débit du compte 737 – Variations des stocks de produits intermédiaires et résiduels.', exclusions = 'Le compte 37 – PRODUITS INTERMEDIAIRES ET RESIDUELS ne doit pas servir à enregistrer :
• les produits en cours qui par définition ne peuvent être inscrits à un compte de magasin
• les produits issus d''immobilisations démontées ou mises hors service en attendant l''affectation définitive
• les produits issus de la récupération affectés définitivement à d''autres stocks
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 34 – Produits en cours
• compte 38
• comptes 31, 32, 33', controle = 'Le compte 37 – PRODUITS INTERMEDIAIRES ET RESIDUELS peut être contrôlé à partir de l''inventaire extra-
comptable et de l''évaluation des coûts de production des produits concernés.' WHERE numero = '37';
UPDATE comptes_ohada SET contenu = 'Ce sont des marchandises, matières, fournitures ou produits fabriqués, expédiés par le fournisseur et non encore
réceptionnés par l''entité ou détenus chez des tiers mais dont l''entité est propriétaire.', commentaires = 'Dans le cadre du système d''inventaire permanent, le compte 38 constitue un compte de passage destiné à enregistrer les
stocks dont l''entité est déjà propriétaire, mais qui sont en voie d''acheminement et non encore réceptionnés. Il peut
également être utilisé pour constater l''envoi de stocks (marchandises, matières et fournitures, produits fabriqués) en dépôt
ou en consignation, jusqu''à réception par le dépositaire ou le consignataire. Il s''agit de stocks contrôlés par l''entité mais non
détenus physiquement à la clôture de l''exercice.
Dès réception, les stocks comptabilisés au compte 38 sont ventilés dans les comptes de stocks appropriés et classés,
conformément à la nomenclature des biens et services en usage dans l''entité.
Les entités qui tiennent un inventaire intermittent enregistrent les stocks en cours de route dans les achats à la date de
transfert de propriété et utilisent, exceptionnellement, le compte 38 si ces stocks ne sont pas encore réceptionnés à la date
d''établissement des comptes annuels.
Les stocks provenant d''immobilisation comprennent les éléments récupérés ou démontés d''immobilisations corporelles.
Ce compte est débité par le crédit du compte d''immobilisation concerné.
En fin de période, les entités doivent inscrire, dans les Notes annexes, le détail par catégorie des stocks figurant au bilan
dans le compte 38.', fonctionnement = 'En cas d''inventaire intermittent :
Le compte 38 – STOCKS EN COURS DE ROUTE, EN CONSIGNATION OU EN DEPÔT est débité, en fin d''exercice,
des stocks en cours de route à cette date(1), pour leur coût approché ou leur coût standard, le coût réel n''étant pas,
dans le cas d''espèce, connu à la date d''établissement des états financiers annuels
• par le crédit des sous-comptes 603 concernés.
Le compte 38 – STOCKS EN COURS DE ROUTE, EN CONSIGNATION OU EN DEPÔT est crédité à la fin de
l''exercice du montant des stocks en cours de route de début d''exercice(2), pour solde
• par le débit des sous-comptes 603 concernés.
En cas d''inventaire permanent :
Le compte 38 – STOCKS EN COURS DE ROUTE, EN CONSIGNATION OU EN DEPÔT est débité du montant des
marchandises, matières premières et fournitures, produits fabriqués, en cours de route et non encore réceptionnés
(coût approché ou coût standard)
• par le crédit des sous-comptes 603 concernés.
Le compte 38 – STOCKS EN COURS DE ROUTE, EN CONSIGNATION OU EN DEPÔT est crédité lorsque les
stocks sont réceptionnés par l''entité, le consignataire ou le dépositaire
• par le débit des comptes de stocks de la classe 3 concernés.
(1) ou de l''augmentation de l''exercice (stock final moins stock initial)
(2) ou de la diminution de l''exercice (stock initial moins stock final)', exclusions = 'Le compte 38 – STOCKS EN COURS DE ROUTE, EN CONSIGNATION OU EN DEPÔT ne doit pas servir à
enregistrer :
• les stocks dont l''entité a pris possession et dont elle continue d''attendre les factures d''achat
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• En cours d''exercice : pas d''écriture à passer.
• A la clôture de l''exercice : les comptes de régularisation', controle = 'Le compte 38 – STOCKS EN COURS DE ROUTE, EN CONSIGNATION OU EN DEPÔT peut être contrôlé à partir de
l''inventaire extra-comptable et au moyen des factures d''achat.' WHERE numero = '38';
UPDATE comptes_ohada SET contenu = 'Ce sont des dépréciations subies par des stocks de marchandises, de matières, et autres approvisionnements résultant de
causes diverses dont les effets ne sont pas jugés irréversibles.', commentaires = 'Les dépréciations des stocks obéissent aux mêmes règles de comptabilisation que les dépréciations constatées sur les
autres éléments de l''actif circulant (classe 4).
La dépréciation doit être certaine quant à sa nature et l''élément d''actif en cause doit être individualisé.
La dépréciation est à constituer même si la dépréciation est d''un montant incertain.
La dépréciation traduit une baisse non définitive et non irréversible de l''évaluation des éléments d''actif par rapport à leur
valeur comptable.
La dépréciation doit être constituée même en l''absence ou en cas d''insuffisance de bénéfices, conformément au principe de
prudence.
Lorsque au jour de l''inventaire, la valeur nette de réalisation des stocks est inférieure à leur valeur comptable déterminée
conformément aux dispositions exposées ci-dessus, classe 3 : comptes de stocks et dans l''Acte uniforme, les entités
doivent constituer des dépréciations qui expriment les moins-values constatées sur ces stocks.
Les éléments en stock détériorés, défraîchis, démodés doivent faire l''objet d''une dépréciation.
Le montant de ces dépréciations est normalement déterminé par différence entre :
• d''une part, la valeur nette comptable;
• d''autre part, la valeur nette de réalisation au jour de l''inventaire.
Les dépréciations sont portées à l''actif du bilan, en déduction de la valeur des postes qu''elles concernent, sous la forme
prévue par le modèle de bil', fonctionnement = 'En fin d''exercice :
Le compte 39 – DEPRECIATIONS DES STOCKS est crédité des dépréciations constatées sur les stocks à la fin de
l''exercice(1)
• par le débit du compte 6593 – Charges pour dépréciations et provisions pour risques à court terme d''exploitation
sur stocks;
• ou par le débit du compte 839 – Charges pour dépréciations et provisions pour risques à court terme H.A.O.
Le compte 39 – DEPRECIATIONS DES STOCKS est débité des dépréciations existant au début de l''exercice sur les
stocks(2), pour solde
• par le crédit du compte 7593 – Reprises de charges pour dépréciations et provisions pour risques à court terme
d''exploitation sur stocks ;
• ou par le crédit du compte 849 – Reprises de charges pour dépréciations et provisions pour risques à court terme
H.A.O.', exclusions = 'Le compte 39 – DEPRECIATIONS DES STOCKS ne doit pas servir à enregistrer :
• les dépréciations de l''actif immobilisé de la classe 2
• les dépréciations des clients et comptes rattachés
• les dépréciations des comptes de trésorerie
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• compte 29 – Dépréciations
• compte 49 – Dépréciations et provisions pour risques à court terme (Tiers)
• compte 59 – Dépréciations et provisions pour risques à court terme (Trésorerie)
(1) ou de l''augmentation de l''exercice (dépréciations finales moins dépréciations initiales)
(2) ou de la diminution de l''exercice (dépréciations initiales moins dépréciations finales)', controle = 'Le compte 39 – DEPRECIATIONS DES STOCKS peut être contrôlé à partir de l''inventaire extra-comptable et par
évaluation, notamment.
COMPTES DE TIERS
Les comptes de la classe 4 retracent les relations de l''entité avec les tiers. Ils servent donc à comptabiliser les dettes et
les créances de l''entité à l''exclusion de celles inscrites respectivement dans les comptes de ressources stables et les
comptes d''actif immobilisé.
Figurent également dans la classe 4 les comptes de régularisation qui sont utilisés pour répartir les charges et les
produits dans le temps, de manière à rattacher à un exercice déterminé toutes les charges et tous les produits qui le
concernent effectivement.
Les abandons de créances consentis en faveur de l''entité sont enregistrés au compte 846 Abandons de créances
obtenus pour le montant hors TVA si l''abandon est passible de TVA. L''entité qui abandonne la créance enregistre la
perte dans ses livres au compte 836 Abandons de créances consentis.
Les abandons de créances ne peuvent être assimilés en aucun cas comme un élément du prix d''achat ou de cession
des titres de participation dans le cadre d''un abandon de créances dans le cadre d''une prise de contrôle ou de
séparation d''une société mère et de sa filiale.
Si l''abandon de créance est consenti avec une clause de retour à meilleure fortune.
Cette clause doit être mentionnée dans les engagements hors bilan. Dès que le retour à meilleure fortune est effectif, la
créance est reconstituée par le biais des comptes
A la clôture de chaque exercice une entité doit apprécier, s''il existe un quelconque indice qu''une créance a subi une
perte de valeur. S''il existe un tel indice, l''entité doit estimer la valeur économique réelle et la comparer avec la valeur
nette comptable.
La créance doit être dépréciée lorsque la valeur nette comptable est supérieure à la valeur économique réelle.
COMPTES DE LA CLASSE 4
40    Fournisseurs et comptes rattachés                 67   45     Organismes internationaux                                79
41    Clients et comptes rattachés                      70   46     Apporteurs, Associés et Groupe                           80
42    Personnel                                         73   47     Débiteurs et créditeurs divers                           82
43    Organismes sociaux                                76   48     Créances et dettes hors activités ordinaires             84
44    Etat et Collectivités publiques                   77   49     Dépréciations et provisions pour risques à court         86
terme (Tiers)' WHERE numero = '39';
UPDATE comptes_ohada SET contenu = 'Les fournisseurs d''exploitation sont des tiers auxquels l''entité a recours pour ses achats de fournitures de toutes natures et
de services.', commentaires = 'Figurent à ce compte les dettes et avances liées à l''acquisition de biens ou de services.
Les dettes d''exploitation se caractérisent par le rattachement à ce compte de tiers de toutes les opérations le concernant :
effets à payer, factures à recevoir à la clôture de l''exercice, les intérêts courus à la clôture de l''exercice, les avances et
acomptes versés, les retenues de garantie.
Si un fournisseur d''exploitation a, en outre, avec l''entité d''autres relations (de client par exemple), seules les opérations
relatives aux achats (factures, avoirs, règlements, rabais, escomptes, etc.) doivent figurer dans le compte "Fournisseurs",
les autres opérations étant imputées aux comptes particuliers qu''elles concernent.
Si un tiers, fournisseur d''exploitation, a, en outre, avec l''entité des relations de fournisseur d''investissements, ces dernières
opérations doivent être imputées au compte qu''elles concernent (481 – Fournisseurs d''investissements).
Les fournisseurs sont classés selon différents critères qui peuvent servir de base à la codification des sous-comptes : 1 –
Responsabilité de l''exécution :
• fournisseurs livrant à l''entité des objets, matières ou fournitures dont ils sont entièrement responsables (conception,
matières, fabrication) ;
• sous-traitants, tiers auxquels l''entité a recours pour exécuter, sur ses ordres et en son nom, des travaux ou services qui
lui ont été confiés par ses propres clients.
Si le sous-traitant travaille sur des objets ou des matières premières qui lui sont fournis par l''entreprise (sous réserve de
l''utilisation de matières accessoires nécessitées par son travail), il est dénommé façonnier et n''est responsable que de la
bonne exécution de son travail.
2 – Relations entre le fournisseur et l''entité
Fournisseurs membres du groupe (sociétés apparentées) et autres fournisseurs.
3 – Nature de la dette
Il conviendra de séparer dans des comptes distincts :
• les retenues de garanties effectuées sur le prix convenu ;
• les avances et acomptes versés sur commandes d''exploitation ou réglés aux sous-traitants ;
• les factures à recevoir dont le montant est définitivement arrêté, mais dont les pièces justificatives ne sont pas encore
parvenues à l''entité (si le montant ne peut être qu''estimé à la date de clôture des écritures, utiliser le compte 408 –
Fournisseurs, factures non parvenues en contrepartie des comptes de la classe 6 et du compte 4455 si la TVA est
récupérable). A l''ouverture de l''exercice ces écritures sont contre- passées pour permettre un meilleur contrôle et
analyse des flux ou soldées par le compte fournisseur à la réception de la facture ;
• le compte 4094 emballages et matériels à rendre, reçoit à son débit, par le crédit du fournisseur consignataire, les
sommes facturées à titre de consignation d''emballages ou de matériels.
Ce compte, emballages et matériels à rendre est soldé par :
• le compte du fournisseur au retour de l''emballage ;
• par le débit du compte (6082)
Achats d''emballages récupérables ou du compte (24) matériel, mobilier et actifs biologiques en cas de conservation de
l''emballage ou du matériel ;
• par le débit du compte fournisseur et du compte (6224) malis sur emballages en cas de reprise pour un montant
inférieur à celui de la consignation.
4 – Identité du fournisseur
Selon le classement adopté par l''entité, en principe, il est tenu un sous-compte individuel pour chaque fournisseur, en vue
d''alimenter directement le fichier fournisseurs.
5 – Nature de l''agent fournisseur
Selon la nomenclature des agents économiques proposée dans le Système
Comptable OHADA et le code d''activité imparti à chaque fournisseur.
6 – Répartition géographique des fournisseurs dans les Etats de la Région et hors Région
Les entités ventilent, en tant que de besoin, leurs opérations selon qu''elles sont faites :
• dans l''Etat-partie ;
• dans les autres Etats de la Région ;
• hors Région.
Dans la situation patrimoniale, aucune compensation ne pourrait s''effectuer entre les comptes fournisseurs à solde débiteur
et les comptes fournisseurs à solde créditeur.
Les premiers figurent à l''actif du bilan et les seconds au passif du bilan. C''est ainsi que les avances et acomptes versés sur
commandes d''exploitation, subsistant à la clôture de l''exercice, figurent en clair à l''actif du bilan.', fonctionnement = 'Le compte 40 – FOURNISSEURS ET COMPTES RATTACHES est crédité du montant des factures d''achats de biens
ou de prestations de services des fournisseurs ou des sous- traitants
• par le débit : des comptes concernés de la classe 6 pour le montant hors taxes récupérables ou, le cas échéant,
de la classe 3 (inventaire permanent)
• par le débit : du compte 4094 – Fournisseurs, créances pour emballages et matériels à rendre
• par le débit : du compte 445 – Etat, T.V.A. récupérable
Le compte 40 – FOURNISSEURS ET COMPTES RATTACHES est débité des avances et acomptes versés aux
fournisseurs ainsi que des règlements effectués sur factures
• par le crédit des comptes de trésorerie ou d''effets à payer ;
• par le crédit : du compte 4094 – Fournisseurs, créances pour emballages et matériels à rendre .
Le compte 40 – FOURNISSEURS ET COMPTES RATTACHES est débité pour le montant des factures d''avoir reçues
pour retour des marchandises au fournisseur
• par le crédit des comptes de la classe 6 et des autres comptes ayant joué lors de l''enregistrement initial des
achats de biens et de services, objets du retour.
Le compte 40 – FOURNISSEURS ET COMPTES RATTACHES est débité des rabais, remises et ristournes sur achats
obtenus hors factures
• par le crédit des comptes de la classe 6 concernés.
Le compte 40 – FOURNISSEURS ET COMPTES RATTACHES est débité des escomptes de règlement obtenus des
fournisseurs
• par le crédit du compte 773 – Escomptes obtenus.', exclusions = 'Le compte 40 – FOURNISSEURS ET COMPTES RATTACHES ne doit pas servir à enregistrer :
• les fournisseurs d''immobilisations
• les avances et acomptes versés sur commande d''immobilisations
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 481 – Fournisseurs d''investissements
• 25 – Avances et acomptes versés sur immobilisations', controle = 'Le compte 40 – FOURNISSEURS ET COMPTES RATTACHES peut être contrôlé à partir des factures, chèques de
règlement, effets ...' WHERE numero = '40';
UPDATE comptes_ohada SET contenu = 'Les clients d''exploitation sont des tiers auxquels l''entité vend les biens ou services, objet de son activité.', commentaires = 'Figurent à ce compte les créances liées à la vente de biens et de services rattachés au cycle d''exploitation de l''entité. Les
créances d''exploitation se caractérisent par le rattachement à ce compte de tiers de toutes les opérations le concernant :
effets à recevoir concernant ces clients, les créances à venir se rapportant à l''exploitation de l''exercice (factures clients non
encore établies), les créances sur cession d''actifs, les effets escomptés non échus, les créances litigieuses ou douteuses,
les intérêts courus à la clôture de l''exercice, les avances et acomptes obtenus, les retenues de garantie dans les comptes
rattachés.
Les clients sont les tiers auxquels l''entité vend les biens ou services, objets de son activité.
Si un tiers a, en outre, avec l''entité d''autres relations (de fournisseur ou de salarié, par exemple), seules les opérations
relatives aux ventes (factures, avoirs, règlements, rabais, escomptes, etc.) doivent figurer dans le compte "Client", les
autres opérations étant enregistrées aux comptes particuliers qu''elles concernent (fournisseurs, personnel, etc.).
Les clients sont classés selon les différents critères dont l''ordre de priorité est déterminé par le degré d''utilité qu''ils
présentent pour les parties intéressées et en fonction des moyens de l''entité.
Si un tiers, client d''exploitation, a, en outre, avec l''entité d''autres relations (de fournisseur, par exemple), seules les
opérations relatives aux ventes (factures, avoirs, règlements, rabais, escomptes, etc.) doivent figurer dans le compte
"Clients", les autres opérations étant imputées aux comptes particuliers qu''elles concernent.
Si un tiers, client d''exploitation, a, en outre, avec l''entité des relations de client d''investissements, ces dernières opérations
doivent être imputées au compte qu''elles concernent (485 – Créances sur cessions d''immobilisations).
Le factoring ou l''affacturage est une opération qui consiste en un transfert de créances commerciales de leur titulaire à un
factor, qui se charge d''en opérer le recouvrement et qui en garantit la bonne fin même en cas de défaillance momentanée
ou permanente du débiteur (voir titre VIII Opérations spécifiques, chapitre 15).
Les clients sont classés selon différents critères qui peuvent servir de base à la codification des sous-comptes : 1 –
Répartition géographique des clients
Les entités ventilent, en tant que de besoin, leurs opérations selon qu''elles sont réalisées :
• dans l''Etat-partie;
• dans les autres Etats de la Région ;
• hors Région ;
• sur internet. 2 – Nature du client
Entité, particulier, Etat, collectivité publique, institutions financières, selon la nomenclature des agents économiques retenue
dans le Système Comptable OHADA.
3 – Relations entre le client et l''entité
Client membre du groupe (sociétés apparentées) et autres clients.
4 – Nature de la créance
On séparera dans des comptes distincts :
• les avances et acomptes reçus sur commandes en cours ;
• les factures à établir dont le montant est définitivement arrêté, mais qui ne sont pas encore expédiées par l''entité (si le
montant ne peut qu''être estimé à la date de clôture de la période, on utilisera le compte 418 – Clients, produits à
recevoir en contrepartie des comptes de la classe 7 et du compte 4435 si le bien entre dans le champ d''application de
la TVA). A l''ouverture de l''exercice ces écritures sont contre-passées pour permettre un meilleur contrôle et analyse des
flux ou soldées par le compte client à l''émission de la facture.
• les clients qui contestent leurs dettes (créances litigieuses) ou se dérobent à leur paiement (créances douteuses) ;
• le compte 4194 emballages et matériels consignés, reçoit à son crédit, par le débit du client consignataire, les sommes
facturées par l''entité à titre de consignation d''emballages ou de matériels.
Ce compte, emballages et matériels consignés est soldé par
• le crédit du compte client à la restitution de l''emballage ou du matériel ;
• le crédit du compte (7074) bonis sur cession d''emballages, s''il s''agit d''un emballage, ou du compte (82) produits des
cessions d''immobilisations, s''il s''agit d''un matériel, en cas de conservation de l''emballage ou du matériel par le client ;
• le crédit du (compte 7074) bonis sur reprises d''emballage en cas de reprise pour un prix inférieur à celui de la
consignation.
• les effets à recevoir en portefeuille qui seront transférés en cas de remise à l''escompte dans un sous – compte distinct
(compte 415 – Clients, effets escomptés non échus).
5 – Identité du client
Selon le classement adopté par l''entité, en principe, il est tenu un sous-compte individuel par client en vue d''alimenter
directement le fichier clients.
6 – Nature du produit ou du service vendu
Selon la nomenclature de biens et services en usage dans chacun des Etats-parties.
Dans la situation patrimoniale, aucune compensation ne doit être établie entre les comptes clients à solde débiteur et les
comptes clients à solde créditeur. Les premiers figurent à l''actif du bilan et les seconds au passif du bilan. C''est ainsi que
les avances et acomptes reçus sur commandes en cours, subsistant à la clôture de l''exercice, figurent en clair au passif du
bilan.', fonctionnement = 'Le compte 41 – CLIENTS ET COMPTES RATTACHES est débité du montant des factures de ventes de biens ou de
prestations de services
• par le crédit des comptes concernés de la classe 7 (montant hors taxes récupérables)
• par le crédit du compte 4194 – clients, dettes pour emballages et matériels consignés
• par le crédit du compte 443 – Etat TVA facturée
Le compte 41 – CLIENTS ET COMPTES RATTACHES est crédité des avances et acomptes ainsi que des règlements
reçus des clients
• par le débit des comptes de trésorerie ou effets à recevoir
• par le débit du compte 4194 – Clients, dettes pour emballages et matériels consignés .
Le compte 41 – CLIENTS ET COMPTES RATTACHES est crédité pour le montant des factures d''avoir émises pour
retour de marchandises
• par le débit des comptes de la classe 7 et des autres comptes ayant joué lors de l''enregistrement initial des ventes
de biens et de services.
Le compte 41 – CLIENTS ET COMPTES RATTACHES est crédité des rabais, ristournes et remises accordés sur
ventes hors factures
• par le débit des comptes 70 – Ventes.
Le compte 41 – CLIENTS ET COMPTES RATTACHES est crédité des créances litigieuses ou douteuses
• par le débit du compte 416 – Créances clients litigieuses ou douteuses.
Le compte 41 – CLIENTS ET COMPTES RATTACHES est crédité des escomptes de règlement accordés aux clients
• par le débit du compte 673 – Escomptes accordés.', exclusions = 'Le compte 41 – CLIENTS ET COMPTES RATTACHES ne doit pas servir à enregistrer :
• les créances sur des tiers nées des opérations autres que la vente des marchandises, biens ou services
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 485 – Créances sur cessions d''immobilisations', controle = 'Le compte 41 – CLIENTS ET COMPTES RATTACHES peut être contrôlé à partir des factures, chèques de règlement,
effets, impayés, relances clients, dossiers contentieux.' WHERE numero = '41';
UPDATE comptes_ohada SET contenu = 'Le compte Personnel enregistre l''ensemble des opérations qui interviennent entre l''entité et les personnes qui lui sont liées
par un contrat de travail. Par extension, les opérations qui concernent les représentants du personnel ou les organismes
similaires lui sont rattachées.
Le personnel de l''entité comprend :
• le personnel de direction et d''encadrement, les employés, les ouvriers et les occasionnels indépendamment de leur
situation ou de leurs fonctions ;
• les représentants salariés ;
• les associés et les dirigeants de société qui exercent des fonctions techniques ;
• les membres de la famille de l''exploitant exerçant un emploi salarié.', commentaires = 'Les opérations traitées par ce compte concernent, d''une part, les rémunérations dues au personnel, les avances et
acomptes consentis au personnel et, d''autre part, les versements effectués aux œuvres sociales internes et la fraction du
salaire soumise à saisie, en cas d''opposition de tiers.
Le compte 426 Personnel participation aux bénéfices et au capital enregistre
• les sommes attribués au personnel après la date d''approbation des comptes par l''assemblée générale ordinaire des
actionnaires ou des associés ;
• l''attribution gratuite d''actions au personnel salarié et aux dirigeants par prélèvement obligatoire sur la part des bénéfices
ou par rachat par la société de ses propres actions (voir titre VIII Opérations spécifiques, Chapitre 19).
Si l''attribution gratuite des actions au personnel salarié et aux dirigeants est réalisée par un prélèvement sur la part des
réserves. Le compte de réserves (compte 1132) sera débité par le crédit du compte de capital (compte 1013). Les actions
attribuées au personnel salarié seront mentionnées sur le registre des titres nominatifs des actions.
A la clôture de l''exercice, il ne doit pas être effectué de compensation entre les sommes dues au personnel et les montants
qui seraient éventuellement dus par le personnel et qui n''auraient pas été retenus sur la dernière paie de l''exercice.', fonctionnement = 'Le compte 42 – PERSONNEL est crédité des rémunérations brutes à payer au personnel (ou au comité d'' entreprise)
• par le débit des comptes de charges intéressés 66 – Charges de personnel.
Le compte 42 – PERSONNEL est débité du montant des avances et acomptes faits au personnel (ou au comité
d''entreprise) ainsi que des rémunérations versées au personnel
• par le crédit des comptes de trésorerie.
Le compte 42 – PERSONNEL est débité des sommes dues par le personnel
• par le crédit des comptes de produits (services exploités dans l''intérêt du personnel, etc.).
Le compte 42 – PERSONNEL est débité des versements effectués aux organismes sociaux pour le compte du
personnel (cotisations salariales)
• par le crédit du compte 43 – Organismes sociaux.
Le compte 42 – PERSONNEL est débité, en cas d''opposition de tiers sur salaires, du versement de la fraction de
salaire soumise à saisie
• par le crédit des comptes de trésorerie.', exclusions = 'Le compte 42 – PERSONNEL ne doit pas servir à enregistrer :
• les prêts consentis au personnel
• les opérations en comptes courants des associés et administrateurs pour les mouvements de fonds n''intéressant
pas la rémunération de leur travail
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 272 – Prêts au personnel
• 46 – Apporteurs, Associés et Groupe', controle = 'Le compte 42 – PERSONNEL peut être contrôlé à partir :
• des fiches de paie ;
• des déclarations sociales ;
• des contrats de prêts ;
• des procès-verbaux de saisie-arrêt ;
• des avis à tiers détenteur.' WHERE numero = '42';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre, d''une part, le montant des cotisations sociales salariales et patronales dues aux organismes sociaux
et, d''autre part, les règlements de cotisations effectués à leur profit.', commentaires = 'Les obligations de l''entité vis-à-vis des organismes sociaux sont remplies à partir des procédures comptables définies dans
le
Système Comptable OHADA.', fonctionnement = 'Le compte 43 – ORGANISMES SOCIAUX est crédité du montant des cotisations sociales, salariales et patronales
dues aux organismes sociaux
• par le débit du compte 664 – Charges sociales, pour la part patronale ;
• et par le débit du compte 422 – Personnel, rémunérations dues, pour la part salariale.
Le compte 43 – ORGANISMES SOCIAUX est débité des règlements de cotisations effectués aux organismes sociaux
• par le crédit des comptes de trésorerie concernés.', exclusions = 'Le compte 43 – ORGANISMES SOCIAUX ne doit pas servir à enregistrer :
• les opérations faites avec les organismes sociaux en tant que clients
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 41 – Clients et comptes rattachés', controle = 'Le compte 43 – ORGANISMES SOCIAUX peut être contrôlé à partir :
• des fiches de paie ;
• des bordereaux de déclarations sociales ;
• des livres de paie.' WHERE numero = '43';
UPDATE comptes_ohada SET contenu = 'Les opérations à inscrire à ce compte concernent d''une manière générale les opérations qui sont faites avec l''Etat et avec
les diverses collectivités publiques en tant que pouvoirs publics.', commentaires = 'Les opérations d''achats et de ventes de biens ou de services avec l''Etat et les collectivités publiques s''inscrivent aux
comptes 40 – Fournisseurs et comptes rattachés et 41 – Clients et comptes rattachés, au même titre que les opérations
faites avec les autres fournisseurs et les autres clients.
Les dettes du compte 442 – Etat, autres impôts et taxes comprennent non seulement les impôts et taxes d''Etat proprement
dits tels que droits de douane à l''exportation, mais, aussi, les impôts et taxes perçus pour le compte des collectivités
locales.', fonctionnement = 'Le compte 44 – ETAT ET COLLECTIVITÉS PUBLIQUES est crédité lors de la constatation par l''entité des dettes
d''impôts dont elle est redevable envers l''Etat
• par le débit des comptes de charges intéressés.
Le compte 44 – ETAT ET COLLECTIVITÉS PUBLIQUES est crédité lors du règlement par l''Etat des sommes dues à
l''entité
• par le débit des comptes de trésorerie.
Le compte 44 – ETAT ET COLLECTIVITÉS PUBLIQUES est débité des sommes versées lors du règlement par l''entité
à l''Etat
• par le crédit des comptes de trésorerie concernés.
Le compte 44 – ETAT ET COLLECTIVITÉS PUBLIQUES est débité lors de la constatation de la dette de l''Etat envers
l''entité (fonds de dotation, subventions, etc.)
• par le crédit des comptes concernés des classes 1 et 4 ou des classes 7 et 8, selon la qualification des fonds
alloués.', exclusions = 'Le compte 44 – ETAT ET COLLECTIVITÉS PUBLIQUES ne doit pas servir à enregistrer :
• les opérations faites avec l''Etat en tant que fournisseur
• les opérations faites avec l''Etat en tant que client
• les droits de douane acquittés à l''entrée des biens sur le territoire national faisant partie du prix d''achat du bien
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 40 – Fournisseurs et comptes rattachés
• 41 – Clients et comptes rattachés
• comptes de la classe 2 ou 6 concernés', controle = 'Le compte 44 – ETAT ET COLLECTIVITÉS PUBLIQUES peut être contrôlé à partir :
• des avis d''imposition ;
• des déclarations fiscales ;
• des relevés bancaires.' WHERE numero = '44';
UPDATE comptes_ohada SET contenu = 'Les opérations à inscrire à ce compte concernent les dettes et créances autres que celles liées à l''activité de l''entité.
Elles concernent exclusivement le montant des dépenses dont l''entité doit assumer la charge, les dettes des organismes
internationaux vis-à-vis de l''entité et, d''autre part, les dettes de l''entité vis-à-vis des organismes internationaux et le
règlement par ces derniers des sommes dues à l''entité.', commentaires = NULL, fonctionnement = 'Le compte 45 – ORGANISMES INTERNATIONAUX est crédité lors de la constatation par l''entité des dettes dont elle
est redevable envers les organismes internationaux
• par le débit des comptes de charges concernés.
ou d''immobilisations
Le compte 45 – ORGANISMES INTERNATIONAUX est crédité lors du règlement par les organismes internationaux
de sommes dues à l''entité
• par le débit des comptes de trésorerie.
Le compte 45 – ORGANISMES INTERNATIONAUX est débité des dépenses dont l''entité doit assumer la charge
• par le crédit des comptes de trésorerie concernés lors du règlement par l''entité aux organismes internationaux.
Le compte 45 – ORGANISMES INTERNATIONAUX est débité lors de la constatation de la dette des organismes
internationaux envers l''entité (fonds de dotation, subventions, etc.)
• par le crédit des comptes concernés des classes 1 et 4 ou des classes 7 et 8, selon la qualification des fonds
alloués.', exclusions = 'Le compte 45 – ORGANISMES INTERNATIONAUX ne doit pas servir à enregistrer :
• les opérations faites avec les organismes internationaux en tant que fournisseurs
• les opérations faites avec les organismes internationaux en tant que clients
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 40 – Fournisseurs et comptes rattachés
• 41 – Clients et comptes rattachés', controle = 'Le compte 45 – ORGANISMES INTERNATIONAUX peut être contrôlé à partir :
• des relevés bancaires ;
• des avis de versement ;
• des avis d''octroi de subventions.' WHERE numero = '45';
UPDATE comptes_ohada SET contenu = 'Le compte 46 enregistre :
• d''une part les créances/dettes envers les associés résultant des divers mouvements du capital social ;
• d''autre part les créances/dettes temporaires en comptes courants.
En ce qui concerne ces derniers, le plan de comptes distingue les associés ordinaires et, dans le cas d''appartenance à un
groupe, les autres sociétés du groupe', commentaires = 'Sont réputés associés les membres des sociétés de capitaux, des sociétés de personnes, des sociétés de fait et des
sociétés en participation. La SARL a la possibilité de libérer le capital sur une durée de deux années à compter de son
immatriculation au registre du commerce depuis la révision de l''Acte uniforme relatif au droit des sociétés commerciales et
GIE.
Il y a lieu de comptabiliser dans des sous- comptes particuliers les opérations suivantes concernant le capital :
• la dette contractée par les associés, lors de la souscription du capital ;
• le règlement de cette dette par les associés ;
• le restant dû sur le capital appelé ;
• les fonds laissés ou mis temporairement à la disposition de l''entité par les associés ;
• les versements reçus par la société sur augmentation de capital ;
• les sommes dues et réglées par la société au titre des dividendes.
Les mouvements du compte 461 – Apporteurs, opérations sur le capital concernent tous les associés, y compris les autres
sociétés du groupe, associées de l''entité.
Les créances/dettes envers les sociétés du groupe, autres que celles enregistrées dans le compte 466 – Groupe, comptes
courants, sont comptabilisées : – dans les comptes de tiers 40 – Fournisseurs et 41 – Clients en ce qui concerne les
opérations commerciales ; – dans les comptes 16, 18 et 27 en ce qui concerne les opérations financières.', fonctionnement = 'Le compte 46 – APPORTEURS, ASSOCIES ET GROUPE est crédité des sommes dues à titre de dividendes
• par le débit des comptes Résultat, Réserves, Report à nouveau.
Le compte 46 – APPORTEURS ASSOCIES ET GROUPE est crédité des fonds mis ou laissés temporairement à la
disposition de la société
• par le débit des comptes de trésorerie (ou de charges, s''il s''agit de frais réglés pour le compte de l''entité).
Le compte 46 – APPORTEURS ASSOCIES ET GROUPE est débité des sommes réglées au titre des dividendes
• par le crédit des comptes de trésorerie (ou des comptes courants).
Le compte 46 – APPORTEURS ASSOCIES ET GROUPE est débité des fonds prélevés par les associés ou des
règlements effectués pour leur compte par l''entité
• par le crédit des comptes de trésorerie (ou des comptes de charges).', exclusions = 'Le compte 46 – APPORTEURS ASSOCIES ET GROUPE ne doit pas servir à enregistrer :
• les dettes et créances des associés contractées ou consenties
• les emprunts et les prêts des associés
• la dette des associés, représentative du capital souscrit non appelé
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 16 – Emprunts et dettes assimilées
• 27 – Autres immobilisations financières
• 109 – Apporteurs, capital souscrit, non appelé', controle = 'Le compte 46 – APPORTEURS, ASSOCIES ET GROUPE peut être contrôlé à partir des décisions des
Assemblées d''actionnaires.' WHERE numero = '46';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre les opérations en instance de régularisation et relatives aux créances et dettes liées à l''acquisition de
titres, des charges non consommées, des produits constatés d''avance, des écarts sur opérations libellées en monnaies
étrangères, et des créances sur travaux non encore facturables.', commentaires = 'Aucune compensation n''est en principe admise entre les dettes et les créances dont les soldes créditeurs et débiteurs
doivent être inscrits au bilan dans les rubriques
Autres créances à l''actif et Autres dettes au passif.
Lors de l''acquisition de titres de placement non libérés entièrement par l''entité, la valeur globale est portée au compte d''actif
immobilisé concerné, y compris les montants non encore appelés. Ces derniers figurent au compte 472 – Versements
restant à effectuer sur titres de placement non encore libérés.
Le compte 474 répartition périodique des charges et des produits est traité au Titre VIII Opérations spécifiques, Chapitre 24.
Le compte 475 compte transitoire, ajustement spécial lié à la révision du SYSCOHADA ne doit servir à enregistrer que les
comptes d''actifs et du passif supprimés ou traités autrement par le SYSCOHADA révisé.
Les comptes 478 – Ecarts de conversion – Actif et 479 – Ecarts de conversion – Passif (prévus pour l''enregistrement des
pertes et gains latents) permettent de constater, à la clôture de l''exercice, les écarts entre créances et dettes en devises
converties en unités monétaires légales du pays, telles qu''elles figurent en comptabilité, et leur évaluation en unités
monétaires légales du pays à la date de clôture de l''exercice.', fonctionnement = 'Le compte 47 – DEBITEURS ET CREDITEURS DIVERS est crédité des dettes contractées ou des remboursements
de créances
• par le débit des comptes de trésorerie concernés.
Le compte 47 – DEBITEURS ET CREDITEURS DIVERS est crédité des apports effectués par l''exploitant en cours
d''exercice
• par le débit des comptes de trésorerie concernés.
Le compte 47 – DEBITEURS ET CREDITEURS DIVERS est crédité des virements restant à effectuer sur des titres
non encore totalement libérés
• par le débit du compte 26 – Titres de participation ; ou du compte 274 – Titres immobilisés ; ou du compte 50 –
Titres de placement.
Le compte 47 – DEBITEURS ET CREDITEURS DIVERS est crédité, à la clôture de l''exercice, des produits perçus
pendant l''exercice et se rattachant à l''exercice à venir (produits constatés d''avance)
• par le débit des comptes de produits concernés.
Le compte 47 – DEBITEURS ET CREDITEURS DIVERS est débité des créances sur les tiers ou du remboursement
des dettes contractées
• par le crédit d''un compte de tiers ou des comptes de trésorerie concernés.
Le compte 47 – DEBITEURS ET CREDITEURS DIVERS est débité des retraits effectués par l''exploitant en cours
d''exercice
• par le crédit d''un compte de trésorerie.
Le compte 47 – DEBITEURS ET CREDITEURS DIVERS est débité, à la clôture de l''exercice, des charges payées
pendant l''exercice et se rattachant à l''exercice à venir (charges payées d''avance)
• par le crédit des comptes de charges concernés.', exclusions = 'Le compte 47 – DEBITEURS ET CREDITEURS DIVERS ne doit pas servir à enregistrer :
• les charges imputables au compte
Fournisseurs
• les produits imputables au compte Clients
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 40 – Fournisseurs et comptes rattachés
• 41 – Clients et comptes rattachés', controle = 'Le compte 47 – DEBITEURS ET CREDITEURS DIVERS peut être contrôlé à partir :
• des contrats ;
• des conventions ;
• des décomptes de régularisation ;
• des chèques ;
• des relevés de banque.' WHERE numero = '47';
UPDATE comptes_ohada SET contenu = 'Ce sont des créances et des dettes consécutives à des opérations effectuées par l''entité mais n''ayant pas de lien direct
avec l''activité ordinaire de l''entité.', commentaires = 'La mise en évidence au bilan des créances et des dettes hors activités ordinaires par l''intermédiaire du compte 48 permet
de mesurer directement le besoin ou la ressource de financement H.A.O., en parallèle avec le besoin ou la ressource de
financement de l''exploitation.
Les créances sur cessions d''immobilisations sont considérées comme
H.A.O. dans tous les cas où elles concernent des opérations n''entrant pas dans l''activité normale et courante de l''entité.
Dans le cas contraire, elles constituent des créances rattachées au compte Client (compte 414) et sont débitées par le
crédit du compte 754 – Produits des cessions courantes d''immobilisations.', fonctionnement = 'Le compte 48 – CREANCES ET DETTES HORS ACTIVITES ORDINAIRES est crédité des dettes H.A.O.
contractées ou des remboursements de créances H.A.O.
• par le débit des comptes de trésorerie concernés ou des comptes de la classe 8.
Le compte 48 – CREANCES ET DETTES HORS ACTIVITES ORDINAIRES est débité des créances H.A.O. sur les
tiers ou des remboursements de dettes H.A.O.
• par le crédit des comptes de trésorerie concernés ou des comptes de la classe 8.', exclusions = 'Le compte 48 – CREANCES ET DETTES HORS ACTIVITES ORDINAIRES ne doit pas servir à enregistrer les dettes
ou les créances ayant pour origine les activités ordinaires de l''entité
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 40 – Fournisseurs et comptes rattachés
• 41 – Clients et comptes rattachés', controle = 'Le compte 48 – CREANCES ET DETTES HORS ACTIVITES ORDINAIRES peut être contrôlé à partir :
• des chèques ;
• des effets de commerce ;
• des contrats d''acquisition d''immobilisations ;
• des factures ;
• des ordres de mouvements en Bourse.' WHERE numero = '48';
UPDATE comptes_ohada SET contenu = 'Ce sont des dépréciations subies par des comptes de tiers résultant de causes diverses dont les effets ne sont pas jugés
irréversibles.', commentaires = 'Les dépréciations des comptes de tiers obéissent aux mêmes règles de comptabilisation que les dépréciations constatées
sur les stocks et les comptes de trésorerie.
La dépréciation doit être certaine quant à sa nature et l''élément d''actif en cause doit être individualisé. En l''occurrence, les
entités désireuses de constituer des dépréciations doivent être en mesure :
• de préciser exactement la nature et l''objet des créances à déprécier ;
• de justifier les motifs qui rendent les créances douteuses et litigieuses.
La dotation est à constituer même si la dépréciation est d''un montant incertain.
La dépréciation traduit une baisse non définitive et non irréversible de l''évaluation des éléments d''actif par rapport à leur
valeur comptable.
Les événements générateurs de dépréciations survenus après la clôture de l''exercice ne sont pas pris en compte dans ledit
exercice ; les dotations ne doivent être constituées que pour des dépréciations subies au cours de l''exercice, et à la clôture
de l''exercice.
La dépréciation doit être constituée même en l''absence ou en cas d''insuffisance de bénéfices, conformément au principe de
prudence.
Lorsque, au jour de l''inventaire, la valeur économique réelle des créances est inférieure à leur valeur comptable déterminée
conformément aux dispositions précédemment exposées, les entités doivent constituer des dépréciations qui expriment les
moins-values constatées sur ces comptes de tiers.
Ces dépréciations sont portées à l''actif du bilan, en déduction de la valeur des postes qu''elles concernent, sous la forme
prévue par le modèle de bilan.
Les provisions pour risques à court terme sont liées au mécanisme des charges et représentent une dette probable à moins
d''un an.
Les dépréciations et les provisions pour risques à court terme correspondent à des charges d''exploitation ou H.A.O. selon
leur nature.', fonctionnement = 'Le compte 49 – DEPRECIATIONS ET PROVISIONS POUR RISQUES A COURT TERME (TIERS) est crédité à la
clôture de l''exercice des dépréciations constatées sur les éléments d''actif de la classe 4 (comptes 41 à 48) ou des
provisions pour risques à court terme, compte 499
• par le débit du compte 659 – Charges pour dépréciations et provisions pour risques à court terme d''exploitation
• ou par le débit du compte 839 – Charges pour dépréciations et provisions pour risques à court terme H.A.O.
Le compte 49 – DEPRECIATIONS ET PROVISIONS POUR RISQUES A COURT TERME (TIERS) est débité à la
clôture de l''exercice de la reprise des dépréciations constatées à la clôture d''un exercice antérieur sur les éléments
d''actif de la classe 4 (comptes 41 à 48) ou des provisions pour risques à court terme (compte 499) dont les raisons qui
les ont motivées ont cessé d''exister
• par le crédit du compte 759 – Reprises de charges pour dépréciations et de provisions pour risques à court terme
d''exploitation ;
• ou par le crédit du compte 849 – Reprises de charges pour dépréciations et provisions pour risques à court terme
charges
H.A.O.', exclusions = 'Le compte 49 – DEPRECIATIONS ET PROVISIONS POUR RISQUES À COURT TERME (TIERS) ne doit pas servir à
enregistrer :
• les provisions pour risques et charges à plus d''un an
• les dépréciations des éléments (classe 2) de l''actif immobilisé
• les dépréciations des comptes de trésorerie (classe 5)
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• compte 19 – Provisions financières pour risques et charges
• compte 29 – Dépréciations
• compte 59 – Dépréciations et provisions pour risques à court terme (Trésorerie)', controle = 'Le compte 49 – DEPRECIATIONS ET PROVISIONS POUR RISQUES A COURT TERME (TIERS) peut être contrôlé
à partir de tous documents à même de justifier les motifs qui rendent la créance douteuse ou litigieuse (courriers et
autres protêts, justificatifs du caractère douteux ou litigieux de la créance).
COMPTES DE TRÉSORERIE
Les comptes de la classe 5 enregistrent les opérations relatives aux valeurs en espèces, aux chèques, aux monnaies
électroniques aux effets de commerce, aux titres de placement, aux coupons ainsi qu''aux opérations faites avec les
établissements de crédit.
Aucune compensation ne doit être effectuée au bilan entre les soldes débiteurs et les soldes créditeurs des comptes de
la classe 5.
Les comptes de la classe 5 peuvent être assortis de comptes de provisions pour dépréciation, notamment les
provisions pour dépréciation des titres de placement ; ces dernières provisions doivent résulter de l''évaluation
comptable des moins-values constatées sur les éléments d''actif considérés.
COMPTES DE LA CLASSE 5
50    Titres de placement                                   89    55   Instruments de monnaie électronique                      97
51    Valeurs à encaisser                                   91    56   Banques, crédits de trésorerie et d''escompte             98
52    Banques                                               92    57   Caisse                                                  100
53    Etablissements financiers et assimilés                93    58   Régies d''avances, accréditifs et virements internes     101
54    Instruments de trésorerie                             95    59   Dépréciations et provisions pour risques à court        103
terme (Trésorerie)' WHERE numero = '49';
UPDATE comptes_ohada SET contenu = 'Ce sont des titres cessibles, acquis en vue d''en retirer un revenu direct ou une plus-value à brève échéance.', commentaires = 'Les titres de placement comprennent les actions et parts sociales, les obligations et les bons aisément négociables sur un
marché réglementé.
Représentatifs de créances souscrites, ils sont réalisables immédiatement, en cas de nécessité. Productifs d''intérêts, ils
constituent des placements financiers.
A leur entrée les titres de placement sont comptabilisés au prix d''achat, frais d''achat inclus. Les frais d''acquisition peuvent
être enregistrés dans un sous compte spécifique; à l''inventaire, ils sont évalués au cours en Bourse, ou, pour les titres non
cotés, à leur valeur probable de négociation. Cette valeur est comparée à la valeur d''achat, frais d''acquisition inclus pour
apprécier la perte de valeur éventuelle ou la plus value générée.
La sortie des titres du patrimoine se fait selon la méthode du premier bien entré est le premier bien sorti (P.E.P.S.). En cas
de cession, la différence entre le prix de cession et la valeur d''entrée des titres est enregistrée, selon le cas : – au débit du
compte 6771 – Pertes sur cessions de titres de placement ; – au crédit du compte 777 – Gains sur cessions de titres de
placement.', fonctionnement = 'Le compte 50 – TITRES DE PLACEMENT est débité de la valeur d''apport ou d''acquisition des titres
• par le crédit des comptes de tiers ou de trésorerie concernés.
Le compte 50 – TITRES DE PLACEMENT est crédité, en cas de cession des titres, de la valeur d''entrée
• par le débit d''un compte de tiers ou de trésorerie, pour le prix de cession ;
• par le débit du compte 6771 – Pertes sur cessions de titres de placement (cas de cession avec perte) ;
• ou par le crédit du compte 777 – Gains sur cessions de titres de placement (cas de cession avec bénéfice).', exclusions = 'Le compte 50 – TITRES DE PLACEMENT ne doit pas servir à enregistrer :
• les titres dont la cession n''est pas facilement réalisable
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 26 – Titres de participation
• 274 – Titres immobilisés', controle = 'Le compte 50 – TITRES DE PLACEMENT peut être contrôlé à partir :
• des ordres d''achat ;
• des ordres de vente des titres ;
• des bordereaux de banque ;
• des contrats ;
• des relevés de titres en portefeuille.' WHERE numero = '50';
UPDATE comptes_ohada SET contenu = 'Les valeurs à encaisser sont les effets, chèques et autres valeurs transmis à la banque et dont l''entité attend
l''encaissement à l''échéance.', commentaires = 'Il est conseillé d''ouvrir un compte d''effets à encaisser par échéance, ce qui permet, éventuellement, d''approvisionner les
comptes bancaires en fonction des mouvements attendus.
Les effets à encaisser sont les effets en portefeuille autres que ceux concernant les clients et enregistrés au compte 412.
Les effets à l''encaissement sont les effets transmis à la banque en vue de l''encaissement à l''échéance.
Les chèques à encaisser sont les chèques que l''entité a reçu de ses clients et qu''elle n''a pas encore transmis en banque.
Les chèques à l''encaissement sont les chèques transmis à la banque qui n''ont pas encore été crédités par cette dernière.
Les cartes de crédit à encaisser enregistrent les paiements effectués par cartes de crédit jusqu''à l''avis de crédit de la
banque.
Les commissions prélevées par la banque pour de tels paiements sont enregistrées en services bancaires.
Les autres valeurs à l''encaissement sont les intérêts des obligations ou les dividendes des actions, échus et non encore
encaissés.
En cours d''exercice, les entités ne sont pas tenues d''utiliser le compte 51. Par contre, à la clôture de l''exercice, il est
obligatoire d''inscrire au débit du compte 51, d''une part, le montant des chèques non encore remis en banque et qui ne
sauraient être de ce fait inclus dans l''avoir disponible chez les banquiers, d''autre part, les coupons échus détenus par
l''entité.', fonctionnement = 'Le compte 51 – VALEURS A ENCAISSER est débité, lors de la réception de l''effet
• par le crédit des comptes de tiers concernés.
Le compte 51 – VALEURS A ENCAISSER est crédité du montant des effets, pour solde
• par le débit des comptes de trésorerie concernés.', exclusions = 'Le compte 51 – VALEURS A ENCAISSER ne doit pas servir à enregistrer :
• les effets à payer à plus d''un an d''échéance
• les effets remis à l''escompte
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 16 – Emprunts et dettes assimilées
• 56 – Banques, crédits de trésorerie et d''escompte', controle = 'Le compte 51 – VALEURS A ENCAISSER peut être contrôlé à partir :
• des effets ;
• des chèques ;
• des bordereaux de remise d''effets ou de chèques ;
• des relevés de banque.' WHERE numero = '51';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre les opérations financières effectuées entre l''entité, les banques agréées dans un Etat-partie et les
autres banques. La liste des banques agréées est tenue par l''organisme chargé de la surveillance bancaire.', commentaires = 'Il y a lieu de distinguer pour les banques locales, les avoirs en unité monétaire légale du pays des avoirs en devises. Parmi
les premiers, il faudra séparer les avoirs liquides des avoirs soumis à restriction.
Le solde qui ressort des livres comptables doit être rapproché du solde du compte tenu par la banque et envoyé
périodiquement à l''entité. Les différences éventuelles doivent être recherchées et faire l''objet d''écritures de redressement
lorsqu''elles n''ont pas pour origine un chevauchement de dates.
Le dépôt bancaire à terme que l''entité peut débloquer à tout moment doit être constaté par le débit du compte 525
Banques, dépôts à terme En revanche si l''entité ne peut débloquer le dépôt sur une durée supérieure à un an le compte
2784
Banques dépôts à terme sera plus approprié.
A la clôture de l''exercice, les avoirs en monnaies étrangères sont évalués au dernier cours officiel de change connu à cette
date.
Les comptes bancaires dont le solde apparaît créditeur en fin de période comptable sont inscrits au passif du bilan sous le
poste "banques, découverts", sans compensation possible avec ceux des comptes bancaires présentant un solde débiteur.', fonctionnement = 'Le compte 52 – BANQUES est débité des mouvements de fonds en faveur des comptes "Banques"
• par le crédit des comptes concernés.
Le compte 52 – BANQUES est crédité des mouvements de fonds en diminution des comptes "Banques"
• par le débit des comptes concernés.', exclusions = 'Le compte 52 – BANQUES ne doit pas servir à enregistrer les mouvements de fonds relatifs aux opérations avec :
• les Chèques postaux et le Trésor
• les représentations locales d''institutions financières internationales ou étrangères
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 53 – Etablissements financiers et assimilés
• 538 – Autres organismes financiers', controle = 'Le compte 52 – BANQUES peut être contrôlé à partir :
• des relevés bancaires ;
• des états de rapprochement bancaire.' WHERE numero = '52';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre les opérations entre l''entité et les Chèques postaux et le Trésor dans un Etat de la Région et les
autres établissements financiers.', commentaires = 'Il y a lieu de distinguer, pour les opérations avec les chèques postaux, les avoirs en unité monétaire légale du pays, d''une
part, des avoirs en devises, d''autre part. Parmi les premiers, il faudra séparer les avoirs liquides des avoirs soumis à
restriction.
Les opérations enregistrées en comptabilité doivent correspondre, sous réserve d''un décalage dans le temps, aux extraits
de comptes envoyés par le Centre de chèques postaux après chaque opération ou après chaque période. Les différences
éventuelles doivent être recherchées et faire l''objet d''écritures de redressement lorsqu''elles n''ont pas pour origine un
chevauchement de dates.
En fin d''exercice, les avoirs en monnaies étrangères sont évalués au dernier cours officiel de change connu à la date du
bilan.
Les comptes chèques postaux dont le solde apparaît créditeur en fin de période comptable sont inscrits au passif du bilan
sous le poste "banques, découverts", sans compensation possible avec ceux des comptes bancaires présentant un solde
débiteur.', fonctionnement = 'Le compte 53 – ETABLISSEMENTS FINANCIERS ET ASSIMILES est débité des mouvements de fonds en faveur des
établissements concernés
• par le crédit des comptes concernés.
Le compte 53 – ETABLISSEMENTS FINANCIERS ET ASSIMILES est crédité des mouvements de fonds diminuant
les avoirs de l''entité dans les établissements
• par le débit des comptes concernés.', exclusions = 'Le compte 53 – ETABLISSEMENTS FINANCIERS ET ASSIMILES ne doit pas servir à enregistrer :
• les mouvements de fonds relatifs aux opérations avec les banques
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 52 – Banques', controle = 'Le compte 53 – ETABLISSEMENTS FINANCIERS ET ASSIMILES peut être contrôlé à partir :
• des relevés de chèques postaux ;
• des relevés du Trésor ;
• des états de rapprochement.' WHERE numero = '53';
UPDATE comptes_ohada SET contenu = 'Les Instruments de trésorerie appartiennent à la catégorie des instruments financiers.
Ils comprennent :
• les options de taux ;
• les options de change ;
• les options sur actions ;
• les instruments de trésorerie à terme.
La qualification et la classification de ces différents instruments sont opérées en fonction de la motivation ou de l''intention
de l''entité.', commentaires = 'En fonction des marchés sur lesquels les opérations sont traitées, les règles et méthodes de comptabilisation diffèrent :
• sur les marchés organisés et assimilés, dotés d''une parfaite liquidité ; évaluation au prix du marché (règle dite de mark
to market) ;
• sur les autres marchés, évaluation au coût historique (règle de prudence).', fonctionnement = '• Les montants nominaux des contrats des instruments à terme, qu''ils aient vocation ou non vocation à être réglés
ne sont pas comptabilisés au bilan. Ils sont mentionnés dans les engagements hors bilan.
• Les primes et soultes ou équivalents sont enregistrés suivant le schéma : débit compte 54 instruments de
trésorerie par le crédit d''un compte de trésorerie (52 ou 53).
• Les appels de marges sont enregistrés comme suit :
• Les dépôts de garantie sont enregistrés au débit du compte 2758
Autres dépôts et cautionnement par le crédit d''un compte de trésorerie (52 ou 53) ;
• Les variations de valeurs sont ainsi enregistrées :
• Pour les instruments de couverture :
L''instrument de couverture peut être un instrument ou une proportion d''instrument financier à terme ferme ou
optionnel ou une combinaison d''instruments à terme fermes ou optionnels quel que soit leur sous- jacent.
Les autres actifs et passifs financiers peuvent être qualifiés d''instruments de couverture contre le risque de change ou
contre d''autres risques lorsque leur exposition au risque couvert compense l''exposition de l''élément couvert.
Les opérations qualifiées de couverture sont identifiées et traitées comptablement en tant que telles dès leur origine et
conservent cette qualification jusqu''à leur échéance ou dénouement.
L''élément couvert peut être un élément ou un groupe d''actifs, de passifs, d''engagements existants ou de transactions
futures non encore matérialisées par un engagement si ces transactions sont définies avec précision et possèdent
une probabilité suffisante de réalisation.
Un instrument financier à terme peut être un élément couvert.
Comptabilisation des instruments de couverture : Les produits et charges (latents ou réalisés) relatifs aux instruments
de couverture sont reconnus au compte de résultat de manière symétrique au mode de comptabilisation des produits
et charges sur l''élément couvert. Les variations de valeur des instruments de couverture ne sont pas reconnues au
bilan, sauf si la reconnaissance en partie ou en totalité de ces variations permet d''assurer un traitement symétrique
avec l''élément couvert.
Dans le cas d''un gain, le compte 54
Instruments de trésorerie est débité par le crédit du compte de la classe 7. Ce gain est étalé au prorata de la
comptabilisation des produits ou charge de l''élément couvert par le biais du compte 477
Produits constatés d''avance ;
Dans le cas d''une perte, le compte
Charges constatées d''avance.
Par symétrie, le résultat de la couverture est présenté dans le même poste ou à défaut dans la même rubrique du
compte de résultat que celui de l''élément couvert.
Cette comptabilité de couverture subsiste même en cas d''arrêt de la couverture dans les cas suivants :
En revanche lorsque l''élément couvert ne répond plus en partie ou totalement à la qualification d''élément couvert
éligible et que l''instrument de couverture est conservé, celui-ci est traité en totalité ou en partie comme un instrument
en position ouverte isolée
Primes d''option : Les primes d''options peuvent être étalées dans le compte de résultat sur la période de couverture.
Elles peuvent également être constatées en résultat au même moment que la transaction couverte ou dans la valeur
d''entrée au bilan de l''élément couvert lorsqu''il s''agit d''un achat futur d''actif.
Report/déport du change à terme: Le report ou déport des contrats de change à terme doit être étalé dans le compte
de résultat, par le biais du comptes 6784 pertes sur instruments de trésorerie ou du compte 7784 Produits sur
instruments de trésorerie selon le sens-, sur la durée de la couverture.
Les gains latents n''interviennent pas dans le résultat en raison du principe de prudence.
Décomptabilisation des instruments financiers : Si l''entité perd le contrôle des droits attachés à l''instrument financier,
elle doit décomptabiliser le bien et le compte 54 Instruments de trésorerie. Ce compte d''instruments de trésorerie peut
être constaté en résultat au même moment que la transaction couverte ou dans la valeur d''entrée au bilan de
l''élément couvert lorsqu''il s''agit d''un achat futur d''actif.', exclusions = 'Le compte 54 – INSTRUMENTS DE TRESORERIE ne doit pas servir à enregistrer :
• les opérations de crédits de trésorerie
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 56 – Banques, crédits de trésorerie et d''escompte', controle = 'Le compte 54 – INSTRUMENTS DE TRESORERIE peut être contrôlé à partir des relevés et états de rapprochement
bancaires.' WHERE numero = '54';
UPDATE comptes_ohada SET contenu = 'L''instrument de monnaie électronique est constitué d''une valeur monétaire stockée sous une forme électronique, y compris
magnétique, représentant une créance sur l''émetteur, qui est émise contre la remise de fonds aux fins d''opérations de
paiement et qui est acceptée par une personne physique ou morale autre que l''émetteur de monnaie électronique;', commentaires = 'Il peut être ouvert autant de sous-comptes en cas de besoin.
L''instrument de monnaie électronique est un moyen de paiement électronique qui permet d''effectuer les opérations
financières suivantes:
• dépôts et retraits d''argent ;
• transferts d''argent ;
• paiement de factures (téléphone, électricité, eau, carburant ...) ;
• achat de biens et services ;
• achat de crédits téléphoniques ;
• etc….
Les comptes instruments de monnaie électronique sont débités lors du chargement et crédités au fur et à mesure des
paiements.
Le porte-monnaie électronique est un moyen de paiement qui se présente sous forme d''une carte bancaire alimentée en
unités monétaires par le porteur. Le porte- monnaie électronique se retire dans les banques en général. Il n''est pas
nécessaire d''avoir un compte bancaire pour pouvoir obtenir un porte-monnaie électronique.
Les frais relatifs aux dépôts, retraits et paiements par instruments de monnaie électronique sont enregistrés au compte
Le solde du compte instruments monétaire électronique ne doit être que débiteur ou nul.', fonctionnement = 'Le compte 55 – INSTRUMENTS MONNAIE ELECTRONIQUE est débité des montants des chargements effectués
• par le crédit des comptes concernés des comptes 52 ; 53 ; 57.
Le compte 55 – INSTRUMENTS MONNAIE ELECTRONIQUE est crédité des règlements
• par le débit des comptes concernés.', exclusions = 'Le compte 55 – INSTRUMENTS MONNAIE ELECTRONIQUE ne doit pas servir à enregistrer :
• les mouvements de fonds des banques
• les opérations de crédit de trésorerie
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 52 – Banques
• 56 – Banques crédits de trésorerie et d''escompte
• les instruments de trésorerie
• les paiements effectués par cartes de crédit
• 54 – Instruments de trésorerie
• 515 – Cartes de crédit à encaisser', controle = 'Le compte 55 – INSTRUMENTS MONNAIE ELECTRONIQUE peut être contrôlé à partir :
• des factures de chargement;
• du code accès au service de l''instrument et du code secret du gestionnaire de l''instrument.' WHERE numero = '55';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre, d''une part, le montant de crédits de trésorerie inscrit au compte courant de l''établissement
dispensateur de ces concours avec lequel l''entité est en relation d''affaires et, d''autre part, le montant nominal des effets
escomptés.', commentaires = 'Le compte 561 – Crédits de trésorerie sert à enregistrer les concours qu''accordent les établissements de crédit sur une
durée de deux ans au plus, pour financer généralement des besoins généraux.
Ils peuvent prendre la forme de prêt et être assortis de contrat indiquant la durée du remboursement, le taux d''intérêt, les
garanties réelles ou personnelles y afférents.
Ils peuvent tout aussi bien revêtir la forme d''avances en compte, et être des crédits de courrier, des crédits de campagne,
des facilités de caisse, voire des découverts (consentis notamment pour le règlement d''une dette, un achat massif de
marchandises et autres biens, ou pour honorer des paiements importants).
Le compte 564 – Escompte de crédits de campagne sert à enregistrer les opérations d''escompte des effets représentatifs
de crédits de campagne.
Par crédit de campagne, il convient d''entendre les concours consentis de façon exclusive et certaine pour la
commercialisation de produits agricoles locaux lorsque :
• cette commercialisation est effectuée par l''intermédiaire ou sous la surveillance d''organismes placés directement ou
indirectement sous le contrôle de l''Etat ;
• le dénouement de ces concours intervient normalement dans un délai maximum de douze mois à compter du début de
la campagne.
Toutefois, le financement des stocks – reports, relatifs aux produits agricoles locaux, au-delà de douze mois – est à
rattacher aux crédits de campagne.
Le compte 565 – Escompte de crédits ordinaires sert à enregistrer les opérations d''escompte des effets représentatifs de
transactions commerciales. Ces effets sont créés en contrepartie :
• d''une livraison effective de biens ou services, hormis les produits de campagne ;
• d''exécution de travaux ;
• de prestations de services.
Le banquier escompteur est censé devenir propriétaire de la créance. Toutefois, la créance ne disparaît pas du bilan de
l''entité en tant que telle, en raison de l''engagement de l''entité de se substituer au débiteur défaillant.
Comptabilisation de l''opération d''escompte d''effets : 1 – A la date de remise à l''escompte, le compte 415 – Clients, effets
escomptés non échus est débité par le crédit du compte 412 – Clients, effets à recevoir en portefeuille. 2 – A la réception du
décompte bancaire, le compte 52 – Banques est débité pour le montant net obtenu de la banque et le compte 675 –
Escompte des effets de commerce, pour le montant des frais bancaires et d''intérêts d''escompte ; en contrepartie le compte
565 – Escompte de crédits ordinaires est crédité, pour le montant nominal des effets concernés.
3 – Après la date d''échéance, et le dénouement de l''opération, le compte 565 – Escompte de crédits ordinaires est débité
pour le montant nominal de l''effet par le crédit du compte 415 – Clients, effets escomptés non échus.', fonctionnement = 'Le compte 56 – BANQUES, CREDITS DE TRESORERIE ET D''ESCOMPTE est crédité du montant des crédits de
trésorerie effectivement portés au compte
• par le débit du compte 52 – Banques.
Le compte 56 – BANQUES, CREDITS DE TRESORERIE ET D''ESCOMPTE est crédité du montant nominal des effets
escomptés
• par le débit du compte 52 – Banques ;
• ou par le débit du compte 675 – Escompte des effets de commerce.
Le compte 56 – BANQUES, CREDITS DE TRESORERIE ET D''ESCOMPTE est débité du montant des
remboursements de crédits de trésorerie
• par le crédit du compte 52 – Banques.
Le compte 56 – BANQUES, CREDITS DE TRESORERIE ET D''ESCOMPTE est débité du montant nominal des effets
remis à l''escompte dont l''échéance est passée et l''opération dénouée
• par le crédit du compte 415 – Clients, effets escomptés non échus.', exclusions = 'Le compte 56 – BANQUES CREDITS DE TRESORERIE ET D''ESCOMPTE ne doit pas servir à enregistrer :
• les prêts bancaires à plus d''un an
• les découverts bancaires autorisés, tant qu''ils n''ont qu''un caractère d''engagement de la
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 16 – Emprunts et dettes assimilées
• comptes d''engagements hors bilan banque vis-à-vis de l''entité et qu''ils s''ajustent donc sur le montant du solde
débiteur chez le banquier
• les effets remis à l''encaissement à leur échéance normale
• 51 – Valeurs à encaisser', controle = 'Le compte 56 – BANQUES, CREDITS DE TRESORERIE peut être contrôlé à partir :
• des attestations de la banque concernant les crédits de trésorerie ;
• des relevés bancaires, étant entendu que le crédit de trésorerie doit avoir été positionné au crédit du compte
courant ;
• des bordereaux de remise des effets à l''escompte.' WHERE numero = '56';
UPDATE comptes_ohada SET contenu = 'Le compte Caisse retrace les opérations d''encaissement et de paiement effectuées en espèces pour les besoins de l''entité.', commentaires = 'Il peut être ouvert autant de sous-comptes en cas de besoin.
Le solde du compte caisse doit toujours correspondre exactement à la somme disponible réellement.
Le solde du compte caisse ne doit être que débiteur ou nul.
Un solde créditeur du compte caisse signifierait que l''entité serait parvenue à débourser davantage d''espèces qu''elle n''en
aurait reçu en caisse et qu''elle ne serait pas à même d''indiquer la manière dont les emplois en dépassement ont été
couverts.
En conséquence, un solde créditeur du compte caisse ou constitue une présomption d''irrégularité de la comptabilité.', fonctionnement = 'Le compte 57 – CAISSE est débité des versements effectués au profit de la caisse
• par le crédit des comptes concernés.
Le compte 57 – CAISSE est crédité des règlements effectués par la caisse ou
• par le débit des comptes concernés.', exclusions = 'Le compte 57 – CAISSE ne doit pas servir à enregistrer :
• les chèques de voyage
• les chèques de banque
• les timbres fiscaux
• les timbres postaux et autres figurines d''affranchissement
• les effets de commerce
• les paiements effectués par cartes de crédit
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 518 – Autres valeurs à l''encaissement
• 513 – Chèques à encaisser ou 514 – Chèques à l''encaissement
• 64 – Impôts et taxes
• 616 – Transports de plis
• 41 – Clients et comptes rattachés
• 515 – Cartes de crédit à encaisser', controle = 'Le compte 57 – CAISSE peut être contrôlé à partir :
• des procès-verbaux de caisse ;
• des états de reddition de la caisse ;
• des bordereaux de situation journalière ;' WHERE numero = '57';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre le montant des avances aux régisseurs, le montant des accréditifs ainsi que la régularisation desdites
avances et le règlement des accréditifs.', commentaires = 'Les comptes 581 et 582 enregistrent, le cas échéant, les opérations relatives :
• aux régies d''avances : fonds gérés par les régisseurs ou les comptables subordonnés (sur un chantier forestier ou de
travaux publics, par exemple) ;
• aux accréditifs, c''est-à-dire, les crédits ouverts par un établissement de crédit, relation d''affaires de l''entité, dans sa
succursale d''une ville, d''un département, d''une localité, afin de permettre au tiers concerné, généralement le
responsable local de l''entité, de couvrir ses besoins de trésorerie.
Les comptes 585 et 588, relatifs aux virements internes, sont utilisés pour des raisons techniques dans les comptabilités
organisées sur la base de journaux auxiliaires. Ce sont des comptes de passage utiles à la comptabilisation d''opérations
internes à l''entité. Leur utilisation a pour but d''éviter les risques de double emploi au cours de la centralisation des écritures.
En tout état de cause ces comptes doivent être soldés au terme de leur utilisation.', fonctionnement = 'Le compte 58 – REGIES D''AVANCES, ACCREDITIFS ET VIREMENTS INTERNES est débité du montant des
avances aux régisseurs et du montant des accréditifs (comptes 581 et 582)
• par le crédit des comptes de trésorerie.
Le compte 58 – REGIES D''AVANCES, ACCREDITIFS ET VIREMENTS INTERNES est débité, en cours d''exercice, du
montant correspondant à un débit à porter dans un compte support d''un journal auxiliaire (comptes 585 et 588)
• par le crédit des comptes de trésorerie.
Le compte 58 – REGIES D''AVANCES, ACCREDITIFS ET VIREMENTS INTERNES est crédité lors de la
régularisation des avances et du règlement définitif des accréditifs (comptes 581 et 582)
• par le débit des comptes concernés.
Le compte 58 – REGIES D''AVANCES, ACCREDITIFS ET VIREMENTS INTERNES est crédité, en cours d''exercice,
du montant correspondant à un crédit à porter dans un compte support d''un journal auxiliaire (comptes 585 et 588)
• par le débit des comptes de trésorerie.', exclusions = 'Le compte 58 – REGIES D''AVANCES, ACCREDITIFS ET VIREMENTS INTERNES ne doit pas servir à enregistrer :
• les opérations internes de trésorerie, lorsque l''entité utilise un journal unique
Il convient dans le cas d''espèce d''utiliser les comptes ci-après :
• les autres comptes de la classe 5 concernés', controle = 'Le compte 58 – REGIES D''AVANCES, ACCREDITIFS ET VIREMENTS INTERNES peut être contrôlé à partir des
relevés bancaires. Il importe de s''assurer que les comptes 585 et 588 relatifs aux virements internes sont soldés à la
fin de l''exercice.' WHERE numero = '58';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre l''amoindrissement de la valeur des titres et valeurs liquides, des avoirs en banque, et autres éléments
financiers résultant de causes précises quant à leur nature, mais dont les effets ne sont pas jugés irréversibles ainsi que les
reprises de charges provisionnées s''y rapportant.
Il enregistre également les provisions de caractère financier pour risques à moins d''un an.', commentaires = 'Les dépréciations des comptes de trésorerie obéissent aux mêmes règles de comptabilisation que les dépréciations
constatées sur les éléments de l''actif circulant (classes 3 et 4).
La dotation est à constituer même si la dépréciation est d''un montant incertain.
La dépréciation traduit une baisse non définitive et non irréversible de l''évaluation des éléments d''actif par rapport à leur
valeur comptable.
Les événements générateurs de dépréciations, survenus après la clôture de l''exercice, ne sont pas pris en compte dans
ledit exercice ; les dépréciations ne doivent être constituées que pour des dépréciations subies au cours de l''exercice, et à
la clôture de l''exercice.
La dépréciation doit être constituée même en l''absence ou en cas d''insuffisance de bénéfices ; de la sorte, il est donné une
image fidèle du patrimoine, de la situation financière et du résultat de l''entité.
Lorsque, au jour de l''inventaire, la valeur économique réelle des titres, valeurs liquides et autres avoirs du genre sur
banques est inférieure à leur valeur comptable, les entités doivent constituer des dépréciations qui expriment les moins-
values constatées sur ces éléments de la trésorerie.
Les dépréciations sont portées à l''actif du bilan, en diminution de la valeur des postes qu''elles concernent, sous la forme
prévue par le modèle de bilan.
Pour les titres de placement, la constitution de dépréciation s''appuie sur une évaluation des cours à la clôture de l''exercice,
basée sur la valeur de la transaction en Bourse, s''il s''agit de titres cotés, ou sur la valeur de négociation potentielle, s''il
s''agit de titres non cotés.
Les provision pour risques à court terme à caractère financier enregistrent les pertes probables à moins d''un an ayant leur
origine dans une opération de nature financière ; exemple : provisions pour pertes de change.', fonctionnement = 'A la clôture de l''exercice :
Le compte 59 – DEPRECIATIONS ET RISQUES PROVISIONNES (TRESORERIE) est crédité des dépréciations de
l''exercice, constatées sur les éléments d''actif de la classe 5, ainsi que des pertes probables de nature financière à
moins d''un an
• par le débit du compte 679 – Charges pour dépréciations et provisions pour risques à court terme financières.
Le compte 59 – DEPRECIATIONS ET PROVISIONS POUR RISQUES A COURT TERME (TRESORERIE) est débité
des dépréciations et provisions existant à l''ouverture de l''exercice
• par le crédit du compte 779 – Reprises de charges pour dépréciations et provisions pour risques à court terme
financières.', exclusions = 'Le compte 59 – DEPRECIATIONS ET PROVISIONS POUR RISQUES A COURT TERME ci-après : (TRESORERIE)
ne doit pas servir à enregistrer les provisions pour dépréciations d''autres éléments du bilan :
• Classe 1
• Classe 2
• Classe 3
• Classe 4
Il convient dans les cas d''espèce d''utiliser les comptes
• 19 – Provisions financières pour risques et charges
• 29 – dépréciations
• 39 – Dépréciations des stocks
• 49 – Dépréciations et provisions pour risques à court terme (Tiers)', controle = 'Le compte 59 – DEPRECIATIONS ET PROVISIONS POUR RISQUES À COURT TERME (TRESORERIE) peut être
contrôlé à partir des cours de Bourse de clôture, des évaluations de titres, des cours du change.
COMPTES DE CHARGES DES ACTIVITÉS ORDINAIRES
Les charges sont des emplois définitifs ou consommations de valeurs décaissées ou à décaisser par l''entité :
• soit en contrepartie de marchandises, approvisionnements, travaux et services consommés par l''entité, ainsi que
des avantages qui leur ont été consentis ;
• soit en vertu d''une obligation légale que l''entité doit remplir ;
• soit exceptionnellement, sans contrepartie directe.
Les charges comprennent également pour
Les charges sont distinguées, selon leur nature :
• en charges des Activités Ordinaires (compte de la classe 6)
• ou en charges Hors Activités
Ordinaires (compte classe 8).
La classe 6 est destinée à enregistrer les charges liées à l''activité ordinaire de l''entité. Ces charges entrent dans la
composition des coûts des produits de la détermination du résultat de l''exercice : * l''entité.
• les dotations aux amortissements, aux provisions et aux dépréciation;
• la valeur comptable des éléments d''actif cédés, détruits ou disparus.
Les charges doivent être comptabilisées dans l''exercice au cours duquel elles ont pris naissance. Elles donnent
éventuellement lieu à abonnement ou à régularisation à la clôture de l''exercice.
COMPTES DE LA CLASSE 6
60    (sauf 603) Achats                                  105    659   Charges pour dépréciations et provisions pour         117
603   Variations des stocks de biens achetés             108          risques a court
61    Transports                                         109
66    Charges de personnel                                  118
62/63 Services extérieurs ; Autres services extérieurs   110
67    Frais financiers et charges assimilées                121
64    Impôts et taxes                                    113
68    Dotations aux amortissements                          123
65    Autres charges (sauf 659)                          116
69    Dotations aux provisions et aux dépréciations         124' WHERE numero = '59';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre, le montant des factures d''achat et la valeur des retours de matières, fournitures et marchandises aux
fournisseurs ainsi que les rabais, remises et ristournes hors factures obtenus des fournisseurs de biens.', commentaires = 'Les comptes 601, 602, 604, 605 et 608, comme les comptes de stocks correspondants, donnent lieu à l''ouverture de sous-
comptes de produits regroupés suivant la nomenclature des biens et services en usage dans chaque Etat-partie.
Sous cette réserve, les entités peuvent choisir une nomenclature à leur convenance.
Le montant des factures d''achat à inscrire au compte 60 (sauf 603) s''entend, le cas échéant, net de taxes récupérables.
Les frais accessoires d''achats peuvent être enregistrés au sous-compte Achats concerné pour le montant net de taxes
récupérables. Les sous comptes frais sur achats peuvent être subdivisés selon qu''il s''agit de marchandises, de matières et
fournitures liées et de matières et fournitures consommables.
Les achats sont comptabilisés, déduction faite des rabais et remises, imputés directement sur le montant de la facture.
Même lorsqu''ils sont déduits sur la facture d''achat, les escomptes de règlement sont portés au compte 773 – Escomptes
obtenus.
A la clôture de l''exercice, les biens reçus par l''entité, avant réception de la facture correspondante, sont néanmoins inscrits
dans les achats, par le crédit d''un compte divisionnaire de fournisseurs (408 – Factures non parvenues). Cette précaution a
pour but de ne pas fausser les résultats.
Les remises, rabais et ristournes sur achats, obtenus des fournisseurs et dont le montant, non déduit des factures d''achats,
n''est connu que postérieurement à la comptabilisation de ces factures, sont enregistrés aux comptes d''achats concernés.', fonctionnement = 'Le compte 60 – ACHATS est débité du montant des factures d''achat hors taxes frais accessoires inclus .
Le compte 445 – ETAT, TVA RECUPERABLE est débité du montant des taxes, si les taxes sont récupérables.
• par le crédit du compte fournisseur ou d''un compte de trésorerie.
Le compte 60 – ACHATS est crédité, en cours d''exercice, des retours de matières, fournitures et marchandises hors
taxes aux fournisseurs ainsi que les rabais, remises, ristournes obtenus par facture d''avoir (après première
facturation)
Le compte 445 – ETAT, TVA RECUPERABLE est crédité du montant des taxes, si les taxes ont été récupérées.
• par le débit des comptes fournisseurs ou de tiers intéressés.
Le compte 60 – ACHATS est crédité pour solde à la clôture de l''exercice
• par le débit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 60 – ACHATS ne doit pas servir à enregistrer :
• les frais accessoires d''achats directement rattachables aux immobilisations
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• comptes de la classe 2', controle = 'Le compte 60 – ACHATS peut être contrôlé à partir :
• des factures et avoirs fournisseurs ;
• des bons de commande ;
• des états d''inventaire.' WHERE numero = '60';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre les variations de stocks de biens et de marchandises achetés en retraçant les opérations relatives
aux entrées en stocks, aux sorties de stocks, et aux différences constatées entre l''inventaire comptable permanent et
l''inventaire physique.
Les variations de stocks sont évaluées différemment selon le système d''inventaire utilisé.', commentaires = 'Les comptes de variations de stocks peuvent être de solde débiteur ou créditeur.
Pour la détermination des soldes significatifs de gestion, les variations de stocks sont calculées à partir du prix d''achat des
biens inscrits dans les stocks, tel qu''il est comptabilisé dans les comptes d''achats.
Les soldes des sous-comptes du compte', fonctionnement = 'En cas d''inventaire intermittent, à la clôture de l''exercice :
Le compte 603 – VARIATIONS DES STOCKS DE BIENS ACHETES est débité de la valeur du stock initial(1)
• par le crédit des comptes de stocks concernés (pour solde des stocks initiaux).
(1) ou de la diminution de l''exercice (stock initial moins stock final).
Le compte 603 – VARIATIONS DES STOCKS DE BIENS ACHETES est crédité de la valeur du stock final, pour sa
valeur d''inventaire(1)
• par le débit des comptes de stocks concernés (constatation des stocks finals).
Le compte 603 – VARIATIONS DES STOCKS DE BIENS ACHETES est viré pour solde, avec les charges, dans le
compte 13 – Résultat net de l''exercice (montant débiteur ou montant créditeur, selon le cas).
En cas d''inventaire permanent :
Le compte 603 – VARIATIONS DES STOCKS DE BIENS ACHETES est crédité, en cours d''exercice, des entrées en
stocks
• par le débit des comptes de stocks concernés.
Le compte 603 – VARIATIONS DES STOCKS DE BIENS ACHETES est débité, en cours d''exercice, des sorties de
stocks
• par le crédit des comptes de stocks concernés.
A la clôture de l''exercice, le compte 603 – VARIATIONS DES STOCKS DE BIENS ACHETES est débité des
différences en moins constatées entre l''inventaire comptable et l''inventaire physique
• par le crédit des stocks concernés.
A la clôture de l''exercice, le compte 603 – VARIATIONS DES STOCKS DE BIENS ACHETES est crédité des
différences en plus constatées entre l''inventaire comptable et l''inventaire physique
• par le débit des comptes de stocks concernés.
(1) ou de l''augmentation de l''exercice (stock final moins stock initial).
A la clôture de l''exercice, le compte 603 – VARIATIONS DES STOCKS DE BIENS ACHETES est viré (pour solde),
avec les charges, dans le compte 13 – Résultat net de l''exercice (montant débiteur ou montant créditeur, selon le
cas).', exclusions = 'Le compte 603 – VARIATIONS DES STOCKS DE BIENS ACHETES ne doit pas servir à enregistrer :
• les variations de stocks d''en-cours ou de produits fabriqués
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 73 – Variations des stocks de biens et de services produits', controle = 'Le compte 603 – VARIATIONS DES STOCKS DE BIENS ACHETES peut être contrôlé à partir de l''inventaire, ou du
décompte physique, et de l''évaluation.' WHERE numero = '603';
UPDATE comptes_ohada SET contenu = 'Les frais de transport comprennent le montant des charges de port ou transports engagés par l''entité, à l''occasion, des
ventes, des déplacements de son personnel ou de l''expédition de plis.
Ces deux comptes enregistrent le montant des factures, paiements et rémunérations versés aux prestataires extérieurs à
l''entité et les éventuels rabais, remises et ristournes obtenus hors factures sur les services extérieurs consommés.', commentaires = 'Le compte 616 – Transports de plis peut être débité soit à l''occasion du paiement d''un affranchissement, soit à l''occasion
de l''achat à l''avance de figurines d''affranchissement ou de bons de courses, représentatifs de courses par coursier.
Les services sont classés par nature ; leur importance et leur diversité sont telles qu''il a été nécessaire d''utiliser deux
comptes à deux chiffres (62 et 63), dont le fonctionnement est rigoureusement identique.
La consommation de services est rapportée à la période comptable par le jeu de comptes d''abonnements ou de
régularisation.
Le personnel intérimaire, détaché ou prêté est le personnel salarié d''une entité qui le met à la disposition d''une autre entité
utilisatrice des services pour une durée déterminée. La prestation est facturée comme service extérieur par l''entité
employeur qui peut être :
En vertu du principe de la prééminence de la réalité sur l''apparence juridique :
Rémunérations de personnel extérieur à l''entité;
Les rémunérations des autres prestataires de services non-salariés d''une autre entité, ou non mis à disposition par une
autre entité doivent être enregistrées au compte
Ne sont pas considérés comme étant des services consommés et sont en principe classés dans la même catégorie que les
produits dans la fabrication desquels ils sont incorporés :
• les travaux à façon : consistent à confier à une personne ou à une entité à qui on fournit les matières premières et
éventuellement du matériel pour fabriquer, des produits semi finis, ou finis suivant des spécifications bien définies.
• les sous-traitances industrielles : La sous-traitance est le fait qu''une entreprise donnée confie partiellement sa
production à une autre dans le cadre d''un travail de sous-œuvre. La sous- traitance industrielle consiste, pour une
entreprise à confier la réalisation à une entreprise, dite « sous-traitant », d''une ou de plusieurs opérations de
conception, d''élaboration, de fabrication, de mise en œuvre ou de maintenance du produit. Ces opérations concernent
un cycle de production déterminé.
• les frais de réparation, lorsqu''ils sont effectués par le fabricant du produit.
Lorsqu''ils le sont par un réparateur, ils sont inscrits au compte 63.', fonctionnement = 'Le compte 61 – TRANSPORTS est débité des charges de port ou transports engagées par l''entreprise
• par le crédit des comptes de tiers ou de trésorerie concernés.
Le compte 61 – TRANSPORTS est crédité, en cours d''exercice, du montant des factures d''avoir représentant des
réductions à caractère commercial ou des annulations de factures
• par le débit des comptes fournisseurs concernés.
Le compte 61 – TRANSPORTS est crédité, à la clôture de l''exercice
• par le débit : du compte 476 – Charges constatées d''avance, pour régularisation ou par le débit du compte 13 –
Résultat net de l''exercice (pour solde).
Les comptes 62 et 63 – SERVICES EXTERIEURS ET AUTRES SERVICES EXTERIEURS sont débités
• par le crédit d''un compte de tiers ou de trésorerie.
Les comptes 62 et 63 – SERVICES EXTERIEURS ET AUTRES SERVICES EXTERIEURS sont crédités
• par le débit des comptes Fournisseurs des rabais, remises et ristournes éventuellement obtenus hors factures.
Les comptes 62 et 63 – SERVICES EXTERIEURS ET AUTRES SERVICES EXTERIEURS sont crédités soit par le
débit du compte 476 – Charges constatées d''avance (régularisation), soit par le débit du compte 13 – Résultat net de
l''exercice, pour solde à la clôture de l''exercice.', exclusions = 'Le compte 61 – TRANSPORTS ne doit pas servir à enregistrer :
• les consommations intermédiaires de biens et de services, lorsque l''entité effectue des transports pour son propre
compte : carburants, réparations de véhicules, etc
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• comptes de charges appropriés', controle = 'Le compte 61 – TRANSPORTS peut être contrôlé à partir :
• des factures et avoirs fournisseurs ;
• des documents de transport (connaissements, lettres de voiture, etc.);
• de l''inventaire des figurines d''affranchissement ;
• des bons de course.
62/63         Services extérieurs ; Autres services extérieurs
Les comptes 62 et 63 – SERVICES EXTERIEURS ET AUTRES SERVICES EXTERIEURS peuvent être contrôlés à
partir des factures et avoirs fournisseurs ainsi que des dispositions des contrats.' WHERE numero = '61';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre le montant des charges correspondant à des versements obligatoires à l''Etat et aux collectivités
publiques pour subvenir à des dépenses publiques, ou encore des versements institués par les autorités pour le
financement d''actions d''intérêt général.', commentaires = 'Le compte 64 enregistre tous les impôts et taxes à la charge de l''entité, à l''exception de ceux dont l''assiette est établie sur
les résultats qui sont inscrits au débit du compte 89 – Impôts sur le résultat.
Les impôts, qui, payés par l''entité, doivent être récupérés sur des tiers ou sur le Trésor public, sont enregistrés aux comptes
de la classe 4.
Les entités comprennent dans le prix d''achat des marchandises, matières et fournitures, les droits de douane qui peuvent
leur être affectés de façon certaine, pour obtenir le prix d''achat rendu frontière.
Le compte 6411 – Impôts fonciers et taxes annexes enregistre les versements obligatoires à l''Etat dont l''entité propriétaire
d''un immeuble bâti ou non bâti ou d''un terrain doit s''acquitter, en application des lois en vigueur dans chacun des Etats-
parties.
Le compte 6412 – Patentes, licences et taxes annexes enregistre les versements obligatoires à l''Etat dont l''entité doit
s''acquitter :
• (cas de la patente) du fait de l''exercice d''un commerce, d''une industrie ou d''une profession. La patente peut, en fonction
des dispositions fiscales dans les Etats-parties, comporter un droit fixe unique indépendamment du nombre de
commerces, d''industries et de professions qu''il exerce dans le même établissement et, par ailleurs, un droit
proportionnel généralement établi sur la valeur locative des bureaux, magasins, boutiques, usines, ateliers, hangars,
remises, chantiers, terrains de dépôts et autres locaux et emplacements servant à l''exercice de la profession ;
• (cas de la licence) du fait de l''exploitation d''un brevet. C''est le cas notamment des exploitants de débits de boisson, de
restaurants.
Le compte 6413 – Taxes sur appointements et salaires enregistre les versements obligatoires à l''Etat dont l''entité est
redevable, en qualité d''employeur, au titre des traitements, salaires, indemnités et émoluments versés.
Le compte 646 – Droits d''enregistrement enregistre les versements obligatoires à l''Etat dont l''entité est redevable en
raison :
• des perceptions requises par la recette des impôts pour l''accomplissement de certains actes juridiques, tels que des
ventes, des échanges, des mutations, des donations, des successions, des baux, des constitutions de sociétés ;
• de timbres afférents à certains actes écrits : timbres de quittances, timbres de contrats de transport, timbres des
affiches, timbres sur les bordereaux d''achat ou de vente en Bourse ;
• des taxes sur les véhicules de société et vignettes (autos, motos, bateaux, etc.).
Le compte 647 – Pénalités et amendes fiscales enregistre les versements obligatoires à l''Etat dont l''entité est redevable en
raison de l''inobservation de dispositions fiscales telles que :
• les pénalités d''assiette consistant en intérêts ou indemnités de retard exigibles en cas d''inexactitude dans les
déclarations, manœuvres frauduleuses, défaut de production ou production tardive de documents ;
• les pénalités de recouvrement sanctionnant le versement tardif des impôts et taxes qui sont déductibles.
Le compte 648 – Autres impôts et taxes enregistre les autres versements obligatoires à l''Etat et aux collectivités locales
dont l''entité est redevable, en raison des activités exercées, et qui ne peuvent pas être imputées aux comptes ci- dessus
définis.', fonctionnement = 'Le compte 64 – IMPÔTS ET TAXES est débité du montant de l''impôt dû
• par le crédit du compte 44 – Etat et Collectivités publiques ou
• par le crédit des comptes de trésorerie.
Le compte 64 – IMPÔTS ET TAXES est crédité pour solde à la clôture de l''exercice
• par le débit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 64 – IMPÔTS ET TAXES ne doit pas servir à enregistrer :
• les annuités de remboursement d''emprunts contractés ou d''avances consenties par l''Etat
• les droits de douane relatifs aux acquisitions d''immobilisations
• les droits de douane relatifs à des achats de biens importés incorporés au prix d''achat (prix rendu frontière)
• l''impôt sur les bénéfices
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 16 – Emprunts et dettes assimilées
• de la classe 2
• 60 – Achats et variations de stocks
• 89 – Impôts sur le résultat', controle = 'Le compte 64 – IMPÔTS ET TAXES peut être contrôlé à partir :
• des déclarations ;
• des avis d''imposition ;
• des règlements à l''ordre du Trésor.' WHERE numero = '64';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre le montant des charges, de caractère souvent accessoire, qui entrent dans les consommations de
l''exercice en provenance de tiers pour le calcul de la valeur ajoutée de gestion, dans le cadre des choix opérés par le
Système
Comptable OHADA.', commentaires = 'Les créances clients et autres débiteurs irrécouvrables sont enregistrées au débit du compte 651 pertes sur créances et
autres débiteurs.
La quote-part de bénéfice revenant aux associés d''une société en participation (dont la comptabilité est tenue par un
gérant, seul connu des tiers) sera enregistrée dans les livres du gérant de la société en participation au débit du compte
La perte imputée aux associés par la société en participation sera enregistrée dans la comptabilité des associés par le
compte débit du compte 6525 Pertes imputés par transfert au crédit du compte
Lorsqu''une entité qui, du fait de la nature de son activité renouvelle de manière fréquente ses immobilisations (loueurs de
biens, transporteur,…). La valeur brute et les amortissements des biens de ces entités, sont enregistrés au compte 654
Valeurs comptables des cessions courantes d''immobilisations.
Les dispositions de l''Acte uniforme de l''OHADA relatif au droit des sociétés commerciales et du GIE prévoient :
Ces rémunérations sont enregistrées au débit du compte 6581 Indemnités de fonction et autres rémunérations
d''administrateurs par le crédit du compte
Les dons et libéralités de toute nature effectués par l''entité au profit d''une autre personne dans le cadre d''une politique de
mécénat destinée à favoriser le développement d''activités humanitaires, civiques, culturelles ou sportives sont enregistrés
au débit du compte 6582
Dons ou 6583 Mécénat.', fonctionnement = 'Les comptes 651 à 658 – AUTRES CHARGES (sauf 654) sont débités du montant de la charge
• par le crédit d''un compte de tiers ou de trésorerie
Le compte 654 – VALEURS COMPTABLES DES CESSIONS COURANTES D''IMMOBILISATIONS est débité du
montant de la valeur brute et crédité des amortissements de l''immobilisation cédée
Les comptes 651 à 658 – AUTRES CHARGES sont crédités pour solde à la clôture de l''exercice
• par le débit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 65 – AUTRES CHARGES ne doit pas servir à enregistrer :
• les charges H.A.O. constatées
• amoindrissement de la valeur d''une créance dont les effets ne sont pas jugés irréversibles
• cessions d''immobilisations non fréquentes et récurrentes
• libéralités et dons à l''occasion d''évènements exceptionnels tels les phénomènes naturels : sécheresse,
inondations, tempête, vols de criquets, guerres, tremblement de terre, …..
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 831 – Charges H.A.O. constatées
• 659 charges pour dépréciations et provisions pour risques à court terme
• 81 Valeurs comptables des cessions d''immobilisations
• 835 Dons et libéralités accordés', controle = 'Le compte 65 – AUTRES CHARGES peut être contrôlé à partir :
• des factures ;
• des notifications de cessation de paiement relevées ou du courrier des avocats ;
• états financiers de la société en participation
• le procès-verbal de l''assemblée générale ou du conseil d''administration' WHERE numero = '65';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre les dotations pour dépréciation des éléments de l''actif circulant ainsi que les dotations aux provisions
pour risques à court terme.', commentaires = 'Les charges pour dépréciations et provisions pour risques à court terme répondent à une conception nouvelle du risque. En
effet, le
Système Comptable OHADA considère ces dotations comme des décaissements probables à brève échéance. Elles
figurent dans le Compte de résultat comme des charges externes.', fonctionnement = 'Le compte 659 – CHARGES POUR DÉPRÉCIATIONS ET PROVISIONS POUR RISQUES A COURT TERME
D''EXPLOITATION est débité
• par le crédit des comptes de dépréciation de l''actif circulant, comptes
Le compte 659 – CHARGES POUR DÉPRÉCIATIONS ET PROVISIONS POUR RISQUES A COURT TERME
D''EXPLOITATION est crédité pour solde à la clôture de l''exercice
• par le débit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 659 – CHARGES POUR DÉPRÉCIATIONS ET PROVISIONS POUR RISQUES A COURT TERME
D''EXPLOITATION ne doit pas servir à enregistrer :
• les Dépréciations et les provisions pour risques à court terme H.A.O.
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 839 – Charges pour dépréciations et provisions pour risques à court terme H.A.O.', controle = 'Le compte 659 – CHARGES POUR DÉPRÉCIATIONS ET PROVISIONS POUR RISQUES A COURT TERME
D''EXPLOITATION peut être contrôlé à partir des factures, notifications de cessation de paiements, relevés, ou des
courriers des avocats.' WHERE numero = '659';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre l''ensemble des rémunérations du personnel de l''entité, qu''il s''agisse d''appointements et salaires, de
commissions, de congés payés, de primes, de gratifications, d''indemnités de logement ou d''indemnités diverses, et, le cas
échéant, les rémunérations de l''exploitant individuel, en contrepartie du travail fourni. Il enregistre aussi les charges
sociales payées par l''entité au titre des salaires, ainsi que les avantages en nature.
Par ailleurs il est débité en fin d''exercice des montants facturés à l''entité au titre du
Personnel extérieur, intérimaire, détaché ou prêté.', commentaires = 'Les charges de personnel comprennent toutes les charges supportées par l''entité, à titre obligatoire ou bénévole, qui
prennent leur source dans les contrats de travail qu''elle a conclus et qui bénéficient directement ou indirectement aux
salariés.
Le compte 66 (sauf 667) est débité de la rémunération brute versée au personnel, les cotisations sociales mises à la charge
des salariés étant débitées au compte 42 – Personnel par le crédit du compte 43 – Organismes sociaux. Les frais de
voyage, de réception, les diverses dépenses exposées par le personnel dans l''exercice de ses fonctions pour le compte de
son employeur et dont le montant lui est soit remboursé, soit compris dans les rémunérations, doivent en principe être
également enregistrés en 62 ou 63.
Les avantages en nature dont bénéficie le personnel sont enregistrés par l''entité dans les différents comptes de charges
par nature concernée. Ces avantages en nature sont ensuite transférés dans les frais de personnel. Les entités débitent le
compte
Le compte 667 est débité, en fin d''exercice, du montant des rémunérations du personnel extérieur enregistrées au compte', fonctionnement = 'Le compte 66 – CHARGES DE PERSONNEL est débité
• par le crédit du compte 422 – Personnel, rémunérations dues ;
• ou par le crédit du compte 781 – Transferts de charges d''exploitation.
Le compte 66 – CHARGES DE PERSONNEL est débité des charges afférentes à ces rémunérations
• par le crédit du compte 43 – Organismes sociaux ;
• ou par le crédit du compte 44 – Etat et Collectivités publiques.
Le compte 66 – CHARGES DE PERSONNEL (compte 667) est débité des charges de personnel extérieur
• par le crédit du 78– Transferts de charges des rémunérations de personnel extérieur à l''entité (en fin d''exercice).
Le compte 66 – CHARGES DE PERSONNEL est crédité pour solde à la clôture de l''exercice
• par le débit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 66 – CHARGES DE PERSONNEL ne doit pas servir à enregistrer :
• les impôts dont l''assiette repose sur la rémunération
• les charges considérées comme des consommations intermédiaires (dépenses exposées par les salariés pour le
compte de l''entité, notamment)
• les rémunérations de toutes natures attribuées à des tiers
• les indemnités versées à des tierces personnes qui ne sont pas membres de l''entité (honoraires)
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 6413 – Taxes sur appointements et salaires
• comptes appropriés de la classe 6
• 632 – Rémunérations d''intermédiaires et de conseils', controle = 'Le compte 66 – CHARGES DE PERSONNEL peut être contrôlé à partir :
• des livres de paie ;
• des fiches de paie ;
• des déclarations sociales et fiscales.' WHERE numero = '66';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre l''ensemble des charges financières dues à différents tiers intervenant dans le financement de l''entité
(à l''exclusion de la rémunération des capitaux propres et à celle des services bancaires).', commentaires = 'Lorsque l''entreprise considère comme frais à immobiliser les intérêts intercalaires dus sur la période de construction d''une
immobilisation, ces intérêts sont d''abord comptabilisés au débit du compte 67 – Frais financiers, puis transférés au débit du
compte d''immobilisation concerné par le crédit du compte 72 – Production immobilisée.
Des nomenclatures internes à l ‘entité doivent permettre de raccorder les intérêts payés ou dus aux emprunts ou avances
reçues auxquels ils se rapportent.
Le compte 671 – Intérêts des emprunts enregistre le montant des charges financières et assimilées que l ‘entité doit payer,
en rémunération de l''utilisation des capitaux ou des avances de fonds qui lui ont été consentis par des tiers ou des entités
liées.
Le compte 672 – Intérêts dans loyers de location acquisition enregistre la quote-part des charges financières dans les
redevances versées par l ‘entité locataire (cf. titre VIII Opérations spécifiques chapitre 8).
Le compte 673 – Escomptes accordés enregistre le montant des réductions que l ‘entité consent sous forme d''escompte de
règlement aux clients qui s''acquittent de leurs factures ou règlent leurs créances avant le terme normal d''exigibilité.
Le compte 674 – Autres intérêts enregistre les charges financières versées aux associés et à divers tiers.
Le compte 675 – Escomptes des effets de commerce enregistre, après la remise au banquier de l''effet à l''escompte, lors de
la réception du bordereau d''escompte, le montant indiqué sur le décompte bancaire, au titre des frais prélevés pour
l''opération d''escompte.
Le compte 676 – Pertes de change enregistre à son débit les pertes de change supportées par l ‘entité au cours de
l''exercice. Les écarts de conversion négatifs constatés à la clôture de l''exercice sur les disponibilités en devises sont
considérés comme étant des pertes de change supportées. Le compte 676 – Pertes de change ne doit pas être confondu
avec le compte 478 – Ecarts de conversion-Actif qui n''enregistre que les pertes probables de change.
Le compte 677 – Pertes sur titres de placement enregistre les charges nettes effectivement supportées par l''entité lorsque
cette dernière réalise des pertes sur titres dont le prix de cession se trouverait inférieur au coût d''acquisition.
Dans le cas d''espèce, la perte subie, à savoir la différence entre la valeur d''entrée et le prix de cession, net toutefois des
frais de cession, est portée au débit du compte 677 – Pertes titres de placement. Dans le cas où les frais de cession sont
enregistrés distinctement (décalage de facturations), ils sont également portés au débit du compte 677.
Le compte 6772 Malis provenant d''attribution gratuite d''actions au personnel salarié et aux dirigeants est destiné à solder le
compte 5021 actions propres lors de l''attribution des actions gratuites au personnel salarié et aux dirigeants (voir Titre VIII
opérations spécifiques, chapitre 19).
Le compte 678 – Pertes sur risques financiers enregistre les pertes subies sur des opérations financières comportant un
risque autre que le risque de perte de change.
Exemples : rentes viagères, instruments de trésorerie, primes, options...
Le compte 679 – Charges pour dépréciations et provisions pour risques à court terme financières enregistre le montant des
charges financières potentielles évaluées à l''arrêté des comptes, nettement précisées quant à leur objet, mais dont
l''échéance ou le montant est incertain.', fonctionnement = 'Le compte 67 (sauf 679) – FRAIS FINANCIERS ET CHARGES ASSIMILEES est débité des frais dus et des pertes
financières constatées
• par le crédit des comptes de tiers concernés ou des comptes de trésorerie.
Le compte 679 – CHARGES POUR DÉPRÉCIATIONS ET PROVISIONS POUR RISQUES À COURT TERME
FINANCIERES est débité des dépréciations à court terme des risques financiers et des comptes de trésorerie
• par le crédit du compte 59 – Dépréciations et provisions pour risques à court terme (Trésorerie).
Le compte 67 – FRAIS FINANCIERS ET CHARGES ASSIMILEES est crédité pour solde à la clôture de l''exercice
• par le débit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 67 – FRAIS FINANCIERS ET CHARGES ASSIMILEES ne doit pas servir à enregistrer :
• les remboursements d''emprunts contractés ou d''avances reçues
• les intérêts intercalaires d''emprunts dus au titre de la période de construction et de mise en route des
immobilisations
• les commissions et courtages bancaires, rémunérations de services
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 16 – Emprunts et dettes assimilées
• comptes de la classe 2 concernés
• 631 – Frais bancaires', controle = 'Le compte 67 – FRAIS FINANCIERS ET CHARGES ASSIMILEES peut être contrôlé à partir des relevés de banque et
décomptes d''intérêt.' WHERE numero = '67';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre, au titre de l''exercice, les dotations aux amortissements, d''exploitation et à caractère financier, dans
leur conception économique et comptable (et non pas fiscale).', commentaires = 'L''amortissement consiste pour l''entité à répartir le montant amortissable du bien sur sa durée d''utilité selon un plan
prédéfini.
Le montant du bien amortissable s''entend de la différence entre le coût d''entrée d''un actif et sa valeur résiduelle
prévisionnelle.
Le choix du mode de répartition (linéaire, dégressif, variable...) relève de la politique et de la gestion de l''entité, dans le
respect des principes comptables retenus.
Le compte 68 est destiné à enregistrer, à la clôture de l''exercice, les charges "calculées" de la période.
Le compte 68 enregistre aussi le complément éventuel d''amortissement relatif aux immobilisations cédées, mises hors
service ou au rebut.
Lorsque les dispositions fiscales en vigueur autorisent des méthodes d''amortissements accélérés (amortissement
dégressif, etc.) et imposent la comptabilisation effective des amortissements fiscaux pratiqués, il importe de faire apparaître
distinctement l''amortissement technique et économique normal au compte 68. Le complément d''amortissement fiscal
autorisé figure au débit du compte 85 – Dotations H.A.O., par le crédit du compte 151 – Amortissements dérogatoires.', fonctionnement = 'Le compte 68 – DOTATIONS AUX AMORTISSEMENTS est débité du montant des dotations de la période
• par le crédit des comptes d''amortissements pour le montant de la dépréciation économique de la période
Le compte 68 – DOTATIONS AUX AMORTISSEMENTS est crédité pour solde à la clôture de l''exercice
• par le débit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 68 – DOTATIONS AUX AMORTISSEMENTS ne doit pas servir à enregistrer :
• les dotations aux provisions
• les charges provisionnées
• les dotations aux amortissements H.A.O.
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 69 – Dotations aux provisions et aux dépréciations
• 659 – Charges pour dépréciations et provisions pour risques à court terme d''exploitation
• 679 – Charges pour dépréciations et provisions pour risques à court terme financières
• 852 – Dotations aux amortissements H.A.O.', controle = 'Le compte 68 – DOTATIONS AUX AMORTISSEMENTS peut être contrôlé à partir des plans et tableaux
d''amortissement.' WHERE numero = '68';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre, au titre de l''exercice, les dotations aux provisions d''exploitation et à caractère financier, en
couverture de dépréciations, risques, charges ou pertes à prévoir.', commentaires = 'La comptabilisation des provisions se fait selon les règles suivantes :
• les provisions sont créées ou ajustées en hausse en débitant le compte 69 – Dotations aux provisions et par le crédit du
compte de provision concerné (19 ou 29) ;
• les provisions sont ajustées en baisse ou annulées en débitant le compte de provision (19 ou 29) par le crédit du
compte 79 – Reprises de provisions, de dépréciations et autres ;
• lorsqu''un risque ou une charge provisionnée se réalise, il est régulièrement comptabilisé dans un compte approprié de
la classe 6 ou 8.
En conséquence, la provision est reprise intégralement (débit : compte 19 ou 29 ; crédit : compte 79).', fonctionnement = 'Le compte 69 – DOTATIONS AUX PROVISIONS ET AUX DÉPRÉCIATIONS est débité du montant des dotations de
l''exercice
• par le crédit des comptes de provisions (19 et 29).
Le compte 69 – DOTATIONS AUX PROVISIONS ET AUX DÉPRÉCIATIONS est crédité
• par le débit du compte 13 – Résultat net de l''exercice pour solde à la clôture de l''exercice.', exclusions = 'Le compte 69 – DOTATIONS AUX PROVISIONS ET AUX DÉPRÉCIATIONS ne doit pas servir à enregistrer :
• les dotations aux provisions H.A.O.
• les charges à la clôture de l''exercice correspondant à la dépréciation probable constatée sur les éléments de l''actif
circulant (stocks, clients)
• les charges correspondant à la dépréciation probable constatée sur les éléments de trésorerie
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 85 – Dotations H.A.O.
• 659 – Charges pour dépréciations et provisions pour risques à court terme d''exploitation
• 679 – Charges pour dépréciations et provisions pour risques à court terme financières', controle = 'Le compte 69 – DOTATIONS AUX PROVISIONS ET AUX DEPRECIATIONS peut être contrôlé à partir de tous
documents susceptibles d''éclairer le jugement sur les charges à prévoir par suite de dépréciation d''éléments d''actif ou
les risques attachés à des événements ou opérations intervenus au cours de l''exercice.
COMPTES DE PRODUITS DES ACTIVITÉS ORDINAIRES
Les produits sont généralement pris en compte lorsqu''une augmentation des retombées économiques, liée à une
augmentation d''actif ou à une diminution de passif, s''est produite et qu''elle peut être mesurée de façon fiable.
La comptabilisation des produits résultant de contrats avec les clients doit traduire le transfert à un client du contrôle
d''un bien ou d''un service pour le montant auquel le vendeur s''attend à avoir droit. En général,
Le transfert est opéré à la date de livraison du bien ou de l''achèvement de la prestation de service.
Les comptes de la classe 7 enregistrent les produits liés à l''activité ordinaire de l''entité. Ils résultent en principe de la
vente de biens ou de services, de la production de biens ou de services non encore vendus ou livrés à soi-même.
Doivent être rattachés à l''exercice, tous les produits le concernant effectivement et ceux-là seulement. A la clôture de
l''exercice, ces produits donnent éventuellement lieu à régularisation.
COMPTES DE LA CLASSE 7
70    Ventes                                                126   759   Reprises de charges pour dépréciations et           133
71    Subventions d''exploitation                            128         provisions pour risques à court terme
d''exploitation
72    Production immobilisée                                129
77    Revenus financiers et produits assimilés            134
73    Variations des stocks de biens et services produits   130
78    Transferts de charges                               136
75    Autres produits (sauf compte 759)                     132
79    Reprises de provisions, dépréciations et autres     136' WHERE numero = '69';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre les ressources de l''entité provenant de la vente des marchandises, des travaux effectués et des
services rendus à des tiers.', commentaires = 'Le compte 701 est ouvert par les entités commerciales.
Les entités qui ont seulement une activité industrielle utilisent les comptes 702 à 705.
Les ventes sont comptabilisées dans l''entité selon une nomenclature compatible avec la nomenclature de biens et services
en usage dans chacun des Etats-parties.
Le prix de vente s''entend du prix facturé, le cas échéant, net de taxes collectées, déductions faites des rabais et remises
lorsqu''ils sont déduits sur la facture elle- même.
Même lorsqu''ils sont déduits sur la facture de vente, les escomptes de règlement sont comptabilisés au débit du compte
673 – Escomptes accordés.', fonctionnement = 'Le compte 70 – VENTES est crédité du montant des facturations hors taxes si les taxes sont facturées.
Le compte 443 – VENTES est crédité du montant des taxes si les taxes collectées sont facturées.
• par le débit du compte 41 – Clients et comptes rattachés ;
• ou par le débit d''un compte de trésorerie.
Le compte 70 – VENTES est débité des retours hors taxes facturées sur ventes et des rabais, remises et ristournes
accordés hors factures aux clients
Le compte 441 – ETAT TVA FACTUREE est débité des taxes facturées des retours sur ventes et des rabais, remises
et ristournes accordés hors factures aux clients
• par le crédit du compte client 41– Clients et comptes rattachés ;
• ou par le crédit du compte 13 – Résultat net de l''exercice, pour solde du compte 70 à la clôture de l''exercice', exclusions = 'Le compte 70 – VENTES ne doit pas servir à enregistrer :
• les subventions d''exploitation compensatrices d''insuffisances de tarifs
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 71 – Subventions d''exploitation', controle = 'Le compte 70 – VENTES peut être contrôlé à partir
• des factures de ventes ;
• des factures d''avoirs ;
• de la vérification des marges.' WHERE numero = '70';
UPDATE comptes_ohada SET contenu = 'Ce sont des aides financières accordées par l''Etat, des collectivités publiques ou des tiers, qui ne sont ni des fonds de
dotation, ni des subventions d''investissement. Elles sont destinées à compenser l''insuffisance du prix de vente administré,
ou à faire face à des charges d''exploitation.', commentaires = 'Les subventions d''exploitation ne doivent pas être confondues avec les subventions d''investissement ou d''équilibre.
Elles peuvent être accordées sous des formes variées : primes d''embauche, primes de création d''emplois.', fonctionnement = 'Le compte 71 – SUBVENTIONS D''EXPLOITATION est crédité des subventions acquises
• par le débit du compte 4495 – Etat, subventions d''exploitation à recevoir
• ou par le débit du compte 4582 – Organismes internationaux, subventions à recevoir ;
• et par le débit des comptes de trésorerie concernés à la date de l''encaissement.
Le compte 71 – SUBVENTIONS D''EXPLOITATION est débité pour solde à la clôture de l''exercice
• par le crédit du compte 13 – Résultat net de l''exercice', exclusions = 'Le compte 71- SUBVENTIONS D''EXPLOITATION ne doit pas servir à enregistrer :
• les aides accordées par les collectivités publiques et organismes internationaux ayant le caractère de fonds de
dotation
• les subventions accordées en vue d''acquérir, de créer, de remplacer et de mettre en l''état des immobilisations
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 102 – Capital par dotation
• 14 – Subventions d''investissement', controle = 'Le compte 71 – SUBVENTIONS D''EXPLOITATION peut être contrôlé à partir des courriers d''octroi des subventions.' WHERE numero = '71';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre le coût de production des travaux faits par l''entité pour elle-même.', commentaires = 'Le compte 72 – Production immobilisée enregistre les travaux effectués par l''entité pour elle – même au coût de production
déterminé par la comptabilité analytique de gestion ou à défaut par des calculs extra- comptables.
Les calculs extra-comptables doivent néanmoins cerner le coût de production des biens concernés en intégrant tous les
intrants, notamment:
• le coût d''acquisition des matériaux consommés pour la production des biens ;
• les autres coûts engagés sous forme de charges directes de production et des charges indirectes rattachables ;
• les frais financiers supportés sur les emprunts exclusivement affectés au financement de la fabrication des biens
concernant la période de fabrication.
La production auto-consommée est constituée par des consommations prélevées sur la production de l''exploitation, sans
contrepartie monétaire, par l''exploitant, sa famille, et les salariés.
En raison de son importance, cette autoconsommation est enregistrée au crédit du compte 724 Production immobilisée et
autoconsommée par le débit :
• du compte 104 Compte de l''exploitant;
• du compte 6617 et 6627 Avantages en nature dans le cas de consommation des salariés.', fonctionnement = 'Le compte 72 – PRODUCTION IMMOBILISEE est crédité
• par le débit du compte 21 – Immobilisations incorporelles
• ou par le débit du compte 23 – Bâtiments, installations techniques et agencements ; ou 24 – Matériel, mobilier et
actifs biologiques.
Le compte 72 – PRODUCTION IMMOBILISEE est débité pour solde à la clôture de l''exercice
• par le crédit du compte 13 – Résultat net de l''exercice', exclusions = NULL, controle = 'Le compte 72 – PRODUCTION IMMOBILISEE peut être contrôlé à partir:
• des immobilisations portées à l''actif ;
• des charges saisies par la comptabilité analytique.' WHERE numero = '72';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre les variations de stocks de biens et de services produits en retraçant les opérations relatives aux
entrées en stocks, aux sorties de stocks et aux différences constatées à la clôture de l''exercice entre l''inventaire comptable
permanent et l''inventaire physique et, dans le cas de l''inventaire intermittent, le stock initial et le stock final, ou leur
différence.', commentaires = 'Le compte 73 donne par son solde la variation des stocks de produits fabriqués, pour l''exercice considéré. Créditeur, il
représente l''augmentation globale de ces stocks du début à la fin de l''exercice ; débiteur, il représente la diminution de ces
stocks (déstockage) ; production de l''entité, stockée au cours de la période.
Dans ce cas, il est porté en négatif du côté des produits dans le Compte de résultat.', fonctionnement = 'En cas de tenue d''inventaire intermittent :
A la clôture de l''exercice, le compte 73 – VARIATIONS DES STOCKS DE BIENS ET SERVICES PRODUITS est
débité de la valeur du stock initial (1) pour solde
• par le crédit des comptes de stocks concernés
(1) ou de la diminution de l''exercice (stock initial moins stock final)
A la clôture de l''exercice, le compte 73 – VARIATIONS DES STOCKS DE BIENS ET SERVICES PRODUITS est
crédité de la valeur du stock final (1)
• par le débit des comptes de stocks concernés
A la clôture de l''exercice, le compte 73 – VARIATIONS DES STOCKS DE BIENS ET SERVICES PRODUITS est viré,
pour solde, avec les produits dans le compte 13 – Résultat net de l''exercice (montant débiteur ou montant créditeur,
selon le cas).
En cas d''inventaire permanent :
En cours d''exercice, le compte 73 – VARIATIONS DES STOCKS DE BIENS ET SERVICES PRODUITS est crédité,
des entrées en stocks (en-cours, produits fabriqués)
• par le débit des comptes de stocks concernés
En cours d''exercice, le compte 73 – VARIATIONS DES STOCKS DE BIENS ET SERVICES PRODUITS est débité
des sorties de stocks (virements d''en-cours en produits fabriqués ou du fait de ventes)
• par le crédit des comptes de stocks concernés
A la clôture de l''exercice, le compte 73 – VARIATIONS DES STOCKS DE BIENS ET SERVICES PRODUITS est
crédité des différences en plus constatées entre l''inventaire comptable et l''inventaire physique
• par le débit des comptes de stocks.
(1) ou de l''augmentation de l''exercice (stock final moins stock initial)
A la clôture de l''exercice, le compte 73 – VARIATIONS DES STOCKS DE BIENS ET SERVICES PRODUITS est
débité des différences en moins constatées entre l''inventaire comptable et l''inventaire physique
• par le crédit des comptes de stocks
A la clôture de l''exercice, le compte 73 – VARIATIONS DES STOCKS DE BIENS ET SERVICES PRODUITS est viré,
pour solde, avec les produits.
dans le compte 13 – Résultat net de l''exercice (montant débiteur ou montant créditeur, selon le cas)', exclusions = 'Le compte 73 – VARIATION DE STOCKS DE BIENS ET SERVICES PRODUITS ne doit pas servir à enregistrer :
• la variation de la période, afférente aux stocks de marchandises, de matières, de fournitures et d''emballages
commerciaux
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• comptes 603 – Variations des stocks de biens achetés', controle = 'Le compte 73 – VARIATIONS DES STOCKS DE BIENS ET SERVICES PRODUITS peut être contrôlé à partir:
• des fiches d''inventaire ;
• de l''évaluation des stocks ;
• de la comptabilité analytique.' WHERE numero = '73';
UPDATE comptes_ohada SET contenu = 'Ce sont tous les produits divers qui ne proviennent pas directement de l''activité productrice ou commerciale de l''entité, ni
de son activité financière ou de ses relations avec l''Etat (subventions) mais qui relèvent néanmoins de ses activités
ordinaires.
Ce compte enregistre les annulations ou les régularisations en baisse des provisions à court terme sur éléments de l''actif
circulant et des risques provisionnés', commentaires = 'La quote-part de résultat sur opérations faites en commun est la reprise dans la comptabilité de l''entité du résultat obtenu
dans le cadre d''une autre structure (société en participation ... ) à laquelle l''entité est associée, mais qui est juridiquement
transparente.
Le compte 752 – Quote-part de résultats sur opérations faites en commun enregistre:
• pour l''entité non gérante, sa participation aux bénéfices ;
• pour l''entité gérante, le montant des pertes mises à la charge des associés non gérants.
Le compte 754 – Produits des cessions courantes d''immobilisations enregistre le prix de cession des immobilisations
lorsque ces cessions présentent un caractère ordinaire en raison des politiques de désinvestissement et de renouvellement
des immobilisations.
Le compte 758 – Produits divers enregistre les autres produits non imputables aux autres subdivisions du compte 75.
Le compte 759 reprend en fin d''exercice tout ou partie des dépréciations et provisions à court terme devenues sans objet
ou pour toute autre cause justifiant la régularisation en baisse.
Symétriquement aux charges correspondant à des décaissements probables à brève échéance, les reprises doivent être
traitées comme des encaissements probables.', fonctionnement = 'Le compte 75 – AUTRES PRODUITS est crédité du montant des produits
• par le débit des comptes de tiers concernés ou des comptes de trésorerie
Le compte 75 – AUTRES PRODUITS est débité pour solde à la clôture de l''exercice
• par le crédit du compte 13 – Résultat net de l''exercice
Le compte 759 – REPRISES DE CHARGES POUR DEPRECIATIONS ET PROVISIONS POUR RISQUES A COURT
TERME D''EXPLOITATION est crédité du montant des dépréciations d''actif circulant et des risques provisionnés
existant à l''ouverture de l''exercice.
• par le débit du compte 39 – Dépréciations des stocks et en cours ou par le débit du compte 49 – Dépréciations et
provisions pour risques à court terme (Tiers).
Le compte 759 – REPRISES DE CHARGES POUR DEPRECIATIONS ET DE PROVISIONS POUR RISQUES A
COURT TERME D''EXPLOITATION est débité pour solde à la clôture de l''exercice
• par le crédit du compte 13 – Résultat net de l''exercice', exclusions = 'Le compte 75 – AUTRES PRODUITS (sauf 759) ne doit pas servir à enregistrer :
• les rabais, remises et ristournes accordés, hors factures, aux clients
Il convient dans les cas d''espèce d''utiliser le compte ci-après :
• compte 70 – Ventes
Le compte 759 – REPRISES DE CHARGES POUR DEPRECIATIONS ET PROVISIONS POUR RISQUES A COURT
TERME D''EXPLOITATION ne doit pas servir à enregistrer :
• les reprises de dépréciations d''éléments de l''actif immobilisé
• les reprises de dépréciations d''éléments à caractère financier
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 791 Reprises de charges pour dépréciations et provisions pour risques à court terme d''exploitation
• 797 Reprises de charges pour dépréciations et provisions pour risques à court terme financières', controle = 'Le compte 75 – AUTRES PRODUITS peut être contrôlé à partir :
• des factures,
• des avis bancaires ;
• des correspondances échangées.
Reprises de charges pour dépréciations et provisions pour risques à court
759
terme d''exploitation
Le compte 759 – REPRISES DE CHARGES POUR DEPRECIATIONS ET PROVISIONS POUR RISQUES A COURT
TERME D''EXPLOITATION peut être contrôlé à partir du relevé des décisions de gestion des organes compétents.' WHERE numero = '75';
UPDATE comptes_ohada SET contenu = 'Ce sont les ressources que tire l''entité de ses activités financières.', commentaires = 'Les intérêts et dividendes reçus de l''étranger sont comptabilisés distinctement de ceux acquis dans l''Etat.
La subdivision utilisée par l''entité doit permettre de raccorder les revenus financiers et produits assimilés aux prêts ou
avances et titres auxquels ils se rapportent.', fonctionnement = 'Le compte 77 (sauf 779) – REVENUS FINANCIERS ET PRODUITS ASSIMILES est crédité du montant des produits
financiers acquis
• par le débit des comptes de tiers concernés ou des comptes de trésorerie
Le compte 779 – REPRISES DE CHARGES POUR DEPRECIATIONS ET PROVISIONS POUR RISQUES A COURT
TERME FINANCIERES est crédité de la reprise des dépréciations des comptes de trésorerie et des provisions pour
risques à court terme à caractère financier sans objet, existant au début de l''exercice
• par le débit du compte 59 – Dépréciations et provisions pour risques à court terme (Trésorerie), pour solde ou pour
rajustement.
Le compte 77 – REVENUS FINANCIERS ET PRODUITS ASSIMILES est débité
• par le crédit du compte 13 – Résultat net de l''exercice, pour solde du compte 77 en fin d''exercice', exclusions = 'Le compte 77 – REVENUS FINANCIERS ET PRODUITS ASSIMILES ne doit pas servir à enregistrer :
• les récupérations de prêts ou d''avances consenties
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 27 – Autres immobilisations financières', controle = 'Le compte 77 – REVENUS FINANCIERS ET PRODUITS ASSIMILES peut être contrôlé à partir:
• de virements bancaires ;
• de décompte d''intérêts ;
• de factures avec escompte ;
• de bordereaux de cession de titres ;
• d''encaissement des coupons.' WHERE numero = '77';
UPDATE comptes_ohada SET contenu = NULL, commentaires = 'Les transferts de charges concernent les dépenses de l''entité mises à la charge de tiers (remboursement de débours et
frais divers) et pouvant aussi le cas échéant être opérés vers d''autres comptes de charges (exemple : avantages en nature
accordés au personnel).
Les transferts de charges sont à mentionner dans les Notes annexes.', fonctionnement = 'Le compte 78 – TRANSFERTS DE CHARGES est crédité du montant des charges d''exploitation ou financières à
transférer
• par le débit des comptes de bilan concernés (autre que les comptes d''immobilisation)
• par le débit des comptes de charges concernés (en cas de transfert de charges à charges)
Le compte 78 – TRANSFERTS DE CHARGES est débité pour solde à la clôture de l''exercice
• par le crédit du compte 13 – Résultat net de l''exercice', exclusions = 'Le compte 78 – TRANSFERTS DE CHARGES ne doit pas servir à enregistrer
• les transferts de charges en actif immobilisé
• les transferts de charges H.A.O
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 72 – Production immobilisée
• 848 – Transferts de charges H.A.O.', controle = 'Le compte 78 – TRANSFERTS DE CHARGES peut être contrôlé à partir du relevé des décisions de gestion des
organes compétents.' WHERE numero = '78';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre les annulations et les rajustements en baisse des provisions financières pour risques et charges,
ainsi que des provisions pour dépréciation des éléments de l''actif immobilisé.', commentaires = 'Les reprises de provisions constatent soit la diminution de la provision ramenée à un montant inférieur, soit l''intégration
dans les résultats de l''entité de la provision existante par suite de la réalisation ou de l''annulation de la charge ou de la
disparition du risque. Dans le cas exceptionnel d''une révision rétroactive du plan d''amortissement initial, la réduction du
cumul des amortissements est opérée par le crédit du compte 798 – Reprises d''amortissements
Le compte 799 – Reprises de subventions d''investissement enregistre à son crédit :
• soit un montant égal à celui de la dotation de l''exercice aux comptes d''amortissements des immobilisations
amortissables acquises ou créées au moyen de ladite subvention, affecté du coefficient résultant du rapport :
Montant de la subvention/Montant de l''investissement correspondant ;
• soit une somme déterminée en fonction du nombre d''années pendant lesquelles les immobilisations non amortissables
créées ou acquises au moyen de ladite subvention sont inaliénables aux termes du contrat ou, à défaut de clause
d''inaliénabilité dans le contrat, une somme égale au dixième du montant de la subvention.', fonctionnement = 'Le compte 79 (sauf 799) – REPRISES DE PROVISIONS, DE DEPRECIATIONS ET AUTRES est crédité
• par le débit des comptes (19, 29) pour le montant des diminutions des provisions, par suite d''annulation ou de
réduction.
Le compte 799 – REPRISES DE SUBVENTION D''INVESTISSEMENT est crédité du montant de la subvention
d''investissement reprise au résultat
• par le débit du compte 14 – Subventions d''investissement.
Le compte 79 – REPRISES DE PROVISIONS, DE DEPRECIATIONS ET AUTRES est débité pour solde à la clôture
de l''exercice
• par le crédit du compte 13 – Résultat net de l''exercice', exclusions = 'Le compte 79 – REPRISES DE PROVISIONS, DE DEPRECIATIONS ET AUTRES ne doit pas servir à enregistrer :
• les reprises HAO
• les reprises de charges provisionnées
Il convient dans le cas d''espèce d''utiliser les comptes ci-après :
• 86 Reprises HAO
• 759 Reprises de charges pour dépréciations et provisions pour risques à court terme d''exploitation
• 779 Reprises de charges pour dépréciations et provisions pour risques à court terme financières
• 849 Reprises de charges pour dépréciations et provisions pour risques à court terme HAO', controle = 'Le compte 79 – REPRISES DE PROVISIONS, DE DEPRECIATIONS ET AUTRES peut être contrôlé à partir du relevé
des décisions des organes compétents.
COMPTES DES AUTRES CHARGES ET DES AUTRES
PRODUITS (H.A.O.)
L''utilisation de la classe 8 permet d''enregistrer les charges et les produits correspondant à des opérations qui ne se
rapportent pas à l''activité ordinaire de l''entité.
Les produits et les charges hors activité ordinaire (H.A.O.) sont liés à des changements de structure (significatifs) ou de
stratégie de l''entité, aux cessions, changements importants dans l''environnement (exemple: modification de la
législation commerciale qui impliquera sans doute un changement de la stratégie et des investissements de la firme) ou
à des événements extraordinaires (tels les phénomènes naturels : sécheresse, inondations, tempêtes, raz-de-marée,
tremblements de terre, vols de criquets ...)
Les sorties et les prix de cession des d''immobilisations sont des charges et produits HAO. Par contre, les opérations
relativement légères et régulières d''investissement – financement (renouvellement du « parc » de matériel sans
novation profonde) ne doivent donc pas être traitées en « H.A.O. », mais être «remontées » dans les activités
ordinaires.
Les produits et les charges H.A.O. sont des charges et des produits non récurrents, de nature non liés à l''activité
ordinaire de l''entité. Ces charges et produits HAO ne doivent pas être définis en termes moraux, d''opportunité ou
exceptionnels.
Une charge ou un produit d''exploitation d''un montant exceptionnellement élevé doit rester inscrite dans le niveau
«ordinaire » (exemple : grosse perte sur une importante créance clients). Le niveau significativement élevé de la charge
ou du produit sera signalé, avec ses conséquences, dans les Notes annexes.
Une charge ou un produit d''exploitation omis au cours d''un exercice antérieur doit être comptabilisé dans les charges
ou les produits des Activités ordinaires de l''exercice de rectification.
Figurent également dans cette classe la participation des travailleurs aux bénéfices et l''impôt sur le résultat.
COMPTES DE LA CLASSE 8
81    Valeurs comptables des cessions d''immobilisations   139   86   Reprises hors activités ordinaires               144
82    Produits des cessions d''immobilisations             140   87   Participation des travailleurs                   146
83    Charges hors activités ordinaires                   141   88   Subventions d''équilibre                          146
84    Produits hors activités ordinaires                  143   89   Impôts sur le résultat                           147
85    Dotations hors activités ordinaires                 144' WHERE numero = '79';
UPDATE comptes_ohada SET contenu = 'Ce compte sert à déterminer la valeur comptable nette des éléments de l''actif immobilisé cédés. Pour les biens non
amortissables, cette valeur est la valeur d''entrée, sans déduction des éventuelles dépréciations. Pour les biens
amortissables, elle est la différence entre la valeur d''entrée brute des immobilisations cédées et le cumul des
amortissements pratiqués depuis l''entrée du bien dans le patrimoine de l''entité jusqu''à la date de sa cession.', commentaires = 'Par cession, il faut entendre : vente, échange, mise au rebut ou destruction.
La sortie d''une immobilisation du patrimoine de l''entité donne lieu à :
• constatation de l''amortissement complémentaire pour la période écoulée entre l''ouverture de l''exercice et la date de
cession du bien ;
• enregistrement de la sortie du bien pour sa valeur nette au compte 81 sous la forme d''une double écriture :
• comptabilisation de la valeur de sortie si celle-ci est supérieure à zéro, au compte 82 – Produits des cessions
d''immobilisations.
Les cessions d''immobilisations considérées comme courantes (fréquentes et récurrentes) ne sont pas enregistrées à ce
niveau H.A.O., mais dans les comptes 654 (Valeur comptable) et 754 (Prix de cession) ; exemples : transporteurs ; loueurs
de matériels ...', fonctionnement = 'Le compte 81 – VALEURS COMPTABLES DES CESSIONS D''IMMOBILISATIONS est débité de la valeur d''entrée
des éléments sortis sous déduction des amortissements pratiqués
• par le crédit du compte d''immobilisation concerné (classe 2).
Le compte 81 – VALEURS COMPTABLES DES CESSIONS D''IMMOBILISATIONS est crédité pour solde à la clôture
de l''exercice
• par le débit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 81 – VALEURS COMPTABLES DES CESSIONS D''IMMOBILISATIONS ne doit pas servir à enregistrer :
• les dépréciations afférentes aux éléments d''actif immobilisé cédés
• les cessions considérées comme courantes, compte tenu de l''activité de l''entité
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 29 – Dépréciations
• 654 – Valeurs comptables des cessions courantes d''immobilisations', controle = 'Le compte 81 – VALEURS COMPTABLES DES CESSIONS D''IMMOBILISATIONS peut être contrôlé à partir :' WHERE numero = '81';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre le produit net de la cession : dans le cas de vente, prix résultant de l''accord entre les cocontractants
et figurant sur l''acte de vente diminué des commissions et des frais de vente ; dans le cas d''apport, montant contractuel,
etc.', commentaires = 'En cas de versement d''indemnité d''assurance pour réparation, celle-ci figurera au crédit du compte 82, même si l''entité
prend la décision de ne pas effectuer de réparation et de mettre l''immobilisation au rebut ou de la céder en l''état (le prix de
vente net viendrait dans ce cas en complément au crédit du compte 82).
L''indemnité d''assurance perçue au cas où le bien est détruit est assimilée au prix de cession.
Le produit des cessions considérées comme "courantes" (cf. compte 81) est enregistré au crédit du compte 754 (niveau
Exploitation).', fonctionnement = 'Le compte 82 – PRODUITS DES CESSIONS D''IMMOBILISATIONS est crédité des produits de cession d''actif, nets
de commissions et des frais de vente
• par le débit du compte de tiers 485 – Créances sur cessions d''immobilisations ou par le débit d''un compte de
trésorerie
Le compte 82 – PRODUITS DES CESSIONS D''IMMOBILISATIONS est crédité de l''indemnité d''assurance perçue
pour indemnisation d''une destruction d''immobilisation
• par le débit du compte de tiers 485 – Créances sur cessions d''immobilisations ou par le débit d''un compte de
trésorerie
Le compte 82 – PRODUITS DES CESSIONS D''IMMOBILISATIONS est débité pour solde du compte à la clôture de
l''exercice
• par le crédit du compte 13 – Résultat net de l''exercice', exclusions = 'Le compte 82 – PRODUITS DES CESSIONS D''IMMOBILISATIONS ne doit pas servir à enregistrer :
• les indemnités d''assurances autres que celles représentatives de l''indemnisation du bien détruit
• les produits des cessions courantes d''immobilisations
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 7582 – Indemnités d''assurances reçues
• 754 – Produits des cessions courantes d''immobilisations', controle = 'Le compte 82 – PRODUITS DES CESSIONS D''IMMOBILISATIONS peut être contrôlé à partir :' WHERE numero = '82';
UPDATE comptes_ohada SET contenu = 'Ce sont les charges qui ne sont pas liées à l''activité ordinaire de l''entité et qui, de ce fait, n''ont généralement pas de
caractère récurrent. Elles comprennent des charges constatées et des charges pour dépréciations et provisions pour
risques à court terme.', commentaires = 'Seules les charges liées à la restructuration de l''entité ou à des événements extraordinaires (tels les phénomènes naturels :
Sécheresse, inondations tempêtes, raz-de-marée, tremblements de terre, vols de criquets ...) doivent être considérées
comme relevant des activités autres que ordinaires.
Toute autre charge est ordinaire, y compris, par exemple, les amendes fiscales ou pénales. Il en est de même des charges
sur exercices antérieurs liées aux activités courantes de l''entité.
Lorsque la réalisation de la charge H.A.O., bien qu''incertaine, est envisagée à court terme, elle constitue une charge
provisionnée.
Lorsque, au contraire, cette charge probable est envisagée à plus d''un an, elle doit faire l''objet d''une dotation aux
provisions.', fonctionnement = 'Le compte 83 – CHARGES HORS ACTIVITES ORDINAIRES est débité des charges constatées ne concernant pas
l''activité ordinaire de l''entité
• par le crédit d''un compte de tiers ou de trésorerie.
Le compte 83 – CHARGES HORS ACTIVITES ORDINAIRES est débité des charges hors activités ordinaires non
encore engagées, mais dont la survenance à moins d''un an est probable et mesurable
• par le crédit du compte 48 – Créances et dettes H.A.O.
Le compte 83 – CHARGES HORS ACTIVITES ORDINAIRES est crédité pour solde à la clôture de l''exercice
• par le débit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 83 – CHARGES HORS ACTIVITES ORDINAIRES ne doit pas servir à enregistrer :
• les provisions pour risques et charges hors activités ordinaires à plus d''un an
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 854 – Dotations aux provisions pour risques et charges H.A.O.', controle = 'Le compte 83 – CHARGES HORS ACTIVITES ORDINAIRES peut être contrôlé à partir :' WHERE numero = '83';
UPDATE comptes_ohada SET contenu = 'Ce sont des produits qui ne sont pas liés à l''activité ordinaire de l''entité et sont donc dépourvus de caractère récurrent. Ils
comprennent des produits constatés, des reprises de charges provisionnées et des transferts de charges.', commentaires = 'Les produits sont considérés comme
H.A.O. lorsqu''ils relèvent d''événements extraordinaires, liés notamment à des phénomènes naturels ou à des modifications
de structure de l''entité.', fonctionnement = 'Le compte 84 – PRODUITS HORS ACTIVITES ORDINAIRES est crédité du montant des produits constatés
• par le débit d''un compte de tiers ou de trésorerie.
Le compte 84 – PRODUITS HORS ACTIVITES ORDINAIRES est crédité des reprises de charges provisionnées
• par le débit du compte 4998 – Provisions pour risques à court terme sur opérations H.A.O.
Le compte 84 – PRODUITS HORS ACTIVITES ORDINAIRES est débité pour solde du compte à la clôture de
l''exercice
• par le crédit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 84 – PRODUITS HORS ACTIVITES ORDINAIRES ne doit pas servir à enregistrer :
• les reprises de provisions H.A.O. antérieurement constituées
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 86 – Reprises H.A.O.', controle = 'Le compte 84 – PRODUITS HORS ACTIVITES ORDINAIRES peut être contrôlé à partir de l''analyse, des factures,
des évaluations et des tableaux de provisions.' WHERE numero = '84';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre les dotations aux amortissements et aux provisions qui ne concernent pas l''activité ordinaire de
l''entité.', commentaires = 'La notion "hors activités ordinaires" doit être appréhendée de façon restrictive : restructurations d''entités par exemple,
autres événements (telles les catastrophes naturelles) par essence non prévisibles, et dépourvus de caractère récurrent. Le
compte 85 est aussi utilisé dans les Etats- parties pour enregistrer les opérations liées à l''application de dispositions
fiscales (provisions réglementées ...).', fonctionnement = 'Le compte 85 – DOTATIONS HORS ACTIVITES ORDINAIRES est débité du montant de la provision pour risques ou
charges H.A.O. ou de l''amortissement
• par le crédit du compte 15 – Provisions réglementées et Fonds assimilés ;
• par le crédit du compte 19 – Provisions financières pour risques et charges ou du compte 29 – Dépréciations ;
• par le crédit du compte 28 – Amortissements.
Le compte 85 – DOTATIONS HORS ACTIVITES ORDINAIRES est crédité pour solde à la clôture de l''exercice
• par le débit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 85 – DOTATIONS HORS ACTIVITES ORDINAIRES ne doit pas servir à enregistrer :
• les charges calculées H.A.O. à court terme (moins d''un an)
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 839 – Charges pour dépréciations et provisions pour risques à court terme H.A.O.', controle = 'Le compte 85 – DOTATIONS HORS ACTIVITES ORDINAIRES peut être contrôlé à partir de l''évaluation de la
provision.' WHERE numero = '85';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre les annulations et rajustements en baisse des provisions, amortissements et subventions qui ne sont
pas liés à l''activité ordinaire de l''entité.', commentaires = 'Toute provision constituée par l''intermédiaire du compte 85 – Dotations hors activités ordinaires doit être reprise au cours
de l''un des exercices suivants par le compte 86 – Reprises de dotations
H.A.O. ; cette reprise se produisant l''année de survenance de la charge, ou l''année où l''appréciation en est modifiée.
Tel est le cas des Provisions réglementées (compte 15) comme des Provisions financières pour risques et charges (compte
19).
Des circonstances particulières peuvent justifier des mesures dérogatoires à ces dispositions générales.', fonctionnement = 'Le compte 86 – REPRISES HORS ACTIVITES ORDINAIRES est crédité de l''annulation ou de la réduction de la
provision concernée
• par le débit du compte 15 – Provisions réglementées et Fonds assimilés, du compte 19 – Provisions financières
pour risques et charges ou par le débit du compte 29 – Dépréciations.
Le compte 86 – REPRISES HORS ACTIVITES ORDINAIRES est débité pour solde à la clôture de l''exercice
• par le crédit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 86 – REPRISES HORS ACTIVITES ORDINAIRES ne doit pas servir à enregistrer :
• les reprises de dépréciations et provisions pour risques à court terme
• les reprises de dépréciations d''éléments de l''actif immobilisé
• les reprises de dotations à caractère financier
• les dotations aux provisions et aux dépréciations d''exploitation ou à caractère financier
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 759 – Reprises de charges pour dépréciations et provisions pour risques à court terme d''exploitation ou
• 779 – Reprises de charges pour dépréciations et provisions pour risques à court terme financières
• 791 – Reprises de provisions et de dépréciations d''exploitation
• 797 – Reprises de provisions et de dépréciations financières
• 691 – Dotations aux provisions et aux dépréciations d''exploitation ou
• 697 – Dotations aux provisions et aux dépréciations financières', controle = 'Le compte 86 – REPRISES HORS ACTIVITES ORDINAIRES peut être contrôlé à partir :' WHERE numero = '86';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre les montants prélevés sur les bénéfices réalisés et affectés par l''entité à un fonds légal ou contractuel
à l''avantage des travailleurs.', commentaires = 'En raison de son assiette de calcul, la "participation" n''est pas considérée comme une "charge de personnel" mais comme
un élément de répartition du résultat.', fonctionnement = 'Le compte 87 – PARTICIPATION DES TRAVAILLEURS est débité de la part de bénéfices affectée aux salariés au titre
de la participation
• par le crédit du compte 426 – Personnel, participation aux bénéfices.
Le compte 87 – PARTICIPATION DES TRAVAILLEURS est crédité pour solde de ce compte à la clôture de l''exercice
• par le débit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 87 – PARTICIPATION DES TRAVAILLEURS ne doit pas servir à enregistrer :
• la participation du personnel au capital de l''entité
• les rémunérations diverses versées au personnel (intéressement)
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 10 – Capital
• 66 – Charges de personnel', controle = 'Le compte 87 – PARTICIPATION DES TRAVAILLEURS peut être contrôlé à partir des conventions, des accords
d''entités.' WHERE numero = '87';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre le montant des subventions allouées par l''Etat ou l''un de ses démembrements à l''entité, pour lui
permettre de compenser, en totalité ou partiellement, des pertes survenues dans des circonstances exceptionnelles.', commentaires = 'Il importe, avant tout enregistrement, d''analyser la subvention pour en définir la finalité : aide à l''investissement, à
l''exploitation ou à l''équilibre.
Les subventions d''équilibre se distinguent des subventions d''exploitation en ce qu''elles ne sont pas directement liées à une
insuffisance des prix de vente imposés.', fonctionnement = 'Le compte 88 – SUBVENTIONS D''EQUILIBRE est crédité du montant des subventions d''équilibre allouées à l''entité
• par le débit d''un compte de tiers ou de trésorerie.
Le compte 88 – SUBVENTIONS D''EQUILIBRE est débité pour solde de ce compte à la clôture de l''exercice
• par le crédit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 88 – SUBVENTIONS D''EQUILIBRE ne doit pas servir à enregistrer :
• les subventions d''investissement
• les subventions d''exploitation
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 14 – Subventions d''investissement
• 71 – Subventions d''exploitation', controle = 'Le compte 88 – SUBVENTIONS D''EQUILIBRE peut être contrôlé à partir de décrets ou d''arrêtés ministériels, de
décisions de collectivités publiques accordant la subvention.' WHERE numero = '88';
UPDATE comptes_ohada SET contenu = 'C''est la part de bénéfice affectée obligatoirement à l''Etat au titre de l''impôt sur le résultat', commentaires = 'Le montant de l''impôt sur le résultat doit être calculé sur la base du résultat comptable retraité selon les règles fiscales.
Le compte 891 doit correspondre au montant total de l''impôt dû de l''exercice, quelles que soient les modalités de
règlement, éventuellement augmenté des rappels d''impôts et diminué des dégrèvements et des annulations sur des
exercices antérieurs', fonctionnement = 'Le compte 89 – IMPÔTS SUR LE RESULTAT est débité de l''impôt exigible
• par le crédit du compte 441 – Etat, impôt sur les bénéfices.
Le compte 89 – IMPÔTS SUR LE RESULTAT est crédité pour solde de ce compte à la clôture de l''exercice
• par le débit du compte 13 – Résultat net de l''exercice.', exclusions = 'Le compte 89 – IMPÔTS SUR LE RESULTAT ne doit pas servir à enregistrer :
• les impôts et taxes
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 64 – Impôts et taxes', controle = 'Le compte 89 – IMPÔTS SUR LE RESULTAT peut être contrôlé à partir :
COMPTES DES ENGAGEMENTS HORS BILAN ET
COMPTES DE LA COMPTABILITÉ ANALYTIQUE DE
GESTION
Sous-Section 1 : Comptes des engagements hors bilan (90-91)
L''usage de la classe 9 est facultatif. Toutefois, cette classe permet à l''entité d''enregistrer les engagements hors bilan et,
à ce titre, facilite la confection des Notes annexes.
Les engagements hors bilan représentent les droits et obligations de l''entité dont les effets chiffrables sur le montant et
la consistance du patrimoine sont subordonnés à la réalisation de conditions ou d''événements ultérieurs.
Pour être enregistrés, les engagements hors bilan doivent faire obligatoirement l''objet d''une convention écrite.
Les engagements hors bilan se distinguent en deux rubriques, engagements obtenus et engagements accordés ;
chaque rubrique est subdivisée selon les natures suivantes : engagements de financement, engagements de garantie,
engagements réciproques, autres engagements.
Les engagements obtenus, représentatifs de droits, s''enregistrent par convention et par analogie avec les créances du
bilan au débit des comptes 901 à 904.
Par analogie avec les dettes du bilan, les engagements accordés, qui constituent des obligations, s''enregistrent par
convention au crédit des comptes 905 à 908.
Les comptes de contrepartie des engagements hors bilan (obtenus et accordés) sont:911 à
Les entités doivent répartir par tous moyens techniques adéquats leurs engagements hors bilan, en fonction de la durée
initiale ainsi que de la qualité des bénéficiaires ou donneurs d''ordre.
Elles doivent également identifier les garanties obtenues couvrant les créances et les garanties accordées en
couverture des dettes figurant au bilan
COMPTES DE LA CLASSE 9
9011 Crédits confirmés obtenus                           150   9043/9048 Ventes avec clause de réserve de propriété ;    157
9012 Emprunts restant à encaisser                        150              Divers engagements obtenus
9013/9014/9018 Facilités de financement                  151
9051/9058 Crédits accordés non décaissés ; Autres         158
renouvelables ; Facilités d''émission ;                    engagements de financement accordés
Autres engagements de financement              9061 Avals accordés                                       159
obtenus                                        9062 Cautions, garanties accordées                        159
9021 Avals obtenus                                       152   9063 Hypothèques accordées                                160
9022 Cautions, garanties obtenues                        152   9064 Effets endossés par l''entité                         160
9023 Hypothèques obtenues                                153   9068 Autres garanties accordées                           161
9024 Effets endossés par des tiers                       154   9071 Ventes de marchandises à terme                       162
9028 Autres garanties obtenues                           155   9072 Ventes à terme de devises                            163
9031 Achats de marchandises à terme                      155   9073 Commandes fermes aux fournisseurs                    163
9032 Achats à terme de devises                           156   9078 Autres engagements réciproques                       163
9032/9033 Commandes fermes des clients ; Autres          156   9081 Annulations conditionnelles de dettes                164
engagements réciproques
9083/9088 Achats avec clause de réserve de propriété ;    164
9041 Abandons de créances conditionnels                  157              Divers engagements accordés
92—99 Comptabilité analytique de gestion                    165' WHERE numero = '89';
UPDATE comptes_ohada SET contenu = 'Le solde débiteur de ce compte représente la partie non utilisée des crédits qu''une banque s''est engagée, d''une façon
irrévocable, à accorder à l''entité, y compris les crédits documentaires import- export confirmés.', commentaires = 'Le crédit documentaire est une opération de crédit à court terme ayant pour objet le financement des transactions
commerciales internationales. Par l''intermédiaire de cet instrument financier, l''acheteur donne l''ordre à son banquier de
verser au banquier du vendeur la valeur des marchandises en cours de route, contre remise de documents prouvant
l''expédition et la conformité des marchandises.
Par l''ouverture de crédit documentaire, l''acheteur reçoit de son banquier l''engagement de régler au vendeur la valeur des
marchandises.', fonctionnement = 'A la notification du crédit confirmé, le compte 9011 – CRÉDITS CONFIRMÉS OBTENUS est débité
• par le crédit du compte de contrepartie 9lll
Lors de l''utilisation partielle ou totale du crédit confirmé, le compte 9011 – CRÉDITS CONFIRMES OBTENUS est
crédité
• par le débit du compte de contrepartie 9111', exclusions = 'Le compte 9011 – CREDITS CONFIRMES OBTENUS ne doit pas servir à enregistrer :
Il convient dans le cas d''espèce d''utiliser le compte ci-après : CREDOC', controle = 'Le compte 9011 – CRÉDITS CONFIRMÉS OBTENUS peut être contrôlé à partir des lettres de notification de la
banque.' WHERE numero = '9011';
UPDATE comptes_ohada SET contenu = 'Ce compte enregistre la partie non encore encaissée des emprunts contractés par l''entité auprès des tiers autres que les
établissements bancaires, notamment les filiales et les sociétés – mères.
Les facilités de financement renouvelables sont des contrats par lesquels un ensemble de banques (syndicat bancaire)
s''engage, pour une période donnée, envers un émetteur de titres (entité industrielle ou commerciale) soit à lui acheter tout
ou partie des titres qu''il pourrait émettre, soit à lui consentir un crédit d''un montant équivalent. Dans le cas d''émission de
billets de trésorerie, ces facilités prennent le nom de " lignes de substitution ".
Les facilités d''émission sont des contrats par lesquels un établissement de crédit s''engage, dans le cadre d''une émission
de titres, à consentir des concours de trésorerie à la société émettrice, sans pour autant acquérir les titres qui n''auraient
pas trouvé de preneurs sur le marché.
Les autres engagements de financement obtenus sont des engagements autres que les facilités de financement
renouvelables et les facilités d''émission.', commentaires = '" L''engagement à payer " représente, dans le cadre d''un crédit documentaire confirmé, l''engagement pris par la banque
(émettrice) de payer l''exportateur ou la banque de ce dernier sur simple présentation, à l''échéance, de l''acceptation.', fonctionnement = 'A la signature de la convention,
Le compte 9012 – EMPRUNTS RESTANT A ENCAISSER et débité
• par le crédit du compte de contrepartie 9112
Lors de la mobilisation partielle ou totale de l''emprunt,
Le compte 9012 – EMPRUNTS RESTANT A ENCAISSER est crédité
• par le débit du compte de contrepartie 9112
A la signature de la convention, les comptes 9013, 9014 et 9018 sont débités du montant des engagements obtenus
ou de la part non utilisée de ces engagements
• par le crédit respectif des comptes de contrepartie 9113, 9114 et 9118.
A l''échéance ou au dénouement des engagements, les comptes 9013, 9014 et 9018 sont crédités
• par le débit respectif des comptes de contrepartie 9113, 9114 et 9118.', exclusions = 'Le compte 9012 – EMPRUNTS RESTANT
A ENCAISSER ne doit pas servir à enregistrer :
• les crédits bancaires notifiés non encore encaissés
Il convient dans le cas d''espèce d''utiliser le compte ci – après :
• 9011 – Crédits confirmés obtenus
Les comptes 9013, 9014 et 9018 ne doivent pas servir à enregistrer :
• les crédits confirmés
Il convient dans le cas d''espèce d''utiliser le compte ci – après :
• 9011 – Crédits confirmés obtenus', controle = 'Le compte 9012 – EMPRUNTS RESTANT A ENCAISSER peut être contrôlé à l''aide des conventions de prêt.
Facilités de financement renouvelables ; Facilités d''émission ;
9013/9014/9018
Autres engagements de financement obtenus
Les comptes 9013 – FACILITES DE FINANCEMENT RENOUVELABLES, 9014 – FACILITE D''EMISSION et 9018 –
AUTRES ENGAGEMENTS DE FINANCEMENT OBTENUS peuvent être contrôlés à partir des conventions conclues
dans le cadre d''émission de titre et des effets représentatifs des " engagements à payer" obtenus' WHERE numero = '9012';
UPDATE comptes_ohada SET contenu = 'L''avaliseur prend l''engagement de payer au créancier, à l''échéance, tout ou partie du nominal d''une lettre de change, d''un
billet à ordre ou d''un chèque, à la place du tiré ou du souscripteur, en cas de défaillance éventuelle de celui-ci.', commentaires = 'L''avaliseur peut être un établissement bancaire ou un autre tiers.', fonctionnement = 'A la conclusion de la transaction,
Le compte 9021 – AVALS OBTENUS est débité
• par le crédit du compte de contrepartie 9121.
A l''échéance ou au dénouement de la transaction,
Le compte 9021 – AVALS OBTENUS est crédité
• par le débit du compte de contrepartie 9121.', exclusions = NULL, controle = 'Le compte 9021 – AVALS OBTENUS peut être contrôlé à l''aide des effets avalisés.' WHERE numero = '9021';
UPDATE comptes_ohada SET contenu = 'La caution s''engage à payer l''entité créancière au cas où le débiteur de cette dernière n''exécuterait pas son obligation.', commentaires = 'Il convient de distinguer les cautions bancaires et les cautions obtenues des autres tiers.
Les cautions bancaires se présentent essentiellement sous forme :
Les cautions fiscales concernent principalement les obligations cautionnées, les cautions pour impositions constatées et les
cautions pour paiement différé des droits d''enregistrement.
Les cautions en douane comprennent notamment les cautions pour admissions temporaires, les cautions de transit ou
acquit-à-caution, les cautions d''entrepôts, les cautions pour transbordement à destination de l''étranger, les lettres de
garantie pour absence de connaissement original, les soumissions pour absence de certificat d''origine. Les cautions sur
marchés, notamment publics, revêtent plusieurs formes dont les plus importantes sont
S''agissant des cautions obtenues des autres tiers, seules les lettres d''intention ou lettres de confort assimilables à un
cautionnement contiennent de véritables obligations pour le garant. Les autres lettres d''intention sont de simples
déclarations constitutives tout au plus d''un engagement moral ou d''une obligation de moyens, rarement de résultat.', fonctionnement = 'A la conclusion du contrat, le compte 9022 – CAUTIONS, GARANTIES OBTENUES est débité
• par le crédit du compte de contrepartie 9122.
A l''échéance et au dénouement de la transaction, le compte 9022 – CAUTIONS, GARANTIES OBTENUES est crédité
• par le débit du compte de contrepartie 9122.', exclusions = 'Le compte 9022 – CAUTIONS, GARANTIES OBTENUES ne doit pas servir à enregistrer :
• les gages
• les nantissements
• les antichrèses
Il convient dans les cas d''espèce d''utiliser le compte ci-après :
• 9028 – Autres garanties obtenues', controle = 'Le compte 9022 – CAUTIONS, GARANTIES OBTENUES peut être contrôlé à l''aide des conventions de prêts et de
l''acte constitutif de la caution' WHERE numero = '9022';
UPDATE comptes_ohada SET contenu = 'Lorsque l''entité reçoit de son débiteur un immeuble en hypothèque, cette dernière lui confère le droit de faire saisir et
vendre l''immeuble en quelques mains qu''il se trouve et de se faire payer par préférence sur le prix de la vente.', commentaires = 'La validité de l''hypothèque est assurée par son inscription sur un registre légal (cadastre, domaine, registre foncier, etc.).', fonctionnement = 'A l''inscription de l''hypothèque, le compte 9023 – HYPOTHEQUES OBTENUES est débité de la valeur de l''immeuble
telle que fixée au début de la transaction
• par le crédit du compte de contrepartie 9123.
Au dénouement de la créance ou à la réalisation de l''hypothèque, le compte 9023 – HYPOTHEQUES OBTENUES est
crédité
• par le débit du compte de contrepartie 9123.', exclusions = 'Le compte 9023 – HYPOTHEQUES OBTENUES ne doit pas servir à enregistrer :
Il convient dans les cas d''espèce d''utiliser le compte ci-après:', controle = 'Le compte 9023 – HYPOTHEQUES OBTENUES peut être contrôlé à partir des conventions de prêts, des récépissés
d''inscription de l''hypothèque, des rapports d''expertise immobilière.' WHERE numero = '9023';
UPDATE comptes_ohada SET contenu = 'Dans le cadre de la garantie d''endossement, l''endosseur (le débiteur de l''entité) est, sauf clause contraire, garant de
l''acceptation et du paiement de l''effet.', commentaires = 'L''endossement est le mode de transmission des titres à ordre. Il est réalisé par une signature apposée au dos du titre par le
porteur appelé endosseur. L''endossataire, bénéficiaire de l''endossement, peut être désigné ; s''il ne l''est pas, l''endossement
peut être au porteur ou en blanc.
Les effets de l''endossement varient selon la nature de l''endossement : endossement de procuration, endossement
pignoratif ou endossement translatif.
L''endossement de procuration emporte pour l''endossataire l''obligation de présenter le titre au paiement pour le compte de
l''endosseur qui lui en a donné mandat.
L''endossement pignoratif confère à l''endossataire un droit de gage sur le titre remis par l''endosseur.
L''endossement translatif transfère à l''endossataire tous les droits résultant du titre endossé.
Lorsque l''entité réendosse ou escompte les effets reçus et endossés par des tiers, le compte 9024 – Effets endossés par
des tiers n''est pas mouvementé. Toutefois, l''entité doit enregistrer l''engagement qu''elle prend ainsi dans les comptes
d''engagements donnés (effets sous endos ou effets escomptés).', fonctionnement = 'A la réception de l''effet endossé, le compte 9024 – EFFETS ENDOSSÉS PAR DES TIERS est débité
• par le crédit du compte de contrepartie 9124.
A l''échéance ou au dénouement de l''opération, le compte 9024 – EFFETS ENDOSSÉS PAR DES TIERS est crédité
• par le débit du compte de contrepartie 9124.', exclusions = 'Le compte 9024 – EFFETS ENDOSSÉS PAR DES TIERS ne doit pas servir à enregistrer :
• les effets transmis aux tiers par endossement de procuration
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 9088 – Divers engagements accordés', controle = 'Le compte 9024 – EFFETS ENDOSSÉS PAR DES TIERS peut être contrôlé à partir des effets reçus et des
bordereaux d''escompte.' WHERE numero = '9024';
UPDATE comptes_ohada SET contenu = 'Les autres garanties obtenues comprennent notamment le gage, le nantissement et l''antichrèse pour lesquels
l''engagement porte spécialement sur un ou plusieurs biens affectés à l''entité créancière en vue de garantir ses droits.', commentaires = 'Figurent également dans ce compte: les promesses d''hypothèque, les chèques de caution reçus, les actions reçues en
garantie de gestion, les effets transmis aux tiers par endossement de procuration (encaissement). L''antichrèse représente
le nantissement sur un immeuble permettant uniquement d''en percevoir les fruits.', fonctionnement = 'A la constitution de la garantie, le compte 9028 – AUTRES GARANTIES OBTENUES est débité de la valeur des biens
reçus en garantie
• par le crédit du compte de contrepartie 9128.
Au dénouement de la créance ou à la réalisation de la garantie, le compte 9028 – AUTRES GARANTIES OBTENUES
est crédité
• par le débit du compte de contrepartie 9128.', exclusions = 'Le compte 9028 – AUTRES GARANTIES OBTENUES ne doit pas servir à enregistrer :
• les avals
• les cautions
• les hypothèques
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 9021 – Avals obtenus
• 9022 – Cautions, garanties obtenues
• 9023 – Hypothèques obtenues', controle = 'Le compte 9028 – AUTRES GARANTIES OBTENUES peut être contrôlé à partir des conventions de prêt, de l''acte
constitutif de la garantie, des chèques et des actions.' WHERE numero = '9028';
UPDATE comptes_ohada SET contenu = 'Pour deux partenaires, les engagements réciproques se décomposent en un engagement donné par l''entité à son
cocontractant en contrepartie d''un engagement reçu de ce dernier. A ce titre, dans le cadre des achats de marchandises à
terme, le fournisseur de l''entité s''engage à livrer des marchandises et l''entité s''engage à en prendre livraison et à en payer
le prix convenu à la date de livraison.', commentaires = NULL, fonctionnement = 'A la signature de la transaction, le compte 9031 – ACHATS DE MARCHANDISES A TERME est débité
• par le crédit du compte de contrepartie 9131
A la livraison du marché, le compte 9031 – ACHATS DE MARCHANDISES A TERME est crédité
• par le débit du compte de contrepartie 9131', exclusions = NULL, controle = 'Le compte 9031 – ACHATS DE MARCHANDISES A TERME peut être contrôlé à partir des demandes d''achat, des
bons de commande, des bons de livraison et des factures' WHERE numero = '9031';
UPDATE comptes_ohada SET contenu = 'Le cocontractant s''engage à livrer des devises et réciproquement l''entité s''engage à livrer de la monnaie nationale.
Les commandes fermes des clients sont les engagements irrévocables pris par un client de régler le prix des travaux
exécutés pour son compte conformément à ses spécifications exprimées sur la base des conditions de vente indiquées par
l''entité (fournisseur) selon le cas dans les catalogues, les devis ou les offres de services.
Le compte 9038 enregistre les droits relatifs aux engagements réciproques qui ne trouvent pas place dans les sous-
comptes 9031, 9032 et 9033.', commentaires = NULL, fonctionnement = 'A la conclusion de la transaction,
Le compte 9032 – ACHATS A TERME DE DEVISES est débité
• par le crédit du compte de contrepartie 9132.
Au dénouement de la transaction,
Le compte 9032 – ACHATS A TERME DE DEVISES est crédité
• par le débit du compte de contrepartie 9132.
A la réception de la commande, le compte 9033 – COMMANDES FERMES DES CLIENTS ou le compte 9038 –
AUTRES ENGAGEMENTS RÉCIPROQUES est débité
• par le crédit du compte de contrepartie 9133 ou 9138.
A l''exécution de la commande, le compte 9033 – COMMANDES FERMES DES CLIENTS ou le compte 9038
AUTRES ENGAGEMENTS RÉCIPROQUES est crédité
• par le débit du compte de contrepartie 9133 et 9138.', exclusions = NULL, controle = 'Le compte 9032 – ACHATS A TERME DE DEVISES peut être contrôlé à partir des ordres d''achat et de l''avis d''opéré.
9032/9033          Commandes fermes des clients ; Autres engagements réciproques
Le compte 9033 – COMMANDES FERMES DES CLIENTS et le compte' WHERE numero = '9032';
UPDATE comptes_ohada SET contenu = 'La convention d''abandon de créances assortie d''une clause de retour à meilleure fortune est caractérisée par l''extinction de
la créance de l''entité sous condition résolutoire : l''entité débitrice retrouve des moyens (gains) financiers suffisants qui
rétablissent sa dette originelle.
Dans le cadre de la vente avec clause de réserve de propriété, le vendeur demeure propriétaire des actifs vendus jusqu''à
complet paiement du prix.
Les divers engagements obtenus comprennent notamment les titres à recevoir dans le cadre de souscription à l''émission
ainsi que les ventes à réméré par lesquelles le vendeur se réserve le droit de racheter l''objet de la vente dans un certain
délai, en remboursant à l''acquéreur le prix principal et les frais d''acquisition.
Le solde créditeur du compte 9051 représente la partie non décaissée des crédits qu''une entité s''est engagée, d''une façon
irrévocable, à accorder à un tiers autre qu''un établissement de crédit.
Les autres engagements de financement accordés comprennent notamment les engagements pris par la société-mère d''un
groupe de combler les éventuels déficits de trésorerie de ses différentes filiales auprès d''une banque.', commentaires = 'Chez l''entité qui consent l''abandon, la créance abandonnée sous condition disparaît de son bilan et est suivie en tant
qu''engagement hors bilan reçu.', fonctionnement = 'A la conclusion des conventions, le compte 9041 – ABANDONS DE CONDITIONNELS est débité CRÉANCES
• par le crédit du compte de contrepartie 9141.
A la réalisation de l''éventualité (retour à bonne fortune), le compte 9041 – ABANDONS DE CRÉANCES
CONDITIONNELS est crédité
• par le débit du compte de contrepartie 9141.
A la cession des actifs ou à la souscription des titres, les comptes 9043 – VENTES AVEC CLAUSE DE RÉSERVE DE
PROPRIÉTÉ et 9048 – DIVERS ENGAGEMENTS OBTENUS sont débités
• par le crédit des comptes de contrepartie respectifs 9143ou 48
Au dénouement de la transaction, les comptes 9043 – VENTES AVEC CLAUSE DE RÉSERVE DE PROPRIÉTÉ et
9048 – DIVERS ENGAGEMENTS OBTENUS sont crédités
• par le débit des comptes de contrepartie respectifs 9143 ou 9148
A la signature de l''engagement, les comptes 90-51 – CRÉDITS ACCORDÉS NON DÉCAISSÉS et 9058 – AUTRES
ENGAGEMENTS DE FINANCEMENT ACCORDÉS sont crédités
• par le débit des comptes de contrepartie 9151et 9158
Lors du décaissement partiel ou total du crédit, ou lors de la couverture des déficits de trésorerie, les comptes 9051 –
CRÉDITS ACCORDÉS NON DÉCAISSÉS et 9058 – AUTRES ENGAGEMENTS DE FINANCEMENT ACCORDÉS
sont débités
• par le crédit des comptes de contrepartie respectifs', exclusions = NULL, controle = 'Le compte 9041 – ABANDONS DE CRÉANCES CONDITIONNELS peut être contrôlé à partir des " grosses " du
jugement et des conventions.
Ventes avec clause de réserve de propriété ; Divers engagements
9043/9048
obtenus
Les comptes 9043 – VENTES AVEC CLAUSE DE RÉSERVE DE PROPRIÉTÉ et 9048 – DIVERS ENGAGEMENTS
OBTENUS peuvent être contrôlés à partir des conventions de cession et des bordereaux ou attestations de
souscription de titres.
Crédits accordés non décaissés ; Autres engagements de financement
9051/9058
accordés
Les comptes 9051 – CRÉDITS ACCORDÉS NON DÉCAISSÉS et 9058 – AUTRES ENGAGEMENTS DE
FINANCEMENT ACCORDÉS peuvent être contrôlés à partir des lettres de notification de l''entité , des conventions de
crédit et des décisions des organes compétents.' WHERE numero = '9041';
UPDATE comptes_ohada SET contenu = 'Par l''aval, l''entité prend l''engagement de payer au bénéficiaire, et à l''échéance, tout ou partie du nominal d''une lettre de
change, d''un billet à ordre ou d''un chèque, à la place du tiré ou du souscripteur éventuellement défaillant.', commentaires = NULL, fonctionnement = 'A la conclusion de la transaction,
Le compte 9061 – AVALS ACCORDES est crédité
• par le débit du compte de contrepartie 9161
A l''échéance ou au dénouement de la transaction,
Le compte 9061 – AVALS ACCORDES est débité
• par le crédit du compte de contrepartie 9161', exclusions = NULL, controle = 'Le compte 9061 – AVALS ACCORDÉS peut être contrôlé à partir des effets avalisés et des décisions des organes
compétents.' WHERE numero = '9061';
UPDATE comptes_ohada SET contenu = 'L''entité, en tant que caution, promet à un créancier de le payer si le débiteur de ce dernier n''exécute pas son obligation.', commentaires = 'Ce compte enregistre également les lettres d''intention ou lettres de confort dans lesquelles l''entité s''engage sans
équivoque, envers un créancier, à satisfaire l''obligation du débiteur de ce créancier si ce débiteur n''y satisfait pas lui-même.', fonctionnement = 'A la conclusion de la caution, le compte 9062 – CAUTIONS, GARANTIES ACCORDÉES est crédité
• par le débit du compte de contrepartie 9162
A l''échéance ou au dénouement de la transaction, le compte 9062 – CAUTIONS, GARANTIES ACCORDÉES est
débité
• par le crédit du compte de contrepartie 9162', exclusions = 'Le compte 9062 – CAUTIONS, GARANTIES ACCORDÉES ne doit pas servir à enregistrer :
• les gages
• les nantissements
• les antichrèses
Il convient dans les cas d''espèce d''utiliser le compte ci-après :
• 9068 – Autres garanties accordées', controle = 'Le compte 9062 – CAUTIONS, GARANTIES ACCORDÉES peut être contrôlé à partir des conventions de prêts, des
actes constitutifs de la caution, des lettres d''intention ou lettres de confort, des décisions des organes compétents.' WHERE numero = '9062';
UPDATE comptes_ohada SET contenu = 'Lorsque l''entité donne à son créancier un immeuble en hypothèque, cette hypothèque confère à ce créancier le droit de
faire saisir et vendre l''immeuble en quelques mains qu''il se trouve et de se faire payer par préférence sur le prix de la vente.', commentaires = 'La validité de l''hypothèque est assurée par son inscription sur un registre légal (Cadastre, Domaines, registre foncier, etc.).', fonctionnement = 'A l''inscription de l''hypothèque, le compte 9063 – HYPOTHEQUES ACCORDÉES est crédité de la valeur de
l''immeuble telle que fixée
• par le débit du compte de contrepartie 9163
Au dénouement de la créance ou à la réalisation de l''hypothèque, le compte 9063 – HYPOTHEQUES ACCORDÉES
est débité
• par le crédit du compte de contrepartie 9163', exclusions = 'Le compte 9063 – HYPOTHEQUES ACCORDÉES ne doit pas servir à enregistrer :
• les promesses d''hypothèques
• les gages
• les nantissements
• les antichrèses
Il convient dans les cas d''espèce d''utiliser le compte ci-après:
• 9068 – Autres garanties accordées', controle = 'Le compte 9063 – HYPOTHEQUES ACCORDÉES peut être contrôlé à partir des conventions de prêts, des
récépissés d''inscription de l''hypothèque, des rapports d''expertise immobilière.' WHERE numero = '9063';
UPDATE comptes_ohada SET contenu = 'Dans le cadre de la garantie d''endossement, l''endosseur (l''entité) est, sauf clause contraire, garant de l''acceptation et du
paiement de l''effet.', commentaires = 'L''endossement est le mode de transmission des titres à ordre. Il est réalisé par une signature apposée au dos du titre par le
porteur appelé endosseur.
L''endossataire, bénéficiaire de l''endossement, peut être désigné ; s''il ne l''est pas, l''endossement peut être au porteur ou en
blanc.
Les effets de l''endossement varient selon la nature de l''endossement : endossement de procuration, endossement
pignoratif ou endossement translatif. L''endossement de procuration emporte pour l''endossataire l''obligation de présenter le
titre au paiement pour le compte de l''endosseur qui lui en a donné mandat.
L''endossement pignoratif confère à l''endossataire un droit de gage sur le titre remis par l''endosseur.
L''endossement translatif transfère à l''endossataire tous les droits résultant du titre endossé.', fonctionnement = 'Lors de l''endossement, le compte 9064 – EFFETS ENDOSSÉS PAR L''ENTITE est crédité
• par le débit du compte de contrepartie 9164
A l''échéance ou au dénouement de l''opération, le compte 9064 – EFFETS ENDOSSES PAR L''ENTITE est débité
• par le crédit du compte de contrepartie 9164', exclusions = 'Le compte 9064 – EFFETS ENDOSSÉS PAR L''ENTITE ne doit pas servir à enregistrer :
• les effets transmis par endossement de procuration
Il convient dans le cas d''espèce d''utiliser le compte ci-après :
• 9048 – Divers engagements obtenus', controle = 'Le compte 9064 – EFFETS ENDOSSÉS PAR L''ENTITE peut être contrôlé à partir des effets.' WHERE numero = '9064';
UPDATE comptes_ohada SET contenu = 'Les autres garanties accordées comprennent notamment le gage, le nantissement et l''antichrèse pour lesquels
l''engagement porte spécialement sur un ou plusieurs biens affectés par l''entité à l''acquittement de ses obligations.', commentaires = 'Figurent également dans ce compte :
• les promesses d''hypothèque,
• les effets reçus des tiers par endossement de procuration (encaissement).
L''antichrèse représente le nantissement sur un immeuble permettant uniquement d''en percevoir les fruits.', fonctionnement = 'A la constitution de la garantie, le compte 9068 – AUTRES GARANTIES ACCORDÉES est crédité de la valeur des
biens donnés en garantie
• par le débit du compte de contrepartie 9168
Au dénouement de la créance ou à la réalisation de la garantie, le compte 9068 – AUTRES GARANTIES
ACCORDÉES est débité
• par le crédit du compte de contrepartie 9168', exclusions = 'Le compte 9068 – AUTRES GARANTIES ACCORDEES ne doit pas servir à enregistrer :
• les avals
• les cautions
• les hypothèques
Il convient dans les cas d''espèce d''utiliser les comptes ci-après :
• 9061 – Avals accordés
• 9062 – Cautions, garanties accordées
• 9063 – Hypothèques accordées', controle = 'Le compte 9068 – AUTRES GARANTIES ACCORDÉES peut être contrôlé à partir des conventions de prêt et de l''acte
constitutif de la garantie.' WHERE numero = '9068';
UPDATE comptes_ohada SET contenu = 'Pour deux partenaires, les engagements réciproques se décomposent en un engagement donné par l''entité à son
cocontractant en contrepartie d''un engagement reçu de ce dernier. A ce titre, dans le cadre des ventes de marchandises à
terme, l''entité s''engage à livrer des marchandises et son client s''engage à en prendre livraison et à en payer le prix à la
date de livraison.', commentaires = NULL, fonctionnement = 'A la signature de la transaction, le compte 9071 – VENTES DE MARCHANDISES A TERME est crédité
• par le débit du compte de contrepartie 9171
A l''exécution du marché, le compte 9071 – VENTES DE MARCHANDISES A TERME est débité
• par le crédit du compte de contrepartie 9171', exclusions = NULL, controle = 'Le compte 9071 – VENTES DE MARCHANDISES A TERME peut être contrôlé à partir des bons de commandes, des
bons de livraison et des factures.' WHERE numero = '9071';
UPDATE comptes_ohada SET contenu = 'L''entité s''engage à livrer des devises et réciproquement le cocontractant s''engage à livrer de la monnaie nationale.', commentaires = NULL, fonctionnement = 'A la conclusion de la transaction,
Le compte 9072 – VENTES A TERME DE DEVISES est crédité
• par le débit du compte de contrepartie 9172
Au dénouement de la transaction,
Le compte 9072 – VENTES A TERME DE DEVISES est débité
• par le crédit du compte de contrepartie 9172', exclusions = NULL, controle = 'Le compte 9072 – VENTES A TERME DE DEVISES peut être contrôlé à partir des ordres d''achat et de l''avis d''opéré.' WHERE numero = '9072';
UPDATE comptes_ohada SET contenu = 'Dans le cadre d''une commande ferme, l''entité s''engage de façon irrévocable à payer le prix des travaux exécutés pour son
compte conformément aux spécifications contenues dans sa commande.', commentaires = NULL, fonctionnement = 'A l''émission du bon de commande,
Le compte 9073 – COMMANDES FERMES AUX FOURNISSEURS est crédité
• par le débit du compte de contrepartie 9173
A la réception des travaux,
Le compte 9073 – COMMANDES FERMES FOURNISSEURS est débité AUX
• par le crédit du compte de contrepartie 9173', exclusions = NULL, controle = 'Le compte 9073 – COMMANDES FERMES AUX FOURNISSEURS peut être contrôlé à partir des bons de commande
et des factures' WHERE numero = '9073';
UPDATE comptes_ohada SET contenu = 'Le compte 9078 enregistre les obligations relatives aux engagements réciproques qui ne trouvent pas leur place dans les
sous-comptes 9071, 9072 et 9073.', commentaires = NULL, fonctionnement = 'A la signature de la convention,
Le compte 9078 – AUTRES ENGAGEMENTS RECIPROQUES est crédité
• par le débit du compte de contrepartie 9178
A l''échéance ou au dénouement de la transaction
Le compte 9078 – AUTRES ENGAGEMENTS RECIPROQUES est débité
• par le crédit du compte de contrepartie 9178', exclusions = NULL, controle = 'Le compte 9078 – AUTRES ENGAGEMENTS RECIPROQUES peut être contrôlé à partir des conventions.' WHERE numero = '9078';
UPDATE comptes_ohada SET contenu = 'La convention d''annulation de dettes assortie d''une clause de retour à meilleure fortune est caractérisée par l''extinction de
la dette de l''entité sous condition résolutoire : l''entité retrouve des moyens (gains) financiers suffisants qui rétablissent sa
dette originelle.
Dans le cadre d''achat avec clause de réserve de propriété, l''entité ne sera propriétaire des biens achetés qu''au paiement
complet du prix.
Les divers engagements accordés comprennent notamment les titres à livrer dans le cadre d''une émission, ainsi que les
achats à réméré par lesquels l''entité (acheteur) garantit au vendeur la faculté de racheter l''objet de la vente dans un certain
délai et à un prix convenus d''avance. Figurent également dans ce compte les subventions à reverser.', commentaires = 'Chez l''entité qui bénéficie de l''annulation, la dette annulée sous condition disparaît de son bilan et est suivie en tant
qu''engagement hors bilan donné.', fonctionnement = 'A la conclusion de la convention,
Le compte 9081 – ANNULATIONS CONDITIONNELLES DE DETTES est crédité
• par le débit du compte de contrepartie 9181
A la réalisation de l''éventualité (retour à bonne fortune), le compte 9081 – ANNULATIONS CONDITIONNELLES DE
DETTES est débité
• par le crédit du compte de contrepartie 9181
A la conclusion de la transaction (achat, souscription), les comptes 9083 – ACHATS AVEC CLAUSE DE RÉSERVE
DE PROPRIÉTÉ et 9088 – DIVERS ENGAGEMENTS ACCORDÉS sont crédités
• par le débit des comptes de contrepartie respectifs
Au dénouement de la transaction, les comptes 9083 ACHATS AVEC CLAUSE DE RÉSERVE DE PROPRIÉTÉ et
9088 DIVERS ENGAGEMENTS ACCORDÉS sont débités
• par le crédit des comptes de contrepartie respectifs 9181 et 9188', exclusions = NULL, controle = 'Le compte 9081 – ANNULATIONS CONDITIONNELLES DE DETTES peut être contrôlé à partir des " grosses " du
jugement, des conventions. et des décisions des organes compétents.
Achats avec clause de réserve de propriété ; Divers engagements
9083/9088
accordés
Les comptes 9083 – ACHATS AVEC CLAUSE DE RÉSERVE DE PROPRIÉTÉ et 9088 – DIVERS ENGAGEMENTS
ACCORDÉS peuvent être contrôlés à partir des conventions de cession, des bordereaux ou des attestations de
souscription de titres, des conventions de subvention, des décisions des organes compétents.
92—99         Comptes de la comptabilité analytique de gestion
Sous-section 2 : Comptes de la Comptabilité analytique de gestion (92 – 99)
L''usage des comptes 92 à 99 est laissé à l''initiative des entités qui utilisent les découpages convenant le mieux :
• à leur structure ;
• à leur politique des coûts ;
• à leur organisation.
Les comptes à deux chiffres 92 à 99 ci-après rappelés sont de caractère suffisamment général pour répondre aux besoins
de toute entité qui les subdivise à sa convenance.
▤ COMPTES À DEUX CHIFFRES DISPONIBLES
92         COMPTES REFLECHIS
93         COMPTES DE RECLASSEMENTS
94         COMPTES DE COÛTS
95         COMPTES DE STOCKS
96         COMPTES D''ECARTS SUR COÛTS PREETABLIS
97         COMPTES DE DIFFERENCES DE TRAITEMENT COMPTABLE
98         COMPTES DE RESULTATS
99         COMPTES DE LIAISONS INTERNES
Note méthodologique
Ce document compile l''intégralité des commentaires officiels du plan comptable SYSCOHADA révisé (norme 2016, cadre
AUDCIF 2017, applicable depuis le 1er janvier 2018) pour les classes 1 à 9, tels que publiés sur plan-comptable-ohada.com.
La classe 9 (engagements hors bilan et comptabilité analytique de gestion) est de nature et de structure différentes des
classes 1 à 8 : ses comptes 9011 à 9088 conservent le détail complet (Contenu, Commentaires, Fonctionnement, Exclusions,
Éléments de contrôle), tandis que ses comptes 92 à 99 (comptabilité analytique) sont, conformément au texte SYSCOHADA
lui-même, un simple cadre à deux chiffres laissé à l''initiative de chaque entité — ils sont donc présentés en fin de classe 9
sous forme de liste, sans les mêmes rubriques.
Document de référence à usage professionnel — à recouper, pour toute application réglementaire ou contentieuse, avec l''Acte uniforme
relatif au Droit Comptable et à l''Information Financière (AUDCIF) publié par l''OHADA.' WHERE numero = '9081';

-- 5. Hierarchie et imputabilite ---------------------------------------
-- Le parent d'un compte est le compte existant dont le numero est le plus
-- long prefixe strict du sien.
UPDATE comptes_ohada c
SET parent_id = (
    SELECT p.id FROM comptes_ohada p
    WHERE p.numero <> c.numero
      AND c.numero LIKE p.numero || '%'
    ORDER BY length(p.numero) DESC
    LIMIT 1
);

-- Un compte de regroupement n'est pas imputable...
UPDATE comptes_ohada c
SET imputable = FALSE
WHERE EXISTS (SELECT 1 FROM comptes_ohada e
              WHERE e.numero <> c.numero AND e.numero LIKE c.numero || '%');

-- ...sauf s'il porte deja des ecritures. Le referentiel subdivise par
-- exemple 571 (Caisse) en 5711/5712, ce qui en ferait un compte de
-- regroupement : le rendre insaisissable romprait les paiements de caisse,
-- qui y sont imputes depuis l'origine. La realite des ecritures prime.
UPDATE comptes_ohada c
SET imputable = TRUE
WHERE EXISTS (SELECT 1 FROM grand_livre g WHERE g.compte_id = c.id);

DROP TABLE IF EXISTS comptes_historiques;


-- 6. Retrait des comptes de l'ancien plan ------------------------------
-- Les comptes de l'ancien plan absents du referentiel officiel sont
-- desactives (et non supprimes) des lors qu'ils ne sont references nulle
-- part : ils disparaissent des listes de saisie sans casser la moindre
-- cle etrangere. Ceux qui portent une ecriture, une imputation, un budget
-- ou un article restent actifs : l'historique prime sur le referentiel.
CREATE TEMP TABLE referentiel_officiel (numero VARCHAR(20) PRIMARY KEY);
INSERT INTO referentiel_officiel (numero) VALUES
    ('101'),
    ('1011'),
    ('1012'),
    ('1013'),
    ('1014'),
    ('1018'),
    ('102'),
    ('1021'),
    ('1022'),
    ('1028'),
    ('103'),
    ('104'),
    ('1041'),
    ('1042'),
    ('1043'),
    ('1047'),
    ('1048'),
    ('105'),
    ('1051'),
    ('1052'),
    ('1053'),
    ('1054'),
    ('1058'),
    ('106'),
    ('1061'),
    ('1062'),
    ('109'),
    ('11'),
    ('111'),
    ('112'),
    ('113'),
    ('1131'),
    ('1132'),
    ('1133'),
    ('1134'),
    ('1138'),
    ('118'),
    ('1181'),
    ('1188'),
    ('12'),
    ('121'),
    ('129'),
    ('1291'),
    ('1292'),
    ('13'),
    ('130'),
    ('1301'),
    ('1309'),
    ('131'),
    ('132'),
    ('133'),
    ('134'),
    ('135'),
    ('136'),
    ('137'),
    ('138'),
    ('1381'),
    ('1382'),
    ('1383'),
    ('1384'),
    ('139'),
    ('14'),
    ('141'),
    ('1411'),
    ('1412'),
    ('1413'),
    ('1414'),
    ('1415'),
    ('1416'),
    ('1417'),
    ('1418'),
    ('148'),
    ('15'),
    ('151'),
    ('152'),
    ('153'),
    ('1531'),
    ('1532'),
    ('154'),
    ('155'),
    ('1551'),
    ('156'),
    ('1561'),
    ('1562'),
    ('157'),
    ('158'),
    ('16'),
    ('161'),
    ('1611'),
    ('1612'),
    ('1613'),
    ('1618'),
    ('162'),
    ('163'),
    ('164'),
    ('165'),
    ('1651'),
    ('1652'),
    ('166'),
    ('1661'),
    ('1662'),
    ('1663'),
    ('1664'),
    ('1665'),
    ('1667'),
    ('1668'),
    ('167'),
    ('1671'),
    ('1672'),
    ('1673'),
    ('1674'),
    ('1676'),
    ('168'),
    ('1681'),
    ('1682'),
    ('1683'),
    ('1685'),
    ('1686'),
    ('17'),
    ('172'),
    ('173'),
    ('174'),
    ('176'),
    ('1762'),
    ('1763'),
    ('1764'),
    ('1768'),
    ('178'),
    ('181'),
    ('1811'),
    ('1812'),
    ('182'),
    ('183'),
    ('184'),
    ('185'),
    ('186'),
    ('187'),
    ('188'),
    ('19'),
    ('191'),
    ('192'),
    ('193'),
    ('194'),
    ('195'),
    ('196'),
    ('1961'),
    ('1962'),
    ('197'),
    ('198'),
    ('1981'),
    ('1983'),
    ('1984'),
    ('1985'),
    ('1988'),
    ('21'),
    ('211'),
    ('212'),
    ('2121'),
    ('2122'),
    ('2123'),
    ('2128'),
    ('213'),
    ('2131'),
    ('2132'),
    ('214'),
    ('215'),
    ('216'),
    ('217'),
    ('218'),
    ('2181'),
    ('2182'),
    ('2183'),
    ('2188'),
    ('219'),
    ('2191'),
    ('2193'),
    ('2198'),
    ('22'),
    ('221'),
    ('2211'),
    ('2212'),
    ('2218'),
    ('222'),
    ('2221'),
    ('2228'),
    ('223'),
    ('2231'),
    ('2232'),
    ('2234'),
    ('2235'),
    ('2238'),
    ('224'),
    ('2241'),
    ('2245'),
    ('2248'),
    ('225'),
    ('2251'),
    ('226'),
    ('2261'),
    ('227'),
    ('228'),
    ('2281'),
    ('2285'),
    ('2286'),
    ('2288'),
    ('229'),
    ('2291'),
    ('2292'),
    ('2295'),
    ('2298'),
    ('23'),
    ('231'),
    ('2311'),
    ('2312'),
    ('2313'),
    ('2314'),
    ('2315'),
    ('2316'),
    ('232'),
    ('2321'),
    ('2322'),
    ('2323'),
    ('2324'),
    ('2325'),
    ('2326'),
    ('233'),
    ('2331'),
    ('2332'),
    ('2333'),
    ('2334'),
    ('2335'),
    ('2338'),
    ('234'),
    ('2341'),
    ('2342'),
    ('2343'),
    ('2344'),
    ('2345'),
    ('235'),
    ('2351'),
    ('2358'),
    ('237'),
    ('238'),
    ('239'),
    ('2391'),
    ('2392'),
    ('2393'),
    ('24'),
    ('241'),
    ('2411'),
    ('2412'),
    ('2413'),
    ('2414'),
    ('2416'),
    ('242'),
    ('2421'),
    ('2422'),
    ('2426'),
    ('243'),
    ('244'),
    ('2441'),
    ('2442'),
    ('2443'),
    ('2444'),
    ('2446'),
    ('2447'),
    ('245'),
    ('2451'),
    ('2452'),
    ('2453'),
    ('2454'),
    ('2455'),
    ('2456'),
    ('2457'),
    ('2458'),
    ('246'),
    ('2461'),
    ('2462'),
    ('2463'),
    ('2465'),
    ('2468'),
    ('247'),
    ('2471'),
    ('2472'),
    ('248'),
    ('2481'),
    ('249'),
    ('2491'),
    ('2492'),
    ('2493'),
    ('2494'),
    ('2495'),
    ('2496'),
    ('2497'),
    ('2498'),
    ('25'),
    ('251'),
    ('252'),
    ('26'),
    ('261'),
    ('262'),
    ('263'),
    ('265'),
    ('266'),
    ('268'),
    ('27'),
    ('271'),
    ('2711'),
    ('2712'),
    ('2713'),
    ('2714'),
    ('2715'),
    ('272'),
    ('2721'),
    ('2722'),
    ('2728'),
    ('273'),
    ('2731'),
    ('2733'),
    ('2734'),
    ('2738'),
    ('274'),
    ('2741'),
    ('2742'),
    ('2743'),
    ('2744'),
    ('2745'),
    ('2746'),
    ('2748'),
    ('275'),
    ('2751'),
    ('2752'),
    ('2753'),
    ('2754'),
    ('2755'),
    ('2756'),
    ('2757'),
    ('2758'),
    ('276'),
    ('2761'),
    ('2762'),
    ('2763'),
    ('2764'),
    ('2765'),
    ('2766'),
    ('2767'),
    ('2768'),
    ('277'),
    ('2771'),
    ('2772'),
    ('2773'),
    ('2774'),
    ('278'),
    ('2781'),
    ('2782'),
    ('2784'),
    ('2785'),
    ('28'),
    ('281'),
    ('2811'),
    ('2812'),
    ('2813'),
    ('2814'),
    ('2815'),
    ('2816'),
    ('2817'),
    ('2818'),
    ('282'),
    ('2821'),
    ('2824'),
    ('2825'),
    ('283'),
    ('2831'),
    ('2832'),
    ('2833'),
    ('2834'),
    ('2835'),
    ('2837'),
    ('2838'),
    ('284'),
    ('2841'),
    ('2842'),
    ('2843'),
    ('2844'),
    ('2845'),
    ('2846'),
    ('2847'),
    ('2848'),
    ('29'),
    ('291'),
    ('2911'),
    ('2912'),
    ('2913'),
    ('2914'),
    ('2915'),
    ('2916'),
    ('2917'),
    ('2918'),
    ('2919'),
    ('292'),
    ('2921'),
    ('2922'),
    ('2923'),
    ('2924'),
    ('2925'),
    ('2926'),
    ('2927'),
    ('2928'),
    ('2929'),
    ('293'),
    ('2931'),
    ('2932'),
    ('2933'),
    ('2934'),
    ('2935'),
    ('2937'),
    ('2938'),
    ('2939'),
    ('294'),
    ('2941'),
    ('2942'),
    ('2943'),
    ('2944'),
    ('2945'),
    ('2946'),
    ('2947'),
    ('2948'),
    ('2949'),
    ('295'),
    ('2951'),
    ('2952'),
    ('296'),
    ('2961'),
    ('2962'),
    ('2963'),
    ('2965'),
    ('2966'),
    ('2968'),
    ('297'),
    ('2971'),
    ('2972'),
    ('2973'),
    ('2974'),
    ('2975'),
    ('2977'),
    ('2978'),
    ('31'),
    ('311'),
    ('3111'),
    ('3112'),
    ('312'),
    ('3121'),
    ('3122'),
    ('318'),
    ('32'),
    ('321'),
    ('322'),
    ('323'),
    ('33'),
    ('331'),
    ('333'),
    ('334'),
    ('335'),
    ('3351'),
    ('3352'),
    ('3353'),
    ('3358'),
    ('338'),
    ('34'),
    ('341'),
    ('3411'),
    ('3412'),
    ('342'),
    ('3421'),
    ('3422'),
    ('343'),
    ('3431'),
    ('3432'),
    ('344'),
    ('3441'),
    ('3442'),
    ('35'),
    ('351'),
    ('3511'),
    ('3512'),
    ('352'),
    ('3521'),
    ('3522'),
    ('36'),
    ('361'),
    ('362'),
    ('37'),
    ('371'),
    ('3711'),
    ('3712'),
    ('372'),
    ('3721'),
    ('3722'),
    ('3723'),
    ('38'),
    ('381'),
    ('382'),
    ('383'),
    ('386'),
    ('387'),
    ('3871'),
    ('3872'),
    ('388'),
    ('39'),
    ('391'),
    ('392'),
    ('393'),
    ('394'),
    ('395'),
    ('396'),
    ('397'),
    ('398'),
    ('40'),
    ('401'),
    ('4011'),
    ('4012'),
    ('4013'),
    ('4017'),
    ('402'),
    ('4021'),
    ('4022'),
    ('4023'),
    ('408'),
    ('4081'),
    ('4082'),
    ('4083'),
    ('4086'),
    ('409'),
    ('4091'),
    ('4092'),
    ('4093'),
    ('4094'),
    ('4098'),
    ('41'),
    ('411'),
    ('4111'),
    ('4112'),
    ('4114'),
    ('4115'),
    ('4116'),
    ('4117'),
    ('4118'),
    ('412'),
    ('4121'),
    ('4122'),
    ('4124'),
    ('4125'),
    ('4126'),
    ('414'),
    ('4141'),
    ('4142'),
    ('415'),
    ('416'),
    ('4161'),
    ('4162'),
    ('418'),
    ('4181'),
    ('4186'),
    ('419'),
    ('4191'),
    ('4192'),
    ('4194'),
    ('4198'),
    ('42'),
    ('421'),
    ('4211'),
    ('4212'),
    ('4213'),
    ('422'),
    ('423'),
    ('4231'),
    ('4232'),
    ('4233'),
    ('424'),
    ('4241'),
    ('4242'),
    ('4245'),
    ('4248'),
    ('425'),
    ('4251'),
    ('4252'),
    ('4258'),
    ('426'),
    ('4261'),
    ('4264'),
    ('427'),
    ('428'),
    ('4281'),
    ('4286'),
    ('4287'),
    ('43'),
    ('431'),
    ('4311'),
    ('4312'),
    ('4313'),
    ('4314'),
    ('4318'),
    ('432'),
    ('433'),
    ('4331'),
    ('4332'),
    ('4333'),
    ('438'),
    ('4381'),
    ('4382'),
    ('4386'),
    ('4387'),
    ('44'),
    ('441'),
    ('442'),
    ('4421'),
    ('4422'),
    ('4423'),
    ('4424'),
    ('4426'),
    ('4428'),
    ('443'),
    ('4431'),
    ('4432'),
    ('4433'),
    ('4434'),
    ('4435'),
    ('444'),
    ('4441'),
    ('4449'),
    ('445'),
    ('4451'),
    ('4452'),
    ('4453'),
    ('4454'),
    ('4455'),
    ('4456'),
    ('446'),
    ('447'),
    ('4471'),
    ('4472'),
    ('4473'),
    ('4474'),
    ('4478'),
    ('448'),
    ('4486'),
    ('4487'),
    ('449'),
    ('4491'),
    ('4492'),
    ('4493'),
    ('4494'),
    ('4495'),
    ('4496'),
    ('4497'),
    ('4499'),
    ('45'),
    ('451'),
    ('452'),
    ('458'),
    ('4581'),
    ('4582'),
    ('46'),
    ('461'),
    ('4611'),
    ('4612'),
    ('4613'),
    ('4614'),
    ('4615'),
    ('4616'),
    ('4617'),
    ('4618'),
    ('4619'),
    ('462'),
    ('4621'),
    ('4626'),
    ('463'),
    ('465'),
    ('466'),
    ('467'),
    ('47'),
    ('471'),
    ('4711'),
    ('4712'),
    ('4713'),
    ('4714'),
    ('4715'),
    ('4716'),
    ('4717'),
    ('4718'),
    ('4719'),
    ('472'),
    ('473'),
    ('4731'),
    ('4732'),
    ('4733'),
    ('4734'),
    ('474'),
    ('4746'),
    ('4747'),
    ('475'),
    ('4751'),
    ('4752'),
    ('476'),
    ('477'),
    ('478'),
    ('4781'),
    ('4782'),
    ('4788'),
    ('479'),
    ('4791'),
    ('4792'),
    ('4798'),
    ('48'),
    ('481'),
    ('4811'),
    ('4812'),
    ('4813'),
    ('4817'),
    ('4818'),
    ('482'),
    ('484'),
    ('485'),
    ('4851'),
    ('4852'),
    ('4857'),
    ('4858'),
    ('488'),
    ('49'),
    ('490'),
    ('491'),
    ('4911'),
    ('4912'),
    ('492'),
    ('493'),
    ('494'),
    ('495'),
    ('496'),
    ('4962'),
    ('4963'),
    ('4966'),
    ('497'),
    ('498'),
    ('4981'),
    ('4982'),
    ('4983'),
    ('499'),
    ('4991'),
    ('4998'),
    ('50'),
    ('501'),
    ('5011'),
    ('5012'),
    ('5013'),
    ('5016'),
    ('502'),
    ('5021'),
    ('5022'),
    ('5023'),
    ('5024'),
    ('5025'),
    ('5026'),
    ('503'),
    ('5031'),
    ('5032'),
    ('5033'),
    ('5035'),
    ('5036'),
    ('504'),
    ('5042'),
    ('5043'),
    ('505'),
    ('506'),
    ('5061'),
    ('5062'),
    ('5063'),
    ('508'),
    ('51'),
    ('511'),
    ('512'),
    ('513'),
    ('514'),
    ('515'),
    ('518'),
    ('5181'),
    ('5182'),
    ('5185'),
    ('5186'),
    ('5187'),
    ('52'),
    ('521'),
    ('5211'),
    ('5212'),
    ('522'),
    ('523'),
    ('524'),
    ('525'),
    ('526'),
    ('53'),
    ('531'),
    ('532'),
    ('533'),
    ('536'),
    ('5361'),
    ('5367'),
    ('538'),
    ('54'),
    ('541'),
    ('542'),
    ('543'),
    ('544'),
    ('545'),
    ('55'),
    ('551'),
    ('552'),
    ('553'),
    ('554'),
    ('5561'),
    ('558'),
    ('56'),
    ('561'),
    ('564'),
    ('565'),
    ('566'),
    ('5667'),
    ('57'),
    ('571'),
    ('5711'),
    ('5712'),
    ('572'),
    ('5721'),
    ('5722'),
    ('573'),
    ('5731'),
    ('5732'),
    ('58'),
    ('581'),
    ('582'),
    ('585'),
    ('588'),
    ('59'),
    ('590'),
    ('591'),
    ('592'),
    ('593'),
    ('594'),
    ('599'),
    ('60'),
    ('601'),
    ('6011'),
    ('6012'),
    ('6013'),
    ('6014'),
    ('6015'),
    ('6019'),
    ('602'),
    ('6021'),
    ('6022'),
    ('6023'),
    ('6024'),
    ('6025'),
    ('6029'),
    ('603'),
    ('6031'),
    ('6032'),
    ('6033'),
    ('604'),
    ('6041'),
    ('6042'),
    ('6043'),
    ('6044'),
    ('6045'),
    ('6046'),
    ('6047'),
    ('6049'),
    ('605'),
    ('6051'),
    ('6052'),
    ('6053'),
    ('6054'),
    ('6055'),
    ('6056'),
    ('6057'),
    ('6058'),
    ('6059'),
    ('608'),
    ('6081'),
    ('6082'),
    ('6083'),
    ('6085'),
    ('6089'),
    ('61'),
    ('612'),
    ('613'),
    ('614'),
    ('616'),
    ('618'),
    ('6181'),
    ('6182'),
    ('6183'),
    ('621'),
    ('622'),
    ('6221'),
    ('6222'),
    ('6223'),
    ('6224'),
    ('6225'),
    ('6226'),
    ('6228'),
    ('623'),
    ('6232'),
    ('6233'),
    ('6234'),
    ('6235'),
    ('624'),
    ('6241'),
    ('6242'),
    ('6243'),
    ('6248'),
    ('625'),
    ('6251'),
    ('6252'),
    ('6253'),
    ('6254'),
    ('6255'),
    ('6257'),
    ('6258'),
    ('626'),
    ('6261'),
    ('6265'),
    ('6266'),
    ('627'),
    ('6271'),
    ('6272'),
    ('6273'),
    ('6274'),
    ('6275'),
    ('6276'),
    ('6277'),
    ('6278'),
    ('628'),
    ('6281'),
    ('6282'),
    ('6283'),
    ('6288'),
    ('631'),
    ('6311'),
    ('6312'),
    ('6313'),
    ('6314'),
    ('6315'),
    ('6316'),
    ('6317'),
    ('6318'),
    ('632'),
    ('6322'),
    ('6324'),
    ('6325'),
    ('6327'),
    ('6328'),
    ('633'),
    ('634'),
    ('6342'),
    ('6343'),
    ('6344'),
    ('6345'),
    ('6346'),
    ('635'),
    ('6351'),
    ('6358'),
    ('637'),
    ('6371'),
    ('6372'),
    ('638'),
    ('6381'),
    ('6382'),
    ('6383'),
    ('6384'),
    ('64'),
    ('641'),
    ('6411'),
    ('6412'),
    ('6413'),
    ('6414'),
    ('6415'),
    ('6418'),
    ('645'),
    ('646'),
    ('6461'),
    ('6462'),
    ('6463'),
    ('6464'),
    ('6468'),
    ('647'),
    ('6471'),
    ('6472'),
    ('6473'),
    ('6474'),
    ('6478'),
    ('648'),
    ('65'),
    ('651'),
    ('6511'),
    ('6515'),
    ('652'),
    ('6521'),
    ('6525'),
    ('654'),
    ('658'),
    ('6581'),
    ('6582'),
    ('6583'),
    ('6588'),
    ('659'),
    ('6591'),
    ('6593'),
    ('6594'),
    ('6598'),
    ('66'),
    ('661'),
    ('6611'),
    ('6612'),
    ('6613'),
    ('6614'),
    ('6615'),
    ('6616'),
    ('6617'),
    ('6618'),
    ('662'),
    ('6621'),
    ('6622'),
    ('6623'),
    ('6624'),
    ('6625'),
    ('6626'),
    ('6627'),
    ('6628'),
    ('663'),
    ('6631'),
    ('6632'),
    ('6633'),
    ('6638'),
    ('664'),
    ('6641'),
    ('6642'),
    ('666'),
    ('6661'),
    ('6662'),
    ('667'),
    ('6671'),
    ('6672'),
    ('668'),
    ('6681'),
    ('6682'),
    ('6683'),
    ('6684'),
    ('6685'),
    ('6686'),
    ('67'),
    ('671'),
    ('6711'),
    ('6712'),
    ('6713'),
    ('6714'),
    ('672'),
    ('6721'),
    ('6722'),
    ('6723'),
    ('6724'),
    ('673'),
    ('674'),
    ('6741'),
    ('6742'),
    ('6743'),
    ('6744'),
    ('6745'),
    ('6748'),
    ('675'),
    ('676'),
    ('677'),
    ('6771'),
    ('6772'),
    ('678'),
    ('6781'),
    ('6782'),
    ('6784'),
    ('679'),
    ('6791'),
    ('6795'),
    ('6798'),
    ('68'),
    ('681'),
    ('6812'),
    ('6813'),
    ('687'),
    ('69'),
    ('691'),
    ('6911'),
    ('6913'),
    ('6914'),
    ('697'),
    ('6971'),
    ('6972'),
    ('70'),
    ('701'),
    ('7011'),
    ('7012'),
    ('7013'),
    ('7014'),
    ('7015'),
    ('702'),
    ('7021'),
    ('7022'),
    ('7023'),
    ('7024'),
    ('7025'),
    ('703'),
    ('7031'),
    ('7032'),
    ('7033'),
    ('7034'),
    ('7035'),
    ('704'),
    ('7041'),
    ('7042'),
    ('7043'),
    ('7044'),
    ('7045'),
    ('705'),
    ('7051'),
    ('7052'),
    ('7053'),
    ('7054'),
    ('7055'),
    ('706'),
    ('7061'),
    ('7062'),
    ('7063'),
    ('7064'),
    ('7065'),
    ('707'),
    ('7071'),
    ('7072'),
    ('7073'),
    ('7074'),
    ('7075'),
    ('7076'),
    ('7077'),
    ('7078'),
    ('71'),
    ('711'),
    ('712'),
    ('713'),
    ('718'),
    ('7181'),
    ('7182'),
    ('7183'),
    ('72'),
    ('721'),
    ('722'),
    ('724'),
    ('726'),
    ('73'),
    ('734'),
    ('7341'),
    ('7342'),
    ('735'),
    ('7351'),
    ('7352'),
    ('736'),
    ('737'),
    ('7371'),
    ('7372'),
    ('75'),
    ('752'),
    ('7521'),
    ('7525'),
    ('754'),
    ('758'),
    ('7581'),
    ('7582'),
    ('7588'),
    ('759'),
    ('7591'),
    ('7593'),
    ('7594'),
    ('7598'),
    ('77'),
    ('771'),
    ('7712'),
    ('7713'),
    ('772'),
    ('7721'),
    ('7722'),
    ('773'),
    ('774'),
    ('7745'),
    ('7746'),
    ('775'),
    ('776'),
    ('777'),
    ('778'),
    ('7781'),
    ('7782'),
    ('7784'),
    ('779'),
    ('7791'),
    ('7795'),
    ('7798'),
    ('78'),
    ('781'),
    ('787'),
    ('79'),
    ('791'),
    ('7911'),
    ('7913'),
    ('7914'),
    ('797'),
    ('7971'),
    ('7972'),
    ('798'),
    ('799'),
    ('81'),
    ('811'),
    ('812'),
    ('816'),
    ('82'),
    ('821'),
    ('822'),
    ('826'),
    ('83'),
    ('831'),
    ('833'),
    ('834'),
    ('835'),
    ('836'),
    ('837'),
    ('839'),
    ('84'),
    ('841'),
    ('843'),
    ('845'),
    ('846'),
    ('847'),
    ('848'),
    ('849'),
    ('85'),
    ('851'),
    ('852'),
    ('853'),
    ('854'),
    ('858'),
    ('86'),
    ('861'),
    ('862'),
    ('863'),
    ('864'),
    ('868'),
    ('87'),
    ('871'),
    ('872'),
    ('878'),
    ('88'),
    ('881'),
    ('884'),
    ('886'),
    ('888'),
    ('89'),
    ('891'),
    ('8911'),
    ('8912'),
    ('8913'),
    ('892'),
    ('895'),
    ('899'),
    ('8991'),
    ('8994'),
    ('9011'),
    ('9012'),
    ('9021'),
    ('9022'),
    ('9023'),
    ('9024'),
    ('9028'),
    ('9031'),
    ('9032'),
    ('9038'),
    ('9041'),
    ('9061'),
    ('9062'),
    ('9063'),
    ('9064'),
    ('9068'),
    ('9071'),
    ('9072'),
    ('9073'),
    ('9078'),
    ('9081'),
    ('9151'),
    ('9183');

UPDATE comptes_ohada c
SET actif = FALSE
WHERE NOT EXISTS (SELECT 1 FROM referentiel_officiel r WHERE r.numero = c.numero)
  AND NOT EXISTS (SELECT 1 FROM grand_livre g            WHERE g.compte_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM lignes_note_frais l      WHERE l.compte_imputation_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM lignes_budget b          WHERE b.compte_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM articles a               WHERE a.compte_stock_id = c.id
                                                            OR a.compte_charge_id = c.id
                                                            OR a.compte_produit_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM depenses_vehicule d      WHERE d.compte_charge_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM mouvements_stock m       WHERE m.compte_contrepartie_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM etablissements_tresorerie e WHERE e.compte_id = c.id);

DROP TABLE IF EXISTS referentiel_officiel;
