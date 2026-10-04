<script setup lang="ts">
// DA exclu explicitement : voir NavigationDrawer.vue (comptabiliteItems).
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DG', 'COMPTABLE'] })

interface Piece {
  id: number
  reference: string
  datePiece: string
  journal: string
  libelle?: string
  statut: string
  soldeOuverture?: boolean
  totalDebit: number
  totalCredit: number
  createdByEmail?: string
  /**
   * Calculé par le serveur : une pièce de stock, de vente ou de trésorerie
   * s'annule depuis son opération d'origine, jamais seule d'ici.
   */
  extournable: boolean
  /** Brouillon validable d'ici : ni journal ni compte de trésorerie. */
  comptabilisable: boolean
  /** Mouvemente la trésorerie (classe 5) : ni comptabilisée ni extournée d'ici. */
  tresorerie: boolean
}

const api = useApi()
const auth = useAuthStore()
const loading = ref(false)
const erreur = ref('')
const pieces = ref<Piece[]>([])

const canWrite = computed(() => auth.hasAnyRole(['DFIN', 'COMPTABLE']))
// Reclassification ouverture/mouvements : reservee au DFIN (ADMIN a le
// filet de securite cote serveur mais pas ce controle a l'ecran, meme
// convention que le reste de l'app), COMPTABLE exclu — ca affecte la
// presentation des etats financiers officiels, pas une simple saisie.
const peutBasculerOuverture = computed(() => auth.hasAnyRole(['DFIN']))

const statutColor: Record<string, string> = {
  BROUILLON: 'grey',
  COMPTABILISEE: 'success',
  ANNULEE: 'error',
}

const journalLabel: Record<string, string> = {
  OPERATIONS_DIVERSES: 'Opérations diverses',
  CAISSE: 'Caisse',
  BANQUE: 'Banque',
  MOBILE_MONEY: 'Mobile Money',
  ACHATS: 'Achats',
  VENTES: 'Ventes',
}

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    pieces.value = await api<Piece[]>('/comptabilite/pieces')
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger les pièces comptables.'
  } finally {
    loading.value = false
  }
}

onMounted(charger)

async function comptabiliser(id: number) {
  erreur.value = ''
  try {
    await api(`/comptabilite/pieces/${id}/comptabiliser`, { method: 'POST' })
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Comptabilisation impossible.'
  }
}

async function annuler(id: number) {
  if (!confirm('Annuler cette pièce ? Une pièce d\'extourne sera générée.')) return
  erreur.value = ''
  try {
    await api(`/comptabilite/pieces/${id}/annuler`, { method: 'POST' })
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Annulation impossible.'
  }
}

async function supprimerBrouillon(id: number) {
  if (!confirm('Supprimer définitivement ce brouillon ?')) return
  erreur.value = ''
  try {
    await api(`/comptabilite/pieces/${id}`, { method: 'DELETE' })
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Suppression impossible.'
  }
}

/**
 * Reclasse une pièce en/hors solde d'ouverture — y compris déjà
 * comptabilisée (case à cocher oubliée à la saisie) : ne touche ni montants
 * ni comptes, seulement la colonne où la balance la range.
 */
async function basculerOuverture(item: Piece) {
  erreur.value = ''
  try {
    await api(`/comptabilite/pieces/${item.id}/solde-ouverture`, {
      method: 'PATCH',
      body: { soldeOuverture: !item.soldeOuverture },
    })
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Reclassification impossible.'
  }
}

// La trésorerie ne se modifie jamais depuis cet écran (voir
// ComptabiliteService.exigerHorsTresorerie) : on dit pourquoi une action
// manque plutôt que de la proposer pour la voir refusée.
const MOTIF_TRESORERIE = 'Mouvemente la trésorerie : un encaissement ou un décaissement passe par une note de frais, pas par cet écran.'
function motifVerrou(item: Piece): string | null {
  if (item.statut === 'BROUILLON' && !item.comptabilisable) {
    return item.tresorerie ? MOTIF_TRESORERIE + ' Supprimez ce brouillon.' : 'Journal réservé à la trésorerie : supprimez ce brouillon.'
  }
  if (item.statut === 'COMPTABILISEE' && !item.extournable) {
    return item.tresorerie
      ? MOTIF_TRESORERIE
      : "Pièce d'un mouvement de stock ou d'une vente : elle s'annule depuis son opération d'origine."
  }
  return null
}

