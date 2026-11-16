/**
 * Renseignements de la liasse fiscale OHADA (page de garde, fiches R1 à R3),
 * tels que l'API les échange sur /parametres/liasse. Tous facultatifs.
 */
export interface Domiciliation { banque: string, numeroCompte: string }
export interface Activite { designation: string, codeNomenclature: string, pourcentage: number | null }
export interface Dirigeant { nom: string, prenoms: string, qualite: string, nif: string, adresse: string }
export interface Administrateur { nom: string, prenoms: string, qualite: string, adresse: string }
export interface InformationsLiasse {
  pays: string, directionImpots: string, centreDepot: string, sigleUsuel: string,
  boitePostale: string, codePostal: string, ville: string,
  dateArreteComptes: string, exercicePrecedentClos: string, dureeExercicePrecedentMois: number | null,
  greffe: string, numeroRepertoireEntites: string, numeroCaisseSociale: string, codeImportateur: string,
  codeActivitePrincipale: string, activitePrincipale: string, personneContact: string,
  professionnelComptable: string, visaExpertComptable: string, etatsApprouvesAg: boolean | null,
  nomSignataire: string, qualiteSignataire: string, dateSignature: string,
  domiciliations: Domiciliation[],
  formeJuridique: string, regimeFiscal: string | null, paysSiege: string | null,
  nbEtablissementsPays: number | null, nbEtablissementsHorsPays: number | null, premiereAnneeExercice: number | null,
  controle: string | null, activites: Activite[], dirigeants: Dirigeant[], administrateurs: Administrateur[]
}
