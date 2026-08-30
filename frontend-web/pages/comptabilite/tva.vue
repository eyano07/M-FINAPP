<script setup lang="ts">
// Aucune ecriture ne se fait depuis cette page (la TVA se genere depuis les
// ventes et les notes de frais), donc aucun role d'ecriture a restreindre.
// DA exclu explicitement de la consultation.
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DG', 'COMPTABLE'] })

interface LigneTva {
  date: string
  pieceReference?: string
  journal?: string
  compteNumero: string
  compteLibelle: string
  libelle: string
  montant: number
  nature: 'COLLECTEE' | 'RECUPERABLE'
}

interface Situation {
  du: string
  au: string
  tvaCollectee: number
  tvaRecuperable: number
  soldeNet: number
  lignes: LigneTva[]
}

const api = useApi()
const loading = ref(false)
const erreur = ref('')
const situation = ref<Situation | null>(null)

const debutAnnee = new Date().getFullYear() + '-01-01'
const aujourdhui = new Date().toISOString().slice(0, 10)
const filtres = reactive({ du: debutAnnee, au: aujourdhui })

const filtreNature = ref<'TOUS' | 'COLLECTEE' | 'RECUPERABLE'>('TOUS')

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const params = new URLSearchParams({ du: filtres.du, au: filtres.au })
    situation.value = await api<Situation>(`/comptabilite/tva?${params}`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger la situation TVA.')
    situation.value = null
  } finally {
    loading.value = false
  }
}
onMounted(charger)

const lignesFiltrees = computed(() => {
  if (!situation.value) return []
  if (filtreNature.value === 'TOUS') return situation.value.lignes
  return situation.value.lignes.filter(l => l.nature === filtreNature.value)
})

const sensSolde = computed(() => {
  const s = situation.value?.soldeNet ?? 0
  if (s > 0.005) return { label: 'TVA due à l\'État', color: 'error' }
  if (s < -0.005) return { label: 'Crédit de TVA à reporter', color: 'success' }
  return { label: 'Solde nul', color: 'grey' }
})

function fmt(v: number) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(v || 0)
}
function fmtDate(d: string) {
  return d ? new Date(d).toLocaleDateString('fr-FR') : ''
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Gestion de la TVA</h1>
        <p class="page-sub">
          TVA collectée sur les ventes (443x) face à la TVA récupérable sur les achats (445x) — traçabilité complète
        </p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-6 mb-4">
      <v-row align="end">
        <v-col cols="12" md="4">
          <v-text-field v-model="filtres.du" label="Du" type="date" variant="outlined" density="comfortable" />
        </v-col>
        <v-col cols="12" md="4">
          <v-text-field v-model="filtres.au" label="Au" type="date" variant="outlined" density="comfortable" />
        </v-col>
        <v-col cols="12" md="4">
          <v-btn color="primary" block :loading="loading" @click="charger">Actualiser</v-btn>
        </v-col>
      </v-row>
    </v-card>

    <template v-if="situation">
      <v-row class="mb-4">
        <v-col cols="12" md="4">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">TVA collectée (ventes)</div>
            <div class="kpi-value text-success">{{ fmt(situation.tvaCollectee) }}</div>
          </v-card>
        </v-col>
        <v-col cols="12" md="4">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">TVA récupérable (achats)</div>
            <div class="kpi-value text-error">{{ fmt(situation.tvaRecuperable) }}</div>
          </v-card>
        </v-col>
        <v-col cols="12" md="4">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">Solde net</div>
            <div class="kpi-value" :class="`text-${sensSolde.color}`">{{ fmt(situation.soldeNet) }}</div>
            <v-chip :color="sensSolde.color" size="small" variant="tonal" class="mt-1">{{ sensSolde.label }}</v-chip>
          </v-card>
        </v-col>
      </v-row>

      <v-card class="classroom-card">
        <v-card-title class="d-flex align-center justify-space-between flex-wrap ga-3 pa-4 pb-2">
          <span class="text-subtitle-1 font-weight-semibold">Détail des écritures</span>
          <v-btn-toggle v-model="filtreNature" color="primary" density="comfortable" mandatory variant="outlined">
            <v-btn value="TOUS" size="small">Tout</v-btn>
            <v-btn value="COLLECTEE" size="small">Collectée</v-btn>
            <v-btn value="RECUPERABLE" size="small">Récupérable</v-btn>
          </v-btn-toggle>
        </v-card-title>
        <v-data-table
          :headers="[
            { title: 'Date', key: 'date' },
            { title: 'Pièce', key: 'pieceReference' },
            { title: 'Journal', key: 'journal' },
            { title: 'Compte', key: 'compteNumero' },
            { title: 'Libellé', key: 'libelle' },
            { title: 'Nature', key: 'nature' },
            { title: 'Montant', key: 'montant', align: 'end' },
          ]"
          :items="lignesFiltrees"
          :loading="loading"
          items-per-page="25"
          no-data-text="Aucun mouvement de TVA sur cette période."
        >
          <template #item.date="{ item }">{{ fmtDate(item.date) }}</template>
          <template #item.compteNumero="{ item }">
            <span class="font-weight-medium">{{ item.compteNumero }}</span>
            <span class="text-medium-emphasis"> — {{ item.compteLibelle }}</span>
          </template>
          <template #item.nature="{ item }">
            <v-chip :color="item.nature === 'COLLECTEE' ? 'success' : 'error'" size="small" variant="tonal">
              {{ item.nature === 'COLLECTEE' ? 'Collectée' : 'Récupérable' }}
            </v-chip>
          </template>
          <template #item.montant="{ item }">{{ fmt(item.montant) }}</template>
        </v-data-table>
      </v-card>
    </template>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.kpi-label { font-size: 0.72rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.4rem; font-weight: 700; margin-top: 4px; color: #111827; }
</style>
