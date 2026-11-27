<script setup lang="ts">
/**
 * Projets à budgéter (27/11/2026), côté comptable et DFIN : à partir de la liste de besoins de la logistique, création
 * d'un sous-compte « Projet … » par besoin (6588 pour un achat divers ; 6058 ou 21-24 pour une acquisition) puis du
 * budget SECONDAIRE du projet en brouillon. Le comptable le soumet ensuite au DA depuis la page du budget ; le DFIN
 * le met en exécution.
 */
import { DUREES, periodesDe, type DureeBudget } from '~/composables/useBudget'

definePageMeta({ module: 'BUDGET', roles: ['COMPTABLE', 'DFIN'] })
useHead({ title: 'Projets à budgéter' })

interface Projet { id: number, code: string, nom: string, description: string | null, statut: string, budgetId: number | null, budgetReference: string | null, budgetStatut: string | null, nombreBesoins: number, prevuUsd: number }
interface Article { id: number, designation: string, unite: string | null, quantitePrevue: number, prixEstimeUsd: number, nature: 'ACHAT' | 'ACQUISITION', compteParent: string | null }
interface Detail { projet: Projet, articles: Article[] }

const api = useApi()
const projets = ref<Projet[]>([])
const erreur = ref('')
const chargement = ref(false)

async function charger() {
  chargement.value = true
  try {
    projets.value = await api<Projet[]>('/budgets/projets')
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Projets indisponibles.')
  } finally {
    chargement.value = false
  }
}
onMounted(charger)
const aBudgeter = computed(() => projets.value.filter(p => p.statut === 'ENVOYE'))
const autres = computed(() => projets.value.filter(p => p.statut !== 'ENVOYE'))

// ── Création du budget ───────────────────────────────────────────────────
interface Choix { articleId: number, designation: string, unite: string | null, nature: 'ACHAT' | 'ACQUISITION', compteParent: string, quantite: number, prixEstimeUsd: number }
const creation = reactive({ ouvert: false, projet: null as Projet | null, exercice: new Date().getFullYear(), duree: 'ANNUEL' as DureeBudget,
  periode: 1, choix: [] as Choix[], enCours: false })
async function ouvrirCreation(p: Projet) {
  try {
    const d = await api<Detail>(`/budgets/projets/${p.id}`)
    Object.assign(creation, { ouvert: true, projet: p, exercice: new Date().getFullYear(), duree: 'ANNUEL', periode: 1,
      choix: d.articles.map(a => ({ articleId: a.id, designation: a.designation, unite: a.unite, nature: a.nature,
        compteParent: a.nature === 'ACHAT' ? '6588' : (a.compteParent && a.compteParent !== '6588' ? a.compteParent : '6058'),
        quantite: Number(a.quantitePrevue), prixEstimeUsd: Number(a.prixEstimeUsd) })) })
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Projet indisponible.')
  }
}
const totalCreation = computed(() => creation.choix.reduce((t, c) => t + (Number(c.quantite) || 0) * (Number(c.prixEstimeUsd) || 0), 0))
const parentAcquisition = (n: string) => n === '6058' || /^2[1-4]/.test(n)
const creationValide = computed(() => creation.choix.length > 0 && creation.choix.every(c => Number(c.quantite) > 0 && Number(c.prixEstimeUsd) >= 0
  && (c.nature === 'ACHAT' || parentAcquisition(c.compteParent || ''))))
async function creerBudget() {
  if (!creation.projet) return
  creation.enCours = true
  try {
    const b = await api<{ id: number }>(`/budgets/projets/${creation.projet.id}/budget`, { method: 'POST', body: {
      exercice: Number(creation.exercice), duree: creation.duree, periode: creation.duree === 'ANNUEL' ? 1 : creation.periode,
      besoins: creation.choix.map(c => ({ articleId: c.articleId, nature: c.nature, compteParent: c.nature === 'ACHAT' ? '6588' : c.compteParent,
        quantite: Number(c.quantite), prixEstimeUsd: Number(c.prixEstimeUsd) })) } })
    creation.ouvert = false
    await navigateTo(`/budgets/${b.id}`)
  } catch (e) {
    erreur.value = messageErreurApi(e, "Le budget du projet n'a pas été créé.")
    creation.ouvert = false
  } finally {
    creation.enCours = false
  }
}

