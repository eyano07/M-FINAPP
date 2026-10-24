<script setup lang="ts">
/**
 * Liste des notes de frais en TABLEAU (PC) : l'alternative sobre aux cartes de la page, choisie par un bouton à bascule et
 * enregistrée sur le compte (stores/preferences.ts). Chaque ligne porte la couleur de l'étape du circuit de validation (même
 * palette que les cartes : composables/useStatutNote.ts) : liseré à gauche, pastille de statut, teinte très légère de la ligne.
 * En dessous de 960 px la page n'affiche que les cartes.
 */
interface NoteTableau {
  id: number
  reference: string
  objet: string
  montant: number
  devise?: string
  statut: string
  sens?: string | null
  priorite?: string | null
  /** Contrôle budgétaire relevé à la soumission (hors budget, dépassement...). */
  statutBudget?: string | null
  createurNom?: string
  dateCreation?: string
  /** Notes du module DRH : PAIE ou IMPOT_PAIE (comptes imposés), avec leur mois de paie. */
  categorie?: string | null
  paieMois?: number | null
  paieAnnee?: number | null
  organismePaie?: string | null
  /** Service qui engage la dépense. */
  serviceNom?: string | null
}

defineProps<{ notes: NoteTableau[] }>()
const emit = defineEmits<{ (e: 'ouvrir', id: number): void }>()

/** Ordre des étapes du circuit (tri de la colonne Statut), puis les sorties. */
const ORDRE_STATUT = ['BROUILLON', 'SOUMISE', 'VERIFIEE_DFIN', 'VALIDEE_DA', 'TRANSMISE_CAISSE', 'PAYEE', 'REJETEE_DA', 'ANNULEE']
const rang = (statut: string) => {
  const i = ORDRE_STATUT.indexOf(statut)
  return i === -1 ? ORDRE_STATUT.length : i
}

const entetes = [
  { title: 'Référence', key: 'reference', width: 160 },
  { title: 'Objet', key: 'objet', sortable: false },
  { title: 'Statut', key: 'statut', width: 200, sort: (a: string, b: string) => rang(a) - rang(b) },
  { title: 'Date', key: 'dateCreation', width: 110 },
  // Pas de tri par montant : la liste mêle des francs et des dollars, l'ordre serait trompeur.
  { title: 'Montant', key: 'montant', width: 140, align: 'end' as const, sortable: false },
  { title: 'Actions', key: 'actions', width: 96, align: 'end' as const, sortable: false },
]

const meta = (n: NoteTableau) => statutNoteMeta(n.statut, n.sens)

const prioriteMeta: Record<string, { label: string; bg: string; color: string; icon: string }> = {
  HAUTE:   { label: 'Haute',   bg: '#fee2e2', color: '#dc2626', icon: 'mdi-arrow-up-bold' },
  MOYENNE: { label: 'Moyenne', bg: '#ffedd5', color: '#ea580c', icon: 'mdi-minus' },
  BASSE:   { label: 'Basse',   bg: '#dbeafe', color: '#2563eb', icon: 'mdi-arrow-down-bold' },
}
const ALERTES_BUDGET = ['DEPASSEMENT', 'HORS_BUDGET', 'SANS_BUDGET']
const alerteBudget = (n: NoteTableau) => (n.statutBudget && ALERTES_BUDGET.includes(n.statutBudget) ? statutControle(n.statutBudget) : null)
const priorite = (n: NoteTableau) => (n.priorite ? prioriteMeta[n.priorite] ?? null : null)

/** Ligne du dessous de l'objet : émetteur, service qui engage la dépense, et nature (paie, impôt) des notes générées par la DRH. */
function sousLigne(n: NoteTableau): string {
  return [n.createurNom, n.serviceNom, libelleCategorieNote(n)].filter(Boolean).join(' · ')
}

