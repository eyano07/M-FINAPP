<script setup lang="ts">
definePageMeta({ module: 'PATRIMOINE' })

/**
 * Les consommables reutilisent le moteur de stock existant : un article de
 * type CONSOMMABLE vit dans le meme catalogue, dans les memes entrepots, et sa
 * sortie debite deja le compte de charge. Cette page en est la vue metier —
 * elle ne duplique aucune mecanique de stock.
 */
interface Article {
  id: number
  code: string
  libelle: string
  type: string
  uniteMesure: string | null
  stockMin: number
  actif: boolean
  entrepotNom: string | null
}
interface StockNiveau {
  articleId: number
  articleCode: string
  articleLibelle: string
  entrepotNom: string
  quantite: number
  coutMoyen: number
  valeurTotale: number
  stockMin: number
  sousSeuil: boolean
}

const api = useApi()
const parametresStore = useParametresStore()
const tauxChange = ref(0)
const loading = ref(false)
const erreur = ref('')
const articles = ref<Article[]>([])
const stock = ref<StockNiveau[]>([])

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [arts, stk, taux] = await Promise.all([
      api<Article[]>('/logistique/articles'),
      api<StockNiveau[]>('/logistique/stock').catch(() => []),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
    ])
    articles.value = arts.filter(a => a.type === 'CONSOMMABLE')
    const ids = new Set(articles.value.map(a => a.id))
    stock.value = stk.filter(s => ids.has(s.articleId))
    tauxChange.value = taux.taux || 0
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les consommables.')
  } finally {
    loading.value = false
  }
}
onMounted(() => {
  charger()
  window.addEventListener('beforeprint', () => { lignesParPage.value = -1 })
  window.addEventListener('afterprint', () => { lignesParPage.value = 25 })
})

// ── Filtres (écran + impression, qui n'imprime que le résultat filtré) ────
const filtreRecherche = ref('')
const filtreEntrepot = ref('TOUS')
const filtreSousSeuil = ref(false)

const entrepotsPresents = computed(() => [...new Set(stock.value.map(s => s.entrepotNom))].sort())

const stockFiltre = computed(() => {
  const q = filtreRecherche.value.trim().toLowerCase()
  return stock.value.filter((s) => {
    if (q && !s.articleCode.toLowerCase().includes(q) && !s.articleLibelle.toLowerCase().includes(q)) return false
    if (filtreEntrepot.value !== 'TOUS' && s.entrepotNom !== filtreEntrepot.value) return false
    if (filtreSousSeuil.value && !s.sousSeuil) return false
    return true
  })
})

const resumeFiltres = computed(() => {
  const parts: string[] = []
  if (filtreEntrepot.value !== 'TOUS') parts.push(filtreEntrepot.value)
  if (filtreSousSeuil.value) parts.push('Sous le seuil uniquement')
  if (filtreRecherche.value.trim()) parts.push(`« ${filtreRecherche.value.trim()} »`)
  return parts.length ? parts.join(' · ') : 'Tous les consommables'
})

// Le tableau pagine ne rend que la page courante dans le DOM : sans bascule
// vers "toutes les lignes" au moment d'imprimer, seule la 1re page sortirait
// sur le papier — meme mecanique que balance-verification/index.vue.
const lignesParPage = ref(25)
const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  window.print()
}

const toUSD = (fc: number) => (tauxChange.value > 0 ? (fc || 0) / tauxChange.value : 0)
const fmt = (fc: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 })
    .format(toUSD(fc))
const fmtQte = (v: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 3 }).format(v || 0)

