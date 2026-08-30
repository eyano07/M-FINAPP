<script setup lang="ts">
// Meme restriction que les mouvements : lecture reservee aux roles
// logistiques et financiers (StockService.grandLivreStock).
definePageMeta({ module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'ADMIN'] })

interface LigneStockGL {
  id: number
  dateEcriture: string
  articleCode: string
  entrepotCode: string
  mouvementReference?: string
  qteEntree: number
  qteSortie: number
  qteApres: number
  valeurUnitaire: number
  valeurApres: number
}

const api = useApi()
const loading = ref(false)
const erreur = ref('')
const lignes = ref<LigneStockGL[]>([])

const debutAnnee = new Date().getFullYear() + '-01-01'
const aujourdhui = new Date().toISOString().slice(0, 10)

const filtres = reactive({
  article: null as number | null,
  entrepot: null as number | null,
  du: debutAnnee,
  au: aujourdhui,
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const params = new URLSearchParams({ du: filtres.du, au: filtres.au })
    if (filtres.article) params.set('article', String(filtres.article))
    if (filtres.entrepot) params.set('entrepot', String(filtres.entrepot))
    lignes.value = await api<LigneStockGL[]>(`/logistique/stock/grand-livre?${params}`)
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger le grand livre de stock.'
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function fmt(v: number) {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(v)
}
function fmtDate(d: string) {
  return new Date(d).toLocaleDateString('fr-FR')
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Grand livre de stock</h1>
        <p class="page-sub">Mouvements valorisés par article et entrepôt</p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-6 mb-4">
      <v-row align="end">
        <v-col cols="12" md="3">
          <LogistiqueSelecteurArticle v-model="filtres.article" label="Article (tous)" />
        </v-col>
        <v-col cols="12" md="3">
          <LogistiqueSelecteurEntrepot v-model="filtres.entrepot" label="Entrepôt (tous)" />
        </v-col>
        <v-col cols="6" md="2">
          <v-text-field v-model="filtres.du" label="Du" type="date" variant="outlined" density="comfortable" />
        </v-col>
        <v-col cols="6" md="2">
          <v-text-field v-model="filtres.au" label="Au" type="date" variant="outlined" density="comfortable" />
        </v-col>
        <v-col cols="12" md="2">
          <v-btn color="primary" block :loading="loading" @click="charger">Consulter</v-btn>
        </v-col>
      </v-row>
    </v-card>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Date', key: 'dateEcriture' },
          { title: 'Article', key: 'articleCode' },
          { title: 'Entrepôt', key: 'entrepotCode' },
          { title: 'Mouvement', key: 'mouvementReference' },
          { title: 'Entrée', key: 'qteEntree', align: 'end' },
          { title: 'Sortie', key: 'qteSortie', align: 'end' },
          { title: 'Solde qté', key: 'qteApres', align: 'end' },
          { title: 'CMP', key: 'valeurUnitaire', align: 'end' },
          { title: 'Valeur stock', key: 'valeurApres', align: 'end' },
        ]"
        :items="lignes"
        :loading="loading"
        items-per-page="25"
      >
        <template #item.dateEcriture="{ item }">{{ fmtDate(item.dateEcriture) }}</template>
        <template #item.qteEntree="{ item }">{{ item.qteEntree ? fmt(item.qteEntree) : '—' }}</template>
        <template #item.qteSortie="{ item }">{{ item.qteSortie ? fmt(item.qteSortie) : '—' }}</template>
        <template #item.qteApres="{ item }">{{ fmt(item.qteApres) }}</template>
        <template #item.valeurUnitaire="{ item }">{{ fmt(item.valeurUnitaire) }}</template>
        <template #item.valeurApres="{ item }">{{ fmt(item.valeurApres) }}</template>
      </v-data-table>
    </v-card>
  </div>
</template>
