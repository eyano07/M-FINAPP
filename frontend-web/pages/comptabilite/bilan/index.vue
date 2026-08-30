<script setup lang="ts">
// DA exclu explicitement de la consultation du Bilan.
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DG', 'COMPTABLE'] })

interface LigneEtat {
  numero: string
  libelle: string
  classe: number | null
  montant: number
}

interface Bilan {
  au: string
  actifs: LigneEtat[]
  passifs: LigneEtat[]
  totalActif: number
  totalPassifHorsResultat: number
  resultatNet: number
  totalPassif: number
  equilibre: boolean
}

const auth = useAuthStore()
const api = useApi()
const parametresStore = useParametresStore()
const loading = ref(false)
const exportEnCours = ref(false)
const erreur = ref('')
const bilan = ref<Bilan | null>(null)

const aujourdhui = new Date().toISOString().slice(0, 10)
const filtres = reactive({ au: aujourdhui })

const peutExporter = computed(() => auth.hasAnyRole(['DFIN', 'ADMIN']))

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const params = new URLSearchParams({ au: filtres.au })
    bilan.value = await api<Bilan>(`/comptabilite/bilan?${params}`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le bilan.')
    bilan.value = null
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
    const params = new URLSearchParams({ au: filtres.au })
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
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Bilan</h1>
        <p class="page-sub">Situation patrimoniale à une date — actif face au passif (devise de base : USD)</p>
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
          v-if="bilan"
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
        <v-col cols="12" md="6">
          <v-text-field v-model="filtres.au" label="Bilan au" type="date" variant="outlined" density="comfortable" />
        </v-col>
        <v-col cols="12" md="6">
          <v-btn color="primary" block :loading="loading" @click="charger">Actualiser</v-btn>
        </v-col>
      </v-row>
    </v-card>

    <template v-if="bilan">
      <!-- En-tete d'impression (visible uniquement sur le document imprime) -->
      <div class="etat-print-header">
        <div class="etat-print-header__brand">
          <div class="etat-print-header__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
            <v-icon v-else icon="mdi-finance" size="16" color="white" />
          </div>
          <div>
            <span class="etat-print-header__company">{{ parametresStore.parametres.nom }}</span>
            <span class="etat-print-header__doc">Bilan comptable</span>
          </div>
        </div>
        <div class="etat-print-header__meta">
          <span>Au : <strong>{{ fmtDate(bilan.au) }}</strong></span>
          <span>Imprimé le : {{ dateImpression }}</span>
        </div>
      </div>

      <v-alert :type="bilan.equilibre ? 'success' : 'warning'" variant="tonal" class="mb-4">
        Bilan au {{ fmtDate(bilan.au) }} —
        {{ bilan.equilibre
          ? 'Équilibré : total actif = total passif (' + fmt(bilan.totalActif) + ' USD)'
          : 'Déséquilibré : actif ' + fmt(bilan.totalActif) + ' USD ≠ passif ' + fmt(bilan.totalPassif) + ' USD' }}
      </v-alert>

      <v-row>
        <v-col cols="12" md="6">
          <v-card class="classroom-card">
            <v-card-title class="d-flex align-center ga-2">
              <v-icon icon="mdi-bank-outline" color="primary" />
              Actif
              <v-spacer />
              <span class="font-weight-bold">{{ fmt(bilan.totalActif) }} USD</span>
            </v-card-title>
            <v-divider />
            <v-table density="comfortable">
              <thead>
                <tr><th>Compte</th><th>Libellé</th><th class="text-right">Montant (USD)</th></tr>
              </thead>
              <tbody>
                <tr v-if="bilan.actifs.length === 0">
                  <td colspan="3" class="text-center text-medium-emphasis py-4">Aucun poste d'actif</td>
                </tr>
                <tr v-for="l in bilan.actifs" :key="l.numero">
                  <td class="font-weight-medium">{{ l.numero }}</td>
                  <td>{{ l.libelle }}</td>
                  <td class="text-right">{{ fmt(l.montant) }}</td>
                </tr>
              </tbody>
              <tfoot>
                <tr class="font-weight-bold">
                  <td colspan="2">Total actif</td>
                  <td class="text-right">{{ fmt(bilan.totalActif) }}</td>
                </tr>
              </tfoot>
            </v-table>
          </v-card>
        </v-col>

        <v-col cols="12" md="6">
          <v-card class="classroom-card">
            <v-card-title class="d-flex align-center ga-2">
              <v-icon icon="mdi-scale-balance" color="deep-purple" />
              Passif
              <v-spacer />
              <span class="font-weight-bold">{{ fmt(bilan.totalPassif) }} USD</span>
            </v-card-title>
            <v-divider />
            <v-table density="comfortable">
              <thead>
                <tr><th>Compte</th><th>Libellé</th><th class="text-right">Montant (USD)</th></tr>
              </thead>
              <tbody>
                <tr v-if="bilan.passifs.length === 0 && !bilan.resultatNet">
                  <td colspan="3" class="text-center text-medium-emphasis py-4">Aucun poste de passif</td>
                </tr>
                <tr v-for="l in bilan.passifs" :key="l.numero">
                  <td class="font-weight-medium">{{ l.numero }}</td>
                  <td>{{ l.libelle }}</td>
                  <td class="text-right">{{ fmt(l.montant) }}</td>
                </tr>
                <tr>
                  <td class="font-weight-medium">13</td>
                  <td>Résultat net de l'exercice ({{ bilan.resultatNet >= 0 ? 'bénéfice' : 'perte' }})</td>
                  <td class="text-right">{{ fmt(bilan.resultatNet) }}</td>
                </tr>
              </tbody>
              <tfoot>
                <tr class="font-weight-bold">
                  <td colspan="2">Total passif</td>
                  <td class="text-right">{{ fmt(bilan.totalPassif) }}</td>
                </tr>
              </tfoot>
            </v-table>
          </v-card>
        </v-col>
      </v-row>

      <ComptabiliteAnalyseFinanciereIa :au="filtres.au" />
    </template>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
</style>
