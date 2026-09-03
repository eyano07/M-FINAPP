<script setup lang="ts">
// DA exclu explicitement : voir NavigationDrawer.vue (comptabiliteItems).
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DG', 'COMPTABLE'] })

interface LigneGrandLivre {
  id: number
  dateEcriture: string
  pieceReference?: string
  transactionReference?: string
  libelle: string
  debit: number
  credit: number
  soldeProgressif: number
  tauxApplique?: number | null
}

interface GrandLivreCompte {
  compteNumero: string
  compteLibelle: string
  du: string
  au: string
  reportDebit: number
  reportCredit: number
  reportSolde: number
  lignes: LigneGrandLivre[]
}

const api = useApi()
const route = useRoute()
const loading = ref(false)
const erreur = ref('')
const resultat = ref<GrandLivreCompte | null>(null)

const debutAnnee = new Date().getFullYear() + '-01-01'
const aujourdhui = new Date().toISOString().slice(0, 10)

// Pré-sélection depuis un lien entrant (ex. "Grand livre" depuis la page
// Caisse, ?compte=571) : le compte est chargé directement sans que
// l'utilisateur ait à le ressaisir.
const compteInitial = typeof route.query.compte === 'string' ? route.query.compte : ''

const filtres = reactive({
  compte: compteInitial,
  du: debutAnnee,
  au: aujourdhui,
})

onMounted(() => {
  if (compteInitial) charger()
})

async function charger() {
  if (!filtres.compte.trim()) {
    erreur.value = 'Sélectionnez un compte.'
    return
  }
  loading.value = true
  erreur.value = ''
  try {
    const params = new URLSearchParams({
      compte: filtres.compte.trim(),
      du: filtres.du,
      au: filtres.au,
    })
    resultat.value = await api<GrandLivreCompte>(`/comptabilite/grand-livre?${params}`)
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger le grand livre.'
    resultat.value = null
  } finally {
    loading.value = false
  }
}

// Le Grand Livre est tenu en devise de base (USD) : le serveur renvoie déjà
// debit/credit/soldeProgressif/reportSolde convertis. `tauxApplique` reste
// une donnée d'audit (le taux appliqué si la ligne provient d'une devise
// étrangère) — il n'y a plus aucune conversion à refaire côté client.
const reportSoldeUSD = computed(() => resultat.value?.reportSolde || 0)

const lignesUSD = computed(() => {
  const r = resultat.value
  if (!r) return []
  return r.lignes.map((l) => ({
    ...l,
    taux: l.tauxApplique || null,
    debitUsd: l.debit || 0,
    creditUsd: l.credit || 0,
    soldeUsd: l.soldeProgressif || 0,
  }))
})

function fmtUSD(v: number) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(v || 0)
}

function fmtTaux(v?: number | null) {
  return v ? new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(v) : '—'
}

function fmtDate(d: string) {
  return new Date(d).toLocaleDateString('fr-FR')
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Grand livre par compte</h1>
        <p class="page-sub">Consultation filtrée avec report à nouveau et solde progressif</p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-6 mb-4">
      <!-- hide-details sur les champs : sans lui, Vuetify réserve la place des
           messages sous l'input et le bouton se retrouve décalé vers le bas. -->
      <div class="gl-filtres">
        <div class="gl-filtres__compte">
          <ComptabiliteSelecteurCompte v-model="filtres.compte" label="Compte" :seulement-actifs="false" />
        </div>
        <v-text-field
          v-model="filtres.du"
          label="Du"
          type="date"
          variant="outlined"
          density="comfortable"
          rounded="lg"
          hide-details
        />
        <v-text-field
          v-model="filtres.au"
          label="Au"
          type="date"
          variant="outlined"
          density="comfortable"
          rounded="lg"
          hide-details
        />
        <v-btn
          color="primary"
          rounded="lg"
          prepend-icon="mdi-magnify"
          class="gl-filtres__btn"
          :loading="loading"
          @click="charger"
        >
          Consulter
        </v-btn>
      </div>
    </v-card>

    <template v-if="resultat">
      <v-row class="mb-4">
        <v-col cols="12" md="4">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">Compte</div>
            <div class="kpi-value">{{ resultat.compteNumero }} — {{ resultat.compteLibelle }}</div>
          </v-card>
        </v-col>
        <v-col cols="12" md="4">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">Report à nouveau (USD)</div>
            <div class="kpi-value">{{ fmtUSD(reportSoldeUSD) }}</div>
          </v-card>
        </v-col>
        <v-col cols="12" md="4">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">Période</div>
            <div class="kpi-value">{{ fmtDate(resultat.du) }} → {{ fmtDate(resultat.au) }}</div>
          </v-card>
        </v-col>
      </v-row>

      <v-card class="classroom-card">
        <v-data-table
          :headers="[
            { title: 'Date', key: 'dateEcriture' },
            { title: 'Pièce', key: 'pieceReference' },
            { title: 'Transaction', key: 'transactionReference' },
            { title: 'Libellé', key: 'libelle' },
            { title: 'Taux', key: 'taux', align: 'end', sortable: false },
            { title: 'Débit (USD)', key: 'debitUsd', align: 'end' },
            { title: 'Crédit (USD)', key: 'creditUsd', align: 'end' },
            { title: 'Solde (USD)', key: 'soldeUsd', align: 'end' },
          ]"
          :items="lignesUSD"
          :loading="loading"
          items-per-page="25"
        >
          <template #item.dateEcriture="{ item }">{{ fmtDate(item.dateEcriture) }}</template>
          <template #item.taux="{ item }">
            <span class="text-medium-emphasis">{{ fmtTaux(item.taux) }}</span>
          </template>
          <template #item.debitUsd="{ item }">{{ item.debit ? fmtUSD(item.debitUsd) : '—' }}</template>
          <template #item.creditUsd="{ item }">{{ item.credit ? fmtUSD(item.creditUsd) : '—' }}</template>
          <template #item.soldeUsd="{ item }">{{ fmtUSD(item.soldeUsd) }}</template>
        </v-data-table>
      </v-card>
    </template>
  </div>
</template>

<style scoped>
.kpi-label { font-size: 0.72rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.1rem; font-weight: 700; color: #111827; margin-top: 4px; }
.kpi-sub { font-size: 0.8rem; color: #6b7280; margin-top: 4px; }
.gl-filtres { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.gl-filtres :deep(.v-input) { flex: 1 1 200px; }
.gl-filtres__compte { flex: 1 1 260px; }
.gl-filtres__btn { height: 48px; flex: 0 0 auto; }
@media (max-width: 600px) {
  .gl-filtres__btn { width: 100%; }
}
</style>
