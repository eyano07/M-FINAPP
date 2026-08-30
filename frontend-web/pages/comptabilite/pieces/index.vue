<script setup lang="ts">
// CAISSIER a COMPTABILITE en LECTURE (pour Balance/Compte de resultat
// uniquement) mais ne voit pas les Pieces comptables.
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] })

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
        <p class="page-sub">Journal entries — écritures en partie double équilibrées</p>
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
                v-if="item.statut === 'BROUILLON'"
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
                v-if="item.statut === 'COMPTABILISEE'"
                size="small"
                color="error"
                variant="tonal"
                @click="annuler(item.id)"
              >
                Annuler
              </v-btn>
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
