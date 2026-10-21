<script lang="ts">
/** Ligne réduite d'un retour de fonds : l'ancien et le nouveau de la ligne de note, et la part rendue (TTC). */
export interface LigneRetourFonds {
  ligneNoteId: number | null
  description?: string | null
  articleLibelle?: string | null
  unite?: string | null
  /** Prix unitaire HT pour un achat de marchandise, montant HT de la ligne sinon. */
  ancienMontant: number
  nouveauMontant: number
  ancienneQuantite?: number | null
  nouvelleQuantite?: number | null
  montantRetour: number
}

export type StatutRetourFonds = 'EN_ATTENTE' | 'VALIDE' | 'REFUSE' | 'ANNULE'

/** Demande de retour de fonds sur une note payée (RetourFondsResponse côté serveur). */
export interface RetourFonds {
  id: number
  noteId: number
  noteReference: string
  noteObjet?: string | null
  noteBeneficiaire?: string | null
  devise?: string | null
  statut: StatutRetourFonds
  motif: string
  /** Montant rendu, TTC, dans la devise de la note. */
  montant: number
  demandeurNom?: string | null
  dateDemande?: string | null
  caissierNom?: string | null
  dateDecision?: string | null
  motifRefus?: string | null
  transactionReference?: string | null
  numeroRecu?: string | null
  lignes: LigneRetourFonds[]
}

export const STATUTS_RETOUR_FONDS: Record<StatutRetourFonds, { label: string, color: string, icon: string }> = {
  EN_ATTENTE: { label: 'En attente de la caisse', color: 'amber-darken-3', icon: 'mdi-timer-sand' },
  VALIDE: { label: 'Reçu à la caisse', color: 'teal', icon: 'mdi-cash-check' },
  REFUSE: { label: 'Refusé par la caisse', color: 'red', icon: 'mdi-cash-remove' },
  ANNULE: { label: "Retiré par l'émetteur", color: 'grey', icon: 'mdi-undo' },
}
</script>

<script setup lang="ts">
// Détail d'un retour de fonds : qui l'a demandé et pourquoi, chaque ligne réduite (avant → après, part rendue) et la
// décision de la caisse. Sert à l'historique de la note et à la file d'attente de la page Caisse.
const props = withDefaults(defineProps<{
  retour: RetourFonds
  /** Masque la pastille de statut (la file d'attente de la caisse ne montre que des demandes en attente). */
  avecStatut?: boolean
  /** Masque le montant rendu quand le conteneur l'affiche déjà (carte de la file d'attente de la caisse). */
  avecMontant?: boolean
}>(), {
  avecStatut: true,
  avecMontant: true,
})

const meta = computed(() => STATUTS_RETOUR_FONDS[props.retour.statut] ?? STATUTS_RETOUR_FONDS.EN_ATTENTE)

function fmt(v?: number | null) {
  return new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(v || 0))
}
function fmtQuantite(v?: number | null) {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 3 }).format(Number(v || 0))
}
function fmtDate(d?: string | null) {
  return d ? new Date(d).toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' }) : ''
}

function titre(l: LigneRetourFonds) {
  return l.articleLibelle || l.description || 'Ligne de la note'
}

/**
 * Unité accordée : « 48 bouteilles », « 1 bouteille », « 12,5 kg ». Seul un nom (lettres, plus de deux) prend la marque du
 * pluriel, à partir de 2 ; un symbole (kg, L) reste invariable.
 */
function quantiteUnite(q: number, unite?: string | null) {
  if (!unite) return fmtQuantite(q)
  const nom = /^\p{L}{3,}$/u.test(unite) && !/[sxz]$/i.test(unite)
  return `${fmtQuantite(q)} ${nom && Math.abs(Number(q)) >= 2 ? `${unite}s` : unite}`
}