const valeurTotale = computed(() => stock.value.reduce((s, l) => s + Number(l.valeurTotale), 0))
const sousSeuil = computed(() => stock.value.filter(l => l.sousSeuil))
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Consommables</h1>
        <p class="page-sub">Fournitures consommées en interne — stock, valeur et seuils de réapprovisionnement</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn v-if="stockFiltre.length" color="error" variant="tonal" rounded="lg"
          prepend-icon="mdi-printer-outline" @click="imprimer">
          Imprimer
        </v-btn>
        <v-btn color="primary" variant="tonal" rounded="lg" prepend-icon="mdi-package-variant-closed"
          to="/logistique/articles">
          Gérer le catalogue
        </v-btn>
      </div>
    </div>

    <!-- En-tête d'impression : masquée à l'écran, visible uniquement sur le papier. -->
    <div class="etat-print-header">
      <div class="etat-print-header__brand">
        <div class="etat-print-header__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-finance" size="16" color="white" />
        </div>
        <div>
          <span class="etat-print-header__company">{{ parametresStore.parametres.nom }}</span>
          <span class="etat-print-header__doc">Consommables</span>
          <span class="etat-print-header__service">{{ resumeFiltres }}</span>
        </div>
      </div>
      <div class="etat-print-header__meta">
        <span>Imprimé le : {{ dateImpression }}</span>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print">{{ erreur }}</v-alert>

    <v-alert v-if="!loading && articles.length === 0" type="info" variant="tonal" class="mb-4 no-print">
      Aucun consommable enregistré. Créez un article de type <strong>Consommable</strong> depuis le catalogue :
      il utilisera les mêmes entrepôts et mouvements que le reste du stock, et sa sortie passera automatiquement
      en charge.
    </v-alert>

    <v-row v-if="articles.length" class="mb-2 no-print">
      <v-col cols="6" md="4">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Références</div>
          <div class="kpi-value">{{ articles.length }}</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="4">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Valeur du stock</div>
          <div class="kpi-value">{{ fmt(valeurTotale) }}</div>
        </v-card>
      </v-col>
      <v-col cols="12" md="4">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Sous le seuil</div>
          <div class="kpi-value" :class="sousSeuil.length ? 'text-error' : 'text-success'">
            {{ sousSeuil.length }}
          </div>
        </v-card>
      </v-col>
    </v-row>

    <v-alert v-if="sousSeuil.length" type="warning" variant="tonal" class="mb-4">
      <strong>{{ sousSeuil.length }} consommable(s) à réapprovisionner :</strong>
      {{ sousSeuil.map(s => s.articleLibelle).join(', ') }}
    </v-alert>

    <v-card v-if="stock.length" class="classroom-card pa-4 mb-4 no-print">
      <v-row align="center" dense>
        <v-col cols="12" md="5">
          <v-text-field v-model="filtreRecherche" label="Rechercher (code, libellé)" variant="outlined"
            density="comfortable" hide-details prepend-inner-icon="mdi-magnify" clearable />
        </v-col>
        <v-col cols="7" md="4">
          <v-select v-model="filtreEntrepot"
            :items="[{ title: 'Tous les entrepôts', value: 'TOUS' }, ...entrepotsPresents.map(e => ({ title: e, value: e }))]"
            label="Entrepôt" variant="outlined" density="comfortable" hide-details />
        </v-col>
        <v-col cols="5" md="3">
          <v-switch v-model="filtreSousSeuil" label="Sous le seuil" color="warning" density="comfortable" hide-details />
        </v-col>
      </v-row>
    </v-card>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Code', key: 'articleCode' },
          { title: 'Libellé', key: 'articleLibelle' },
          { title: 'Entrepôt', key: 'entrepotNom' },
          { title: 'Quantité', key: 'quantite', align: 'end' },
          { title: 'Coût moyen', key: 'coutMoyen', align: 'end' },
          { title: 'Valeur', key: 'valeurTotale', align: 'end' },
          { title: 'Seuil', key: 'stockMin', align: 'end' },
        ]"
        :items="stockFiltre"
        :loading="loading"
        :items-per-page="lignesParPage"
      >
        <template #item.quantite="{ item }">
          <span :class="item.sousSeuil ? 'text-error font-weight-bold' : ''">{{ fmtQte(item.quantite) }}</span>
        </template>
        <template #item.coutMoyen="{ item }">{{ fmt(item.coutMoyen) }}</template>
        <template #item.valeurTotale="{ item }">{{ fmt(item.valeurTotale) }}</template>
        <template #item.stockMin="{ item }">{{ fmtQte(item.stockMin) }}</template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">
            {{ stock.length ? 'Aucun consommable ne correspond aux filtres.' : "Aucun mouvement de stock sur les consommables pour l'instant." }}
          </div>
        </template>
      </v-data-table>
    </v-card>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.kpi-label { font-size: 0.72rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.25rem; font-weight: 700; color: #111827; margin-top: 4px; }
</style>
