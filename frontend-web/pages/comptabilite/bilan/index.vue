<script setup lang="ts">
import { useExerciceStore } from '~/stores/exercice'
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

// Date du bilan par défaut : fin de l'exercice choisi dans la barre du haut (aujourd'hui pour l'exercice en cours).
const aujourdhui = useExerciceStore().fin
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

// Immobilisations (comptes 21 à 24) et leurs amortissements (28) : la valeur nette s'affiche juste après les
// amortissements, comme la carte « Valeur nette comptable » de la page Immobilisations.
const estImmo = (n: string) => /^2[1-4]/.test(n)
const estAmort = (n: string) => n.startsWith('28')
const immos = computed(() => {
  const a = bilan.value?.actifs ?? []
  const brut = a.filter(l => estImmo(l.numero)).reduce((t, l) => t + l.montant, 0)
  const amort = -a.filter(l => estAmort(l.numero)).reduce((t, l) => t + l.montant, 0)
  return { brut, amort, net: brut - amort, present: a.some(l => estImmo(l.numero)) }
})
const lignesActif = computed(() => {
  const a = bilan.value?.actifs ?? []
  const simple = (l: LigneEtat) => ({ ...l, net: false })
  if (!immos.value.present) return a.map(simple)
  // Brut, puis amortissements, puis valeur nette ; les autres postes (prêts, dépôts, trésorerie...) ensuite.
  return [
    ...a.filter(l => estImmo(l.numero)).map(simple),
    ...a.filter(l => estAmort(l.numero)).map(simple),
    { numero: 'net-immo', libelle: '', classe: 2, montant: immos.value.net, net: true },
    ...a.filter(l => !estImmo(l.numero) && !estAmort(l.numero)).map(simple),
  ]
})
const fmt2 = (v: number) => new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(v || 0)

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
          <v-text-field v-model="filtres.au" label="Bilan au" type="date" variant="outlined" density="comfortable" hide-details />
        </v-col>
        <v-col cols="12" md="6">
          <v-btn color="primary" block height="48" :loading="loading" @click="charger">Actualiser</v-btn>
        </v-col>
      </v-row>
    </v-card>

    <template v-if="bilan">
      <!-- En-tete d'impression (visible uniquement sur le document imprime) -->
      <div class="etat-print-header print-entete">
        <div class="etat-print-header__brand print-entete__marque">
          <div class="etat-print-header__logo print-entete__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
            <v-icon v-else icon="mdi-finance" size="16" color="white" />
          </div>
          <div>
            <span class="etat-print-header__company print-entete__nom">{{ parametresStore.parametres.nom }}</span>
            <span class="etat-print-header__doc">Bilan comptable</span>
          </div>
        </div>
        <div class="etat-print-header__meta print-entete__meta">
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
                <template v-for="l in lignesActif" :key="l.numero">
                  <tr v-if="l.net" class="ligne-net">
                    <td colspan="2">
                      Immobilisations — valeur nette
                      <div class="text-caption text-medium-emphasis">brut {{ fmt2(immos.brut) }} − amortissements {{ fmt2(immos.amort) }}</div>
                    </td>
                    <td class="text-right">{{ fmt2(l.montant) }}</td>
                  </tr>
                  <tr v-else>
                    <td class="font-weight-medium">{{ l.numero }}</td>
                    <td>{{ l.libelle }}</td>
                    <td class="text-right">{{ fmt(l.montant) }}</td>
                  </tr>
                </template>
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
.ligne-net td { font-weight: 700; background: color-mix(in srgb, var(--color-primary) 8%, transparent); }
</style>