/** « 48 bouteilles → 36 bouteilles » pour un achat de marchandise, « 50,00 → 30,00 USD » pour une autre ligne. */
function avantApres(l: LigneRetourFonds) {
  if (l.ancienneQuantite != null && l.nouvelleQuantite != null) {
    return `${quantiteUnite(l.ancienneQuantite, l.unite)} → ${quantiteUnite(l.nouvelleQuantite, l.unite)}`
  }
  return `${fmt(l.ancienMontant)} → ${fmt(l.nouveauMontant)} ${props.retour.devise || ''}`.trim()
}
</script>

<template>
  <div class="rfd">
    <div class="rfd__tete">
      <v-chip v-if="avecStatut" size="small" variant="tonal" :color="meta.color" :prepend-icon="meta.icon">
        {{ meta.label }}
      </v-chip>
      <strong v-if="avecMontant" class="rfd__montant">{{ fmt(retour.montant) }} {{ retour.devise }}</strong>
      <span class="rfd__meta">
        demandé par {{ retour.demandeurNom || '—' }}<template v-if="retour.dateDemande"> le {{ fmtDate(retour.dateDemande) }}</template>
      </span>
    </div>
    <p class="rfd__motif"><span class="rfd__libelle">Motif :</span> {{ retour.motif }}</p>
    <ul class="rfd__lignes">
      <li v-for="(l, i) in retour.lignes" :key="l.ligneNoteId ?? i" class="rfd__ligne">
        <span class="rfd__ligne-titre">{{ titre(l) }}</span>
        <span class="rfd__ligne-valeurs">{{ avantApres(l) }}</span>
        <span class="rfd__ligne-rendu">{{ fmt(l.montantRetour) }} {{ retour.devise }}</span>
      </li>
    </ul>
    <p v-if="retour.statut === 'VALIDE'" class="rfd__decision">
      <v-icon icon="mdi-cash-check" size="14" class="mr-1" />
      Reçu par {{ retour.caissierNom || '—' }} le {{ fmtDate(retour.dateDecision) }}<template v-if="retour.numeroRecu"> · reçu n° {{ retour.numeroRecu }}</template><template v-if="retour.transactionReference"> ({{ retour.transactionReference }})</template>
    </p>
    <p v-else-if="retour.statut === 'REFUSE'" class="rfd__decision rfd__decision--refus">
      <v-icon icon="mdi-cash-remove" size="14" class="mr-1" />
      Refusé par {{ retour.caissierNom || '—' }} le {{ fmtDate(retour.dateDecision) }} : {{ retour.motifRefus }}
    </p>
    <p v-else-if="retour.statut === 'ANNULE'" class="rfd__decision rfd__decision--neutre">
      <v-icon icon="mdi-undo" size="14" class="mr-1" />
      Demande retirée le {{ fmtDate(retour.dateDecision) }}
    </p>
  </div>
</template>

<style scoped>
.rfd { display: flex; flex-direction: column; gap: 6px; }
.rfd__tete { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.rfd__montant { font-size: 0.95rem; color: #111827; }
.rfd__meta { font-size: 0.75rem; color: #6b7280; }
.rfd__motif { margin: 0; font-size: 0.85rem; color: #374151; white-space: pre-line; overflow-wrap: anywhere; }
.rfd__libelle { font-weight: 600; }
.rfd__lignes { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
.rfd__ligne {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 4px 12px;
  align-items: baseline;
  font-size: 0.8rem;
  padding: 4px 8px;
  border-radius: 6px;
  background: #f9fafb;
}
.rfd__ligne-titre { color: #111827; font-weight: 500; overflow-wrap: anywhere; }
.rfd__ligne-valeurs { color: #6b7280; white-space: nowrap; }
.rfd__ligne-rendu { color: #0f766e; font-weight: 600; white-space: nowrap; }
@media (max-width: 600px) {
  .rfd__ligne { grid-template-columns: minmax(0, 1fr) auto; }
  .rfd__ligne-valeurs { grid-column: 1 / -1; grid-row: 2; white-space: normal; }
}
.rfd__decision { margin: 0; font-size: 0.78rem; color: #0f766e; display: flex; align-items: center; flex-wrap: wrap; }
.rfd__decision--refus { color: #b91c1c; }
.rfd__decision--neutre { color: #6b7280; }
</style>