const fmtMontant = (n: NoteTableau) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(n.montant)
const fmtDate = (d?: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')

/** Couleur du statut pour la ligne : `--statut` alimente le liseré et la teinte (voir les styles). */
function proprietesLigne({ item }: { item: NoteTableau }) {
  return { class: 'nft__ligne', style: { '--statut': meta(item).color } }
}

function imprimer(id: number) {
  navigateTo(`/notes-frais/${id}?print=1`)
}
</script>

<template>
  <v-card class="classroom-card nft">
    <v-data-table
      :headers="entetes"
      :items="notes"
      item-value="id"
      :items-per-page="25"
      :items-per-page-options="[25, 50, 100]"
      items-per-page-text="Notes par page"
      :row-props="proprietesLigne"
      class="nft__tableau"
      @click:row="(_e: Event, { item }: { item: NoteTableau }) => emit('ouvrir', item.id)"
    >
      <template #item.reference="{ item }">
        <NuxtLink :to="`/notes-frais/${item.id}`" class="nft__reference" @click.stop>{{ item.reference }}</NuxtLink>
      </template>

      <template #item.objet="{ item }">
        <div class="nft__objet">{{ item.objet }}</div>
        <div v-if="sousLigne(item)" class="nft__sous">{{ sousLigne(item) }}</div>
      </template>

      <template #item.statut="{ item }">
        <div class="nft__statut-cellule">
          <span class="nft__statut" :style="{ background: meta(item).bg, color: meta(item).text }">
            <span class="nft__point" :style="{ background: meta(item).color }" />{{ meta(item).label }}
          </span>
          <div v-if="alerteBudget(item) || priorite(item)" class="nft__puces">
            <span v-if="alerteBudget(item)" class="nft__puce" style="background: #fee2e2; color: #b91c1c">
              <v-icon :icon="alerteBudget(item)!.icon" size="12" class="mr-1" />{{ alerteBudget(item)!.label }}
            </span>
            <span v-if="priorite(item)" class="nft__puce" :style="{ background: priorite(item)!.bg, color: priorite(item)!.color }">
              <v-icon :icon="priorite(item)!.icon" size="11" class="mr-1" />{{ priorite(item)!.label }}
            </span>
          </div>
        </div>
      </template>

      <template #item.dateCreation="{ item }"><span class="nft__date">{{ fmtDate(item.dateCreation) }}</span></template>

      <template #item.montant="{ item }">
        <span class="nft__montant">{{ fmtMontant(item) }}</span>
        <span class="nft__devise">{{ item.devise || 'CDF' }}</span>
      </template>

      <template #item.actions="{ item }">
        <div class="nft__actions">
          <v-btn
            icon="mdi-printer-outline" size="small" variant="text" aria-label="Imprimer la note"
            @click.stop="imprimer(item.id)"
          />
          <v-icon icon="mdi-chevron-right" size="20" class="nft__ouvrir" />
        </div>
      </template>

      <template #no-data><div class="py-8 text-center text-medium-emphasis">Aucune note à afficher.</div></template>
    </v-data-table>
  </v-card>
</template>

<style scoped>
.nft { overflow: hidden; }

/* Ligne : teinte très légère de la couleur du statut, un peu plus soutenue au survol ; liseré de 4 px à gauche. */
.nft :deep(.nft__ligne) {
  cursor: pointer;
  background: color-mix(in srgb, var(--statut) 3.5%, #fff);
  transition: background 0.12s;
}
.nft :deep(.nft__ligne:hover) { background: color-mix(in srgb, var(--statut) 8%, #fff); }
.nft :deep(.nft__ligne > td:first-child) { box-shadow: inset 4px 0 0 var(--statut); }

/* Pas de lignes verticales ; un simple filet entre les lignes ; lignes de 60 px. */
.nft :deep(.v-data-table__td) {
  height: 60px;
  border-bottom: 1px solid #f3f4f6 !important;
  vertical-align: middle;
}
.nft :deep(.v-data-table__th) { height: 44px; }

.nft__reference {
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  color: #111827;
  text-decoration: none;
}
.nft__reference:hover { color: var(--color-primary); text-decoration: underline; }

.nft__objet { padding: 6px 0 2px; font-weight: 500; line-height: 1.35; color: #111827; overflow-wrap: anywhere; }
.nft__sous { padding-bottom: 6px; font-size: 0.75rem; color: #6b7280; }

.nft__statut {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 4px 11px 4px 9px; border-radius: 100px;
  font-size: 0.75rem; font-weight: 700; white-space: nowrap;
}
.nft__point { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }

.nft__statut-cellule { display: flex; flex-direction: column; align-items: flex-start; gap: 5px; padding: 6px 0; }
.nft__puces { display: flex; flex-wrap: wrap; gap: 4px; }
.nft__puce {
  display: inline-flex; align-items: center; padding: 2px 8px; border-radius: 100px;
  font-size: 0.7rem; font-weight: 700; white-space: nowrap;
}
.nft__date { white-space: nowrap; color: #4b5563; }

.nft__montant { font-weight: 700; font-variant-numeric: tabular-nums; white-space: nowrap; color: #111827; }
.nft__devise { margin-left: 4px; font-size: 0.72rem; font-weight: 600; color: #9ca3af; }

.nft__actions { display: flex; align-items: center; justify-content: flex-end; gap: 2px; }
.nft__ouvrir { color: #9ca3af; }
</style>
