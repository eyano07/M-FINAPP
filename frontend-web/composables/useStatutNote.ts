export interface StatutNoteMeta {
  label: string
  /** Couleur pleine (icônes, texte accentué). */
  color: string
  /** Fond clair pour badges/chips. */
  bg: string
  /** Couleur de texte assortie au fond clair. */
  text: string
  /** Dégradé pour bannières/héros. */
  gradient: string
}

/**
 * Palette de couleurs par statut du circuit de note de frais : chaque étape
 * du workflow (Brouillon → Soumise → Vérifiée DFIN → Validée DA → Transmise
 * caisse → Payée, avec les sorties Rejetée/Annulée) a sa propre couleur pour
 * une lecture visuelle immédiate, cohérente sur tout le module.
 */
const PALETTE: Record<string, StatutNoteMeta> = {
  BROUILLON: {
    label: 'Brouillon',
    color: '#2563eb',
    bg: '#dbeafe',
    text: '#1d4ed8',
    gradient: 'linear-gradient(140deg, #60a5fa 0%, #2563eb 50%, #1e3a8a 100%)',
  },
  SOUMISE: {
    label: 'Soumise',
    color: '#7c3aed',
    bg: '#ede9fe',
    text: '#6d28d9',
    gradient: 'linear-gradient(140deg, #a78bfa 0%, #7c3aed 50%, #4c1d95 100%)',
  },
  VERIFIEE_DFIN: {
    label: 'Vérifiée DFIN',
    color: '#f97316',
    bg: '#ffedd5',
    text: '#c2410c',
    gradient: 'linear-gradient(140deg, #fb923c 0%, #ea580c 50%, #7c2d12 100%)',
  },
  VALIDEE_DA: {
    label: 'Validée DA',
    color: '#0891b2',
    bg: '#cffafe',
    text: '#0e7490',
    gradient: 'linear-gradient(140deg, #22d3ee 0%, #0891b2 50%, #164e63 100%)',
  },
  REJETEE_DA: {
    label: 'Rejetée',
    color: '#b45309',
    bg: '#fef3c7',
    text: '#92400e',
    gradient: 'linear-gradient(140deg, #fbbf24 0%, #b45309 50%, #78350f 100%)',
  },
  TRANSMISE_CAISSE: {
    label: 'À payer',
    color: '#16a34a',
    bg: '#dcfce7',
    text: '#15803d',
    gradient: 'linear-gradient(140deg, #4ade80 0%, #16a34a 50%, #14532d 100%)',
  },
  PAYEE: {
    label: 'Payée',
    color: '#047857',
    bg: '#a7f3d0',
    text: '#065f46',
    gradient: 'linear-gradient(140deg, #34d399 0%, #047857 50%, #022c22 100%)',
  },
  ANNULEE: {
    label: 'Annulée',
    color: '#dc2626',
    bg: '#fee2e2',
    text: '#b91c1c',
    gradient: 'linear-gradient(140deg, #f87171 0%, #dc2626 50%, #7f1d1d 100%)',
  },
}

const DEFAUT: StatutNoteMeta = {
  label: 'Inconnu',
  color: '#6b7280',
  bg: '#f3f4f6',
  text: '#6b7280',
  gradient: 'linear-gradient(140deg, #9ca3af 0%, #6b7280 50%, #374151 100%)',
}

/**
 * @param sens Sens de la note ('ENCAISSEMENT' | 'DECAISSEMENT'). Pour une
 * note d'encaissement, l'état terminal PAYEE est relabellisé « Encaissée »
 * (même couleur/palette, seul le libellé change à l'affichage).
 */
export function statutNoteMeta(statut?: string | null, sens?: string | null): StatutNoteMeta {
  if (!statut) return DEFAUT
  const meta = PALETTE[statut]
  if (!meta) return { ...DEFAUT, label: statut.replace(/_/g, ' ') }
  if (statut === 'PAYEE' && sens === 'ENCAISSEMENT') {
    return { ...meta, label: 'Encaissée' }
  }
  return meta
}

export function useStatutNote() {
  return { statutNoteMeta, PALETTE }
}
