<script setup lang="ts">
// StockService.listerMouvements exclut le caissier, qui a pourtant le
// module en LECTURE pour le catalogue : sans ce controle il voyait la page.
definePageMeta({ module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'ADMIN'] })

interface Mouvement {
  id: number
  reference: string
  type: string
  dateMouvement: string
  libelle?: string
  statut: string
  pieceReference?: string
}

const api = useApi()
const auth = useAuthStore()
const loading = ref(false)
const erreur = ref('')
const mouvements = ref<Mouvement[]>([])

const canWrite = computed(() => auth.hasAnyRole(['LOGISTIQUE']))

const statutColor: Record<string, string> = {
  BROUILLON: 'grey',
  VALIDE: 'success',
  ANNULE: 'error',
}
const typeColor: Record<string, string> = {
  ENTREE: 'teal',
  SORTIE: 'orange',
  TRANSFERT: 'indigo',
}

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    mouvements.value = await api<Mouvement[]>('/logistique/mouvements')
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger les mouvements.'
  } finally {
    loading.value = false
  }
}
onMounted(charger)

async function valider(id: number) {
  erreur.value = ''
  try {
    await api(`/logistique/mouvements/${id}/valider`, { method: 'POST' })
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Validation impossible.'
  }
}

async function annuler(id: number) {
  if (!confirm('Annuler ce mouvement ? Les stocks et écritures seront extournés.')) return
  erreur.value = ''
  try {
    await api(`/logistique/mouvements/${id}/annuler`, { method: 'POST' })
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Annulation impossible.'
  }
}

function fmtDate(d: string) {
  return new Date(d).toLocaleDateString('fr-FR')
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Mouvements de stock</h1>
        <p class="page-sub">Entrées, sorties et transferts (inventaire permanent)</p>
      </div>
      <v-btn v-if="canWrite" color="primary" prepend-icon="mdi-plus" to="/logistique/mouvements/nouvelle">
        Nouveau mouvement
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Référence', key: 'reference' },
          { title: 'Type', key: 'type' },
          { title: 'Date', key: 'dateMouvement' },
          { title: 'Libellé', key: 'libelle' },
          { title: 'Statut', key: 'statut' },
          { title: 'Pièce', key: 'pieceReference' },
          { title: 'Actions', key: 'actions', sortable: false },
        ]"
        :items="mouvements"
        :loading="loading"
        items-per-page="15"
      >
        <template #item.type="{ item }">
          <v-chip :color="typeColor[item.type] || 'grey'" size="small" variant="flat">{{ item.type }}</v-chip>
        </template>
        <template #item.dateMouvement="{ item }">{{ fmtDate(item.dateMouvement) }}</template>
        <template #item.statut="{ item }">
          <v-chip :color="statutColor[item.statut] || 'grey'" size="small" variant="flat">{{ item.statut }}</v-chip>
        </template>
        <template #item.pieceReference="{ item }">{{ item.pieceReference || '—' }}</template>
        <template #item.actions="{ item }">
          <div v-if="canWrite" class="d-flex ga-1">
            <v-btn v-if="item.statut === 'BROUILLON'" size="small" color="success" variant="tonal" @click="valider(item.id)">
              Valider
            </v-btn>
            <v-btn v-if="item.statut === 'VALIDE'" size="small" color="error" variant="tonal" @click="annuler(item.id)">
              Annuler
            </v-btn>
          </div>
        </template>
      </v-data-table>
    </v-card>
  </div>
</template>
