<script setup lang="ts">
// CAISSIER a COMPTABILITE en LECTURE (pour Balance/Compte de resultat
// uniquement) mais ne voit pas les Budgets.
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] })

interface BudgetResume {
  id: number
  reference: string
  intitule: string
  exercice: number
  statut: string
  numeroRevision: number
  elaboreParNom?: string
  approuveParNom?: string
  prevuProduits: number
  prevuCharges: number
  prevuInvestissements: number
  realiseProduits: number
  realiseCharges: number
  realiseInvestissements: number
  totalPrevu: number
  totalRealise: number
  tauxExecution: number | null
  nombreLignes: number
}

useHead({ title: 'Budgets' })
const api = useApi()
const auth = useAuthStore()
const loading = ref(false)
const erreur = ref('')
const budgets = ref<BudgetResume[]>([])
const filtreExercice = ref<number | null>(null)

// L'administrateur dispose des droits du DFIN et du DA (le serveur l'accepte aussi).
const peutElaborer = computed(() => auth.hasAnyRole(['DFIN', 'ADMIN']))

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    budgets.value = await api<BudgetResume[]>('/budgets')
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les budgets.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

const exercices = computed(() => [...new Set(budgets.value.map(b => b.exercice))].sort((a, b) => b - a))
const affiches = computed(() => budgets.value.filter(b => !filtreExercice.value || b.exercice === filtreExercice.value))
const enExecution = computed(() => budgets.value.find(b => b.statut === 'EN_EXECUTION' && b.exercice === new Date().getFullYear()))
const resultatPrevu = (b: BudgetResume) => centimes(b.prevuProduits - b.prevuCharges)
// Seul un budget mis en exécution (même remplacé ou clôturé depuis) a un taux d'exécution qui a un sens.
const aEteExecute = (b: BudgetResume) => ['EN_EXECUTION', 'REMPLACE', 'CLOTURE'].includes(b.statut)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Budgets</h1>
        <p class="page-sub">Budgets annuels ventilés par mois, élaborés par le DFIN, approuvés par le DA, suivis sur le grand livre</p>
      </div>
      <v-btn v-if="peutElaborer" color="primary" prepend-icon="mdi-plus" height="44" to="/budgets/nouveau">Nouveau budget</v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-alert v-if="!loading && !enExecution" type="warning" variant="tonal" class="mb-4" icon="mdi-calendar-alert">
      Aucun budget n’est en exécution pour {{ new Date().getFullYear() }} : toute note de frais de l’exercice devra
      justifier sa dépense « hors budget ».
      <span v-if="peutElaborer">Créez un budget (ou faites-le proposer par l’IA), faites-le approuver par le DA, puis démarrez-le.</span>
    </v-alert>

    <div v-if="exercices.length > 1" class="d-flex align-center ga-2 mb-3">
      <v-chip-group v-model="filtreExercice" selected-class="text-primary" column>
        <v-chip :value="null" variant="outlined">Tous</v-chip>
        <v-chip v-for="e in exercices" :key="e" :value="e" variant="outlined">{{ e }}</v-chip>
      </v-chip-group>
    </div>

    <v-skeleton-loader v-if="loading" type="card, card, card" />

    <v-row v-else>
      <v-col v-for="b in affiches" :key="b.id" cols="12" md="6" lg="4">
        <v-card class="classroom-card pa-4 bud-carte" height="100%" :to="`/budgets/${b.id}`">
          <div class="d-flex align-start flex-wrap ga-2 mb-1">
            <div class="flex-grow-1" style="min-width: 0; flex-basis: 60%">
              <div class="text-caption text-medium-emphasis">
                {{ b.reference }}<span v-if="b.numeroRevision"> · révision n° {{ b.numeroRevision }}</span>
              </div>
              <div class="text-subtitle-1 font-weight-bold bud-titre" :title="b.intitule">{{ b.intitule }}</div>
            </div>
            <v-chip :color="statutBudget(b.statut).color" size="small" variant="tonal" class="bud-statut" :prepend-icon="statutBudget(b.statut).icon">
              {{ statutBudget(b.statut).label }}
            </v-chip>
          </div>
          <div class="text-caption text-medium-emphasis mb-3">Exercice {{ b.exercice }} · {{ b.nombreLignes }} ligne(s)</div>

          <div class="bud-chiffres">
            <div>
              <span>Produits prévus</span>
              <strong>{{ fmtEntier(b.prevuProduits) }}</strong>
            </div>
            <div>
              <span>Charges prévues</span>
              <strong>{{ fmtEntier(b.prevuCharges) }}</strong>
            </div>
            <div>
              <span>Résultat prévu</span>
              <strong :class="resultatPrevu(b) >= 0 ? 'text-success' : 'text-error'">{{ fmtEntier(resultatPrevu(b)) }}</strong>
            </div>
          </div>

          <template v-if="aEteExecute(b)">
            <div class="d-flex justify-space-between text-caption mt-3 mb-1">
              <span>Dépenses réalisées : {{ fmtEntier(b.totalRealise) }} / {{ fmtEntier(b.totalPrevu) }} USD</span>
              <strong :class="Number(b.tauxExecution || 0) > 100 ? 'text-error' : ''">{{ fmtTaux(b.tauxExecution) }}</strong>
            </div>
            <v-progress-linear
              :model-value="Math.min(100, Number(b.tauxExecution || 0))"
              :color="Number(b.tauxExecution || 0) > 100 ? 'error' : 'primary'"
              height="8"
              rounded
            />
          </template>
          <div v-else class="text-caption text-medium-emphasis mt-3">
            Dépenses prévues : {{ fmtEntier(b.totalPrevu) }} USD — pas encore en exécution
          </div>
        </v-card>
      </v-col>
    </v-row>

    <v-empty-state
      v-if="!loading && budgets.length === 0"
      icon="mdi-chart-box-outline"
      title="Aucun budget"
      text="Aucun budget prévisionnel n'a encore été créé."
    />
  </div>
</template>

<style scoped>
.bud-titre { overflow-wrap: anywhere; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.bud-statut { flex-shrink: 0; max-width: none; }
.bud-statut :deep(.v-chip__content) { overflow: visible; text-overflow: clip; }
.bud-carte { transition: box-shadow 0.15s; }
.bud-carte:hover { box-shadow: 0 6px 18px rgba(15, 23, 42, 0.08); }
.bud-chiffres { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.bud-chiffres div { background: #f8fafc; border-radius: 8px; padding: 8px; }
.bud-chiffres span { display: block; font-size: 0.68rem; color: #6b7280; }
.bud-chiffres strong { font-size: 0.95rem; font-variant-numeric: tabular-nums; }
</style>
