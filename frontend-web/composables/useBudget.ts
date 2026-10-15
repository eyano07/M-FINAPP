/**
 * Outils partagés du module Budget : libellés des statuts et des sections, mois, regroupement des douze
 * mois en trimestres / semestres / année (toujours des totaux exacts des mois, jamais saisis à part),
 * format des montants (USD, devise de base de la comptabilité).
 */

export const MOIS_COURTS = ['Janv.', 'Févr.', 'Mars', 'Avr.', 'Mai', 'Juin', 'Juil.', 'Août', 'Sept.', 'Oct.', 'Nov.', 'Déc.']

export type Periode = 'MENSUEL' | 'TRIMESTRIEL' | 'SEMESTRIEL' | 'ANNUEL'

export const PERIODES: { value: Periode, title: string }[] = [
  { value: 'MENSUEL', title: 'Mensuel' },
  { value: 'TRIMESTRIEL', title: 'Trimestriel' },
  { value: 'SEMESTRIEL', title: 'Semestriel' },
  { value: 'ANNUEL', title: 'Annuel' },
]

/** Libellés des colonnes d'une période. */
export function colonnesPeriode(p: Periode): string[] {
  switch (p) {
    case 'MENSUEL': return MOIS_COURTS
    case 'TRIMESTRIEL': return ['T1', 'T2', 'T3', 'T4']
    case 'SEMESTRIEL': return ['S1', 'S2']
    default: return ['Année']
  }
}

const n = (v: unknown) => {
  const x = Number(v)
  return Number.isFinite(x) ? x : 0
}

/** Arrondi au centime (les sommes de flottants gardent sinon des résidus du type 0,30000000000000004). */
export const centimes = (v: number) => Math.round(v * 100) / 100

/** Regroupe douze montants mensuels selon la période. */
export function regrouper(mensuel: (number | string | null | undefined)[], p: Periode): number[] {
  const m = Array.from({ length: 12 }, (_, i) => n(mensuel?.[i]))
  const somme = (a: number, b: number) => centimes(m.slice(a, b).reduce((s, v) => s + v, 0))
  switch (p) {
    case 'MENSUEL': return m.map(centimes)
    case 'TRIMESTRIEL': return [somme(0, 3), somme(3, 6), somme(6, 9), somme(9, 12)]
    case 'SEMESTRIEL': return [somme(0, 6), somme(6, 12)]
    default: return [somme(0, 12)]
  }
}

export const total = (mensuel: (number | string | null | undefined)[]) => centimes((mensuel || []).reduce<number>((s, v) => s + n(v), 0))

/** Cumul de janvier au mois indiqué (1-12). */
export const cumul = (mensuel: (number | string | null | undefined)[], jusqua: number) =>
  centimes((mensuel || []).slice(0, Math.max(0, Math.min(12, jusqua))).reduce<number>((s, v) => s + n(v), 0))

/**
 * Parts égales au centime près sur les mois `debut` à `fin` (1-12) d'une période, zéro ailleurs ; les centimes
 * restants vont sur le dernier mois de la période (même règle que le serveur).
 */
export function repartirUniformement(montant: number, debut = 1, fin = 12): number[] {
  const totalCentimes = Math.round(n(montant) * 100)
  const nombre = fin - debut + 1
  const part = Math.floor(totalCentimes / nombre)
  const mois = Array(12).fill(0)
  for (let m = debut; m < fin; m++) mois[m - 1] = part / 100
  mois[fin - 1] = (totalCentimes - part * (nombre - 1)) / 100
  return mois
}

// ---------------------------------------------------------------------------------------------
// Période couverte par un budget : l'exercice, un semestre, un trimestre ou un mois
// ---------------------------------------------------------------------------------------------

export const MOIS_LONGS = ['Janvier', 'Février', 'Mars', 'Avril', 'Mai', 'Juin', 'Juillet', 'Août', 'Septembre', 'Octobre', 'Novembre', 'Décembre']

export type DureeBudget = 'ANNUEL' | 'SEMESTRIEL' | 'TRIMESTRIEL' | 'MENSUEL'

export const DUREES: { value: DureeBudget, title: string, mois: number }[] = [
  { value: 'ANNUEL', title: 'Annuel (exercice entier)', mois: 12 },
  { value: 'SEMESTRIEL', title: 'Semestriel (6 mois)', mois: 6 },
  { value: 'TRIMESTRIEL', title: 'Trimestriel (3 mois)', mois: 3 },
  { value: 'MENSUEL', title: 'Mensuel (1 mois)', mois: 1 },
]

const moisDeLaDuree = (d?: string | null) => DUREES.find(x => x.value === d)?.mois ?? 12
const ordinal = (p: number) => (p === 1 ? '1er' : `${p}e`)

/** Périodes possibles d'une durée : S1-S2, T1-T4, janvier-décembre (l'année n'en a qu'une). */
export function periodesDe(d: DureeBudget): { value: number, title: string }[] {
  const nombre = 12 / moisDeLaDuree(d)
  return Array.from({ length: nombre }, (_, i) => {
    const p = i + 1
    const title = d === 'MENSUEL' ? MOIS_LONGS[i]
      : d === 'TRIMESTRIEL' ? `${ordinal(p)} trimestre (${MOIS_COURTS[3 * i]} – ${MOIS_COURTS[3 * i + 2]})`
        : d === 'SEMESTRIEL' ? `${ordinal(p)} semestre (${MOIS_COURTS[6 * i]} – ${MOIS_COURTS[6 * i + 5]})`
          : 'Exercice entier'
    return { value: p, title }
  })
}