function fmt(v: number) {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(v)
}

function fmtDate(d: string) {
  return new Date(d).toLocaleDateString('fr-FR')
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Pièces comptables</h1>
        <p class="page-sub">
          Opérations sans mouvement de trésorerie — un encaissement ou un décaissement passe par une note de frais
        </p>
      </div>
      <v-btn
        v-if="canWrite"
        color="primary"
        prepend-icon="mdi-plus"
        to="/comptabilite/pieces/nouvelle"
      >
        Nouvelle pièce
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Référence', key: 'reference' },
          { title: 'Date', key: 'datePiece' },
          { title: 'Journal', key: 'journal' },
          { title: 'Libellé', key: 'libelle' },
          { title: 'Statut', key: 'statut' },
          { title: 'Débit', key: 'totalDebit', align: 'end' },
          { title: 'Crédit', key: 'totalCredit', align: 'end' },
          { title: 'Actions', key: 'actions', sortable: false },
        ]"
        :items="pieces"
        :loading="loading"
        items-per-page="15"
      >
        <template #item.datePiece="{ item }">{{ fmtDate(item.datePiece) }}</template>
        <template #item.journal="{ item }">{{ journalLabel[item.journal] || item.journal }}</template>
        <template #item.libelle="{ item }">
          {{ item.libelle || '—' }}
          <v-chip
            v-if="item.soldeOuverture"
            color="indigo"
            size="x-small"
            variant="tonal"
            class="ml-2"
          >
            Ouverture
          </v-chip>
        </template>
        <template #item.statut="{ item }">
          <v-chip :color="statutColor[item.statut] || 'grey'" size="small" variant="flat">
            {{ item.statut }}
          </v-chip>
        </template>
        <template #item.totalDebit="{ item }">{{ fmt(item.totalDebit) }}</template>
        <template #item.totalCredit="{ item }">{{ fmt(item.totalCredit) }}</template>
        <template #item.actions="{ item }">
          <div class="d-flex ga-1">
            <template v-if="canWrite">
              <v-btn
                v-if="item.statut === 'BROUILLON' && item.comptabilisable"
                size="small"
                color="success"
                variant="tonal"
                @click="comptabiliser(item.id)"
              >
                Comptabiliser
              </v-btn>
              <v-btn
                v-if="item.statut === 'BROUILLON'"
                size="small"
                color="error"
                variant="text"
                icon="mdi-delete-outline"
                @click="supprimerBrouillon(item.id)"
              />
              <v-btn
                v-if="item.statut === 'COMPTABILISEE' && item.extournable"
                size="small"
                color="error"
                variant="tonal"
                @click="annuler(item.id)"
              >
                Annuler
              </v-btn>
              <v-icon
                v-if="motifVerrou(item)"
                icon="mdi-lock-outline"
                size="18"
                class="text-medium-emphasis"
                :title="motifVerrou(item)!"
                :aria-label="motifVerrou(item)!"
              />
            </template>
            <v-btn
              v-if="peutBasculerOuverture"
              size="small"
              variant="text"
              icon="mdi-calendar-start-outline"
              :color="item.soldeOuverture ? 'indigo' : undefined"
              :title="item.soldeOuverture
                ? 'Retirer le marqueur solde d\'ouverture (repasser en mouvement de la période)'
                : 'Marquer comme solde d\'ouverture (reclasse la pièce sans changer ses montants)'"
              @click="basculerOuverture(item)"
            />
          </div>
        </template>
      </v-data-table>
    </v-card>
  </div>
</template>
