<script setup lang="ts">
definePageMeta({ module: 'COMPTABILITE' })

interface LigneBalance {
  compteNumero: string
  compteLibelle: string
  type: string
  totalDebit: number
  totalCredit: number
  soldeDebiteur: number
  soldeCrediteur: number
}

interface BalanceVerification {
  du: string
  au: string
  lignes: LigneBalance[]
  totalDebit: number
  totalCredit: number
  totalSoldeDebiteur: number
  totalSoldeCrediteur: number
  equilibree: boolean
}

const auth = useAuthStore()
const api = useApi()
const parametresStore = useParametresStore()
const loading = ref(false)
const exportEnCours = ref(false)
const erreur = ref('')
const balance = ref<BalanceVerification | null>(null)

const debutAnnee = new Date().getFullYear() + '-01-01'
const aujourdhui = new Date().toISOString().slice(0, 10)

const filtres = reactive({
  du: debutAnnee,
  au: aujourdhui,
})

const peutExporter = computed(() => auth.hasAnyRole(['DFIN', 'ADMIN']))

// Le tableau paginé ne rend que la page courante dans le DOM : sans bascule
// vers "toutes les lignes" au moment d'imprimer, seule la 1ère page sortirait
// sur le PDF/papier. On restaure la pagination normale une fois l'impression
// terminée (fermeture de la boîte de dialogue navigateur).
const lignesParPage = ref(25)
const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  window.print()
}

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const params = new URLSearchParams({ du: filtres.du, au: filtres.au })
    balance.value = await api<BalanceVerification>(`/comptabilite/balance-verification?${params}`)
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger la balance de vérification.'
    balance.value = null
  } finally {
    loading.value = false
  }
}

/** Classeur Excel complet (Journal, Balance, Bilan, Résultat, Flux, Ratios). */
async function exporterExcel() {
  exportEnCours.value = true
  erreur.value = ''
  try {
    const params = new URLSearchParams({ du: filtres.du, au: filtres.au })
    await telechargerFichier(api, `/comptabilite/etats-financiers/export?${params}`,
      `Etats_financiers_MBSC_${filtres.au}.xlsx`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible de générer le classeur des états financiers.")
  } finally {
    exportEnCours.value = false
  }
}

onMounted(() => {
  charger()
  parametresStore.charger()
  window.addEventListener('beforeprint', () => { lignesParPage.value = -1 })
  window.addEventListener('afterprint', () => { lignesParPage.value = 25 })
})

function fmt(v: number) {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(v)
}

function fmtDate(d: string) {
  return new Date(d).toLocaleDateString('fr-FR')
}
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Balance de vérification</h1>
        <p class="page-sub">Trial balance — contrôle débit = crédit par période</p>
      </div>
      <div class="d-flex ga-2 flex-wrap">
        <v-btn
          v-if="peutExporter"
          color="success"
          variant="flat"
          rounded="lg"
          prepend-icon="mdi-file-excel-outline"
          :loading="exportEnCours"
          @click="exporterExcel"
        >
          États financiers (Excel)
        </v-btn>
        <v-btn
          v-if="balance"
          color="error"
          variant="tonal"
          rounded="lg"
          prepend-icon="mdi-file-pdf-box"
          @click="imprimer"
        >
          PDF
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-6 mb-4 no-print">
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

    <template v-if="balance">
      <!-- En-tete d'impression (visible uniquement sur le document imprime) -->
      <div class="etat-print-header">
        <div class="etat-print-header__brand">
          <div class="etat-print-header__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
            <v-icon v-else icon="mdi-finance" size="16" color="white" />
          </div>
          <div>
            <span class="etat-print-header__company">{{ parametresStore.parametres.nom }}</span>
            <span class="etat-print-header__doc">Balance de vérification</span>
          </div>
        </div>
        <div class="etat-print-header__meta">
          <span>Période : <strong>{{ fmtDate(balance.du) }} au {{ fmtDate(balance.au) }}</strong></span>
          <span>Imprimé le : {{ dateImpression }}</span>
        </div>
      </div>

      <v-alert
        :type="balance.equilibree ? 'success' : 'warning'"
        variant="tonal"
        class="mb-4"
      >
        Période du {{ fmtDate(balance.du) }} au {{ fmtDate(balance.au) }} —
        {{ balance.equilibree ? 'Balance équilibrée (débit = crédit)' : 'Balance déséquilibrée' }}
      </v-alert>

      <v-row class="mb-4">
        <v-col cols="6" md="3">
          <v-card class="classroom-card pa-4 text-center">
            <div class="kpi-label">Total débit</div>
            <div class="kpi-value">{{ fmt(balance.totalDebit) }}</div>
          </v-card>
        </v-col>
        <v-col cols="6" md="3">
          <v-card class="classroom-card pa-4 text-center">
            <div class="kpi-label">Total crédit</div>
            <div class="kpi-value">{{ fmt(balance.totalCredit) }}</div>
          </v-card>
        </v-col>
        <v-col cols="6" md="3">
          <v-card class="classroom-card pa-4 text-center">
            <div class="kpi-label">Soldes débiteurs</div>
            <div class="kpi-value">{{ fmt(balance.totalSoldeDebiteur) }}</div>
          </v-card>
        </v-col>
        <v-col cols="6" md="3">
          <v-card class="classroom-card pa-4 text-center">
            <div class="kpi-label">Soldes créditeurs</div>
            <div class="kpi-value">{{ fmt(balance.totalSoldeCrediteur) }}</div>
          </v-card>
        </v-col>
      </v-row>

      <v-card class="classroom-card">
        <v-data-table
          :headers="[
            { title: 'Compte', key: 'compteNumero' },
            { title: 'Libellé', key: 'compteLibelle' },
            { title: 'Type', key: 'type' },
            { title: 'Débit', key: 'totalDebit', align: 'end' },
            { title: 'Crédit', key: 'totalCredit', align: 'end' },
            { title: 'Solde débiteur', key: 'soldeDebiteur', align: 'end' },
            { title: 'Solde créditeur', key: 'soldeCrediteur', align: 'end' },
          ]"
          :items="balance.lignes"
          :loading="loading"
          :items-per-page="lignesParPage"
        >
          <template #item.totalDebit="{ item }">{{ fmt(item.totalDebit) }}</template>
          <template #item.totalCredit="{ item }">{{ fmt(item.totalCredit) }}</template>
          <template #item.soldeDebiteur="{ item }">{{ item.soldeDebiteur ? fmt(item.soldeDebiteur) : '—' }}</template>
          <template #item.soldeCrediteur="{ item }">{{ item.soldeCrediteur ? fmt(item.soldeCrediteur) : '—' }}</template>
        </v-data-table>
      </v-card>

      <ComptabiliteAnalyseFinanciereIa :du="filtres.du" :au="filtres.au" />
    </template>
  </div>
</template>

<style scoped>
.kpi-label { font-size: 0.72rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.25rem; font-weight: 700; color: #111827; margin-top: 4px; }
</style>
