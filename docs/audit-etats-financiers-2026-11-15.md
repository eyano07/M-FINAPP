# Audit du classeur des états financiers — MBSC, situation au 13/11/2026

Fichier audité : `Etats_financiers_MBSC_2026-11-13 (4).xlsx` (export du 13/11/2026 à 17:01, 62 feuilles).
Période : 01/01/2026 – 13/11/2026. Référentiel : SYSCOHADA révisé, Système normal.
Méthode : relecture de chaque feuille, recalcul indépendant à partir du Journal (1 048 écritures, 271 pièces),
rapprochements entre feuilles, puis revue au regard de l'Acte uniforme relatif au droit comptable (AUDCIF).
Les numéros d'articles sont cités de mémoire : à confirmer sur le texte avant tout usage officiel.

Les montants sont repris tels qu'ils figurent dans le classeur ; l'unité monétaire n'y est indiquée nulle part (voir C-2).

## 1. Synthèse

| Domaine | Constat | Verdict |
|---|---|---|
| Arithmétique (journal, balance, bilan, résultat) | Tout est équilibré et se recoupe au centime | Conforme |
| Tableau des flux de trésorerie | Écart de 895 avec la trésorerie du bilan, corrigé le 15/11 | Corrigé |
| Liasse (BILAN, notes, fiches) | 3 défauts de génération, corrigés et déployés en local le 15/11 | Corrigé |
| Principes comptables | Aucun amortissement, stock non inventorié, libellés, exercice | **Non conforme** |
| Situation financière | Capitaux propres négatifs (−77 201,66), perte de 81 796,66 | **Alerte** |

