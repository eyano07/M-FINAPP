<script setup lang="ts">
definePageMeta({ module: 'DRH_PAIE' })

interface Bulletin {
  id: number
  employeMatricule: string
  employeNomComplet: string
  mois: number
  annee: number
  salaireBaseUsd: number
  presencePct: number
  nombreEnfants: number
  conge: number
  heuresSupplementaires: number
  allocationFamiliale: number
  primeDiplome: number
  primeAnciennete: number
  primeRendement: number
  avanceSalaire: number
  pret: number
  salaireBrut: number
  indemniteLogement: number
  indemniteTransport: number
  cnssOuvriere: number
  cnssPatronale: number
  onem: number
  totalInss: number
  inpp: number
  ipr: number
  salaireNet: number
  tauxChangeApplique: number
  netFc: number
  datePaiement: string
  statut: string
  pieceReference: string | null
  pieceStatut: string | null
  /** Pilote le document imprimé : bulletin complet si vrai, reçu simplifié sinon. */
  employeConforme: boolean
}

const MOIS = [
  'Janvier', 'Février', 'Mars', 'Avril', 'Mai', 'Juin',
  'Juillet', 'Août', 'Septembre', 'Octobre', 'Novembre', 'Décembre',
]

const route = useRoute()
const api = useApi()
const parametresStore = useParametresStore()

