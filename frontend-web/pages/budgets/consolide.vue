<script setup lang="ts">
/**
 * Budget consolidé (27/11/2026) : tous les budgets EN EXÉCUTION de l'exercice, principal et secondaires (projets),
 * additionnés compte par compte à partir de leur suivi (prévu, engagé, réalisé, disponible). Lecture seule.
 */
import { SECTIONS, libelleSection, fmtEntier, fmtTaux, centimes } from '~/composables/useBudget'

definePageMeta({ module: 'BUDGET', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'CAISSIER'] })
useHead({ title: 'Budget consolidé' })

interface BudgetResume { id: number, reference: string, intitule: string, exercice: number, statut: string, numeroRevision: number,
  typeBudget?: 'PRINCIPAL' | 'SECONDAIRE', libellePeriode: string }
interface LigneSuivi { compteNumero: string, compteLibelle: string, section: string, prevuAnnuel: number, realiseAnnuel: number, engageAnnuel: number }
interface Suivi { budgetId: number, lignes: LigneSuivi[] }
interface Source { budget: BudgetResume, prevu: number, engage: number, realise: number }
interface Ligne { compteNumero: string, compteLibelle: string, section: string, budgets: string[], prevu: number, engage: number, realise: number }

const api = useApi()
const chargement = ref(false)
const erreur = ref('')
const enExecution = ref<BudgetResume[]>([])
const suivis = ref<Record<number, Suivi>>({})
const exercice = ref<number | null>(null)

async function charger() {
  chargement.value = true
  erreur.value = ''
  try {
    const tous = await api<BudgetResume[]>('/budgets')
    enExecution.value = tous.filter(b => b.statut === 'EN_EXECUTION')
      .sort((a, b) => (a.typeBudget === 'SECONDAIRE' ? 1 : 0) - (b.typeBudget === 'SECONDAIRE' ? 1 : 0) || a.reference.localeCompare(b.reference))
    const annee = new Date().getFullYear()
    exercice.value = exercices.value.includes(annee) ? annee : (exercices.value[0] ?? null)
    const resultats = await Promise.all(enExecution.value.map(b => api<Suivi>(`/budgets/${b.id}/suivi`)))
    suivis.value = Object.fromEntries(resultats.map(s => [s.budgetId, s]))
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le budget consolidé.')
  } finally {
    chargement.value = false
  }
}
onMounted(charger)

const exercices = computed(() => [...new Set(enExecution.value.map(b => b.exercice))].sort((a, b) => b - a))
const budgets = computed(() => enExecution.value.filter(b => b.exercice === exercice.value))
const n = (v: unknown) => Number(v) || 0
const estDepense = (section: string) => section !== 'PRODUITS'

const sources = computed<Source[]>(() => budgets.value.map((budget) => {
  const lignes = (suivis.value[budget.id]?.lignes || []).filter(l => estDepense(l.section))
  return { budget, prevu: lignes.reduce((s, l) => s + n(l.prevuAnnuel), 0), engage: lignes.reduce((s, l) => s + n(l.engageAnnuel), 0),
    realise: lignes.reduce((s, l) => s + n(l.realiseAnnuel), 0) }
}))

/** Lignes de tous les budgets, additionnées par compte. */
const lignes = computed<Ligne[]>(() => {
  const parCompte = new Map<string, Ligne>()
  for (const b of budgets.value) {
    for (const l of suivis.value[b.id]?.lignes || []) {
      const cle = `${l.section}|${l.compteNumero}`
      const ligne = parCompte.get(cle) || { compteNumero: l.compteNumero, compteLibelle: l.compteLibelle, section: l.section, budgets: [], prevu: 0, engage: 0, realise: 0 }
      ligne.prevu += n(l.prevuAnnuel)
      ligne.engage += n(l.engageAnnuel)
      ligne.realise += n(l.realiseAnnuel)
      if (!ligne.budgets.includes(b.reference)) ligne.budgets.push(b.reference)
      parCompte.set(cle, ligne)
    }
  }
  return [...parCompte.values()].sort((a, b) => a.compteNumero.localeCompare(b.compteNumero))
})
const sections = computed(() => Object.keys(SECTIONS)
  .map(code => ({ code, lignes: lignes.value.filter(l => l.section === code) }))
  .filter(s => s.lignes.length)
  .map(s => ({ ...s, prevu: s.lignes.reduce((t, l) => t + l.prevu, 0), engage: s.lignes.reduce((t, l) => t + l.engage, 0),
    realise: s.lignes.reduce((t, l) => t + l.realise, 0) })))