// ── Retour à la logistique ───────────────────────────────────────────────
const dialogues = useDialogues()
async function retourner(p: Projet) {
  const motif = await dialogues.saisir({ titre: `Retourner ${p.code} à la logistique`, libelle: 'Motif (obligatoire)', obligatoire: true, bouton: 'Retourner' })
  if (!motif) return
  try {
    await api(`/budgets/projets/${p.id}/retourner`, { method: 'POST', body: { motif } })
    await charger()
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Retour impossible.')
  }
}
const fmt = (v: number | null | undefined) => new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(v || 0))
const STATUTS: Record<string, string> = { BUDGETE: 'Budgété', RETOURNE: 'Retourné à la logistique', CLOTURE: 'Clôturé' }
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Projets à budgéter</h1>
        <p class="page-sub">Listes de besoins envoyées par la logistique : un sous-compte « Projet … » par besoin, puis le budget secondaire du projet</p>
      </div>
      <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/budgets">Budgets</v-btn>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-card class="classroom-card mb-4">
      <div class="pa-4 pb-2 font-weight-bold">À budgéter</div>
      <v-table density="comfortable">
        <thead><tr><th>Projet</th><th class="text-end">Besoins</th><th class="text-end">Estimé (USD)</th><th /></tr></thead>
        <tbody>
          <tr v-if="!chargement && !aBudgeter.length"><td colspan="4" class="text-center text-medium-emphasis py-6">Aucun projet en attente de budget.</td></tr>
          <tr v-for="p in aBudgeter" :key="p.id">
            <td><div class="font-weight-bold">{{ p.nom }}</div><div class="text-caption text-medium-emphasis">{{ p.code }}<template v-if="p.description"> · {{ p.description }}</template></div></td>
            <td class="text-end">{{ p.nombreBesoins }}</td>
            <td class="text-end">{{ fmt(p.prevuUsd) }}</td>
            <td class="text-end text-no-wrap">
              <v-btn size="small" variant="text" :to="`/logistique/projets/${p.id}`">Voir</v-btn>
              <v-btn size="small" variant="text" color="warning" @click="retourner(p)">Retourner</v-btn>
              <v-btn size="small" color="primary" prepend-icon="mdi-file-document-plus-outline" @click="ouvrirCreation(p)">Créer le budget</v-btn>
            </td>
          </tr>
        </tbody>
      </v-table>
    </v-card>

    <v-card v-if="autres.length" class="classroom-card">
      <div class="pa-4 pb-2 font-weight-bold">Déjà traités</div>
      <v-table density="compact">
        <thead><tr><th>Projet</th><th>Statut</th><th>Budget</th><th class="text-end">Estimé (USD)</th></tr></thead>
        <tbody>
          <tr v-for="p in autres" :key="p.id">
            <td>{{ p.nom }} <span class="text-caption text-medium-emphasis">{{ p.code }}</span></td>
            <td class="text-caption">{{ STATUTS[p.statut] || p.statut }}</td>
            <td><NuxtLink v-if="p.budgetId" :to="`/budgets/${p.budgetId}`">{{ p.budgetReference }}</NuxtLink><template v-else>—</template></td>
            <td class="text-end">{{ fmt(p.prevuUsd) }}</td>
          </tr>
        </tbody>
      </v-table>
    </v-card>

    <v-dialog v-model="creation.ouvert" max-width="1000" scrollable>
      <v-card v-if="creation.projet" rounded="xl">
        <v-card-title class="pt-4 px-5">Budget du projet {{ creation.projet.code }} — {{ creation.projet.nom }}</v-card-title>
        <v-card-text class="px-5">
          <div class="d-flex flex-wrap ga-3 mb-3">
            <v-text-field v-model.number="creation.exercice" label="Exercice" type="number" variant="outlined" density="comfortable" hide-details style="max-width: 140px" />
            <v-select v-model="creation.duree" :items="DUREES" item-title="title" item-value="value" label="Période couverte" variant="outlined" density="comfortable" hide-details style="max-width: 220px" />
            <v-select v-if="creation.duree !== 'ANNUEL'" v-model="creation.periode" :items="periodesDe(creation.duree)" label="Période" variant="outlined" density="comfortable" hide-details style="max-width: 200px" />
          </div>
          <p class="text-caption text-medium-emphasis">
            Un sous-compte est créé par besoin, libellé « Projet {{ creation.projet.nom }} — … » : sous <strong>6588</strong> pour un achat divers,
            sous <strong>6058</strong> ou un compte d'immobilisation <strong>21 à 24</strong> pour une acquisition (une immobilisation crée aussi sa fiche au paiement).
          </p>
          <v-table density="compact">
            <thead><tr><th>Besoin</th><th style="width: 160px">Nature</th><th style="width: 260px">Compte parent</th><th style="width: 110px" class="text-end">Quantité</th><th style="width: 130px" class="text-end">Prix (USD)</th><th class="text-end">Total</th></tr></thead>
            <tbody>
              <tr v-for="c in creation.choix" :key="c.articleId">
                <td>{{ c.designation }}<span v-if="c.unite" class="text-caption text-medium-emphasis"> ({{ c.unite }})</span></td>
                <td><v-select v-model="c.nature" :items="[{ title: 'Achat divers', value: 'ACHAT' }, { title: 'Acquisition', value: 'ACQUISITION' }]" density="compact" variant="outlined" hide-details
                  @update:model-value="(n: string) => { c.compteParent = n === 'ACHAT' ? '6588' : '6058' }" /></td>
                <td>
                  <span v-if="c.nature === 'ACHAT'" class="text-body-2">6588 — Autres charges diverses</span>
                  <ComptabiliteSelecteurCompte v-else v-model="c.compteParent" label="Compte" :filtre="parentAcquisition" />
                </td>
                <td><v-text-field v-model.number="c.quantite" type="number" density="compact" variant="outlined" hide-details /></td>
                <td><v-text-field v-model.number="c.prixEstimeUsd" type="number" density="compact" variant="outlined" hide-details /></td>
                <td class="text-end text-no-wrap">{{ fmt((Number(c.quantite) || 0) * (Number(c.prixEstimeUsd) || 0)) }}</td>
              </tr>
            </tbody>
            <tfoot><tr class="font-weight-bold"><td colspan="5">Total du budget</td><td class="text-end">{{ fmt(totalCreation) }} USD</td></tr></tfoot>
          </v-table>
        </v-card-text>
        <v-card-actions class="px-5 pb-4">
          <span class="text-caption text-medium-emphasis">Le budget est créé en brouillon : soumettez-le ensuite au DA depuis sa page.</span>
          <v-spacer /><v-btn variant="text" @click="creation.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :disabled="!creationValide" :loading="creation.enCours" @click="creerBudget">Créer les comptes et le budget</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>
