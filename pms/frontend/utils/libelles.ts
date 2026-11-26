/** Libellés et couleurs communs (statuts de réservation, ménage, durées, canaux). */
export const STATUTS: Record<string, { libelle: string, couleur: string }> = {
  OPTION: { libelle: 'Option', couleur: 'var(--res-option)' },
  CONFIRMEE: { libelle: 'Confirmée', couleur: 'var(--res-confirmee)' },
  ARRIVEE: { libelle: 'Arrivé', couleur: 'var(--res-arrivee)' },
  PARTIE: { libelle: 'Parti', couleur: 'var(--res-partie)' },
  ANNULEE: { libelle: 'Annulée', couleur: 'var(--res-annulee)' },
  NO_SHOW: { libelle: 'Non présenté', couleur: 'var(--res-annulee)' },
}

export const MENAGE: Record<string, { libelle: string, couleur: string, icone: string }> = {
  PROPRE: { libelle: 'Propre', couleur: 'var(--menage-propre)', icone: 'mdi-check-circle-outline' },
  A_NETTOYER: { libelle: 'À nettoyer', couleur: 'var(--menage-sale)', icone: 'mdi-broom' },
  EN_COURS: { libelle: 'En cours', couleur: 'var(--menage-encours)', icone: 'mdi-progress-clock' },
  A_INSPECTER: { libelle: 'À inspecter', couleur: 'var(--menage-inspecter)', icone: 'mdi-magnify' },
}

export const DUREES = [
  { value: 'NUIT', title: 'À la nuit' },
  { value: 'HEURE', title: "À l'heure" },
  { value: 'SEMAINE', title: 'À la semaine' },
  { value: 'MOIS', title: 'Au mois' },
  { value: 'CRENEAU', title: 'Forfait (créneau)' },
]

export const CANAUX = [
  { value: 'RECEPTION', title: 'Réception (comptoir)' },
  { value: 'TELEPHONE', title: 'Téléphone' },
  { value: 'DIRECT', title: 'Site web (direct)' },
  { value: 'BOOKING', title: 'Booking.com' },
  { value: 'AIRBNB', title: 'Airbnb' },
  { value: 'EXPEDIA', title: 'Expedia' },
  { value: 'AUTRE', title: 'Autre' },
]

export const TYPES_ETABLISSEMENT = [
  { value: 'HOTEL', title: 'Hôtel' },
  { value: 'MOTEL', title: 'Motel' },
  { value: 'APPARTEMENTS', title: 'Appartements' },
  { value: 'MIXTE', title: 'Hôtel et appartements' },
]

export const MODES = [
  { value: 'CAISSE', title: 'Espèces (caisse)', icone: 'mdi-cash' },
  { value: 'BANQUE', title: 'Banque / carte', icone: 'mdi-credit-card-outline' },
  { value: 'MOBILE_MONEY', title: 'Mobile money', icone: 'mdi-cellphone' },
]

export const usd = (v: number | string | null | undefined) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(Number(v) || 0)

export const dateCourte = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' }) : '—'

export const dateHeure = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : '—'

/** Date locale AAAA-MM-JJ (pas l'UTC de toISOString). */
export const jourIso = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

// ── Phase 3 : motels, appartements, maintenance ──────────────────────────────────────────────────────────────────
export const CATEGORIES_TICKET: Record<string, { libelle: string, icone: string }> = {
  PLOMBERIE: { libelle: 'Plomberie', icone: 'mdi-pipe-wrench' },
  CLIMATISATION: { libelle: 'Climatisation', icone: 'mdi-air-conditioner' },
  ELECTRICITE: { libelle: 'Électricité', icone: 'mdi-flash-outline' },
  ELECTROMENAGER: { libelle: 'Électroménager', icone: 'mdi-fridge-outline' },
  SERRURERIE: { libelle: 'Serrurerie', icone: 'mdi-lock-outline' },
  MOBILIER: { libelle: 'Mobilier', icone: 'mdi-sofa-outline' },
  PEINTURE: { libelle: 'Peinture', icone: 'mdi-format-paint' },
  INTERNET_TV: { libelle: 'Internet / TV', icone: 'mdi-wifi' },
  AUTRE: { libelle: 'Autre', icone: 'mdi-wrench-outline' },
}

