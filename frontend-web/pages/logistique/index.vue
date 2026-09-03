<script setup lang="ts">
// Tableau de bord dedie a la logistique : remplace le tableau de bord
// financier (tresorerie/notes de frais) qui n'avait rien de pertinent pour
// ce role. Comme /restaurant/provisions/tableau-bord, pas d'endpoint
// d'agregation dedie cote backend : les KPI/graphiques sont calcules ici a
// partir des listes existantes (/logistique/stock, /mouvements, /articles,
// /entrepots), a l'image du tableau de bord principal (pages/dashboard).
definePageMeta({ module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'GEST_PATRIMOINE', 'ADMIN'] })

import { Bar, Doughnut } from 'vue-chartjs'

interface StockNiveau {
  articleId: number
  articleCode: string
  articleLibelle: string
  uniteMesure?: string
  entrepotId: number
  entrepotCode: string
  quantite: number
  valeurTotale: number
  stockMin: number
  sousSeuil: boolean
}
interface Mouvement {
  id: number
  reference: string
  type: 'ENTREE' | 'SORTIE' | 'TRANSFERT'
  dateMouvement: string
  libelle?: string
  statut: 'BROUILLON' | 'VALIDE' | 'ANNULE'
}
interface Article { id: number; code: string; libelle: string; actif: boolean }
interface Entrepot { id: number; code: string; nom: string; actif: boolean }

const auth = useAuthStore()
const api = useApi()
const loading = ref(false)
const erreur = ref('')

const stock = ref<StockNiveau[]>([])
const mouvements = ref<Mouvement[]>([])
const articles = ref<Article[]>([])
const entrepots = ref<Entrepot[]>([])

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [s, m, a, e] = await Promise.all([
      api<StockNiveau[]>('/logistique/stock'),
      api<Mouvement[]>('/logistique/mouvements'),
      api<Article[]>('/logistique/articles'),
      api<Entrepot[]>('/logistique/entrepots'),
    ])
    stock.value = s
    mouvements.value = m
    articles.value = a
    entrepots.value = e
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger le tableau de bord.'
  } finally {
    loading.value = false
  }
}
onMounted(charger)

const fmtNb = (n?: number | null) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(n || 0)
const fmtUSD = (v?: number | null) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(v || 0)

// ── KPIs ─────────────────────────────────────────────────────────────────
const valeurStock = computed(() => stock.value.reduce((s, l) => s + (l.valeurTotale || 0), 0))
const articlesActifs = computed(() => articles.value.filter(a => a.actif).length)
const entrepotsActifs = computed(() => entrepots.value.filter(e => e.actif).length)
const sousSeuil = computed(() => stock.value.filter(l => l.sousSeuil))
const brouillons = computed(() => mouvements.value.filter(m => m.statut === 'BROUILLON'))

const stats = computed(() => [
  { label: 'Valeur du stock', value: fmtUSD(valeurStock.value), icon: 'mdi-warehouse', color: 'green' },
  { label: 'Articles actifs', value: articlesActifs.value, icon: 'mdi-package-variant-closed', color: 'blue' },
  { label: 'Entrepôts actifs', value: entrepotsActifs.value, icon: 'mdi-office-building-outline', color: 'indigo' },
  { label: 'Sous le seuil', value: sousSeuil.value.length, icon: 'mdi-alert-outline', color: sousSeuil.value.length ? 'orange' : 'teal' },
  { label: 'Mouvements à valider', value: brouillons.value.length, icon: 'mdi-clock-outline', color: 'purple' },
])

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 12) return 'Bonjour'
  if (h < 18) return 'Bon après-midi'
  return 'Bonsoir'
})

// ── Graphiques ───────────────────────────────────────────────────────────
const cb: any = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: { labels: { font: { family: 'Inter, sans-serif', size: 11 }, color: '#6b7280', boxWidth: 12, padding: 14 } },
    tooltip: { backgroundColor: '#1f2937', padding: 10, cornerRadius: 8, titleFont: { size: 12 }, bodyFont: { size: 11 } },
  },
}
const COULEURS = ['#16a34a', '#2563eb', '#f59e0b', '#dc2626', '#7c3aed', '#0891b2', '#db2777', '#65a30d']

