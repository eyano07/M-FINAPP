<script setup lang="ts">
// COMPTABLE, DA et DG exclus explicitement.
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN'] })

/**
 * Liasse complete : bilan, compte de resultat et balance reunis dans un seul
 * document imprimable. Les pages individuelles gardent leur propre bouton PDF ;
 * celle-ci existe pour sortir l'ensemble d'un coup, comme une liasse OHADA.
 */
interface Ligne { numero: string; libelle: string; montant: number }
interface Bilan {
  au: string; actifs: Ligne[]; passifs: Ligne[]
  totalActif: number; resultatNet: number; totalPassif: number; equilibre: boolean
}
interface Resultat {
  du: string; au: string; produits: Ligne[]; charges: Ligne[]
  totalProduits: number; totalCharges: number; resultatNet: number
}
interface LigneBalance {
  compteNumero: string; compteLibelle: string
  totalDebit: number; totalCredit: number; soldeDebiteur: number; soldeCrediteur: number
}
interface Balance {
  lignes: LigneBalance[]; totalDebit: number; totalCredit: number
  totalSoldeDebiteur: number; totalSoldeCrediteur: number; equilibree: boolean
}

const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()

const debutAnnee = new Date().getFullYear() + '-01-01'
const aujourdhui = new Date().toISOString().slice(0, 10)
const filtres = reactive({ du: debutAnnee, au: aujourdhui })

const loading = ref(false)
const erreur = ref('')
const exportEnCours = ref(false)
const bilan = ref<Bilan | null>(null)
const resultat = ref<Resultat | null>(null)
const balance = ref<Balance | null>(null)

const peutExporter = computed(() => auth.hasAnyRole(['DFIN', 'ADMIN']))

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const p = new URLSearchParams({ du: filtres.du, au: filtres.au })
    const [b, r, bal] = await Promise.all([
      api<Bilan>(`/comptabilite/bilan?au=${filtres.au}`),
      api<Resultat>(`/comptabilite/compte-resultat?${p}`),
      api<Balance>(`/comptabilite/balance-verification?${p}`),
    ])
    bilan.value = b; resultat.value = r; balance.value = bal
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les états financiers.')
  } finally {
    loading.value = false
  }
}

onMounted(() => { charger(); parametresStore.charger() })

const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  nextTick(() => window.print())
}

