<script setup lang="ts">
definePageMeta({ module: 'COMPTABILITE' })

interface LigneEtat {
  numero: string
  libelle: string
  classe: number | null
  montant: number
}

interface CompteResultat {
  du: string
  au: string
  produits: LigneEtat[]
  charges: LigneEtat[]
  totalProduits: number
  totalCharges: number
  resultatNet: number
}

const auth = useAuthStore()
const api = useApi()
const parametresStore = useParametresStore()
const loading = ref(false)
const exportEnCours = ref(false)
const erreur = ref('')
const etat = ref<CompteResultat | null>(null)

const debutAnnee = new Date().getFullYear() + '-01-01'
const aujourdhui = new Date().toISOString().slice(0, 10)
const filtres = reactive({ du: debutAnnee, au: aujourdhui })

const peutExporter = computed(() => auth.hasAnyRole(['DFIN', 'ADMIN']))

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const params = new URLSearchParams({ du: filtres.du, au: filtres.au })
    etat.value = await api<CompteResultat>(`/comptabilite/compte-resultat?${params}`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le compte de résultat.')
    etat.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  charger()
  parametresStore.charger()
})

const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  window.print()
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

const fmt = (v: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(v || 0)
const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '')

const benefice = computed(() => (etat.value?.resultatNet ?? 0) >= 0)
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Compte de résultat</h1>
        <p class="page-sub">Produits (classe 7) − Charges (classe 6) sur la période — devise de base : USD</p>
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
          v-if="etat"
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

    <template v-if="etat">
      <!-- En-tete d'impression (visible uniquement sur le document imprime) -->
      <div class="etat-print-header">
        <div class="etat-print-header__brand">
          <div class="etat-print-header__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
            <v-icon v-else icon="mdi-finance" size="16" color="white" />
          </div>
          <div>
            <span class="etat-print-header__company">{{ parametresStore.parametres.nom }}</span>
            <span class="etat-print-header__doc">Compte de résultat</span>
          </div>
        </div>
        <div class="etat-print-header__meta">
          <span>Période : <strong>{{ fmtDate(etat.du) }} au {{ fmtDate(etat.au) }}</strong></span>
          <span>Imprimé le : {{ dateImpression }}</span>
        </div>
      </div>

      <v-alert :type="benefice ? 'success' : 'error'" variant="tonal" class="mb-4">
        Période du {{ fmtDate(etat.du) }} au {{ fmtDate(etat.au) }} —
        Résultat net : <strong>{{ fmt(etat.resultatNet) }} USD</strong>
        ({{ benefice ? 'bénéfice' : 'perte' }})
      </v-alert>

      <v-row>
        <v-col cols="12" md="6">
          <v-card class="classroom-card">
            <v-card-title class="d-flex align-center ga-2">
              <v-icon icon="mdi-trending-up" color="success" />
              Produits
              <v-spacer />
              <span class="font-weight-bold">{{ fmt(etat.totalProduits) }} USD</span>
            </v-card-title>
            <v-divider />
            <v-table density="comfortable">
              <thead>
                <tr><th>Compte</th><th>Libellé</th><th class="text-right">Montant (USD)</th></tr>
              </thead>
              <tbody>
                <tr v-if="etat.produits.length === 0">
                  <td colspan="3" class="text-center text-medium-emphasis py-4">Aucun produit sur la période</td>
                </tr>
                <tr v-for="l in etat.produits" :key="l.numero">
                  <td class="font-weight-medium">{{ l.numero }}</td>
                  <td>{{ l.libelle }}</td>
                  <td class="text-right">{{ fmt(l.montant) }}</td>
                </tr>
              </tbody>
            </v-table>
          </v-card>
        </v-col>

        <v-col cols="12" md="6">
          <v-card class="classroom-card">
            <v-card-title class="d-flex align-center ga-2">
              <v-icon icon="mdi-trending-down" color="error" />
              Charges
              <v-spacer />
              <span class="font-weight-bold">{{ fmt(etat.totalCharges) }} USD</span>
            </v-card-title>
            <v-divider />
            <v-table density="comfortable">
              <thead>
                <tr><th>Compte</th><th>Libellé</th><th class="text-right">Montant (USD)</th></tr>
              </thead>
              <tbody>
                <tr v-if="etat.charges.length === 0">
                  <td colspan="3" class="text-center text-medium-emphasis py-4">Aucune charge sur la période</td>
                </tr>
                <tr v-for="l in etat.charges" :key="l.numero">
                  <td class="font-weight-medium">{{ l.numero }}</td>
                  <td>{{ l.libelle }}</td>
                  <td class="text-right">{{ fmt(l.montant) }}</td>
                </tr>
              </tbody>
            </v-table>
          </v-card>
        </v-col>
      </v-row>

      <v-card class="classroom-card pa-5 mt-4">
        <div class="d-flex align-center justify-space-between flex-wrap ga-3">
          <div>
            <div class="kpi-label">Total produits</div>
            <div class="kpi-value text-success">{{ fmt(etat.totalProduits) }} USD</div>
          </div>
          <v-icon icon="mdi-minus" />
          <div>
            <div class="kpi-label">Total charges</div>
            <div class="kpi-value text-error">{{ fmt(etat.totalCharges) }} USD</div>
          </div>
          <v-icon icon="mdi-equal" />
          <div>
            <div class="kpi-label">Résultat net</div>
            <div class="kpi-value" :class="benefice ? 'text-success' : 'text-error'">
              {{ fmt(etat.resultatNet) }} USD
            </div>
          </div>
        </div>
      </v-card>

      <ComptabiliteAnalyseFinanciereIa :du="filtres.du" :au="filtres.au" />
    </template>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.kpi-label { font-size: 0.72rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.25rem; font-weight: 700; margin-top: 4px; }
</style>
