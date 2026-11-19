// Aide en ligne (18/11/2026) : ce que chaque rôle fait dans M-FINAPP, et comment. La page /aide affiche d'abord les
// fiches des rôles du compte connecté ; l'administrateur peut ouvrir celles des autres rôles.
// À tenir à jour quand une règle métier change (circuit des notes, trésorerie, restaurant…).

export interface EtapeAide { titre: string; texte: string }
export interface FicheAide {
  role: string
  icone: string
  resume: string
  taches: EtapeAide[]
  /** Ce que le rôle ne fait pas : évite de chercher un bouton qui n'existe pas pour lui. */
  limites?: string[]
}

/** Pour tout le monde : compte, notifications, application mobile. */
export const AIDE_COMMUNE: EtapeAide[] = [
  { titre: 'Se connecter', texte: "Saisissez votre nom d'utilisateur ou votre e-mail, puis votre mot de passe. Un compte créé par l'administrateur reçoit un mot de passe provisoire : il faut le remplacer à la première connexion." },
  { titre: 'Mon profil', texte: 'Depuis votre avatar (en haut à droite) ou le menu : nom, photo, mot de passe et double authentification. Activez la double authentification pour protéger votre compte.' },
  { titre: 'Notifications', texte: "La cloche signale ce qui vous attend : note à vérifier, à valider, à payer, note rejetée… Touchez une notification pour ouvrir l'élément concerné." },
  { titre: 'Mode clair ou nuit', texte: 'Le bouton soleil/lune de la barre du haut bascule entre clair, nuit et le réglage du système. Ce choix est propre à chaque appareil.' },
  { titre: 'Application mobile', texte: "L'application Android sert à valider les notes de frais et à consulter le tableau de bord, les notes et la comptabilité. Le caissier y encaisse, décaisse et vend ; le comptable y crée ses notes. Au premier lancement, saisissez l'adresse du serveur donnée par l'administrateur." },
  { titre: "Widgets de l'écran d'accueil", texte: "Sur Android, appui long sur l'écran d'accueil › Widgets › MBSC Finapp : le widget « Notes de frais » montre les notes qui vous attendent et vos notes en cours ; le widget « Trésorerie » (caissier, DA, DFIN, comptable, DG) est un mini tableau de bord des soldes et des mouvements du jour, à activer dans Mon profil ; il se met à jour à chaque opération. Connectez-vous une fois dans l'application pour qu'ils se remplissent." },
  { titre: "Changer d'exercice", texte: "Sur le site, la liste « Exercice 2026 » de la barre du haut donne accès aux exercices précédents. La page ouverte se recharge avec les données de l'exercice choisi : journaux, balance, grand livre, bilan, compte de résultat, pièces… Un exercice clôturé (cadenas) se consulte en lecture seule : vous pouvez imprimer et exporter les documents permis par vos droits, mais rien n'y est saisi. « Revenir à 2026 », dans le bandeau, ramène à l'exercice en cours." },
  { titre: 'Imprimer et exporter', texte: "Les boutons PDF et Excel des pages enregistrent le fichier ; sur le téléphone il arrive dans Téléchargements › MBSC Finapp et s'ouvre dans l'application adaptée." },
]

