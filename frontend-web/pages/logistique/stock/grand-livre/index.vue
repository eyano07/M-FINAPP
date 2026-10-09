<script setup lang="ts">
// Meme restriction que les mouvements : lecture reservee aux roles
// logistiques et financiers (StockService.grandLivreStock).
definePageMeta({ module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'ADMIN'] })

interface LigneStockGL {
  id: number
  dateEcriture: string
  articleCode: string
  articleLibelle?: string
  entrepotCode: string
  mouvementReference?: string
  qteEntree: number
  qteSortie: number
  qteApres: number
  valeurUnitaire: number
  valeurApres: number
}

const api = useApi()
const parametresStore = useParametresStore()
const loading = ref(false)
const exportEnCours = ref(false)
const erreur = ref('')
const lignes = ref<LigneStockGL[]>([])

const debutAnnee = new Date().getFullYear() + '-01-01'
const aujourdhui = new Date().toISOString().slice(0, 10)

const filtres = reactive({
  article: null as number | null,
  entrepot: null as number | null,
  du: debutAnnee,
  au: aujourdhui,
})

function parametresFiltres() {
  const params = new URLSearchParams({ du: filtres.du, au: filtres.au })
  if (filtres.article) params.set('article', String(filtres.article))
  if (filtres.entrepot) params.set('entrepot', String(filtres.entrepot))
  return params
}

// Filtres du tableau actuellement affiché, en clair : c'est ce que rappelle l'en-tête du document
// imprimé, même si les champs ont été modifiés depuis sans cliquer sur « Consulter ».
const appliques = reactive({
  article: 'Tous les articles',
  entrepot: 'Tous les entrepôts',
  du: debutAnnee,
  au: aujourdhui,
})

// Les sélecteurs ne remontent que des identifiants : les noms se retrouvent dans les listes de
// référence, lues une seule fois et seulement si un filtre est posé.
interface ArticleRef { id: number, code: string, libelle: string }
interface EntrepotRef { id: number, code: string, nom: string }
let articlesConnus: ArticleRef[] | null = null
let entrepotsConnus: EntrepotRef[] | null = null

async function libelleArticle(id: number | null): Promise<string> {
  if (!id) return 'Tous les articles'
  try {
    articlesConnus ??= await api<ArticleRef[]>('/logistique/articles')
    const a = articlesConnus.find(x => x.id === id)
    if (a) return `${a.code} — ${a.libelle}`
  } catch {
    // En-tête imprimé seulement : à défaut du nom, l'identifiant.
  }
  return `Article n° ${id}`
}

async function libelleEntrepot(id: number | null): Promise<string> {
  if (!id) return 'Tous les entrepôts'
  try {
    entrepotsConnus ??= await api<EntrepotRef[]>('/logistique/entrepots')
    const e = entrepotsConnus.find(x => x.id === id)
    if (e) return `${e.code} — ${e.nom}`
  } catch {
    // Idem.
  }
  return `Entrepôt n° ${id}`
}

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    lignes.value = await api<LigneStockGL[]>(`/logistique/stock/grand-livre?${parametresFiltres()}`)
    appliques.du = filtres.du
    appliques.au = filtres.au
    appliques.article = await libelleArticle(filtres.article)
    appliques.entrepot = await libelleEntrepot(filtres.entrepot)
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger le grand livre de stock.'
  } finally {
    loading.value = false
  }
}

// ── Impression PDF et export Excel : toujours selon les filtres saisis ─────────────────────────
// Les deux boutons relancent d'abord la consultation avec les filtres affichés : le tableau, le
// document imprimé et le fichier Excel correspondent ainsi toujours à ce que les champs indiquent.

// Le tableau paginé ne rend que la page courante dans le DOM : sans bascule vers « toutes les
// lignes » au moment d'imprimer, seule la première page sortirait sur le PDF.
const lignesParPage = ref(25)
const dateImpression = ref('')

function avantImpression() {
  lignesParPage.value = -1
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
}
function apresImpression() {
  lignesParPage.value = 25
}

async function imprimer() {
  await charger()
  if (erreur.value) return
  await nextTick()
  window.print()
}

