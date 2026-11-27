<script setup lang="ts">
/**
 * Stock des projets (27/11/2026, V169) : les achats payés sur un compte « Projet — … » y entrent, liés à aucun projet. Le
 * logisticien affecte chaque bien (tout ou partie de la quantité) au projet qui le reçoit ; c'est cette affectation qui
 * exécute la ligne du budget du projet. Tant qu'il attend ici, le bien est « engagé » au budget du projet.
 */
import { useDisplay } from 'vuetify'

definePageMeta({ module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN'] })
useHead({ title: 'Stock des projets' })

interface Article { id: number, designation: string, unite: string | null, compteNumero: string | null, compteLibelle: string | null,
  entrees: number, solde: number, valeurUsd: number, payeUsd: number }
interface Projet { id: number, code: string, nom: string, statut: string }

const api = useApi()
const auth = useAuthStore()
const { xs } = useDisplay()
const peutEcrire = computed(() => auth.hasRole('LOGISTIQUE'))
const articles = ref<Article[]>([])
const projets = ref<Projet[]>([])
const choix = reactive<Record<number, { projetId: number | null, quantite: number | null }>>({})
const erreur = ref('')
const succes = ref('')
const enCours = ref<number | null>(null)

async function charger() {
  try {
    const [a, p] = await Promise.all([api<Article[]>('/logistique/projets/a-affecter'), api<Projet[]>('/logistique/projets')])
    articles.value = a.filter(x => Number(x.solde) > 0)
    projets.value = p.filter(x => x.statut !== 'CLOTURE')
    for (const x of articles.value) choix[x.id] = { projetId: choix[x.id]?.projetId ?? null, quantite: Number(x.solde) }
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Stock des projets indisponible.')
  }
}
onMounted(charger)

const quantiteValide = (a: Article) => Number(choix[a.id]?.quantite) > 0 && Number(choix[a.id]?.quantite) <= Number(a.solde)
async function affecter(a: Article) {
  const c = choix[a.id]
  if (!c?.projetId || !quantiteValide(a)) return
  enCours.value = a.id
  try {
    await api(`/logistique/projets/articles/${a.id}/affectation`, { method: 'POST', body: { projetId: c.projetId, quantite: Number(c.quantite) } })
    const p = projets.value.find(x => x.id === c.projetId)
    succes.value = `${c.quantite} ${a.unite || ''} « ${a.designation} » affecté(s) au projet ${p ? `${p.code} — ${p.nom}` : ''}.`
    await charger()
  } catch (e) {
    erreur.value = messageErreurApi(e, "L'affectation a échoué.")
  } finally {
    enCours.value = null
  }
}
const fmt = (v: number) => new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(v || 0))
const qte = (v: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 3 }).format(Number(v || 0))
const totalValeur = computed(() => articles.value.reduce((t, a) => t + Number(a.valeurUsd || 0), 0))
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Stock des projets</h1>
        <p class="page-sub">Biens payés sur un compte « Projet — … », pas encore affectés : choisissez le projet qui les reçoit</p>
      </div>
      <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/logistique/projets">Projets</v-btn>
    </div>
    <v-alert type="info" variant="tonal" density="compact" class="mb-4" icon="mdi-information-outline">
      Un compte « Projet — … » n'appartient à aucun projet : au paiement d'une note, le bien arrive ici automatiquement, même si la
      note précise le projet. Tant qu'il est disponible ici, il est <strong>engagé</strong> au budget du projet ; une fois
      <strong>affecté</strong>, il est <strong>exécuté</strong> sur le budget du projet qui le reçoit. Un bien ne passe pas
      directement d'un projet à un autre : le projet qui n'en a plus besoin (chantier fermé, surplus) le <strong>retourne</strong>
      ici, son exécuté diminue, et vous pouvez l'affecter à un autre projet.
    </v-alert>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>

    <v-card class="classroom-card">
      <div class="pa-4 pb-2 d-flex align-center flex-wrap ga-2">
        <span class="font-weight-bold">À affecter ({{ articles.length }})</span>
        <v-spacer />
        <span class="text-body-2">Valeur en attente : <strong>{{ fmt(totalValeur) }} USD</strong></span>
      </div>
      <div v-if="!articles.length" class="text-center text-medium-emphasis py-8">Rien à affecter : tous les biens payés sont dans un projet.</div>

      <div v-else-if="xs" class="sp-cartes">
        <div v-for="a in articles" :key="a.id" class="sp-carte">
          <div class="font-weight-bold">{{ a.designation }}</div>
          <div class="text-caption text-medium-emphasis">{{ a.compteNumero }}</div>
          <div class="sp-chiffres"><span>En stock <b>{{ qte(a.solde) }} {{ a.unite || '' }}</b></span><span>Valeur <b>{{ fmt(a.valeurUsd) }} USD</b></span></div>
          <template v-if="peutEcrire && choix[a.id]">
            <v-select v-model="choix[a.id].projetId" :items="projets" item-value="id" :item-title="(p: Projet) => `${p.code} — ${p.nom}`"
              density="compact" variant="outlined" hide-details placeholder="Projet qui reçoit" class="mt-2" />
            <div class="d-flex ga-2 mt-2">
              <v-text-field v-model.number="choix[a.id].quantite" type="number" density="compact" variant="outlined" hide-details label="Quantité"
                :suffix="a.unite || undefined" :error="!quantiteValide(a)" />
              <v-btn color="primary" height="40" :disabled="!choix[a.id].projetId || !quantiteValide(a)" :loading="enCours === a.id" @click="affecter(a)">Affecter</v-btn>
            </div>
          </template>
        </div>
      </div>

      <v-table v-else density="comfortable">
        <thead><tr><th>Bien</th><th class="text-end">En stock</th><th class="text-end">Valeur (USD)</th><th v-if="peutEcrire">Affecter au projet</th></tr></thead>
        <tbody>
          <tr v-for="a in articles" :key="a.id">
            <td><div class="font-weight-medium">{{ a.designation }}</div><div class="text-caption text-medium-emphasis">{{ a.compteNumero }}</div></td>
            <td class="text-end text-no-wrap">{{ qte(a.solde) }} {{ a.unite || '' }}</td>
            <td class="text-end text-no-wrap">{{ fmt(a.valeurUsd) }}</td>
            <td v-if="peutEcrire && choix[a.id]" style="min-width: 460px">
              <div class="d-flex ga-2 align-center py-2">
                <v-select v-model="choix[a.id].projetId" :items="projets" item-value="id" :item-title="(p: Projet) => `${p.code} — ${p.nom}`"
                  density="compact" variant="outlined" hide-details placeholder="Projet qui reçoit" />
                <v-text-field v-model.number="choix[a.id].quantite" type="number" density="compact" variant="outlined" hide-details label="Quantité"
                  style="max-width: 120px" :error="!quantiteValide(a)" />
                <v-btn color="primary" size="small" :disabled="!choix[a.id].projetId || !quantiteValide(a)" :loading="enCours === a.id" @click="affecter(a)">Affecter</v-btn>
              </div>
            </td>
          </tr>
        </tbody>
      </v-table>
    </v-card>
  </div>
</template>

<style scoped>
.sp-cartes { padding: 0 12px 12px; display: grid; gap: 8px; }
.sp-carte { border: 1px solid var(--hairline); border-radius: 12px; padding: 12px; border-left: 4px solid #d97706; }
.sp-chiffres { display: flex; flex-wrap: wrap; gap: 4px 14px; font-size: 0.78rem; color: var(--ink-soft); margin-top: 4px; }
.sp-chiffres b { color: inherit; font-variant-numeric: tabular-nums; }
</style>
