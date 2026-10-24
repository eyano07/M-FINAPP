<script setup lang="ts">
import type { LigneEcritureLecture } from './LignesEcritureLecture.vue'

/**
 * Liste des pièces d'un journal (caisse, banque, mobile money, livre-journal) en accordéon : le titre résume la pièce, le détail
 * montre ses écritures. Un seul composant pour les quatre pages, qui en recopiaient chacune le balisage.
 *
 * Titre : sur PC tout tient sur une ligne ; sur téléphone et tablette, la référence et le montant sont en tête, le libellé
 * dessous sur toute la largeur (jamais coupé), puis la date et le statut.
 */
export interface PieceJournal {
  id: number
  reference: string
  datePiece: string
  libelle: string
  statut: string
  journal?: string
  lignes: LigneEcritureLecture[]
}

const props = withDefaults(defineProps<{
  pieces: PieceJournal[]
  /** Formate un montant dans la devise du journal. */
  fmt: (montant: number) => string
  /** Montant du titre de la pièce (par défaut son total débit, `totalDebit`). */
  montant?: ((piece: PieceJournal) => number) | null
  /** Pastille du journal de chaque pièce (livre-journal), couleur par code de journal. */
  couleursJournal?: Record<string, string> | null
  /** Colonne « Taux » des écritures. */
  fmtTaux?: ((taux: number) => string) | null
  /** Unité des en-têtes Débit / Crédit. */
  unite?: string
}>(), { montant: null, couleursJournal: null, fmtTaux: null, unite: '' })

const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '')
const montantDe = (p: PieceJournal) => (props.montant ? props.montant(p) : ((p as unknown as { totalDebit: number }).totalDebit ?? 0))
</script>

<template>
  <v-expansion-panels variant="accordion" class="jp">
    <v-expansion-panel v-for="p in pieces" :key="p.id" elevation="0" class="classroom-card jp__piece">
      <v-expansion-panel-title>
        <div class="jp__titre">
          <div class="jp__tete">
            <v-chip v-if="couleursJournal && p.journal" size="small" :color="couleursJournal[p.journal] || 'primary'" variant="tonal">
              {{ p.journal }}
            </v-chip>
            <span class="jp__reference">{{ p.reference }}</span>
          </div>
          <span class="jp__montant">{{ fmt(montantDe(p)) }}</span>
          <span class="jp__libelle">{{ p.libelle }}</span>
          <div class="jp__meta">
            <span class="jp__date">{{ fmtDate(p.datePiece) }}</span>
            <v-chip v-if="p.statut === 'ANNULEE'" size="small" color="error" variant="tonal">Annulée</v-chip>
          </div>
        </div>
      </v-expansion-panel-title>
      <v-expansion-panel-text>
        <CommunLignesEcritureLecture :lignes="p.lignes" :fmt="fmt" :fmt-taux="fmtTaux" :unite="unite" />
      </v-expansion-panel-text>
    </v-expansion-panel>
  </v-expansion-panels>
</template>

<style scoped>
.jp :deep(.v-expansion-panel) { margin-bottom: 8px; border-radius: 12px !important; }

.jp__titre {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 14px;
  width: 100%;
  min-width: 0;
}
.jp__tete { display: flex; align-items: center; gap: 8px; order: 1; }
.jp__meta { display: flex; align-items: center; gap: 8px; order: 2; }
.jp__libelle { order: 3; flex: 1 1 240px; min-width: 0; color: #374151; overflow-wrap: anywhere; }
.jp__montant { order: 4; font-weight: 700; white-space: nowrap; font-variant-numeric: tabular-nums; }
.jp__reference { font-weight: 700; white-space: nowrap; }
.jp__date { color: #6b7280; white-space: nowrap; }

@media screen and (max-width: 959px) {
  .jp :deep(.v-expansion-panel-title) { padding: 12px 14px; }
  .jp__titre {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas: 'tete montant' 'libelle libelle' 'meta meta';
    gap: 4px 12px;
  }
  .jp__tete { grid-area: tete; flex-wrap: wrap; }
  .jp__montant { grid-area: montant; font-size: 1rem; }
  .jp__libelle { grid-area: libelle; font-size: 0.9rem; line-height: 1.35; }
  .jp__meta { grid-area: meta; font-size: 0.82rem; }
}
</style>