const total = computed(() => {
  const dep = sources.value
  const prevu = dep.reduce((s, x) => s + x.prevu, 0)
  const engage = dep.reduce((s, x) => s + x.engage, 0)
  const realise = dep.reduce((s, x) => s + x.realise, 0)
  return { prevu, engage, realise, disponible: centimes(prevu - engage - realise), taux: prevu > 0 ? (realise / prevu) * 100 : null }
})
const disponible = (l: { prevu: number, engage: number, realise: number }) => centimes(l.prevu - l.engage - l.realise)
const taux = (l: { prevu: number, realise: number }) => (l.prevu > 0 ? (l.realise / l.prevu) * 100 : null)
const imprimer = () => window.print()
const refsAbregees = (refs: string[]) => refs.map(r => r.replace(/^BUD-\d{4}-0*/, 'BUD-'))
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Budget consolidé</h1>
        <p class="page-sub">Tous les budgets en exécution de l'exercice, principal et projets, additionnés compte par compte</p>
      </div>
      <div class="d-flex ga-2 no-print">
        <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/budgets">Budgets</v-btn>
        <v-btn variant="tonal" prepend-icon="mdi-printer-outline" :disabled="!budgets.length" @click="imprimer">Imprimer</v-btn>
      </div>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-skeleton-loader v-if="chargement" type="card, table" />
    <template v-else>
      <v-empty-state v-if="!budgets.length && !erreur" icon="mdi-chart-box-outline" title="Aucun budget en exécution"
        text="Le budget consolidé additionne les budgets mis en exécution par le DFIN." />

      <template v-else-if="budgets.length">
        <div v-if="exercices.length > 1" class="mb-3 no-print">
          <v-chip-group v-model="exercice" mandatory selected-class="text-primary">
            <v-chip v-for="e in exercices" :key="e" :value="e" variant="outlined">{{ e }}</v-chip>
          </v-chip-group>
        </div>

        <div class="cons-tuiles mb-4">
          <div><span>Dépenses prévues</span><strong>{{ fmtEntier(total.prevu) }}</strong></div>
          <div><span>Engagé</span><strong>{{ fmtEntier(total.engage) }}</strong></div>
          <div><span>Réalisé</span><strong>{{ fmtEntier(total.realise) }}</strong></div>
          <div><span>Disponible</span><strong :class="total.disponible < 0 ? 'text-error' : 'text-success'">{{ fmtEntier(total.disponible) }}</strong></div>
          <div><span>Exécution</span><strong :class="Number(total.taux || 0) > 100 ? 'text-error' : ''">{{ fmtTaux(total.taux) }}</strong></div>
        </div>

        <v-card class="classroom-card mb-4">
          <div class="pa-4 pb-2 font-weight-bold">Budgets consolidés ({{ budgets.length }}) — exercice {{ exercice }}, en USD</div>
          <v-table density="compact">
            <thead><tr><th>Budget</th><th>Type</th><th>Période</th><th class="text-end">Prévu</th><th class="text-end">Engagé</th><th class="text-end">Réalisé</th><th class="text-end">Disponible</th><th class="text-end">Exécution</th></tr></thead>
            <tbody>
              <tr v-for="s in sources" :key="s.budget.id">
                <td>
                  <NuxtLink :to="`/budgets/${s.budget.id}`" class="font-weight-bold">{{ s.budget.reference }}</NuxtLink>
                  <span v-if="s.budget.numeroRevision" class="text-caption text-medium-emphasis"> · rév. {{ s.budget.numeroRevision }}</span>
                  <div class="text-caption">{{ s.budget.intitule }}</div>
                </td>
                <td>
                  <v-chip v-if="s.budget.typeBudget === 'SECONDAIRE'" size="x-small" color="deep-purple" variant="tonal" prepend-icon="mdi-briefcase-outline">Secondaire</v-chip>
                  <v-chip v-else size="x-small" color="primary" variant="tonal">Principal</v-chip>
                </td>
                <td class="text-caption">{{ s.budget.libellePeriode }}</td>
                <td class="text-end">{{ fmtEntier(s.prevu) }}</td>
                <td class="text-end">{{ fmtEntier(s.engage) }}</td>
                <td class="text-end">{{ fmtEntier(s.realise) }}</td>
                <td class="text-end" :class="disponible(s) < 0 ? 'text-error' : ''">{{ fmtEntier(disponible(s)) }}</td>
                <td class="text-end">{{ fmtTaux(taux(s)) }}</td>
              </tr>
            </tbody>
            <tfoot>
              <tr class="font-weight-bold">
                <td colspan="3">Total consolidé</td>
                <td class="text-end">{{ fmtEntier(total.prevu) }}</td><td class="text-end">{{ fmtEntier(total.engage) }}</td>
                <td class="text-end">{{ fmtEntier(total.realise) }}</td>
                <td class="text-end" :class="total.disponible < 0 ? 'text-error' : ''">{{ fmtEntier(total.disponible) }}</td>
                <td class="text-end">{{ fmtTaux(total.taux) }}</td>
              </tr>
            </tfoot>
          </v-table>
        </v-card>

        <v-card v-for="s in sections" :key="s.code" class="classroom-card mb-4">
          <div class="pa-4 pb-2 font-weight-bold" :style="{ color: SECTIONS[s.code]?.color }">{{ libelleSection(s.code) }}</div>
          <v-table density="compact">
            <thead><tr><th>Compte</th><th>Budgets</th><th class="text-end">Prévu</th><th class="text-end">Engagé</th><th class="text-end">Réalisé</th>
              <th v-if="estDepense(s.code)" class="text-end">Disponible</th><th class="text-end">Exécution</th></tr></thead>
            <tbody>
              <tr v-for="l in s.lignes" :key="l.compteNumero">
                <td><span class="font-weight-medium">{{ l.compteNumero }}</span> — {{ l.compteLibelle }}</td>
                <td class="text-caption text-medium-emphasis" :title="l.budgets.join(', ')">{{ refsAbregees(l.budgets).join(', ') }}</td>
                <td class="text-end">{{ fmtEntier(l.prevu) }}</td>
                <td class="text-end">{{ fmtEntier(l.engage) }}</td>
                <td class="text-end">{{ fmtEntier(l.realise) }}</td>
                <td v-if="estDepense(s.code)" class="text-end" :class="disponible(l) < 0 ? 'text-error font-weight-bold' : ''">{{ fmtEntier(disponible(l)) }}</td>
                <td class="text-end" :class="estDepense(s.code) && Number(taux(l) || 0) > 100 ? 'text-error' : ''">{{ fmtTaux(taux(l)) }}</td>
              </tr>
            </tbody>
            <tfoot>
              <tr class="font-weight-bold">
                <td colspan="2">Total {{ libelleSection(s.code).toLowerCase() }}</td>
                <td class="text-end">{{ fmtEntier(s.prevu) }}</td><td class="text-end">{{ fmtEntier(s.engage) }}</td><td class="text-end">{{ fmtEntier(s.realise) }}</td>
                <td v-if="estDepense(s.code)" class="text-end">{{ fmtEntier(disponible(s)) }}</td>
                <td class="text-end">{{ fmtTaux(taux(s)) }}</td>
              </tr>
            </tfoot>
          </v-table>
        </v-card>
      </template>
    </template>
  </div>
</template>

<style scoped>
.cons-tuiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; }
.cons-tuiles div { background: var(--surface-2); border-radius: 10px; padding: 10px 12px; }
.cons-tuiles span { display: block; font-size: 0.72rem; color: var(--ink-soft); }
.cons-tuiles strong { font-size: 1.15rem; font-variant-numeric: tabular-nums; }
@media print { .no-print { display: none !important; } }
</style>