/** Valeur du stock regroupee par entrepot. */
const valeurParEntrepot = computed(() => {
  const parCode = new Map<string, number>()
  for (const l of stock.value) parCode.set(l.entrepotCode, (parCode.get(l.entrepotCode) || 0) + (l.valeurTotale || 0))
  return [...parCode.entries()].filter(([, v]) => v > 0)
})
const entrepotChartData = computed(() => ({
  labels: valeurParEntrepot.value.map(([code]) => code),
  datasets: [{
    data: valeurParEntrepot.value.map(([, v]) => v),
    backgroundColor: valeurParEntrepot.value.map((_, i) => COULEURS[i % COULEURS.length] + 'cc'),
    borderColor: valeurParEntrepot.value.map((_, i) => COULEURS[i % COULEURS.length]),
    borderWidth: 2, hoverOffset: 8,
  }],
}))
const entrepotChartOpts: any = { ...cb, plugins: { ...cb.plugins, legend: { ...cb.plugins.legend, position: 'bottom' } }, cutout: '55%' }

/** Top 8 articles par valeur en stock, tous entrepots confondus. */
const topArticles = computed(() => {
  const parArticle = new Map<string, number>()
  for (const l of stock.value) parArticle.set(l.articleLibelle, (parArticle.get(l.articleLibelle) || 0) + (l.valeurTotale || 0))
  return [...parArticle.entries()].filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]).slice(0, 8)
})
const topArticlesData = computed(() => ({
  labels: topArticles.value.map(([libelle]) => libelle),
  datasets: [{ label: 'Valeur en stock', data: topArticles.value.map(([, v]) => v), backgroundColor: 'rgba(37,99,235,0.7)', borderColor: '#2563eb', borderRadius: 6 }],
}))
const topArticlesOpts: any = { ...cb, indexAxis: 'y' as const, scales: { x: { beginAtZero: true } } }

/** Mouvements par type. */
const TYPES = ['ENTREE', 'SORTIE', 'TRANSFERT']
const typeChartData = computed(() => ({
  labels: TYPES,
  datasets: [{ label: 'Mouvements', data: TYPES.map(t => mouvements.value.filter(m => m.type === t).length), backgroundColor: ['#16a34acc', '#dc2626cc', '#2563ebcc'], borderColor: ['#16a34a', '#dc2626', '#2563eb'], borderRadius: 6 }],
}))
const typeChartOpts: any = { ...cb, plugins: { ...cb.plugins, legend: { display: false } }, scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } }

/** Mouvements par statut. */
const STATUTS = ['BROUILLON', 'VALIDE', 'ANNULE']
const STATUT_CLR: Record<string, string> = { BROUILLON: '#f59e0b', VALIDE: '#16a34a', ANNULE: '#9ca3af' }
const statutChartData = computed(() => ({
  labels: STATUTS,
  datasets: [{ data: STATUTS.map(s => mouvements.value.filter(m => m.statut === s).length), backgroundColor: STATUTS.map(s => STATUT_CLR[s] + 'cc'), borderColor: STATUTS.map(s => STATUT_CLR[s]), borderWidth: 2, hoverOffset: 8 }],
}))
const statutChartOpts: any = { ...cb, plugins: { ...cb.plugins, legend: { ...cb.plugins.legend, position: 'bottom' } }, cutout: '55%' }

// ── Listes ───────────────────────────────────────────────────────────────
const derniersMouvements = computed(() =>
  [...mouvements.value].sort((a, b) => b.dateMouvement.localeCompare(a.dateMouvement)).slice(0, 8))

const fmtDate = (d: string) => new Date(d).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' })
const typeLabel: Record<string, string> = { ENTREE: 'Entrée', SORTIE: 'Sortie', TRANSFERT: 'Transfert' }
const statutLabel: Record<string, string> = { BROUILLON: 'Brouillon', VALIDE: 'Validé', ANNULE: 'Annulé' }
</script>

