// Comptes admis en contrepartie d'une sortie de trésorerie (note de frais de décaissement, 26/11/2026) : ceux que l'on
// débite quand l'argent sort de la caisse, de la banque ou du mobile money (SYSCOHADA révisé). Même règle côté serveur :
// ContrepartieTresorerie.java.
//   6 : charges 60 à 67 (pas les dotations 68-69) ; 2 : immobilisations 21 à 27 (pas 28-29) ;
//   4 : tiers 40 à 48 (pas 49) ; 16-17 : emprunts et dettes de location ; 83, 87, 89 : charges HAO, participation, impôt.
const PREFIXES_DECAISSEMENT = new Set([
  '60', '61', '62', '63', '64', '65', '66', '67',
  '21', '22', '23', '24', '25', '26', '27',
  '40', '41', '42', '43', '44', '45', '46', '47', '48',
  '16', '17',
  '83', '87', '89',
])

export function estContrepartieDecaissement(numero: string | null | undefined): boolean {
  return !!numero && numero.length >= 2 && PREFIXES_DECAISSEMENT.has(numero.slice(0, 2))
}