/** Premier et dernier mois (1-12) couverts par une période. */
export function moisDeLaPeriode(d?: string | null, periode?: number | null): [number, number] {
  const nombre = moisDeLaDuree(d)
  const p = Math.max(1, Math.min(12 / nombre, Number(periode) || 1))
  return [(p - 1) * nombre + 1, p * nombre]
}

/** « Exercice 2026 », « 2e semestre 2026 », « 4e trimestre 2026 », « Décembre 2026 » (même libellé que le serveur). */
export function libellePeriode(d?: string | null, periode?: number | null, exercice?: number | null): string {
  const p = Number(periode) || 1
  switch (d) {
    case 'SEMESTRIEL': return `${ordinal(p)} semestre ${exercice}`
    case 'TRIMESTRIEL': return `${ordinal(p)} trimestre ${exercice}`
    case 'MENSUEL': return `${MOIS_LONGS[p - 1] || ''} ${exercice}`
    default: return `Exercice ${exercice}`
  }
}

/** Nom court de la durée : « année », « semestre », « trimestre », « mois ». */
export const motPeriode = (d?: string | null) => ({ SEMESTRIEL: 'semestre', TRIMESTRIEL: 'trimestre', MENSUEL: 'mois' } as Record<string, string>)[d || ''] || 'année'

/** true si le mois (1-12) appartient à la période. */
export const dansLaPeriode = (mois: number, debut: number, fin: number) => mois >= debut && mois <= fin

const formatteur = new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const formatteurEntier = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 })

/** 1 234,56 (sans devise). */
export const fmtMontant = (v: unknown) => formatteur.format(n(v))
/** 1 235 (arrondi à l'unité, pour les graphiques et les indicateurs). */
export const fmtEntier = (v: unknown) => formatteurEntier.format(n(v))
export const fmtTaux = (v: unknown) => (v === null || v === undefined ? '—' : `${formatteurEntier.format(n(v))} %`)

export const STATUTS_BUDGET: Record<string, { label: string, color: string, icon: string }> = {
  BROUILLON: { label: 'Brouillon', color: 'grey', icon: 'mdi-pencil-outline' },
  SOUMIS: { label: 'Soumis au DA', color: 'blue', icon: 'mdi-send-outline' },
  APPROUVE: { label: 'Approuvé', color: 'green', icon: 'mdi-check-decagram-outline' },
  REJETE: { label: 'Rejeté', color: 'red', icon: 'mdi-close-octagon-outline' },
  EN_EXECUTION: { label: 'En exécution', color: 'teal', icon: 'mdi-play-circle-outline' },
  REMPLACE: { label: 'Remplacé par une révision', color: 'blue-grey', icon: 'mdi-swap-horizontal' },
  CLOTURE: { label: 'Clôturé', color: 'brown', icon: 'mdi-archive-outline' },
}

export const statutBudget = (s?: string | null) => STATUTS_BUDGET[s || ''] || { label: s || '—', color: 'grey', icon: 'mdi-help-circle-outline' }

export const SECTIONS: Record<string, { label: string, color: string, sens: string }> = {
  PRODUITS: { label: 'Produits', color: '#16a34a', sens: 'Recettes attendues (classe 7)' },
  CHARGES: { label: 'Charges', color: '#dc2626', sens: 'Dépenses de fonctionnement (classe 6)' },
  INVESTISSEMENTS: { label: 'Investissements', color: '#2563eb', sens: 'Acquisitions d’immobilisations (classe 2)' },
}

export const libelleSection = (s?: string | null) => SECTIONS[s || '']?.label || s || '—'

/**
 * Lecture d'un écart (réalisé - prévu) selon la section : sur une charge ou un investissement, un écart
 * positif est un dépassement (défavorable) ; sur un produit, une recette supérieure à la prévision (favorable).
 */
export function ecartFavorable(section: string | null | undefined, ecart: number): boolean | null {
  if (!ecart) return null
  return section === 'PRODUITS' ? ecart > 0 : ecart < 0
}

export const STATUTS_CONTROLE: Record<string, { label: string, color: string, icon: string }> = {
  CONFORME: { label: 'Budgétée', color: 'success', icon: 'mdi-check-circle-outline' },
  DEPASSEMENT: { label: 'Dépassement', color: 'warning', icon: 'mdi-alert-outline' },
  HORS_BUDGET: { label: 'Hors budget', color: 'error', icon: 'mdi-alert-octagon-outline' },
  SANS_BUDGET: { label: 'Aucun budget', color: 'error', icon: 'mdi-calendar-remove-outline' },
  NON_CONCERNE: { label: 'Non concernée', color: 'grey', icon: 'mdi-minus-circle-outline' },
}

export const statutControle = (s?: string | null) => STATUTS_CONTROLE[s || ''] || { label: s || '—', color: 'grey', icon: 'mdi-help-circle-outline' }