<template>
  <div class="ldb">
    <header class="ldb__header">
      <div>
        <p class="ldb__eyebrow">{{ greeting }} 👋</p>
        <h1 class="ldb__title">{{ auth.user?.prenom || auth.user?.email }}</h1>
        <p class="ldb__sub">Voici l'état de votre logistique en temps réel.</p>
      </div>
      <v-btn v-if="auth.hasRole('LOGISTIQUE')" color="primary" prepend-icon="mdi-plus" rounded="lg" elevation="0" to="/logistique/mouvements/nouvelle" class="ldb__cta">
        Nouveau mouvement
      </v-btn>
    </header>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-6" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-skeleton-loader v-if="loading" type="card, card, card" />

    <template v-else>
      <div class="ldb__stats">
        <CardsStatCard v-for="s in stats" :key="s.label" :label="s.label" :value="s.value" :icon="s.icon" :color="s.color" />
      </div>

      <div class="ldb__section-header">
        <div>
          <h2 class="ldb__section-title">Rapports logistiques</h2>
          <p class="ldb__section-sub">Répartition du stock et des mouvements · données en temps réel</p>
        </div>
      </div>

      <div class="ldb-grid2 mb-4">
        <div class="ldb-card">
          <p class="ldb-card__title"><v-icon icon="mdi-chart-donut" size="15" class="mr-1" />Valeur du stock par entrepôt</p>
          <div class="ldb-card__body">
            <Doughnut v-if="entrepotChartData.labels.length" :data="entrepotChartData" :options="entrepotChartOpts" />
            <p v-else class="ldb-card__empty">Aucun stock valorisé.</p>
          </div>
        </div>
        <div class="ldb-card">
          <p class="ldb-card__title"><v-icon icon="mdi-chart-donut" size="15" class="mr-1" />Mouvements par statut</p>
          <div class="ldb-card__body">
            <Doughnut v-if="mouvements.length" :data="statutChartData" :options="statutChartOpts" />
            <p v-else class="ldb-card__empty">Aucun mouvement.</p>
          </div>
        </div>
      </div>

      <div class="ldb-grid2 mb-5">
        <div class="ldb-card">
          <p class="ldb-card__title"><v-icon icon="mdi-chart-bar" size="15" class="mr-1" />Top articles par valeur en stock</p>
          <div class="ldb-card__body">
            <Bar v-if="topArticlesData.labels.length" :data="topArticlesData" :options="topArticlesOpts" />
            <p v-else class="ldb-card__empty">Aucun stock valorisé.</p>
          </div>
        </div>
        <div class="ldb-card">
          <p class="ldb-card__title"><v-icon icon="mdi-chart-bar" size="15" class="mr-1" />Mouvements par type</p>
          <div class="ldb-card__body">
            <Bar v-if="mouvements.length" :data="typeChartData" :options="typeChartOpts" />
            <p v-else class="ldb-card__empty">Aucun mouvement.</p>
          </div>
        </div>
      </div>

      <!-- ── Alerte reapprovisionnement ──────────────────────────── -->
      <div class="ldb__section-header">
        <div>
          <h2 class="ldb__section-title">Articles sous le seuil</h2>
          <p class="ldb__section-sub">{{ sousSeuil.length }} article{{ sousSeuil.length !== 1 ? 's' : '' }} à réapprovisionner</p>
        </div>
        <v-btn variant="outlined" color="primary" append-icon="mdi-arrow-right" rounded="lg" size="small" to="/logistique/stock">
          Voir le stock
        </v-btn>
      </div>

      <v-card v-if="sousSeuil.length" class="classroom-card mb-5">
        <v-data-table
          :headers="[
            { title: 'Article', key: 'articleLibelle' },
            { title: 'Entrepôt', key: 'entrepotCode' },
            { title: 'Quantité', key: 'quantite', align: 'end' },
            { title: 'Seuil mini', key: 'stockMin', align: 'end' },
          ]"
          :items="sousSeuil"
          items-per-page="5"
        >
          <template #item.quantite="{ item }">
            <span class="font-weight-bold text-error">{{ fmtNb(item.quantite) }} {{ item.uniteMesure || '' }}</span>
          </template>
          <template #item.stockMin="{ item }">{{ fmtNb(item.stockMin) }} {{ item.uniteMesure || '' }}</template>
        </v-data-table>
      </v-card>
      <div v-else class="ldb__empty mb-5">
        <v-icon icon="mdi-check-circle-outline" size="40" color="success" />
        <p class="ldb__empty-title">Tous les stocks sont au-dessus du seuil.</p>
      </div>

      <!-- ── Derniers mouvements ─────────────────────────────────── -->
      <div class="ldb__section-header">
        <div>
          <h2 class="ldb__section-title">Derniers mouvements</h2>
          <p class="ldb__section-sub">{{ derniersMouvements.length }} mouvement{{ derniersMouvements.length !== 1 ? 's' : '' }} le{{ derniersMouvements.length !== 1 ? 's' : '' }} plus récent{{ derniersMouvements.length !== 1 ? 's' : '' }}</p>
        </div>
        <v-btn variant="outlined" color="primary" append-icon="mdi-arrow-right" rounded="lg" size="small" to="/logistique/mouvements">
          Tout voir
        </v-btn>
      </div>

      <v-card v-if="derniersMouvements.length" class="classroom-card">
        <v-data-table
          :headers="[
            { title: 'Référence', key: 'reference' },
            { title: 'Type', key: 'type' },
            { title: 'Date', key: 'dateMouvement' },
            { title: 'Libellé', key: 'libelle' },
            { title: 'Statut', key: 'statut' },
          ]"
          :items="derniersMouvements"
          items-per-page="8"
          hide-default-footer
        >
          <template #item.type="{ item }">{{ typeLabel[item.type] || item.type }}</template>
          <template #item.dateMouvement="{ item }">{{ fmtDate(item.dateMouvement) }}</template>
          <template #item.libelle="{ item }">{{ item.libelle || '—' }}</template>
          <template #item.statut="{ item }">
            <v-chip size="small" variant="tonal" :color="item.statut === 'VALIDE' ? 'success' : item.statut === 'ANNULE' ? 'grey' : 'warning'">
              {{ statutLabel[item.statut] || item.statut }}
            </v-chip>
          </template>
        </v-data-table>
      </v-card>
      <div v-else class="ldb__empty">
        <v-icon icon="mdi-swap-horizontal-bold" size="40" color="#d1d5db" />
        <p class="ldb__empty-title">Aucun mouvement enregistré.</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ldb { max-width: 1200px; margin: 0 auto; padding: 8px 0 48px; }