async function exporterExcel() {
  exportEnCours.value = true
  try {
    await charger()
    if (erreur.value) return
    await telechargerFichier(api, `/logistique/stock/grand-livre/export?${parametresFiltres()}`,
      `Grand_livre_stock_${filtres.du || 'origine'}_${filtres.au || aujourdhui}.xlsx`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de générer le fichier Excel du grand livre de stock.')
  } finally {
    exportEnCours.value = false
  }
}

onMounted(() => {
  charger()
  parametresStore.charger()
  window.addEventListener('beforeprint', avantImpression)
  window.addEventListener('afterprint', apresImpression)
  // Neuf colonnes : le document imprimé est en paysage (classe portée par <html>, voir classroom.scss).
  document.documentElement.classList.add('print-paysage')
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeprint', avantImpression)
  window.removeEventListener('afterprint', apresImpression)
  document.documentElement.classList.remove('print-paysage')
})

function fmt(v: number) {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(v)
}
function fmtDate(d: string) {
  return new Date(d).toLocaleDateString('fr-FR')
}
function libellePeriode(du: string, au: string) {
  if (du && au) return `du ${fmtDate(du)} au ${fmtDate(au)}`
  if (au) return `jusqu'au ${fmtDate(au)}`
  if (du) return `depuis le ${fmtDate(du)}`
  return 'toute la période'
}
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Grand livre de stock</h1>
        <p class="page-sub">Mouvements valorisés par article et entrepôt</p>
      </div>
      <div class="d-flex ga-2 flex-wrap">
        <v-btn
          color="success"
          variant="flat"
          rounded="lg"
          prepend-icon="mdi-file-excel-outline"
          :loading="exportEnCours"
          :disabled="loading"
          @click="exporterExcel"
        >
          Exporter en Excel
        </v-btn>
        <v-btn
          color="error"
          variant="tonal"
          rounded="lg"
          prepend-icon="mdi-file-pdf-box"
          :disabled="loading || exportEnCours"
          @click="imprimer"
        >
          Imprimer en PDF
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-6 mb-4 no-print">
      <v-row align="end">
        <v-col cols="12" md="3">
          <LogistiqueSelecteurArticle v-model="filtres.article" label="Article (tous)" />
        </v-col>
        <v-col cols="12" md="3">
          <LogistiqueSelecteurEntrepot v-model="filtres.entrepot" label="Entrepôt (tous)" />
        </v-col>
        <v-col cols="6" md="2">
          <v-text-field v-model="filtres.du" label="Du" type="date" variant="outlined" density="comfortable" hide-details />
        </v-col>
        <v-col cols="6" md="2">
          <v-text-field v-model="filtres.au" label="Au" type="date" variant="outlined" density="comfortable" hide-details />
        </v-col>
        <v-col cols="12" md="2">
          <v-btn color="primary" block height="48" :loading="loading" @click="charger">Consulter</v-btn>
        </v-col>
      </v-row>
    </v-card>

    <!-- En-tête d'impression (visible uniquement sur le document imprimé) -->
    <div class="etat-print-header print-entete">
      <div class="etat-print-header__brand print-entete__marque">
        <div class="etat-print-header__logo print-entete__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-finance" size="16" color="white" />
        </div>
        <div>
          <span class="etat-print-header__company print-entete__nom">{{ parametresStore.parametres.nom }}</span>
          <span class="etat-print-header__doc">Grand livre de stock</span>
        </div>
      </div>
      <div class="etat-print-header__meta print-entete__meta">
        <span>Période : <strong>{{ libellePeriode(appliques.du, appliques.au) }}</strong></span>
        <span>Article : <strong>{{ appliques.article }}</strong></span>
        <span>Entrepôt : <strong>{{ appliques.entrepot }}</strong></span>
        <span>Imprimé le : {{ dateImpression }}</span>
      </div>
    </div>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Date', key: 'dateEcriture' },
          { title: 'Article', key: 'articleLibelle' },
          { title: 'Entrepôt', key: 'entrepotCode' },
          { title: 'Mouvement', key: 'mouvementReference' },
          { title: 'Entrée', key: 'qteEntree', align: 'end' },
          { title: 'Sortie', key: 'qteSortie', align: 'end' },
          { title: 'Solde qté', key: 'qteApres', align: 'end' },
          { title: 'CMP', key: 'valeurUnitaire', align: 'end' },
          { title: 'Valeur stock', key: 'valeurApres', align: 'end' },
        ]"
        :items="lignes"
        :loading="loading"
        :items-per-page="lignesParPage"
        class="table-impression-compacte"
      >
        <template #item.dateEcriture="{ item }">{{ fmtDate(item.dateEcriture) }}</template>
        <!-- Le nom de l'article ; son code reste lisible au survol. -->
        <template #item.articleLibelle="{ item }">
          <span :title="item.articleCode">{{ item.articleLibelle || item.articleCode }}</span>
        </template>
        <template #item.qteEntree="{ item }">{{ item.qteEntree ? fmt(item.qteEntree) : '—' }}</template>
        <template #item.qteSortie="{ item }">{{ item.qteSortie ? fmt(item.qteSortie) : '—' }}</template>
        <template #item.qteApres="{ item }">{{ fmt(item.qteApres) }}</template>
        <template #item.valeurUnitaire="{ item }">{{ fmt(item.valeurUnitaire) }}</template>
        <template #item.valeurApres="{ item }">{{ fmt(item.valeurApres) }}</template>
      </v-data-table>
    </v-card>
  </div>
</template>