const loading = ref(true)
const erreur = ref('')
const bulletin = ref<Bulletin | null>(null)

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    bulletin.value = await api<Bulletin>(`/drh/bulletins/${route.params.id}`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le bulletin.')
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

const fmtUsd = (v: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(v || 0)
const fmtFc = (v: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(v || 0) + ' FC'
const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">{{ bulletin?.employeConforme === false ? 'Reçu de paiement' : 'Bulletin de paie' }}</h1>
        <p class="page-sub" v-if="bulletin">{{ bulletin.employeNomComplet }} — {{ MOIS[bulletin.mois - 1] }} {{ bulletin.annee }}</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/drh/bulletins">Retour</v-btn>
        <v-btn color="error" variant="tonal" rounded="lg" prepend-icon="mdi-printer-outline" :disabled="!bulletin" @click="imprimer">
          Imprimer
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print">{{ erreur }}</v-alert>
    <v-skeleton-loader v-if="loading" type="article" />

    <template v-else-if="bulletin">
      <div class="etat-print-header">
        <div class="etat-print-header__brand">
          <div class="etat-print-header__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
            <v-icon v-else icon="mdi-finance" size="16" color="white" />
          </div>
          <div>
            <span class="etat-print-header__company">{{ parametresStore.parametres.nom }}</span>
            <span class="etat-print-header__service">Direction des Ressources Humaines</span>
            <span class="etat-print-header__doc">{{ bulletin.employeConforme ? 'Bulletin de paie' : 'Reçu de paiement' }}</span>
          </div>
        </div>
        <div class="etat-print-header__meta">
          <span>Période : <strong>{{ MOIS[bulletin.mois - 1] }} {{ bulletin.annee }}</strong></span>
          <span>Imprimé le : {{ dateImpression }}</span>
        </div>
      </div>

      <v-card class="classroom-card pa-6 mb-4">
        <v-row>
          <v-col cols="12" md="4"><span class="calc-label">Matricule</span><br><strong>{{ bulletin.employeMatricule }}</strong></v-col>
          <v-col cols="12" md="4"><span class="calc-label">Employé</span><br><strong>{{ bulletin.employeNomComplet }}</strong></v-col>
          <v-col cols="12" md="4"><span class="calc-label">Date de paiement</span><br><strong>{{ fmtDate(bulletin.datePaiement) }}</strong></v-col>
          <v-col cols="12" md="4"><span class="calc-label">Présence</span><br><strong>{{ bulletin.presencePct }}%</strong></v-col>
          <v-col cols="12" md="4"><span class="calc-label">Enfants à charge</span><br><strong>{{ bulletin.nombreEnfants }}</strong></v-col>
          <v-col cols="12" md="4"><span class="calc-label">Statut</span><br><strong>{{ bulletin.statut }}</strong></v-col>
        </v-row>
      </v-card>

      <template v-if="bulletin.employeConforme">
        <section class="etat-bloc">
          <h2 class="etat-titre">Gains</h2>
          <table class="etat-table">
            <tbody>
              <tr><td>Salaire brut (prorata présence)</td><td class="num">{{ fmtUsd(bulletin.salaireBrut) }}</td></tr>
              <tr><td>Indemnité de logement</td><td class="num">{{ fmtUsd(bulletin.indemniteLogement) }}</td></tr>
              <tr><td>Indemnité de transport</td><td class="num">{{ fmtUsd(bulletin.indemniteTransport) }}</td></tr>
              <tr><td>Congé</td><td class="num">{{ fmtUsd(bulletin.conge) }}</td></tr>
              <tr><td>Heures supplémentaires</td><td class="num">{{ fmtUsd(bulletin.heuresSupplementaires) }}</td></tr>
              <tr><td>Allocation familiale</td><td class="num">{{ fmtUsd(bulletin.allocationFamiliale) }}</td></tr>
              <tr><td>Prime diplôme</td><td class="num">{{ fmtUsd(bulletin.primeDiplome) }}</td></tr>
              <tr><td>Prime ancienneté</td><td class="num">{{ fmtUsd(bulletin.primeAnciennete) }}</td></tr>
              <tr><td>Prime rendement</td><td class="num">{{ fmtUsd(bulletin.primeRendement) }}</td></tr>
            </tbody>
          </table>
        </section>

        <section class="etat-bloc">
          <h2 class="etat-titre">Retenues</h2>
          <table class="etat-table">
            <tbody>
              <tr><td>CNSS ouvrière</td><td class="num">{{ fmtUsd(bulletin.cnssOuvriere) }}</td></tr>
              <tr><td>IPR</td><td class="num">{{ fmtUsd(bulletin.ipr) }}</td></tr>
              <tr><td>Avance sur salaire</td><td class="num">{{ fmtUsd(bulletin.avanceSalaire) }}</td></tr>
              <tr><td>Prêt</td><td class="num">{{ fmtUsd(bulletin.pret) }}</td></tr>
            </tbody>
          </table>
        </section>

        <section class="etat-bloc">
          <h2 class="etat-titre">Charges patronales (à titre indicatif)</h2>
          <table class="etat-table">
            <tbody>
              <tr><td>CNSS patronale</td><td class="num">{{ fmtUsd(bulletin.cnssPatronale) }}</td></tr>
              <tr><td>ONEM</td><td class="num">{{ fmtUsd(bulletin.onem) }}</td></tr>
              <tr><td>INPP</td><td class="num">{{ fmtUsd(bulletin.inpp) }}</td></tr>
            </tbody>
          </table>
        </section>
      </template>

      <!-- Reçu simplifié : employé non conforme (dossier incomplet), montant
           net uniquement — pas de détail de calcul sur le document remis. -->
      <section v-else class="etat-bloc">
        <p class="text-body-2 mb-3">
          Reçu de la somme de <strong>{{ fmtUsd(bulletin.salaireNet) }}</strong>
          ({{ fmtFc(bulletin.netFc) }}) au titre du salaire de
          {{ MOIS[bulletin.mois - 1] }} {{ bulletin.annee }}.
        </p>
        <table class="etat-table recu-signature">
          <tbody>
            <tr><td>Signature de l'employé</td><td class="signature-cell" /></tr>
          </tbody>
        </table>
      </section>

      <v-card class="classroom-card pa-6" color="teal" variant="tonal">
        <v-row>
          <v-col cols="12" md="6">
            <span class="calc-label">Salaire net à payer (USD)</span><br>
            <strong class="text-h5">{{ fmtUsd(bulletin.salaireNet) }}</strong>
          </v-col>
          <v-col cols="12" md="6">
            <span class="calc-label">Salaire net à payer (FC) — taux {{ bulletin.tauxChangeApplique }}</span><br>
            <strong class="text-h5">{{ fmtFc(bulletin.netFc) }}</strong>
          </v-col>
        </v-row>
      </v-card>

      <p v-if="bulletin.pieceReference" class="text-caption text-medium-emphasis mt-4 no-print">
        Pièce comptable : <strong>{{ bulletin.pieceReference }}</strong> ({{ bulletin.pieceStatut }})
      </p>
    </template>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.calc-label { font-size: 0.7rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.4px; }

.etat-bloc { margin-bottom: 20px; }
.etat-titre { font-size: 1.05rem; font-weight: 700; color: #111827; margin: 0 0 10px; padding-bottom: 6px; border-bottom: 2px solid #16a34a; }
.etat-table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
.etat-table td { padding: 6px 8px; border-bottom: 1px solid #f3f4f6; color: #374151; }
.etat-table .num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.recu-signature { max-width: 360px; }
.signature-cell { min-width: 160px; border-bottom: 1px solid #111827 !important; }
</style>
