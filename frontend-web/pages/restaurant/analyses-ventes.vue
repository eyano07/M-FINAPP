<script setup lang="ts">
// Ecran du module Restaurant : il en suit l'activation et les permissions
// (le DFIN et le DG y ont la lecture), et parmi ses lecteurs, seuls ceux qui
// pilotent l'offre y accedent — voir RestaurantService.LECTURE_ANALYSES.
definePageMeta({ module: 'RESTAURANT', roles: ['RESP_RESTAURANT', 'DFIN', 'DG', 'ADMIN'] })

/**
 * Analyse des ventes de la carte (plats et boissons) : meilleures/moins
 * bonnes ventes, marge par article ("dividende"), par catégorie, et
 * tendance sur une période au choix (semaine, mois, trimestre, semestre,
 * année). Le coût de chaque ligne est le coût historique réel constaté à
 * la vente (voir RestaurantService.analyserVentes), jamais un CMP recalculé
 * après coup.
 */
import { Bar, Line, Doughnut } from 'vue-chartjs'

interface ArticleStat {
  articleId: number
  code: string
  libelle: string
  type: 'PLAT' | 'BOISSON'
  categorie: string
  quantiteVendue: number
  chiffreAffaires: number
  cout: number
  marge: number
  margePourcentage: number
}
interface CategorieStat {
  categorie: string
  type: 'PLAT' | 'BOISSON'
  quantiteVendue: number
  chiffreAffaires: number
  cout: number
  marge: number
}
interface VenteJour { date: string; quantite: number; chiffreAffaires: number }
interface AnalyseVentes {
  du: string
  au: string
  totalQuantite: number
  totalChiffreAffaires: number
  totalCout: number
  totalMarge: number
  parArticle: ArticleStat[]
  parCategorie: CategorieStat[]
  parJour: VenteJour[]
}

type TypePeriode = 'SEMAINE' | 'MOIS' | 'TRIMESTRE' | 'SEMESTRE' | 'ANNEE'
const TYPES_PERIODE: { value: TypePeriode; label: string }[] = [
  { value: 'SEMAINE', label: 'Semaine' },
  { value: 'MOIS', label: 'Mois' },
  { value: 'TRIMESTRE', label: 'Trimestre' },
  { value: 'SEMESTRE', label: 'Semestre' },
  { value: 'ANNEE', label: 'Année' },
]

const api = useApi()
const loading = ref(false)
const erreur = ref('')
const analyse = ref<AnalyseVentes | null>(null)
const tauxChange = ref(0)
const deviseAffichage = ref<'CDF' | 'USD'>('CDF')

const typePeriode = ref<TypePeriode>('MOIS')
const dateReference = ref(new Date().toISOString().slice(0, 10))

