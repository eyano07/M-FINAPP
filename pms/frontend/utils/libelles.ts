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