Le classeur est **fiable comme image du Grand Livre** : ce qui est saisi est fidèlement restitué. Il n'est **pas
encore présentable comme états financiers SYSCOHADA** : les écarts viennent surtout de la comptabilité elle-même
(écritures d'inventaire absentes) et, pour une part, du générateur.

## 2. Ce qui est juste (contrôles passés)

| Contrôle | Résultat |
|---|---|
| Journal : total débit = total crédit | 5 267 318,84 des deux côtés |
| Chaque pièce équilibrée | 271 / 271 |
| Numérotation des pièces | PC-2026-000535 à 000805, continue, sans trou ni doublon |
| Balance : ouverture, mouvements, clôture | 169 195,00 / 5 098 123,84 / 369 407,00, équilibrés |
| Journal = ouverture + mouvements de la balance | 169 195,00 + 5 098 123,84 = 5 267 318,84 |
| Bilan : total actif = total passif | 179 860,34 (N) ; 169 195,00 (N-1) |
| Résultat du compte de résultat = résultat au passif (CJ) | −81 796,66 |
| Produits − charges recalculés depuis la balance | 107 750,00 − 189 546,66 = −81 796,66 |
| Bilan d'ouverture = soldes d'ouverture de la balance | 169 195,00 |
| Notes 3A, 4, 11, 14, 18, 19, 21 à 27A, 29, 31 = postes du bilan et du résultat | Identiques |
| Soldes de caisse et de banque jamais créditeurs au fil des jours | Vérifié jour par jour |
| Formules du classeur | Aucune erreur (#REF!, #DIV/0!…), toutes calculées |
| Variation du compte courant d'associé | +91 086 = perte 81 796,66 + investissements nets 7 895 + hausse de trésorerie 2 770,34 − dette au personnel 1 376 |

## 3. Anomalies du classeur (générateur)

### G-1 — Le tableau des flux ne retombe pas sur la trésorerie (majeur, corrigé le 15/11)

Le TFT aboutit à une trésorerie de fin de période de **6 470,34**, le bilan affiche **7 365,34** ; la feuille
signale elle-même l'écart de **−895,00**.

Cause : les remboursements de prêts au personnel (comptes 272 : 50 + 375 + 250 + 70 + 50 + 100 = 895) ne sont pas
repris à la ligne FJ « Encaissements liés aux cessions d'immobilisations financières ». Les décaissements (FH,
2 720) sont comptés, les encaissements non. Avec FJ = 895, le flux d'investissement devient −7 895 et le TFT
retombe sur 7 365,34. Correctif : FJ reprend les prix de cession de titres (826) et les remboursements des
comptes 27 ; vérifié sur une copie de la base (écart nul).

### G-2 — Formules « partagées » héritées du modèle (majeur, corrigé le 15/11)

Le modèle de liasse contenait 537 formules recopiées par groupe. En réécrivant une case d'un groupe, le générateur
laissait l'attribut de partage ; le tableur recalculait alors d'après la formule d'origine. Effets dans ce fichier :

| Feuille | Case | Affiché | Attendu |
|---|---|---|---|
| NOTE 13 | Total du capital | 0 | 1 000 |
| BILAN | Immobilisations corporelles N-1 | 0 | 106 800 |
| BILAN | Immobilisations financières N-1 | 0 | 7 800 |
| BILAN | Total actif immobilisé N-1 | 0 | 164 600 |
| BILAN | Créances N-1 | 7 800 | 0 |
| NOTE 34 | Fonds de roulement N-1 | 4 595 | −160 005 |
| NOTE 34 | Trésorerie nette N-1 | 161 395 | 4 595 |

Les colonnes N et les feuilles ACTIF / PASSIF sont exactes. Correctif : chaque case porte désormais sa propre
formule, et un test le vérifie.

### G-3 — Note 3D : fausse moins-value de 895 (corrigé le 15/11)

Les remboursements de prêts étaient présentés comme des cessions d'immobilisations financières sans prix de
cession, d'où une moins-value fictive de −895. La note ne se remplit plus que si des cessions sont comptabilisées
(comptes 81 et 82).

### G-4 — Fiche R2 (corrigé le 15/11)

« Désignation entité » affichait le texte `=_SoNom` au lieu du nom, et le total des parts d'activité valait 101 %
(un « 100 % » en dur restait sur la première ligne du modèle).

### G-5 — Incohérences de présentation (mineur, non corrigé)

- **Chiffre d'affaires** : 107 680 au compte de résultat, 107 750 dans la feuille Ratios, qui y ajoute les autres
  produits (70).
- **Durée** : « 11 mois » sur les états, 10,41 mois dans les Ratios.
- **Libellé du TFT** : « Trésorerie nette au 31 décembre » pour une situation au 13/11.
- **Note 34** : rentabilités de +1,06 obtenues en divisant une perte par des capitaux propres négatifs — le signe
  positif est trompeur ; le ratio n'a pas de sens dans ce cas.
- **TFT, colonne N-1** : entièrement à zéro (pas de flux de l'exercice précédent en base).

## 4. Anomalies comptables (données), au regard du SYSCOHADA

### C-1 — Aucun amortissement (majeur)

172 495 d'immobilisations (permis 50 000, matériel et mobilier 84 442, véhicules 28 428) et **zéro** dotation,
zéro compte 28. Le registre des immobilisations de l'application est vide. L'amortissement est obligatoire même en
l'absence de bénéfice (AUDCIF, art. 45 ; principe de prudence). Le résultat et l'actif sont surévalués.
Ordre de grandeur, à durées usuelles (matériel 5 à 10 ans, véhicules 4 à 5 ans) : 15 000 à 25 000 par an.

### C-2 — Unité monétaire non indiquée et douteuse (majeur)

Aucune feuille n'indique la monnaie. En base, les 1 048 écritures portent la devise **CDF**, alors que les montants
(capital 1 000, salaires mensuels d'environ 9 750) ressemblent à des dollars. Les états doivent être établis dans
la monnaie légale du pays (AUDCIF, art. 17) et la mention de l'unité est obligatoire. À trancher : soit les montants
sont en USD et la devise enregistrée est fausse, soit une conversion manque.

### C-3 — « Exercice clos le 13/11/2026 » (majeur)

Il s'agit d'une situation intermédiaire, non d'un exercice clos : l'exercice coïncide avec l'année civile et dure
douze mois (AUDCIF, art. 7). La dernière écriture date du 05/11. La page de garde et les en-têtes devraient porter
« Situation au… », et la liasse définitive être éditée au 31/12 après les écritures d'inventaire.

### C-4 — Stocks : pas d'inventaire, marge négative (majeur)

Achats de minerais 32 000, ventes 30 000, stock final nul. L'entrée en stock de 17 000 est annulée le jour même
(13/07), trois semaines avant la première vente (06/08) ; le second achat (15 000, 15/10) ne passe pas par le stock.
Si tout a bien été vendu, l'activité de négoce est vendue à perte (marge −2 000) ; sinon il manque un stock final.
Un inventaire physique est requis à la clôture de chaque exercice (AUDCIF).

### C-5 — Libellés d'écritures sans contenu (majeur)

1 030 écritures sur 1 048 (98 %) ont pour libellé l'intitulé du compte (« Caisse siège social », « Autres charges
diverses »…). Chaque écriture doit préciser la nature de l'opération et la référence de la pièce justificative
(AUDCIF, organisation comptable, art. 17 et suivants). En l'état, le journal ne permet pas de remonter aux justificatifs.

### C-6 — Charges sociales et fiscales sur salaires anormalement basses (majeur)

| Élément | Montant | En % des rémunérations (91 440) |
|---|---|---|
| Charges sociales patronales (6641) | 2 226 | 2,4 % |
| Impôt retenu sur salaires (4472) | 920 | 1,0 % |
| CNSS passée par le compte 4315 | 2 678 | 2,9 % |

Les taux légaux congolais sont très supérieurs (CNSS employeur de l'ordre de 13 %, plus INPP et ONEM ; IPR
progressif). Risque de redressement. Par ailleurs 85 705 de salaires sont au compte **6621**, réservé au personnel
**non national**, contre 1 440 au 6611 : à vérifier.

### C-7 — Capitaux propres négatifs (alerte juridique)

Capitaux propres : **−77 201,66** pour un capital de 1 000. Ils sont inférieurs à la moitié du capital : les
associés doivent être consultés dans les quatre mois suivant l'approbation des comptes pour décider de la
dissolution ou de la poursuite, et la situation régularisée dans les deux ans (AUDSCGIE, art. 371 à 373 pour la
SARL). La note 2 du classeur affirme la continuité d'exploitation sans mentionner cette incertitude : c'est une
omission significative.

### C-8 — Dépendance totale au compte courant d'associé

Le compte 46211 passe de 164 600 à **255 686** (+91 086) et représente 142 % du total du bilan. Il finance la perte
et les investissements. Classé en « Autres dettes » (passif circulant), il gonfle le flux opérationnel du TFT à
+10 665 alors que l'exploitation consomme 81 797. S'il est durable, une convention de blocage permettrait de le
classer en dettes financières (compte 16) et d'assainir la lecture.

### C-9 — Flux de caisse sans rapport avec l'activité (contrôle interne)

2,46 millions sont entrés et sortis de la caisse pour un chiffre d'affaires de 107 680. Des espèces transitent par
les comptes 47311 PARAGON (1 101 200) et 47312 SOKOROSHE (157 500), soldés à zéro : 1 000 000 le 04/11 ressorti le
05/11, 600 000 entré et sorti le 04/11. La banque n'enregistre que 6 000 de mouvements. Ces opérations pour compte
de tiers appellent des conventions écrites, des justificatifs, et un examen au regard des règles de lutte contre le
blanchiment et des plafonds de paiement en espèces.

### C-10 — Autres points

| Réf. | Constat | Gravité |
|---|---|---|
| C-10a | Compte 6588 « Autres charges diverses » : 12 475,28 (6,6 % des charges) sans détail | Moyen |
| C-10b | Entreprise déclarée assujettie à la TVA, aucun compte 443/445 mouvementé sur 107 680 de ventes | Majeur |
| C-10c | Aucun impôt sur le résultat ni impôt minimum provisionné (compte 89) | Moyen |
| C-10d | Capital au compte 1011 « Capital souscrit, non appelé » : devrait être au 1013 (appelé, versé) | Mineur |
| C-10e | Permis de recherche minier (50 000) au 2128, ni amorti ni testé pour dépréciation | Moyen |
| C-10f | Prêts à des personnes (comptes 272) sans échéancier ni dépréciation examinée | Mineur |
| C-10g | Amendes (6478 : 400), non déductibles — à réintégrer fiscalement | Mineur |
| C-10h | Septembre : salaires 6611 et congés 6623 passés puis extournés jusqu'à cinq fois | Mineur |
| C-10i | Deux journaux seulement (Caisse, Opérations diverses) ; achats, ventes et banque en OD | Mineur |
| C-10j | Aucune charge à payer, charge constatée d'avance ni provision : séparation des exercices non appliquée | Moyen |
| C-10k | Colonne N-1 du compte de résultat vide et résultat N-1 nul : comparatifs absents | Moyen |
| C-10l | Identité de l'entité : « MBSC Finapp » (nom du logiciel), NIF, RCCM, adresse et fiches R1 à R3 vides | Moyen |

## 5. Lecture financière

| Indicateur | Valeur | Lecture |
|---|---|---|
| Chiffre d'affaires | 107 680 | Services 77 680, négoce 30 000 |
| Valeur ajoutée | 13 654,34 | 12,7 % du chiffre d'affaires |
| Charges de personnel | 95 441 | 88,6 % du chiffre d'affaires, 7 fois la valeur ajoutée |
| Excédent brut d'exploitation | −81 786,66 | L'exploitation ne couvre pas les salaires |
| Résultat net | −81 796,66 | Avant amortissements, impôts et charges sociales manquants |
| Fonds de roulement | −249 696,66 | Immobilisations financées par une dette exigible |
| Liquidité générale | 0,03 | 7 365 de trésorerie pour 257 062 de dettes courantes |
| Autonomie de trésorerie | 0,4 mois | Moins de deux semaines de charges |

Le résultat réel est plus dégradé que −81 797 : il faut y ajouter les amortissements (C-1) et les charges sociales
et fiscales non constatées (C-6, C-10b, C-10c).

## 6. Recommandations, par ordre de priorité

1. Trancher la monnaie de tenue (C-2) et l'afficher sur chaque état.
2. Créer les fiches d'immobilisations et passer les amortissements (C-1).
3. Faire l'inventaire des stocks et régulariser le compte 31 (C-4).
4. Régulariser charges sociales, IPR et TVA ; provisionner l'impôt minimum (C-6, C-10b, C-10c).
5. Documenter les opérations PARAGON et SOKOROSHE et le compte courant d'associé ; envisager son blocage (C-8, C-9).
6. Convoquer les associés sur les capitaux propres négatifs et compléter la note 2 (C-7).
7. Renseigner des libellés explicites et ventiler le compte 6588 (C-5, C-10a).
8. Compléter l'identité de l'entreprise et les fiches R1 à R3 dans Paramètres (C-10l).
9. Générateur : harmoniser chiffre d'affaires, durée et libellés (G-5), intituler
   « Situation au… » une période qui n'est pas un exercice (C-3).