const pad = (n: number) => String(n).padStart(2, '0')
const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`

/** Bornes de la période choisie, calculées côté client à partir d'une date de référence. */
function calculerPeriode(type: TypePeriode, refIso: string): { du: string; au: string } {
  const ref = new Date(refIso + 'T00:00:00')
  if (type === 'SEMAINE') {
    const jourIso = (ref.getDay() + 6) % 7 // 0 = lundi
    const lundi = new Date(ref); lundi.setDate(ref.getDate() - jourIso)
    const dimanche = new Date(lundi); dimanche.setDate(lundi.getDate() + 6)
    return { du: iso(lundi), au: iso(dimanche) }
  }
  if (type === 'MOIS') {
    return { du: iso(new Date(ref.getFullYear(), ref.getMonth(), 1)), au: iso(new Date(ref.getFullYear(), ref.getMonth() + 1, 0)) }
  }
  if (type === 'TRIMESTRE') {
    const t = Math.floor(ref.getMonth() / 3)
    return { du: iso(new Date(ref.getFullYear(), t * 3, 1)), au: iso(new Date(ref.getFullYear(), t * 3 + 3, 0)) }
  }
  if (type === 'SEMESTRE') {
    const s = ref.getMonth() < 6 ? 0 : 1
    return { du: iso(new Date(ref.getFullYear(), s * 6, 1)), au: iso(new Date(ref.getFullYear(), s * 6 + 6, 0)) }
  }
  return { du: `${ref.getFullYear()}-01-01`, au: `${ref.getFullYear()}-12-31` }
}
const periode = computed(() => calculerPeriode(typePeriode.value, dateReference.value))
const periodeLabel = computed(() => {
  const { du, au } = periode.value
  const f = (d: string) => new Date(d).toLocaleDateString('fr-FR', { day: '2-digit', month: 'short', year: 'numeric' })
  return `${f(du)} — ${f(au)}`
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [data, taux] = await Promise.all([
      api<AnalyseVentes>('/restaurant/analyses-ventes', { params: { du: periode.value.du, au: periode.value.au } }),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
    ])
    analyse.value = data
    tauxChange.value = taux.taux || 0
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible de charger l'analyse des ventes.")
  } finally {
    loading.value = false
  }
}
onMounted(charger)
watch(periode, charger)

const fmtUSD = (montant: number) =>
  deviseAffichage.value === 'CDF'
    ? (tauxChange.value > 0 ? `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(montant * tauxChange.value)} FC` : '—')
    : new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(montant)
const fmtNb = (n: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(n || 0)
const fmtPct = (n: number) => `${n >= 0 ? '' : ''}${n.toFixed(1)} %`

// ── Classements ───────────────────────────────────────────────────────────
const meilleuresVentes = computed(() =>
  (analyse.value?.parArticle || []).filter(a => a.quantiteVendue > 0).slice(0, 10))
const moinsVendues = computed(() =>
  [...(analyse.value?.parArticle || [])].sort((a, b) => a.quantiteVendue - b.quantiteVendue).slice(0, 10))
const parMarge = computed(() =>
  [...(analyse.value?.parArticle || [])].sort((a, b) => b.marge - a.marge).slice(0, 10))

// ── Graphiques ────────────────────────────────────────────────────────────
const cb: any = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: { labels: { font: { family: 'Inter, sans-serif', size: 11 }, color: '#6b7280', boxWidth: 12, padding: 14 } },
    tooltip: { backgroundColor: '#1f2937', padding: 10, cornerRadius: 8, titleFont: { size: 12 }, bodyFont: { size: 11 } },
  },
}
const fmtDateCourte = (d: string) => new Date(d).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' })

// Chart.js peint sur un <canvas> : contrairement au DOM, son API 2D ne sait
// pas resoudre var(--color-primary) (couleur invalide → noir). On lit donc
// la couleur de marque directement depuis le store — une chaine hex simple,
// exploitable telle quelle par Canvas — plutot que de sonder le CSS calcule :
// une sonde DOM lue dans onMounted peut s'executer AVANT que
// useParametresStore().charger() (voir stores/parametres.ts, appele au
// niveau du layout) n'ait fini d'appliquer la vraie couleur admin sur
// :root, et capturer a tort la teinte par defaut du store. Une lecture
// reactive du store n'a pas ce probleme : le graphique se met a jour de
// lui-meme des que la couleur arrive, quel que soit l'ordre de chargement.
const identite = useParametresStore()
const couleurPrimaireHex = computed(() => identite.parametres.couleurPrimaire || '#16A34A')
function hexToRgba(hex: string, alpha: number): string {
  const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex)
  if (!m) return hex
  const [r, g, b] = [m[1], m[2], m[3]].map(h => parseInt(h, 16))
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

const COULEURS = computed(() => [couleurPrimaireHex.value, '#2563eb', '#f59e0b', '#dc2626', '#7c3aed', '#0891b2', '#db2777', '#65a30d'])

/** Ramène un montant USD vers la devise choisie ci-dessus — même règle que fmtUSD, mais un nombre pour le graphique. */
function versDeviseAffichage(montantUSD: number): number {
  return deviseAffichage.value === 'CDF' && tauxChange.value > 0 ? montantUSD * tauxChange.value : montantUSD
}

/** Tendance du chiffre d'affaires par jour sur la période. */
const tendanceData = computed(() => ({
  labels: (analyse.value?.parJour || []).map(j => fmtDateCourte(j.date)),
  datasets: [{
    // Réactif à la bascule FC/$US ci-dessus : afficher "(USD)" alors que les
    // valeurs tracées (et les KPI juste au-dessus) sont converties en FC
    // laissait croire à un bug dès que la bascule était sur FC (son défaut).
    label: `Chiffre d'affaires (${deviseAffichage.value === 'CDF' ? 'FC' : 'USD'})`,
    data: (analyse.value?.parJour || []).map(j => versDeviseAffichage(j.chiffreAffaires)),
    borderColor: couleurPrimaireHex.value,
    backgroundColor: hexToRgba(couleurPrimaireHex.value, 0.2),
    fill: true, tension: 0.3, pointRadius: 2,
  }],
}))
const tendanceOpts: any = {
  ...cb,
  scales: { y: { beginAtZero: true } },
  plugins: {
    ...cb.plugins,
    tooltip: {
      ...cb.plugins.tooltip,
      // ctx.parsed.y est deja dans la devise choisie (voir versDeviseAffichage
      // ci-dessus) : on le formate directement, sans repasser par fmtUSD qui
      // attend lui un montant USD brut a convertir.
      callbacks: {
        label: (ctx: any) => `${ctx.dataset.label}: ` + (deviseAffichage.value === 'CDF'
          ? new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(ctx.parsed.y) + ' FC'
          : new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD' }).format(ctx.parsed.y)),
      },
    },
  },
}

const meilleuresVentesData = computed(() => ({
  labels: meilleuresVentes.value.map(a => a.libelle),
  datasets: [{ label: 'Quantité vendue', data: meilleuresVentes.value.map(a => a.quantiteVendue), backgroundColor: hexToRgba(couleurPrimaireHex.value, 0.7), borderColor: couleurPrimaireHex.value, borderRadius: 6 }],
}))
const moinsVenduesData = computed(() => ({
  labels: moinsVendues.value.map(a => a.libelle),
  datasets: [{ label: 'Quantité vendue', data: moinsVendues.value.map(a => a.quantiteVendue), backgroundColor: 'rgba(220,38,38,0.65)', borderColor: '#dc2626', borderRadius: 6 }],
}))
const barHorizontalOpts: any = { ...cb, indexAxis: 'y' as const, plugins: { ...cb.plugins, legend: { display: false } }, scales: { x: { beginAtZero: true, ticks: { precision: 0 } } } }

const repartitionCategorieData = computed(() => {
  const items = (analyse.value?.parCategorie || []).filter(c => c.chiffreAffaires > 0)
  return {
    labels: items.map(c => `${c.categorie} (${c.type === 'PLAT' ? 'plat' : 'boisson'})`),
    datasets: [{
      data: items.map(c => c.chiffreAffaires),
      backgroundColor: items.map((_, i) => hexToRgba(COULEURS.value[i % COULEURS.value.length], 0.8)),
      borderColor: items.map((_, i) => COULEURS.value[i % COULEURS.value.length]),
      borderWidth: 2, hoverOffset: 8,
    }],
  }
})
const repartitionOpts: any = { ...cb, plugins: { ...cb.plugins, legend: { ...cb.plugins.legend, position: 'bottom' } }, cutout: '55%' }
</script>

<template>
  <div class="av-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Analyses des ventes</h1>
        <p class="page-sub">Meilleures ventes, marge par article et tendance — {{ periodeLabel }}</p>
      </div>
      <div class="d-flex ga-2 flex-wrap">
        <!-- Densité par défaut = 48 px, la hauteur des champs voisins (« comfortable » ne donne que 40 px). -->
        <v-btn-toggle v-model="deviseAffichage" mandatory variant="outlined" rounded="lg">
          <v-btn value="CDF" size="small">FC</v-btn>
          <v-btn value="USD" size="small">$US</v-btn>
        </v-btn-toggle>
        <v-select
          v-model="typePeriode" :items="TYPES_PERIODE" item-title="label" item-value="value"
          label="Période" variant="outlined" density="comfortable" rounded="lg" hide-details
          style="max-width: 160px"
        />
        <v-text-field
          v-model="dateReference" type="date" label="Date de référence" variant="outlined"
          density="comfortable" rounded="lg" hide-details style="max-width: 170px"
        />
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-skeleton-loader v-if="loading" type="card, card, card" />

    <template v-else-if="analyse">
      <div class="av-kpis mb-5">
        <div class="av-kpi av-kpi--ca">
          <v-icon icon="mdi-cash-multiple" size="20" />
          <div><span class="av-kpi__val">{{ fmtUSD(analyse.totalChiffreAffaires) }}</span><span class="av-kpi__lbl">Chiffre d'affaires</span></div>
        </div>
        <div class="av-kpi av-kpi--cout">
          <v-icon icon="mdi-sack" size="20" />
          <div><span class="av-kpi__val">{{ fmtUSD(analyse.totalCout) }}</span><span class="av-kpi__lbl">Coût des ventes</span></div>
        </div>
        <div class="av-kpi" :class="analyse.totalMarge >= 0 ? 'av-kpi--marge' : 'av-kpi--alerte'">
          <v-icon icon="mdi-chart-line" size="20" />
          <div><span class="av-kpi__val">{{ fmtUSD(analyse.totalMarge) }}</span><span class="av-kpi__lbl">Marge (dividende)</span></div>
        </div>
        <div class="av-kpi av-kpi--nb">
          <v-icon icon="mdi-silverware-fork-knife" size="20" />
          <div><span class="av-kpi__val">{{ fmtNb(analyse.totalQuantite) }}</span><span class="av-kpi__lbl">Unités vendues</span></div>
        </div>
      </div>

      <div class="av-charts mb-5">
        <div class="av-chart av-chart--wide">
          <p class="av-chart__title"><v-icon icon="mdi-chart-line" size="15" class="mr-1" />Tendance du chiffre d'affaires</p>
          <div class="av-chart__body">
            <Line v-if="analyse.parJour.length" :data="tendanceData" :options="tendanceOpts" />
            <p v-else class="av-chart__empty">Aucune vente sur cette période.</p>
          </div>
        </div>
        <div class="av-chart">
          <p class="av-chart__title"><v-icon icon="mdi-trophy-outline" size="15" class="mr-1" />Meilleures ventes</p>
          <div class="av-chart__body">
            <Bar v-if="meilleuresVentes.length" :data="meilleuresVentesData" :options="barHorizontalOpts" />
            <p v-else class="av-chart__empty">Aucune vente sur cette période.</p>
          </div>
        </div>
        <div class="av-chart">
          <p class="av-chart__title"><v-icon icon="mdi-trending-down" size="15" class="mr-1" />Moins vendus</p>
          <div class="av-chart__body">
            <Bar v-if="moinsVendues.length" :data="moinsVenduesData" :options="barHorizontalOpts" />
            <p v-else class="av-chart__empty">Aucun article sur la carte.</p>
          </div>
        </div>
        <div class="av-chart">
          <p class="av-chart__title"><v-icon icon="mdi-chart-donut" size="15" class="mr-1" />Chiffre d'affaires par catégorie</p>
          <div class="av-chart__body">
            <Doughnut v-if="repartitionCategorieData.labels.length" :data="repartitionCategorieData" :options="repartitionOpts" />
            <p v-else class="av-chart__empty">Aucune vente sur cette période.</p>
          </div>
        </div>
      </div>

      <v-card class="classroom-card mb-5">
        <div class="av-card-head">Top 10 — Marge par article (dividende)</div>
        <v-table density="comfortable">
          <thead>
            <tr>
              <th>Article</th><th>Type</th><th>Catégorie</th>
              <th class="text-end">Qté</th><th class="text-end">CA</th><th class="text-end">Coût</th>
              <th class="text-end">Marge</th><th class="text-end">Marge %</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in parMarge" :key="a.articleId">
              <td>{{ a.code }} — {{ a.libelle }}</td>
              <td><v-chip size="x-small" variant="tonal" :color="a.type === 'PLAT' ? 'deep-orange' : 'indigo'">{{ a.type === 'PLAT' ? 'Plat' : 'Boisson' }}</v-chip></td>
              <td class="text-medium-emphasis">{{ a.categorie }}</td>
              <td class="text-end">{{ fmtNb(a.quantiteVendue) }}</td>
              <td class="text-end">{{ fmtUSD(a.chiffreAffaires) }}</td>
              <td class="text-end text-medium-emphasis">{{ fmtUSD(a.cout) }}</td>
              <td class="text-end font-weight-bold" :class="a.marge >= 0 ? 'text-success' : 'text-error'">{{ fmtUSD(a.marge) }}</td>
              <td class="text-end">{{ fmtPct(a.margePourcentage) }}</td>
            </tr>
            <tr v-if="!parMarge.length"><td colspan="8" class="text-center text-medium-emphasis py-6">Aucune donnée sur cette période.</td></tr>
          </tbody>
        </v-table>
      </v-card>

      <v-card class="classroom-card">
        <div class="av-card-head">Détail par catégorie</div>
        <v-table density="comfortable">
          <thead>
            <tr>
              <th>Catégorie</th><th>Type</th>
              <th class="text-end">Qté</th><th class="text-end">CA</th><th class="text-end">Coût</th><th class="text-end">Marge</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in analyse.parCategorie" :key="c.type + c.categorie">
              <td>{{ c.categorie }}</td>
              <td><v-chip size="x-small" variant="tonal" :color="c.type === 'PLAT' ? 'deep-orange' : 'indigo'">{{ c.type === 'PLAT' ? 'Plat' : 'Boisson' }}</v-chip></td>
              <td class="text-end">{{ fmtNb(c.quantiteVendue) }}</td>
              <td class="text-end">{{ fmtUSD(c.chiffreAffaires) }}</td>
              <td class="text-end text-medium-emphasis">{{ fmtUSD(c.cout) }}</td>
              <td class="text-end font-weight-bold" :class="c.marge >= 0 ? 'text-success' : 'text-error'">{{ fmtUSD(c.marge) }}</td>
            </tr>
            <tr v-if="!analyse.parCategorie.length"><td colspan="6" class="text-center text-medium-emphasis py-6">Aucune donnée sur cette période.</td></tr>
          </tbody>
        </v-table>
      </v-card>
    </template>
  </div>
</template>

<style scoped>
.av-page { max-width: 1280px; margin: 0 auto; padding-bottom: 48px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }

.av-kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.av-kpi { display: flex; align-items: center; gap: 12px; padding: 14px 16px; border-radius: 14px; background: #f9fafb; }
.av-kpi--ca    { background: linear-gradient(135deg,var(--color-primary-lighter),var(--color-primary-light)); color: var(--color-primary-dark); }
.av-kpi--cout  { background: linear-gradient(135deg,#fff7ed,#ffedd5); color: #c2410c; }
.av-kpi--marge { background: linear-gradient(135deg,#eff6ff,#dbeafe); color: #1d4ed8; }
.av-kpi--alerte{ background: linear-gradient(135deg,#fef2f2,#fee2e2); color: #b91c1c; }
.av-kpi--nb    { background: linear-gradient(135deg,#faf5ff,#f3e8ff); color: #7e22ce; }
.av-kpi__val { display: block; font-size: 1.05rem; font-weight: 800; letter-spacing: -0.4px; }
.av-kpi__lbl { display: block; font-size: 0.72rem; font-weight: 500; opacity: 0.8; }

.av-charts { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; }
.av-chart { background: #fff; border: 1px solid #f0f0f0; border-radius: 16px; padding: 16px 18px 20px; }
.av-chart--wide { grid-column: 1 / -1; }
.av-chart__title { font-size: 0.8rem; font-weight: 700; color: #374151; margin: 0 0 12px; display: flex; align-items: center; }
.av-chart__body { height: 260px; position: relative; }
.av-chart__empty { display: flex; align-items: center; justify-content: center; height: 100%; color: #9ca3af; font-size: 0.82rem; }
@media (max-width: 900px) { .av-charts { grid-template-columns: 1fr; } }

.av-card-head {
  font-size: 0.8rem; font-weight: 700; letter-spacing: 0.3px; text-transform: uppercase;
  color: #6b7280; padding: 16px 20px 12px;
}
</style>