.ldb__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 36px; flex-wrap: wrap; }
.ldb__eyebrow { font-size: 0.8125rem; color: #9ca3af; font-weight: 500; margin: 0 0 4px; }
.ldb__title { font-size: clamp(1.5rem, 3vw, 2rem); font-weight: 700; color: #111827; letter-spacing: -0.5px; margin: 0 0 6px; }
.ldb__sub { font-size: 0.875rem; color: #6b7280; margin: 0; }
.ldb__cta { margin-top: 4px; flex-shrink: 0; }

.ldb__stats { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 16px; margin-bottom: 40px; }

.ldb__section-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 20px; flex-wrap: wrap; }
.ldb__section-title { font-size: 1.0625rem; font-weight: 700; color: #111827; margin: 0 0 2px; }
.ldb__section-sub { font-size: 0.8rem; color: #9ca3af; margin: 0; }

.ldb-grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
@media (max-width: 680px) { .ldb-grid2 { grid-template-columns: 1fr; } }
.ldb-card { background: #fff; border: 1px solid #f0f0f0; border-radius: 18px; padding: 20px 22px; }
.ldb-card__title { font-size: 0.875rem; font-weight: 700; color: #111827; margin: 0 0 16px; display: flex; align-items: center; }
.ldb-card__body { height: 260px; position: relative; }
.ldb-card__empty { display: flex; align-items: center; justify-content: center; height: 100%; color: #9ca3af; font-size: 0.82rem; }

.ldb__empty { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; padding: 48px 24px; background: #fff; border: 1px dashed #e5e7eb; border-radius: 16px; text-align: center; }
.ldb__empty-title { font-size: 0.95rem; font-weight: 600; color: #374151; margin: 0; }
</style>