async function exporterExcel() {
  exportEnCours.value = true
  erreur.value = ''
  try {
    const p = new URLSearchParams({ du: filtres.du, au: filtres.au })
    await telechargerFichier(api, `/comptabilite/etats-financiers/export?${p}`,
      `Etats_financiers_${filtres.au}.xlsx`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de générer le classeur.')
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
        <h1 class="page-title">États financiers</h1>
        <p class="page-sub">Liasse complète — bilan, compte de résultat et balance en un seul document</p>
      </div>
      <div class="d-flex ga-2 flex-wrap">
        <v-btn v-if="peutExporter" color="success" variant="flat" rounded="lg"
          prepend-icon="mdi-file-excel-outline" :loading="exportEnCours" @click="exporterExcel">
          Classeur Excel
        </v-btn>
        <v-btn color="error" variant="tonal" rounded="lg" prepend-icon="mdi-printer-outline"
          :disabled="!bilan" @click="imprimer">
          Imprimer la liasse
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-6 mb-4 no-print">
      <v-row align="end">
        <v-col cols="12" md="4">
          <v-text-field v-model="filtres.du" type="date" label="Du" variant="outlined" density="comfortable" />
        </v-col>
        <v-col cols="12" md="4">
          <v-text-field v-model="filtres.au" type="date" label="Au" variant="outlined" density="comfortable" />
        </v-col>
        <v-col cols="12" md="4">
          <v-btn color="primary" block :loading="loading" @click="charger">Actualiser</v-btn>
        </v-col>
      </v-row>
    </v-card>

    <v-skeleton-loader v-if="loading" type="card, article" />

    <template v-else-if="bilan && resultat && balance">
      <!-- En-tete d'impression, une seule fois en tete de liasse -->
      <div class="etat-print-header">
        <div class="etat-print-header__brand">
          <div class="etat-print-header__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
            <v-icon v-else icon="mdi-finance" size="16" color="white" />
          </div>
          <div>
            <span class="etat-print-header__company">{{ parametresStore.parametres.nom }}</span>
            <span class="etat-print-header__service">Direction Financière</span>
            <span class="etat-print-header__doc">États financiers</span>
          </div>
        </div>
        <div class="etat-print-header__meta">
          <span>Exercice : <strong>{{ fmtDate(filtres.du) }} au {{ fmtDate(filtres.au) }}</strong></span>
          <span>Imprimé le : {{ dateImpression }}</span>
        </div>
      </div>

      <!-- ── BILAN ─────────────────────────────────────────── -->
      <section class="etat-bloc">
        <h2 class="etat-titre">Bilan au {{ fmtDate(bilan.au) }}</h2>
        <v-row>
          <v-col cols="12" md="6">
            <table class="etat-table">
              <thead><tr><th colspan="2">ACTIF</th><th class="num">{{ fmt(bilan.totalActif) }}</th></tr></thead>
              <tbody>
                <tr v-for="l in bilan.actifs" :key="'a'+l.numero">
                  <td>{{ l.numero }}</td><td>{{ l.libelle }}</td><td class="num">{{ fmt(l.montant) }}</td>
                </tr>
              </tbody>
              <tfoot><tr><td colspan="2">Total actif</td><td class="num">{{ fmt(bilan.totalActif) }}</td></tr></tfoot>
            </table>
          </v-col>
          <v-col cols="12" md="6">
            <table class="etat-table">
              <thead><tr><th colspan="2">PASSIF</th><th class="num">{{ fmt(bilan.totalPassif) }}</th></tr></thead>
              <tbody>
                <tr v-for="l in bilan.passifs" :key="'p'+l.numero">
                  <td>{{ l.numero }}</td><td>{{ l.libelle }}</td><td class="num">{{ fmt(l.montant) }}</td>
                </tr>
                <tr><td>13</td><td>Résultat net de l'exercice</td><td class="num">{{ fmt(bilan.resultatNet) }}</td></tr>
              </tbody>
              <tfoot><tr><td colspan="2">Total passif</td><td class="num">{{ fmt(bilan.totalPassif) }}</td></tr></tfoot>
            </table>
          </v-col>
        </v-row>
      </section>

      <!-- ── COMPTE DE RESULTAT ────────────────────────────── -->
      <section class="etat-bloc">
        <h2 class="etat-titre">Compte de résultat</h2>
        <v-row>
          <v-col cols="12" md="6">
            <table class="etat-table">
              <thead><tr><th colspan="2">PRODUITS</th><th class="num">{{ fmt(resultat.totalProduits) }}</th></tr></thead>
              <tbody>
                <tr v-for="l in resultat.produits" :key="'pr'+l.numero">
                  <td>{{ l.numero }}</td><td>{{ l.libelle }}</td><td class="num">{{ fmt(l.montant) }}</td>
                </tr>
              </tbody>
            </table>
          </v-col>
          <v-col cols="12" md="6">
            <table class="etat-table">
              <thead><tr><th colspan="2">CHARGES</th><th class="num">{{ fmt(resultat.totalCharges) }}</th></tr></thead>
              <tbody>
                <tr v-for="l in resultat.charges" :key="'ch'+l.numero">
                  <td>{{ l.numero }}</td><td>{{ l.libelle }}</td><td class="num">{{ fmt(l.montant) }}</td>
                </tr>
              </tbody>
            </table>
          </v-col>
        </v-row>
        <p class="etat-resultat">
          Résultat net : <strong>{{ fmt(resultat.resultatNet) }} USD</strong>
          ({{ resultat.resultatNet >= 0 ? 'bénéfice' : 'perte' }})
        </p>
      </section>

      <!-- ── BALANCE ───────────────────────────────────────── -->
      <section class="etat-bloc">
        <h2 class="etat-titre">Balance de vérification</h2>
        <table class="etat-table">
          <thead>
            <tr>
              <th>Compte</th><th>Libellé</th>
              <th class="num">Débit</th><th class="num">Crédit</th>
              <th class="num">Solde débiteur</th><th class="num">Solde créditeur</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="l in balance.lignes" :key="'b'+l.compteNumero">
              <td>{{ l.compteNumero }}</td><td>{{ l.compteLibelle }}</td>
              <td class="num">{{ fmt(l.totalDebit) }}</td><td class="num">{{ fmt(l.totalCredit) }}</td>
              <td class="num">{{ l.soldeDebiteur ? fmt(l.soldeDebiteur) : '—' }}</td>
              <td class="num">{{ l.soldeCrediteur ? fmt(l.soldeCrediteur) : '—' }}</td>
            </tr>
          </tbody>
          <tfoot>
            <tr>
              <td colspan="2">Totaux</td>
              <td class="num">{{ fmt(balance.totalDebit) }}</td>
              <td class="num">{{ fmt(balance.totalCredit) }}</td>
              <td class="num">{{ fmt(balance.totalSoldeDebiteur) }}</td>
              <td class="num">{{ fmt(balance.totalSoldeCrediteur) }}</td>
            </tr>
          </tfoot>
        </table>
      </section>

      <!-- ── ANALYSE IA ────────────────────────────────────── -->
      <section class="etat-bloc">
        <ComptabiliteAnalyseFinanciereIa :du="filtres.du" :au="filtres.au" />
      </section>
    </template>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }

.etat-bloc { margin-bottom: 28px; }
.etat-titre {
  font-size: 1.05rem; font-weight: 700; color: #111827;
  margin: 0 0 10px; padding-bottom: 6px; border-bottom: 2px solid #16a34a;
}
.etat-table { width: 100%; border-collapse: collapse; font-size: 0.82rem; }
.etat-table th {
  text-align: left; font-size: 0.68rem; font-weight: 700; text-transform: uppercase;
  letter-spacing: 0.4px; color: #6b7280; background: #fafafa;
  padding: 7px 8px; border-bottom: 1px solid #e5e7eb;
}
.etat-table td { padding: 5px 8px; border-bottom: 1px solid #f3f4f6; color: #374151; }
.etat-table tfoot td { font-weight: 700; color: #111827; border-top: 2px solid #e5e7eb; background: #fafafa; }
.etat-table .num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.etat-resultat { margin: 10px 0 0; font-size: 0.9rem; color: #111827; }

@media print {
  /* Chaque etat demarre sur une page neuve : une liasse se lit et s'archive
     etat par etat, pas en flux continu. */
  .etat-bloc { break-inside: auto; page-break-inside: auto; }
  .etat-bloc + .etat-bloc { break-before: page; page-break-before: always; }
  .etat-titre { break-after: avoid; page-break-after: avoid; }
  .etat-table thead { display: table-header-group; }  /* en-tetes repetees */
  .etat-table tr { break-inside: avoid; page-break-inside: avoid; }
}
</style>
