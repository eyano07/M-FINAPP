<script setup lang="ts">
/**
 * Achats « à affecter » (27/11/2026) : payés sur un compte dont le libellé commence par « Projet », mais qu'aucun projet
 * ne connaît (compte créé à la main par le comptable). Le logisticien les rattache au bon projet.
 */
definePageMeta({ module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN'] })
useHead({ title: 'Achats de projet à affecter' })

interface Article { id: number, designation: string, compteNumero: string | null, entrees: number, solde: number, payeUsd: number }
interface Projet { id: number, code: string, nom: string, statut: string }

const api = useApi()
const auth = useAuthStore()
const peutEcrire = computed(() => auth.hasRole('LOGISTIQUE'))
const articles = ref<Article[]>([])
const projets = ref<Projet[]>([])
const choix = reactive<Record<number, number | null>>({})
const erreur = ref('')
const succes = ref('')

async function charger() {
  try {
    const [a, p] = await Promise.all([api<Article[]>('/logistique/projets/a-affecter'), api<Projet[]>('/logistique/projets')])
    articles.value = a
    projets.value = p.filter(x => x.statut !== 'CLOTURE')
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Liste indisponible.')
  }
}
onMounted(charger)

async function affecter(a: Article) {
  try {
    await api(`/logistique/projets/articles/${a.id}/affectation`, { method: 'POST', body: { projetId: choix[a.id] } })
    succes.value = `« ${a.designation} » rattaché au projet.`
    await charger()
  } catch (e) {
    erreur.value = messageErreurApi(e, "Le rattachement a échoué.")
  }
}
const fmt = (v: number) => new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(v || 0))
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Achats de projet à affecter</h1>
        <p class="page-sub">Achats payés sur un compte « Projet … » qu'aucun projet ne connaît : rattachez-les au bon projet</p>
      </div>
      <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/logistique/projets">Projets</v-btn>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-card class="classroom-card">
      <v-table density="comfortable">
        <thead><tr><th>Compte</th><th>Libellé</th><th class="text-end">Quantité</th><th class="text-end">Payé (USD)</th><th v-if="peutEcrire">Projet</th></tr></thead>
        <tbody>
          <tr v-if="!articles.length"><td colspan="5" class="text-center text-medium-emphasis py-6">Rien à affecter : tous les achats « Projet » sont rattachés.</td></tr>
          <tr v-for="a in articles" :key="a.id">
            <td class="text-no-wrap">{{ a.compteNumero }}</td>
            <td>{{ a.designation }}</td>
            <td class="text-end">{{ a.solde }}</td>
            <td class="text-end">{{ fmt(a.payeUsd) }}</td>
            <td v-if="peutEcrire" style="min-width: 300px">
              <div class="d-flex ga-2 align-center">
                <v-select v-model="choix[a.id]" :items="projets" item-value="id" :item-title="(p: Projet) => `${p.code} — ${p.nom}`" density="compact" variant="outlined" hide-details placeholder="Choisir le projet" />
                <v-btn color="primary" size="small" :disabled="!choix[a.id]" @click="affecter(a)">Rattacher</v-btn>
              </div>
            </td>
          </tr>
        </tbody>
      </v-table>
    </v-card>
  </div>
</template>