export const PRIORITES: Record<string, { libelle: string, couleur: string }> = {
  BASSE: { libelle: 'Basse', couleur: '#64748b' },
  NORMALE: { libelle: 'Normale', couleur: '#2563eb' },
  HAUTE: { libelle: 'Haute', couleur: '#d97706' },
  URGENTE: { libelle: 'Urgente', couleur: '#e11d48' },
}

export const STATUTS_TICKET: Record<string, { libelle: string, couleur: string }> = {
  OUVERT: { libelle: 'Ouvert', couleur: '#e11d48' },
  EN_COURS: { libelle: 'En cours', couleur: '#d97706' },
  ATTENTE_PIECES: { libelle: 'Attente de pièces', couleur: '#7c3aed' },
  RESOLU: { libelle: 'Résolu', couleur: '#16a34a' },
  ANNULE: { libelle: 'Annulé', couleur: '#64748b' },
}

/** Comptes d'entretien admis pour un achat de maintenance (contrôlés aussi par M-FINAPP). */
export const COMPTES_MAINTENANCE = [
  { value: '6241', title: '6241 — Entretien et réparations des biens immobiliers' },
  { value: '6242', title: '6242 — Entretien et réparations des biens mobiliers' },
  { value: '6243', title: '6243 — Maintenance' },
  { value: '6248', title: '6248 — Autres entretiens et réparations' },
  { value: '6044', title: '6044 — Fournitures d’atelier (pièces)' },
  { value: '6056', title: '6056 — Petit matériel et outillage' },
  { value: '6043', title: '6043 — Produits d’entretien' },
]
/** Compte proposé selon la catégorie de la panne. */
export const COMPTE_PAR_CATEGORIE: Record<string, string> = {
  PLOMBERIE: '6241', ELECTRICITE: '6241', SERRURERIE: '6241', PEINTURE: '6241',
  CLIMATISATION: '6242', ELECTROMENAGER: '6242', MOBILIER: '6242', INTERNET_TV: '6243', AUTRE: '6248',
}

export const STATUTS_NOTE: Record<string, { libelle: string, couleur: string }> = {
  BROUILLON: { libelle: 'Brouillon', couleur: '#64748b' },
  SOUMISE: { libelle: 'Chez le DFIN', couleur: '#2563eb' },
  VERIFIEE_DFIN: { libelle: 'Chez le DA', couleur: '#7c3aed' },
  VALIDEE_DA: { libelle: 'Validée par le DA', couleur: '#0d9488' },
  REJETEE_DA: { libelle: 'Rejetée par le DA', couleur: '#e11d48' },
  TRANSMISE_CAISSE: { libelle: 'À la caisse', couleur: '#d97706' },
  PAYEE: { libelle: 'Payée', couleur: '#16a34a' },
  ANNULEE: { libelle: 'Annulée', couleur: '#64748b' },
}

export const STATUTS_ECHEANCE: Record<string, { libelle: string, couleur: string }> = {
  A_FACTURER: { libelle: 'À facturer', couleur: '#64748b' },
  EN_ATTENTE: { libelle: 'En attente de M-FINAPP', couleur: '#d97706' },
  FACTUREE: { libelle: 'Facturée', couleur: '#2563eb' },
  REGLEE: { libelle: 'Réglée', couleur: '#16a34a' },
}

export const ETATS_ELEMENT: Record<string, { libelle: string, couleur: string }> = {
  NEUF: { libelle: 'Neuf', couleur: '#0d9488' },
  BON: { libelle: 'Bon', couleur: '#16a34a' },
  USAGE: { libelle: 'Usagé', couleur: '#d97706' },
  ABIME: { libelle: 'Abîmé', couleur: '#e11d48' },
  MANQUANT: { libelle: 'Manquant', couleur: '#7f1d1d' },
}

export const heure = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) : '—'
