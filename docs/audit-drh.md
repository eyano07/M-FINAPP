# Audit du module DRH (paie) — octobre 2026

Périmètre : bulletins de paie, paramètres de paie, déclaration sociale, export « DEBOURS MBSC »,
comptabilisation de la paie, et leur raccordement aux notes de frais et à la trésorerie.

## 1. Nouveau circuit de règlement de la paie (SYSCOHADA révisé)

Avant : la clôture d'un mois créait, si un réglage était actif, une pièce brouillon par bulletin, et
rien ne réglait la paie ni les impôts. Désormais :

1. **Clôture du mois** (écran Bulletins de paie) : verrouille les bulletins validés ; refusée s'il en
   reste en brouillon. Aucune écriture.
2. **Note de paie** (une par mois) : une ligne par agent au débit de **4221.1** pour son net à payer.
   Elle suit le circuit normal (vérification DFIN, approbation DA, transmission) et se paie par la
   caisse, la banque ou le mobile money.
3. **Au paiement de la note de paie**, dans la même transaction :
   - **Constatation** (journal OD, une pièce groupée, datée du dernier jour du mois de paie) :

     | Débit | Crédit |
     |---|---|
     | 6611 / 6621 appointements (national / non national) | 431.1 CNSS salariale + patronale |
     | 6612 / 6622 primes | 4472 IPR retenu |
     | 6613 / 6623 congés payés | 4478.1 ONEM |
     | 6616 / 6626 supplément familial | 4478.2 INPP |
     | 6618 / 6628 heures supplémentaires | 4211 avances sur salaire |
     | 6631 logement, 6638 transport | 2762 prêts au personnel |
     | 6641 / 6642 CNSS patronale | **4221.1 salaires nets** |
     | **6413** ONEM + INPP (taxes sur salaires) | |

   - **Règlement** : débit 4221.1, crédit trésorerie (571, 521x ou 552x).
4. **Notes fiscales** (une par organisme et par mois, possibles une fois la paie payée) : IPR (débit
   4472, DGI), CNSS (431.1), INPP (4478.2), ONEM (4478.1), crédit trésorerie.

Vérifié sur une base PostgreSQL de test : après le parcours complet, les soldes de 4221.1, 431.1,
4472, 4478.1 et 4478.2 sont nuls ; la pièce de constatation est équilibrée et datée du 30/09.

Annulation : une note non payée s'annule depuis l'écran DRH ; les bulletins sont libérés, la paie peut
être rouverte, corrigée puis recréée. Une note de paie ne peut être annulée qu'après ses notes fiscales.

## 2. Bugs corrigés

| # | Constat | Correction |
|---|---|---|
| 1 | Compte **4221.1** crédité par la paie mais créé par aucune migration : clôture en erreur 500 sur une base neuve. | Créé par la migration V103. |
| 2 | Un bulletin **validé ou annulé** restait modifiable tant que le mois n'était pas clôturé. | Seul un bulletin en brouillon se modifie. |
| 3 | La modification recalculait l'IPR avec le nombre d'enfants **actuel** de l'employé, pas celui figé sur le bulletin. | Le nombre figé est réutilisé. |
| 4 | La modification dérivait la date du taux de change du mois de la **requête**, pas du bulletin. | Mois, année et date du bulletin font foi. |
| 5 | Un **net négatif** (avances + prêts > salaire) faisait échouer toute la clôture du mois. | Validation refusée avec un message clair. |
| 6 | Le message « annulez la pièce pour rouvrir » ne correspondait à aucune fonction. | Vraie réouverture du mois. |
| 7 | Écritures de paie marquées en **CDF** alors que les montants sont en USD. | Devise USD. |
| 8 | Le total de l'export « DEBOURS MBSC » additionnait aussi les bulletins **brouillons et annulés**. | Total des seuls bulletins validés. |
| 9 | Clôture possible avec des bulletins encore en brouillon ; bulletin possible pour un employé **désactivé**. | Refusés. |
| 10 | Sans taux de change, l'**IPR valait 0** sans alerte. | Erreur explicite. |
| 11 | Les paramètres de paie pouvaient être créés dans une transaction en **lecture seule** (refus PostgreSQL au premier accès). | Création dans sa propre transaction. |
| 12 | Le **DG** était autorisé sur la déclaration sociale mais n'avait pas accès au module. | Autorisation retirée, cohérente avec les droits du module. |
| 13 | ONEM et INPP comptabilisés en **charges sociales (664)** ; rémunérations accessoires des expatriés en 661. | 6413 « Taxes sur appointements et salaires » ; toutes les rémunérations des expatriés en 662x. |

## 3. Écarts au droit congolais — signalés, non corrigés (décision du 09/10/2026)

Les formules de calcul n'ont pas été modifiées. À arbitrer avec le conseil fiscal et social :

1. **Bases de cotisation et d'impôt incomplètes** : la CNSS, l'ONEM et l'IPR sont calculés sur le
   salaire de base moins le logement et le transport (`PayrollCalculationService`, lignes 76 à 89). Les
   primes, les heures supplémentaires et le congé payé n'y entrent pas, alors qu'ils sont imposables et
   cotisables : les retenues sont sous-évaluées.
2. **Base INPP** : elle inclut les allocations familiales légales, normalement exonérées.
3. **Logement et transport** : 30 % et 10 % du brut, exonérés sans plafond légal ; le contrôle de
   l'exonération du transport est désactivé par défaut (plafond 0).
4. **Taux ONEM par défaut** : 0,5 % au lieu de 0,2 %.
5. **Taux INPP** : 3 % fixe, sans la dégressivité selon l'effectif (3 %, 2 %, 1 %).
6. **CNSS salariale non déduite de la base IPR** par défaut (`cnssDeductibleIpr = false`).
7. **Barème IPR codé en dur** (tranches à 3 %, 15 %, 30 % et 40 %), alors que la documentation le dit
   paramétrable.
8. **Conjoint ignoré** dans la réduction familiale de l'IPR : seuls les enfants comptent.
9. **Double arrondi de l'IPR** (en francs congolais puis en dollars) : petits écarts possibles avec la
   déclaration en francs congolais.
10. **Employés « non conformes »** : leurs cotisations sont calculées, déclarées et comptabilisées
    comme les autres ; seul le document imprimé change.

## 4. Autres constats

- **Présences** : le pourcentage de présence est saisi à la main ; le module Présences
  (`PresenceService.pourcentagePresence`) n'est pas utilisé par la paie.
- **Date d'impression** : elle peut être antidatée sans trace.
- **Suppression** : un bulletin annulé peut être supprimé définitivement ; seule une ligne de journal
  applicatif en garde la trace.
- **Tests** : seul le moteur de calcul était testé. Ajout de `PaieComptabilisationServiceTest` et
  `PaieNoteServiceTest` (équilibre, comptes, règles de création, constatation au paiement) ; les
  services de bulletins restent sans tests d'intégration.
- **Mois déjà comptabilisés** : les bulletins clôturés avec l'ancien circuit (une pièce par
  bulletin) ne sont pas repris par une note de paie ; leur règlement reste manuel.

## 5. Constat hors DRH

- **Minerais** : la clôture des camions et des frais liés à une note de règlement
  (`MineraiService.finaliserReglementNoteInterne`) n'est faite que pour un paiement en **caisse** ;
  payée par banque ou mobile money, la note laisse les camions « à régler ».
