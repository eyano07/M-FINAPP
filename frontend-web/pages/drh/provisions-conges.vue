<script setup lang="ts">
import { useExerciceStore } from '~/stores/exercice'
// Provision pour congés payés de fin d'exercice (audit DRH du 15/11/2026, D-05). Simulation : RESP_DRH, DFIN,
// ADMIN ; constitution et annulation : DFIN seul (le serveur le vérifie aussi).
definePageMeta({ module: 'DRH_PAIE' })

interface LigneProvision {
  employeId: number
  matricule: string
  nomComplet: string
  expatrie: boolean
  dateEmbauche: string | null
  moisService: number
  ancienneteAnnees: number
  joursAcquis: number
  salaireJournalier: number
  droitsAcquis: number
  congesPayes: number
  provision: number
  charges: number
}
interface ProvisionResume {
  id: number
  annee: number
  dateArrete: string
  montantConges: number
  montantCharges: number
  piece: string | null
  pieceExtourne: string | null
  statut: 'ACTIVE' | 'ANNULEE'
  creePar: string | null
  creeLe: string | null
}
interface Simulation {
  annee: number
  dateArrete: string
  tauxCharges: number
  lignes: LigneProvision[]
  totalDroits: number
  totalCongesPayes: number
  totalProvision: number
  totalCharges: number
  provisionActive: ProvisionResume | null
}

const api = useApi()
const auth = useAuthStore()
const dialogues = useDialogues()
const estDfin = computed(() => auth.hasRole('DFIN'))

const anneeCourante = new Date().getFullYear()
// Exercice choisi dans la barre du haut.
const annee = ref(useExerciceStore().anneeChoisie)
const annees = Array.from({ length: 6 }, (_, i) => anneeCourante - i)
const dateArrete = ref(`${annee.value}-12-31`)
watch(annee, a => { dateArrete.value = `${a}-12-31` })

const simulation = ref<Simulation | null>(null)
const provisions = ref<ProvisionResume[]>([])
const loading = ref(false)
const enCours = ref(false)
const erreur = ref('')
const succes = ref('')

const fmtUsd = (v: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(v) || 0)
const fmtNb = (v: number) => (Number(v) || 0).toLocaleString('fr-FR', { maximumFractionDigits: 2 })
const fmtDate = (d: string | null) => d ? new Date(d).toLocaleDateString('fr-FR') : '—'

const entetes = [
  { title: 'Agent', key: 'nomComplet' },
  { title: 'Mois de service', key: 'moisService', align: 'end' as const },
  { title: 'Ancienneté', key: 'ancienneteAnnees', align: 'end' as const },
  { title: 'Jours acquis', key: 'joursAcquis', align: 'end' as const },
  { title: 'Salaire journalier', key: 'salaireJournalier', align: 'end' as const },
  { title: 'Droits acquis', key: 'droitsAcquis', align: 'end' as const },
  { title: 'Congés payés', key: 'congesPayes', align: 'end' as const },
  { title: 'Provision', key: 'provision', align: 'end' as const },
  { title: 'Charges sociales', key: 'charges', align: 'end' as const },
]

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [s, l] = await Promise.all([
      api<Simulation>('/drh/provisions-conges/simulation', { params: { annee: annee.value, dateArrete: dateArrete.value } }),
      api<ProvisionResume[]>('/drh/provisions-conges'),
    ])
    simulation.value = s
    provisions.value = l
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de calculer la provision pour congés payés.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)
watch([annee, dateArrete], charger)

async function constituer() {
  const s = simulation.value
  if (!s) return
  const confirme = await dialogues.confirmer({
    titre: `Constituer la provision pour congés payés ${s.annee} ?`,
    message: [
      `Une pièce au journal PAIE datée du ${fmtDate(s.dateArrete)} : ${fmtUsd(s.totalProvision)} de congés `
        + `(débit 6613 / 6623, crédit 4281) et ${fmtUsd(s.totalCharges)} de charges sociales (débit 6641 / 6642, crédit 4382).`,
      "Son extourne est écrite au lendemain : l'exercice suivant repart des congés réellement payés.",
    ],
    bouton: 'Constituer la provision',
    nature: 'attention',
    icone: 'mdi-beach',
  })
  if (!confirme) return
  enCours.value = true
  erreur.value = ''
  try {
    const p = await api<ProvisionResume>('/drh/provisions-conges', {
      method: 'POST', params: { annee: s.annee, dateArrete: s.dateArrete },
    })
    succes.value = `Provision ${p.annee} constituée : pièce ${p.piece ?? ''}, extourne ${p.pieceExtourne ?? ''}.`
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la constitution de la provision.')
  } finally {
    enCours.value = false
  }
}

async function annuler(p: ProvisionResume) {
  const confirme = await dialogues.confirmer({
    titre: `Annuler la provision pour congés payés ${p.annee} ?`,
    message: `Les pièces ${p.piece ?? ''} et ${p.pieceExtourne ?? ''} seront extournées. La date d'arrêté doit être dans une période ouverte.`,
    bouton: 'Annuler la provision',
    abandon: 'Retour',
    nature: 'danger',
  })
  if (!confirme) return
  enCours.value = true
  erreur.value = ''
  try {
    await api(`/drh/provisions-conges/${p.id}/annuler`, { method: 'POST' })
    succes.value = `Provision ${p.annee} annulée.`
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'annulation de la provision.")
  } finally {
    enCours.value = false
  }
}
</script>