export const FICHES_AIDE: FicheAide[] = [
  {
    role: 'CAISSIER',
    icone: 'mdi-cash-register',
    resume: "Vous tenez la trésorerie : caisse, banque et mobile money (si leur module est activé). Vous encaissez, décaissez, payez les notes validées et vendez.",
    taches: [
      { titre: 'Payer une note de frais', texte: "Caisse (ou Banque, Mobile Money) › « Notes en attente de paiement » : ouvrez la note validée par le DA et transmise par le DFIN, vérifiez le montant et confirmez le paiement. Un reçu est proposé à l'impression." },
      { titre: 'Encaisser', texte: "Bouton « Encaissement » de la caisse, ou le « + » de la barre du bas sur le téléphone › Note d'encaissement. Toute entrée d'argent passe par une note : aucune écriture de trésorerie n'est saisie directement." },
      { titre: 'Décaisser', texte: "Créez une note de décaissement (Notes de frais › Nouvelle note, ou « + » › Note de décaissement), joignez les pièces et soumettez-la. Elle suit le circuit DFIN, DA, puis le DFIN la transmet à la caisse : elle revient alors chez vous pour paiement." },
      { titre: 'Vendre', texte: "Ventes › Nouvelle vente (ou « + » › Nouvelle vente) : choisissez le client, les articles, puis validez et réglez. Les retours de vente payés sont remboursés après accord du responsable." },
      { titre: 'Retour de fonds', texte: "Quand un émetteur réduit sa note payée, vous recevez l'argent rendu : validez le retour à la réception depuis la caisse." },
      { titre: 'Journaux et rapport', texte: 'Journal de caisse, de banque, de mobile money : filtrez par période, basculez USD/FC, imprimez le rapport de caisse du jour, de la semaine ou du mois.' },
      { titre: 'Clients et fournisseurs', texte: 'Ventes › Clients et Logistique › Fournisseurs : créez ou complétez les fiches ; chaque fournisseur reçoit son compte propre automatiquement.' },
    ],
    limites: ["Vous ne validez pas les notes (c'est le rôle du DFIN puis du DA).", "Vous ne saisissez pas de pièces comptables."],
  },
  {
    role: 'COMPTABLE',
    icone: 'mdi-calculator-variant-outline',
    resume: 'Vous tenez la comptabilité SYSCOHADA : saisie des pièces, contrôle des imputations, états. Vous créez les notes de décaissement.',
    taches: [
      { titre: 'Créer une note de frais', texte: "Notes de frais › Nouvelle note (ou « + » sur le téléphone) : objet, bénéficiaire, lignes avec leur compte, service budgétaire, pièces jointes. Une dépense hors budget n'est jamais bloquée : elle passe chez le DFIN avec la mention « hors budget »." },
      { titre: 'Saisir une pièce comptable', texte: 'Comptabilité › Pièces comptables : saisissez la pièce équilibrée (débit = crédit). Elle reste en brouillon jusqu\'à sa comptabilisation par le DFIN.' },
      { titre: 'Annuler une pièce', texte: "Vous seul annulez une pièce ; le DFIN ou l'administrateur peuvent la restaurer. Une pièce en brouillon se restaure aussi de votre côté." },
      { titre: "Solde d'ouverture", texte: "Comptabilité › Solde d'ouverture : la pièce du 1er janvier aux seuls comptes de bilan reprend les soldes de l'exercice précédent." },
      { titre: 'États et contrôles', texte: 'Grand livre par compte, balance de vérification, livre-journal, compte de résultat, bilan, TVA, régularisations, clôture : consultation, impression et export Excel.' },
    ],
    limites: ["Vous n'encaissez ni ne décaissez : le paiement est fait par le caissier.", "Vous ne comptabilisez pas vos propres pièces : le DFIN les valide."],
  },
  {
    role: 'DFIN',
    icone: 'mdi-finance',
    resume: 'Directeur financier : vous vérifiez les notes de frais, corrigez leur imputation et comptabilisez les pièces saisies par le comptable.',
    taches: [
      { titre: 'Vérifier une note', texte: "Notes de frais › onglet « En attente » : ouvrez la note soumise. Vous pouvez corriger le libellé, le compte d'imputation de chaque ligne et le service budgétaire, ajouter une observation, puis la vérifier (elle part chez le DA) ou la rejeter en motivant." },
      { titre: 'Transmettre à la caisse', texte: "Une note validée par le DA revient chez vous : transmettez-la à la caisse pour paiement. Une note rejetée par le DA revient aussi chez vous, pour correction ou annulation." },
      { titre: 'Notes hors budget', texte: "Elles vous arrivent avec la mention « (hors budget) » : vérifiez la justification ; la décision finale revient au DA." },
      { titre: 'Comptabiliser les pièces', texte: 'Comptabilité › Pièces comptables : relisez les brouillons du comptable et comptabilisez-les. Vous pouvez restaurer une pièce annulée.' },
      { titre: 'Budgets', texte: 'Budgets : préparez les budgets par période et par service, suivez leur exécution et les dépassements.' },
      { titre: 'États financiers', texte: 'Bilan, compte de résultat, TFT, liasse OHADA : consultation et export.' },
      { titre: 'Sur le téléphone', texte: "Vous vérifiez, rejetez, commentez et corrigez libellé, compte et service directement depuis l'application." },
    ],
    limites: ["Vous ne payez pas les notes (caissier).", "La validation finale d'une note appartient au DA."],
  },
  {
    role: 'DA',
    icone: 'mdi-shield-check-outline',
    resume: "Vous validez les notes de frais vérifiées par le DFIN et fixez leur priorité de paiement.",
    taches: [
      { titre: 'Valider ou rejeter', texte: "Notes de frais › « En attente » : ouvrez la note vérifiée, lisez les observations du DFIN, puis validez (elle revient au DFIN, qui la transmet à la caisse) ou rejetez en motivant. Pour une note hors budget, motivez votre décision." },
      { titre: 'Priorité', texte: "Sur chaque note, fixez la priorité de paiement. Le caissier paie d'abord les notes prioritaires." },
      { titre: 'Seuils de priorité', texte: 'Notes de frais › Seuils de priorité : la réserve minimale de trésorerie à préserver pour chaque niveau de priorité. Vous seul les réglez.' },
      { titre: 'Observations', texte: "Ajoutez un commentaire ou une observation sur la note : l'émetteur et le DFIN la voient." },
      { titre: 'Sur le téléphone', texte: "Validation, rejet, priorité, observations et seuils se font aussi depuis l'application ; une notification vous prévient de chaque note à valider." },
    ],
  },
  {
    role: 'DG',
    icone: 'mdi-account-tie-outline',
    resume: "Directeur général : vous suivez l'activité et les finances de l'entreprise.",
    taches: [
      { titre: 'Tableau de bord', texte: 'Trésorerie, notes en cours, ventes, budget : la vue d’ensemble de l’entreprise.' },
      { titre: 'Consulter', texte: 'Notes de frais, comptabilité (balance, bilan, compte de résultat), budgets, analyses des ventes : tout en lecture, avec export.' },
    ],
  },
  {
    role: 'ADMIN',
    icone: 'mdi-cog-outline',
    resume: "Vous paramétrez le système : utilisateurs, rôles, modules, établissements, taux, sécurité.",
    taches: [
      { titre: 'Utilisateurs', texte: "Administration › Utilisateurs : créez les comptes (mot de passe provisoire à changer au premier accès), attribuez les rôles, désactivez un compte." },
      { titre: 'Modules et permissions', texte: "Administration › Modules : activez ou coupez un module (Caisse, Banque, Ventes, Restaurant…). Couper un module masque ses rôles et désactive leurs comptes, réactivés à son retour. Permissions : niveau lecture/écriture par rôle." },
      { titre: 'Paramètres', texte: "Nom, logo et couleur de l'entreprise (le thème suit sur le site et l'application mobile), banques et mobile money, taux de change et de TVA, facture normalisée DGI, messagerie, IA." },
      { titre: "Journal d'audit", texte: "Administration › Journal d'audit : qui a fait quoi et quand. Il n'est jamais effacé." },
      { titre: 'Serveur local', texte: 'Administration › Serveur local et synchronisation : mode local ou en ligne, état de la synchronisation.' },
    ],
    limites: ["Par séparation des tâches, vous n'encaissez, ne décaissez ni ne vendez.", "Les seuils de priorité appartiennent au DA."],
  },
  {
    role: 'DIRECTEUR',
    icone: 'mdi-briefcase-outline',
    resume: 'Vous créez les notes de frais de votre direction et suivez leur avancement.',
    taches: [
      { titre: 'Créer une note', texte: 'Notes de frais › Nouvelle note : objet, bénéficiaire, lignes, pièces jointes, puis soumettez.' },
      { titre: 'Suivre', texte: 'Le statut de chaque note (soumise, vérifiée, validée, transmise à la caisse, payée, rejetée) et les observations des validateurs sont sur la fiche de la note.' },
      { titre: 'Retour de fonds', texte: "Sur une note payée, vous pouvez réduire le montant et rendre l'argent non utilisé : le caissier valide la réception." },
    ],
  },
  {
    role: 'LOGISTIQUE',
    icone: 'mdi-truck-outline',
    resume: 'Vous gérez les articles, les entrepôts, les mouvements de stock et les fournisseurs.',
    taches: [
      { titre: 'Articles et entrepôts', texte: 'Logistique › Articles, Services, Entrepôts : tenez le catalogue et les lieux de stockage.' },
      { titre: 'Mouvements', texte: "Logistique › Mouvements : entrées, sorties et transferts. L'état du stock et le grand livre stock se mettent à jour." },
      { titre: 'Inventaires', texte: 'Inventaires physiques : comptez, saisissez les écarts, validez.' },
      { titre: 'Camions de minerais', texte: "Suivi des camions et de leur règlement (module Mine, activé par l'administrateur)." },
      { titre: 'Inventaire du patrimoine', texte: "Patrimoine › Inventaire et étiquettes : chaque bien relevé a son code ; imprimez les étiquettes (code QR) et collez-les. « Valoriser » : saisissez la valeur et la date. À partir de 100 USD, la fiche d'amortissement (durée SYSCOHADA de la catégorie) est créée et l'acquisition comptabilisée ; en dessous, c'est du petit matériel suivi sans écriture. La fiche se modifie ensuite dans Patrimoine › Immobilisations (crayon)." },
      { titre: 'Sorties de consommables', texte: "Patrimoine › Sorties de consommables › Nouvelle sortie : choisissez le consommable et l'entrepôt, la quantité et le service qui le reçoit. La sortie est validée d'un coup et sa charge passe en comptabilité au coût moyen." },
    ],
  },
  {
    role: 'GEST_PATRIMOINE',
    icone: 'mdi-office-building-cog-outline',
    resume: "Vous tenez le patrimoine : immobilisations, amortissements, consommables, inventaires.",
    taches: [
      { titre: 'Immobilisations', texte: "Patrimoine › Immobilisations : enregistrez chaque bien (date, valeur, durée) ; le plan d'amortissement est calculé." },
      { titre: 'Amortissements', texte: 'Patrimoine › Amortissements : dotations de la période, à comptabiliser.' },
      { titre: 'Consommables et inventaires', texte: 'Suivez les consommables et faites les inventaires du patrimoine. Patrimoine › Sorties de consommables liste les fournitures remises aux services (saisies par la logistique).' },
      { titre: 'Notes de frais', texte: 'Vos dépenses passent par une note de frais, comme pour les autres services.' },
    ],
  },
  {
    role: 'RECEPTIONNISTE',
    icone: 'mdi-room-service-outline',
    resume: "Vous tenez la réception de l'hôtellerie : réservations, arrivées, départs et encaissement des folios.",
    taches: [
      { titre: "Ouvrir l'hôtellerie", texte: "Menu Hôtellerie › Ouvrir l'hôtellerie : l'application s'ouvre dans un nouvel onglet, déjà connectée à votre compte." },
      { titre: 'Réservations et calendrier', texte: 'Le calendrier montre chaque unité jour par jour ; une unité ne peut pas être réservée deux fois sur les mêmes dates.' },
      { titre: 'Arrivée', texte: "Fiche du client, pièce d'identité, unité attribuée, code d'accès ou clé ; acompte et caution s'encaissent ici." },
      { titre: 'Départ et encaissement', texte: "Le folio (nuitées et extras) devient une vente M-FINAPP : vous l'encaissez en caisse, banque ou mobile money ; l'acompte et la caution retenue s'imputent d'eux-mêmes." },
    ],
    limites: ["Vous n'encaissez que les folios de l'hôtellerie.", 'Les annulations de ventes validées restent à l\'administrateur de M-FINAPP.'],
  },
  {
    role: 'GOUVERNANTE',
    icone: 'mdi-broom',
    resume: "Vous suivez le ménage : état de chaque unité et tâches de nettoyage créées aux départs.",
    taches: [
      { titre: 'État des unités', texte: 'Propre, à nettoyer, en cours, hors service : mettez à jour l\'état depuis la tablette ou le téléphone.' },
      { titre: 'Tâches', texte: 'Chaque départ crée une tâche « à nettoyer » ; marquez-la terminée pour rendre l\'unité disponible.' },
    ],
    limites: ["Vous n'encaissez rien et ne modifiez pas les réservations."],
  },
  {
    role: 'TECHNICIEN',
    icone: 'mdi-tools',
    resume: 'Vous traitez la maintenance des unités : réparations signalées par la réception ou le ménage.',
    taches: [
      { titre: 'Tickets', texte: 'Ouvrez le ticket, indiquez l\'avancement ; une unité hors service est bloquée dans le calendrier jusqu\'à la fin de la réparation.' },
    ],
    limites: ["Les achats de pièces passent par une note de frais dans M-FINAPP."],
  },
  {
    role: 'DIR_HEBERGEMENT',
    icone: 'mdi-office-building-marker-outline',
    resume: "Vous dirigez l'hébergement : établissements, unités, tarifs, et vous suivez l'occupation et le chiffre d'affaires.",
    taches: [
      { titre: 'Établissements et unités', texte: 'Hôtel, motel ou résidence ; types d\'unité, chambres et appartements, capacités.' },
      { titre: 'Tarifs', texte: "Prix à l'heure, à la nuit, à la semaine ou au mois, par type d'unité." },
      { titre: 'Suivi', texte: "Tableau de bord de l'hôtellerie : arrivées, départs, occupation ; le chiffre d'affaires figure aussi dans M-FINAPP (ventes, analytique 986)." },
    ],
    limites: ['Vous pouvez encaisser les folios comme la réception.'],
  },
  {
    role: 'RESP_RESTAURANT',
    icone: 'mdi-silverware-fork-knife',
    resume: 'Vous dirigez le restaurant : carte, salles, cuisine et bar, analyses des ventes.',
    taches: [
      { titre: 'Carte et salles', texte: 'Restaurant › Carte : plats et boissons avec leur prix. Salles et tables : plan de la salle.' },
      { titre: 'Retours de vente', texte: 'Restaurant › Retours de ventes : acceptez ou refusez les retours ; un retour payé est remboursé par le caissier après votre accord.' },
      { titre: 'Analyses', texte: 'Analyses des ventes par jour, plat, serveur ; stock consolidé cuisine et bar.' },
      { titre: 'Plats périmés', texte: "Les portions non vendues après leur durée de conservation sont retirées de la vente : selon le choix de l'administrateur, vous recevez une notification pour les retirer, ou le retrait est automatique." },
    ],
  },
  {
    role: 'RESP_CUISINE',
    icone: 'mdi-chef-hat',
    resume: 'Vous gérez la cuisine : fiches techniques, production des plats, provisions.',
    taches: [
      { titre: 'Fiches techniques', texte: 'Cuisine › Fiches techniques : ingrédients, quantités et durée de conservation de chaque plat.' },
      { titre: 'Production', texte: "Cuisine › Production : enregistrez les portions produites ; les provisions utilisées sortent du stock. Les portions non vendues après leur durée de conservation deviennent périmées." },
      { titre: 'Provisions', texte: "Les provisions entrent par l'achat de provisions ; les sorties se font par type (liste) avec une observation." },
      { titre: 'Sorties de plats', texte: 'Cadeau, repas du personnel, périmé, moisi, renversé, brûlé : chaque motif a son compte.' },
    ],
  },
  {
    role: 'RESP_BAR',
    icone: 'mdi-glass-cocktail',
    resume: 'Vous gérez le bar : boissons, achats, bouteilles vides, pertes.',
    taches: [
      { titre: 'Achat de boissons', texte: "Bar › Achat de boissons : par carton ou à l'unité ; la boisson est avec ou sans consigne selon sa fiche." },
      { titre: 'Bouteilles vides', texte: 'Bar › Bouteilles vides et Mouvements : une boisson offerte ou périmée remet sa bouteille dans les vides.' },
      { titre: 'Pertes sans consigne', texte: 'Bar › Pertes sans consigne : casse et pertes des boissons sans bouteille consignée.' },
      { titre: 'Stock', texte: 'Stock cuisine & bar : quantités disponibles ; les commandes en brouillon réservent le stock.' },
    ],
  },
  {
    role: 'RESP_DRH',
    icone: 'mdi-account-group-outline',
    resume: 'Vous gérez le personnel : employés, présences, paie, missions.',
    taches: [
      { titre: 'Employés', texte: "DRH › Employés : dossier de chaque employé ; un compte comptable propre lui est attribué." },
      { titre: 'Présences', texte: 'Présences et fiche manuelle : pointage quotidien ; sorties de travailleurs.' },
      { titre: 'Paie', texte: "Bulletins de paie (formule RDC, CNSS, ONEM, INPP), avances et prêts, provision pour congés payés. Paramètres de paie : barèmes." },
      { titre: 'Missions', texte: 'Ordres de mission, sites opérationnels, rotations.' },
    ],
  },
]