<template>
  <div class="provisions-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Provision pour congés payés</h1>
        <p class="page-sub">
          Congés acquis et non pris à la clôture, rattachés à l'exercice : 1 jour ouvrable par mois entier de service,
          plus 1 jour par période de 5 ans d'ancienneté (Code du travail), valorisés au salaire journalier, moins les
          indemnités de congé déjà payées. Charges sociales au taux de la CNSS patronale.
        </p>
      </div>
    </div>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-4 mb-4">
      <v-row dense align="center">
        <v-col cols="6" md="3">
          <v-select v-model="annee" :items="annees" label="Exercice" variant="outlined" density="comfortable" hide-details />
        </v-col>
        <v-col cols="6" md="3">
          <v-text-field v-model="dateArrete" type="date" label="Date d'arrêté" variant="outlined" density="comfortable" hide-details />
        </v-col>
        <v-col cols="12" md="6" class="d-flex justify-md-end">
          <v-btn v-if="estDfin && simulation && !simulation.provisionActive && simulation.totalProvision > 0"
            color="primary" variant="flat" rounded="lg" prepend-icon="mdi-beach" :loading="enCours" @click="constituer">
            Constituer la provision
          </v-btn>
        </v-col>
      </v-row>
      <p v-if="simulation?.provisionActive" class="text-body-2 mt-3 mb-0">
        Provision {{ simulation.annee }} déjà constituée (pièce {{ simulation.provisionActive.piece }}) : annulez-la
        dans la liste ci-dessous pour la refaire.
      </p>
      <p v-else-if="!estDfin" class="text-caption text-medium-emphasis mt-3 mb-0">
        Simulation seulement : le DFIN constitue la provision.
      </p>
    </v-card>

    <v-card class="classroom-card mb-4">
      <v-data-table :headers="entetes" :items="simulation?.lignes ?? []" :loading="loading" density="comfortable"
        items-per-page="-1" hide-default-footer no-data-text="Aucun agent actif avec un mois entier de service">
        <template #item.nomComplet="{ item }">
          {{ item.matricule }} — {{ item.nomComplet }}<span v-if="item.expatrie" class="text-caption text-medium-emphasis"> (expatrié)</span>
        </template>
        <template #item.ancienneteAnnees="{ item }">{{ item.ancienneteAnnees }} an(s)</template>
        <template #item.joursAcquis="{ item }">{{ fmtNb(item.joursAcquis) }}</template>
        <template #item.salaireJournalier="{ item }">{{ fmtUsd(item.salaireJournalier) }}</template>
        <template #item.droitsAcquis="{ item }">{{ fmtUsd(item.droitsAcquis) }}</template>
        <template #item.congesPayes="{ item }">{{ fmtUsd(item.congesPayes) }}</template>
        <template #item.provision="{ item }"><strong>{{ fmtUsd(item.provision) }}</strong></template>
        <template #item.charges="{ item }">{{ fmtUsd(item.charges) }}</template>
        <template v-if="simulation && simulation.lignes.length" #body.append>
          <tr class="ligne-total">
            <td colspan="5">Total ({{ simulation.lignes.length }} agent(s))</td>
            <td class="text-end">{{ fmtUsd(simulation.totalDroits) }}</td>
            <td class="text-end">{{ fmtUsd(simulation.totalCongesPayes) }}</td>
            <td class="text-end">{{ fmtUsd(simulation.totalProvision) }}</td>
            <td class="text-end">{{ fmtUsd(simulation.totalCharges) }}</td>
          </tr>
        </template>
      </v-data-table>
      <p v-if="simulation" class="text-caption text-medium-emphasis pa-3 mb-0">
        Taux des charges sociales : {{ (Number(simulation.tauxCharges) * 100).toLocaleString('fr-FR') }} % (CNSS patronale :
        pensions, prestations familiales, risques professionnels).
      </p>
    </v-card>

    <v-card class="classroom-card pa-4">
      <div class="section-title">Provisions enregistrées</div>
      <v-table v-if="provisions.length" density="comfortable">
        <thead>
          <tr>
            <th>Exercice</th><th>Arrêté</th><th class="text-end">Congés</th><th class="text-end">Charges</th>
            <th>Pièce</th><th>Extourne</th><th>Statut</th><th />
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in provisions" :key="p.id">
            <td>{{ p.annee }}</td>
            <td>{{ fmtDate(p.dateArrete) }}</td>
            <td class="text-end">{{ fmtUsd(p.montantConges) }}</td>
            <td class="text-end">{{ fmtUsd(p.montantCharges) }}</td>
            <td>{{ p.piece ?? '—' }}</td>
            <td>{{ p.pieceExtourne ?? '—' }}</td>
            <td>{{ p.statut === 'ACTIVE' ? 'Active' : 'Annulée' }}</td>
            <td class="text-end">
              <v-btn v-if="estDfin && p.statut === 'ACTIVE'" size="small" variant="text" color="error" :loading="enCours"
                @click="annuler(p)">Annuler</v-btn>
            </td>
          </tr>
        </tbody>
      </v-table>
      <p v-else class="text-body-2 text-medium-emphasis mb-0">Aucune provision enregistrée.</p>
    </v-card>
  </div>
</template>

<style scoped>
.provisions-page { max-width: 1200px; margin: 0 auto; padding-bottom: 48px; }
.section-title { font-size: 0.8rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.5px; color: #0d9488; margin-bottom: 12px; }
.ligne-total td { font-weight: 700; }
</style>
