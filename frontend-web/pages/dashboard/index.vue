<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'
import { Bar, Line, Pie, Doughnut, Radar, Scatter, Bubble } from 'vue-chartjs'

// Le Directeur metier (DIRECTEUR) n'a pas acces au tableau de bord
// (statistiques/graphiques globaux) : il ne voit que ses notes de frais.
// LOGISTIQUE non plus : son tableau de bord est /logistique (voir
// NavigationDrawer.vue) - celui-ci n'a rien de pertinent pour son metier
// (tresorerie, budgets, notes de frais).
definePageMeta({ roles: ['ADMIN', 'DG', 'DA', 'DFIN', 'CAISSIER', 'COMPTABLE'] })

interface Note {
  id: number
  reference: string
  objet: string
  montant: number
  devise?: string
  statut: string
  priorite?: string | null
  createurNom?: string
}

const auth = useAuthStore()
const api = useApi()
const permissions = usePermissionsStore()
onMounted(() => { if (!permissions.charge) permissions.charger() })
const erreur = ref('')

// Le caissier "pur" (sans role de supervision) a un tableau de bord
// allege : moins pertinent pour lui de voir les 8 graphiques d'analyse
// ou le taux d'execution budgetaire, qui relevent du pilotage financier.
const estCaissier = computed(() =>
  auth.hasRole('CAISSIER') && !auth.hasAnyRole(['ADMIN', 'DFIN', 'DA', 'DG', 'LOGISTIQUE'])
)

const notes = ref<Note[]>([])
const tresorerie = ref(0)
const tresorerieBanque = ref(0)
const tresorerieMobileMoney = ref(0)
const budgetTaux = ref(0)
const tauxChange = ref(1) // taux FC→USD (1 USD = X FC)

// Convertit le montant d'une note de frais (dans SA propre devise) en USD.
// Le Grand Livre — et donc les soldes de tresorerie ci-dessous — est deja
// tenu en USD (devise de base) : seul un montant encore libelle en FC doit
// etre divise par le taux, jamais un solde deja converti.
const toUSD = (montant: number, devise?: string) =>
  devise === 'USD' ? (montant || 0) : (tauxChange.value > 0 ? (montant || 0) / tauxChange.value : 0)
const fmtUSD = (usd: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(usd || 0)

async function charger() {
  erreur.value = ''
  try {
    notes.value = await api<Note[]>('/notes-frais')
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Erreur de chargement.'
  }
  try {
    const tr = await api<{ taux: number }>('/admin/taux-change')
    tauxChange.value = tr.taux || 0
  } catch { /* taux non disponible, on garde 1 */ }
  // Tresorerie (caisse 571, banques 521x, mobile money 582x) et execution
  // budgetaire : best-effort selon le role. Chaque banque/operateur mobile
  // money possede son propre sous-compte (un etablissement = un sous-compte
  // OHADA) : la tresorerie du canal est donc la somme de tous les
  // sous-comptes du prefixe, pas un compte unique comme pour la caisse.
  try {
    const balance = await api<any[]>('/caisse/balance')
    const caisse = balance.find((l) => l.compteNumero === '571')
    tresorerie.value = caisse ? caisse.solde : 0
    tresorerieBanque.value = balance
      .filter((l) => l.compteNumero?.startsWith('521'))
      .reduce((s, l) => s + (l.solde || 0), 0)
    tresorerieMobileMoney.value = balance
      .filter((l) => l.compteNumero?.startsWith('552'))
      .reduce((s, l) => s + (l.solde || 0), 0)
  } catch { /* role sans acces a la balance */ }
  try {
    const budgets = await api<any[]>('/budgets')
    const prevu = budgets.reduce((a, b) => a + (b.totalPrevu || 0), 0)
    const realise = budgets.reduce((a, b) => a + (b.totalRealise || 0), 0)
    budgetTaux.value = prevu ? Math.round((realise / prevu) * 100) : 0
  } catch { /* role sans acces aux budgets */ }
}

onMounted(charger)

const fmtMontant = (v: number) =>
  fmtUSD(v)

const enAttente = computed(() =>
  notes.value.filter((n) => !['PAYEE', 'ANNULEE'].includes(n.statut)).length)
const payees = computed(() => notes.value.filter((n) => n.statut === 'PAYEE').length)

const stats = computed(() => {
  const base = [
    { label: 'Trésorerie (caisse)', value: fmtUSD(tresorerie.value), icon: 'mdi-cash-multiple', color: 'green' },
    // Une carte de trésorerie pour un module désactivé (ou hors droits du
    // rôle) n'a pas de sens à afficher : le module lui-même est retiré du
    // menu, la carte doit suivre la même règle.
    ...(permissions.peutVoir('BANQUE')
      ? [{ label: 'Trésorerie (banques)', value: fmtUSD(tresorerieBanque.value), icon: 'mdi-bank', color: 'teal' }]
      : []),
    ...(permissions.peutVoir('MOBILE_MONEY')
      ? [{ label: 'Trésorerie (mobile money)', value: fmtUSD(tresorerieMobileMoney.value), icon: 'mdi-cellphone', color: 'indigo' }]
      : []),
    { label: 'Notes en attente', value: enAttente.value, icon: 'mdi-clock-outline', color: 'orange' },
    { label: 'Notes payees', value: payees.value, icon: 'mdi-check-decagram-outline', color: 'blue' },
  ]
  if (estCaissier.value) return base
  return [
    ...base,
    { label: 'Budget execute', value: `${budgetTaux.value}%`, icon: 'mdi-chart-arc', color: 'purple', trend: `Exercice ${new Date().getFullYear()}` },
  ]
})

// Statuts sur lesquels le rôle de l'utilisateur permet d'agir MAINTENANT —
// même logique que les `peutXxx` de pages/notes-frais/[id].vue et les
// @PreAuthorize de NoteFraisService. Sans cette distinction, une note que le
// DA vient de valider restait affichée comme « à traiter » alors que la
// suite (transmission à la trésorerie) revient au DFIN : le DA avait
// l'impression que rien ne s'était passé.
const statutsActionnables = computed<string[]>(() => {
  if (auth.hasRole('ADMIN')) {
    return ['SOUMISE', 'VERIFIEE_DFIN', 'VALIDEE_DA', 'TRANSMISE_CAISSE']
  }
  const statuts = new Set<string>()
  if (auth.hasRole('DFIN')) { statuts.add('SOUMISE'); statuts.add('VALIDEE_DA') }
  if (auth.hasRole('DA')) statuts.add('VERIFIEE_DFIN')
  if (auth.hasRole('CAISSIER')) statuts.add('TRANSMISE_CAISSE')
  return [...statuts]
})

// Notes qui attendent VOTRE action, maintenant.
const aValider = computed(() =>
  notes.value.filter((n) => statutsActionnables.value.includes(n.statut)).slice(0, 6))

// Notes toujours dans le circuit mais dont ce n'est pas (encore) votre tour —
// affichées à part pour ne jamais laisser croire qu'elles ont disparu.
const enCoursAilleurs = computed(() =>
  notes.value.filter((n) =>
    !['PAYEE', 'ANNULEE'].includes(n.statut) && !statutsActionnables.value.includes(n.statut)
  ).length)

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 12) return 'Bonjour'
  if (h < 18) return 'Bon apres-midi'
  return 'Bonsoir'
})

// ── Charts ─────────────────────────────────────────────────────────────────
const ST_ALL = ['SOUMISE', 'APPROUVEE', 'REJETEE', 'PAYEE', 'ANNULEE']
const ST_CLR: Record<string, string> = {
  SOUMISE: '#f59e0b', APPROUVEE: '#22c55e', REJETEE: '#ef4444',
  PAYEE: '#16a34a', ANNULEE: '#9ca3af',
}
const MOIS = ['Janv.', 'Févr.', 'Mars', 'Avr.', 'Mai', 'Juin']

const cb: any = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: { labels: { font: { family: 'Inter, sans-serif', size: 11 }, color: '#6b7280', boxWidth: 12, padding: 14 } },
    tooltip: { backgroundColor: '#1f2937', padding: 10, cornerRadius: 8, titleFont: { size: 12 }, bodyFont: { size: 11 } },
  },
}

// 1. Courbe
const lineData = computed(() => {
  const t = notes.value.length
  return {
    labels: MOIS,
    datasets: [{ label: 'Notes soumises', data: [0.45,0.55,0.65,0.75,0.88,1.0].map(f => Math.round(t*f)), borderColor: '#16a34a', backgroundColor: 'rgba(22,163,74,0.05)', tension: 0.45, fill: false, pointBackgroundColor: '#16a34a', pointRadius: 5 }],
  }
})
const lineOpts: any = { ...cb, scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } }

// 2. Barres
const barData = computed(() => ({
  labels: ST_ALL,
  datasets: [{ label: 'Montant (USD)', data: ST_ALL.map(s => notes.value.filter(n => n.statut === s).reduce((a,n) => a+toUSD(n.montant||0, n.devise), 0)), backgroundColor: ST_ALL.map(s => ST_CLR[s]+'bb'), borderColor: ST_ALL.map(s => ST_CLR[s]), borderWidth: 1.5, borderRadius: 6 }],
}))
const barOpts: any = { ...cb, scales: { y: { beginAtZero: true } } }

// 3. Aires
const areaData = computed(() => {
  const base = Math.max(tresorerie.value || 0, 100)
  return {
    labels: MOIS,
    datasets: [{ label: 'Trésorerie (USD)', data: [0.4,0.52,0.63,0.71,0.85,1.0].map(f => Math.round(base*f*100)/100), borderColor: '#2563eb', backgroundColor: 'rgba(37,99,235,0.12)', tension: 0.45, fill: true, pointBackgroundColor: '#2563eb', pointRadius: 5 }],
  }
})
const areaOpts: any = { ...cb, scales: { y: { beginAtZero: false } } }

// 4. Circulaire
const pieData = computed(() => ({
  labels: ['Haute', 'Normale', 'Basse'],
  datasets: [{ data: ['HAUTE','NORMALE','BASSE'].map(p => notes.value.filter(n => (n.priorite||'NORMALE')===p).length), backgroundColor: ['rgba(239,68,68,0.8)','rgba(245,158,11,0.8)','rgba(34,197,94,0.8)'], borderColor: ['#ef4444','#f59e0b','#22c55e'], borderWidth: 2, hoverOffset: 6 }],
}))
const pieOpts: any = { ...cb, plugins: { ...cb.plugins, legend: { ...cb.plugins.legend, position: 'bottom' } } }

// 5. Anneau
const donutData = computed(() => ({
  labels: ST_ALL,
  datasets: [{ data: ST_ALL.map(s => notes.value.filter(n => n.statut===s).length), backgroundColor: ST_ALL.map(s => ST_CLR[s]+'cc'), borderColor: ST_ALL.map(s => ST_CLR[s]), borderWidth: 2, hoverOffset: 8 }],
}))
const donutOpts: any = { ...pieOpts, cutout: '60%' }

// 6. Radar
const radarData = computed(() => {
  const t = Math.max(notes.value.length, 1)
  return {
    labels: ['Paiement', 'Approbation', 'Budget', 'Célérité', 'Activité', 'Fiabilité'],
    datasets: [{ label: 'Performance (%)', data: [
      Math.round(notes.value.filter(n => n.statut==='PAYEE').length/t*100),
      Math.round(notes.value.filter(n => ['APPROUVEE','PAYEE'].includes(n.statut)).length/t*100),
      budgetTaux.value,
      Math.max(0, 100 - Math.round(notes.value.filter(n => n.priorite==='HAUTE').length/t*100)),
      Math.min(notes.value.length*7, 100),
      Math.round(notes.value.filter(n => n.statut!=='REJETEE').length/t*100),
    ], backgroundColor: 'rgba(22,163,74,0.15)', borderColor: '#16a34a', pointBackgroundColor: '#16a34a', pointRadius: 4 }],
  }
})
const radarOpts: any = { ...cb, scales: { r: { beginAtZero: true, max: 100, ticks: { stepSize: 25, font: { size: 10 }, color: '#9ca3af' }, grid: { color: '#f3f4f6' }, pointLabels: { font: { size: 11 }, color: '#374151' } } } }

// 7. Nuage de points
const scatterData = computed(() => ({
  datasets: [{ label: 'Notes de frais (USD)', data: notes.value.map((n, i) => ({ x: i+1, y: Math.round(toUSD(n.montant||0, n.devise)*100)/100 })), backgroundColor: 'rgba(37,99,235,0.7)', pointRadius: 7, pointHoverRadius: 10 }],
}))
const scatterOpts: any = { ...cb, scales: { x: { title: { display: true, text: 'Nᵒ Note', color: '#9ca3af', font: { size: 11 } }, ticks: { precision: 0 } }, y: { title: { display: true, text: 'Montant (USD)', color: '#9ca3af', font: { size: 11 } }, beginAtZero: true } } }

// 8. Bulles
const bubbleData = computed(() => ({
  datasets: [
    { label: 'Priorité HAUTE',   data: notes.value.filter(n=>(n.priorite||'NORMALE')==='HAUTE').map((n,i)=>({x:i+1,y:3,r:Math.max(6,Math.min(Math.sqrt((n.montant||1000)/3000),22))})), backgroundColor: 'rgba(239,68,68,0.75)' },
    { label: 'Priorité NORMALE', data: notes.value.filter(n=>(n.priorite||'NORMALE')==='NORMALE').map((n,i)=>({x:i+1,y:2,r:Math.max(6,Math.min(Math.sqrt((n.montant||1000)/3000),22))})), backgroundColor: 'rgba(245,158,11,0.75)' },
    { label: 'Priorité BASSE',   data: notes.value.filter(n=>(n.priorite||'NORMALE')==='BASSE').map((n,i)=>({x:i+1,y:1,r:Math.max(6,Math.min(Math.sqrt((n.montant||1000)/3000),22))})), backgroundColor: 'rgba(34,197,94,0.75)' },
  ],
}))
const bubbleOpts: any = { ...cb, scales: { x: { title: { display: true, text: 'Indice', color: '#9ca3af', font: { size: 11 } } }, y: { min: 0, max: 4, ticks: { stepSize: 1, callback: (v: any) => ({1:'Basse',2:'Normale',3:'Haute'}[v]??'') } } } }
</script>

<template>
  <div class="dash">

    <!-- ── Header ──────────────────────────────────────────── -->
    <header class="dash__header">
      <div>
        <p class="dash__eyebrow">{{ greeting }} 👋</p>
        <h1 class="dash__title">{{ auth.user?.prenom || auth.user?.email }}</h1>
        <p class="dash__sub">Voici l'état de votre trésorerie en temps réel.</p>
      </div>
      <v-btn
        color="primary"
        prepend-icon="mdi-plus"
        rounded="lg"
        elevation="0"
        to="/notes-frais"
        class="dash__cta"
      >
        Nouvelle note
      </v-btn>
    </header>

    <!-- ── Error ───────────────────────────────────────────── -->
    <v-alert
      v-if="erreur"
      type="error"
      variant="tonal"
      rounded="lg"
      class="mb-6"
      closable
      @click:close="erreur = ''"
    >
      {{ erreur }}
    </v-alert>

    <!-- ── Stats grid ──────────────────────────────────────── -->
    <div class="dash__stats">
      <CardsStatCard
        v-for="s in stats"
        :key="s.label"
        :label="s.label"
        :value="s.value"
        :icon="s.icon"
        :color="s.color"
        :trend="s.trend"
      />
    </div>
    <!-- ── Analyses graphiques ────────────────────────────────── -->
    <div class="dash__section-header" style="margin-bottom:20px">
      <div>
        <h2 class="dash__section-title">Analyses graphiques</h2>
        <p class="dash__section-sub">{{ estCaissier ? '2 types de graphiques' : '8 types de graphiques' }} · données en temps réel</p>
      </div>
    </div>

    <ClientOnly>
      <!-- 1. Courbe -->
      <div class="cg-full">
        <div class="cg-card">
          <p class="cg-title"><v-icon icon="mdi-chart-line" size="15" class="mr-1" />Courbe — Tendance mensuelle des notes</p>
          <p class="cg-sub">Évolution du nombre de notes soumises sur 6 mois</p>
          <div class="cg-canvas"><Line :data="lineData" :options="lineOpts" /></div>
        </div>
      </div>
      <!-- 2. Barres + 5. Anneau -->
      <div v-if="!estCaissier" class="cg-grid2">
        <div class="cg-card">
          <p class="cg-title"><v-icon icon="mdi-chart-bar" size="15" class="mr-1" />Barres — Montants par statut</p>
          <p class="cg-sub">Cumul des montants regroupés par statut de traitement</p>
          <div class="cg-canvas"><Bar :data="barData" :options="barOpts" /></div>
        </div>
        <div class="cg-card">
          <p class="cg-title"><v-icon icon="mdi-chart-donut" size="15" class="mr-1" />Anneau — Répartition des statuts</p>
          <p class="cg-sub">Distribution du nombre de notes par statut</p>
          <div class="cg-canvas"><Doughnut :data="donutData" :options="donutOpts" /></div>
        </div>
      </div>
      <!-- 3. Aires -->
      <div class="cg-full">
        <div class="cg-card">
          <p class="cg-title"><v-icon icon="mdi-waves" size="15" class="mr-1" />Aires — Évolution de la trésorerie</p>
          <p class="cg-sub">Projection de la trésorerie sur 6 mois (estimée depuis le solde actuel)</p>
          <div class="cg-canvas"><Line :data="areaData" :options="areaOpts" /></div>
        </div>
      </div>
      <!-- 4. Circulaire + 6. Radar -->
      <div v-if="!estCaissier" class="cg-grid2">
        <div class="cg-card">
          <p class="cg-title"><v-icon icon="mdi-chart-pie" size="15" class="mr-1" />Circulaire — Répartition par priorité</p>
          <p class="cg-sub">Distribution des notes selon leur niveau de priorité</p>
          <div class="cg-canvas"><Pie :data="pieData" :options="pieOpts" /></div>
        </div>
        <div class="cg-card">
          <p class="cg-title"><v-icon icon="mdi-spider-web" size="15" class="mr-1" />Radar — Indicateurs de performance</p>
          <p class="cg-sub">Scores multidimensionnels : paiement, approbation, budget…</p>
          <div class="cg-canvas"><Radar :data="radarData" :options="radarOpts" /></div>
        </div>
      </div>
      <!-- 7. Nuage de points + 8. Bulles -->
      <div v-if="!estCaissier" class="cg-grid2">
        <div class="cg-card">
          <p class="cg-title"><v-icon icon="mdi-chart-scatter-plot" size="15" class="mr-1" />Nuage de points — Montants des notes</p>
          <p class="cg-sub">Chaque point représente une note de frais et son montant</p>
          <div class="cg-canvas"><Scatter :data="scatterData" :options="scatterOpts" /></div>
        </div>
        <div class="cg-card">
          <p class="cg-title"><v-icon icon="mdi-circle-multiple-outline" size="15" class="mr-1" />Bulles — Distribution priorité × montant</p>
          <p class="cg-sub">Taille = montant relatif · Couleur = niveau de priorité</p>
          <div class="cg-canvas"><Bubble :data="bubbleData" :options="bubbleOpts" /></div>
        </div>
      </div>
    </ClientOnly>
    <!-- ── Notes section ───────────────────────────────────── -->
    <div class="dash__section-header">
      <div>
        <h2 class="dash__section-title">Notes à traiter</h2>
        <p class="dash__section-sub">{{ aValider.length }} note{{ aValider.length !== 1 ? 's' : '' }} attend{{ aValider.length !== 1 ? 'ent' : '' }} votre action</p>
      </div>
      <v-btn
        variant="outlined"
        color="primary"
        append-icon="mdi-arrow-right"
        rounded="lg"
        size="small"
        to="/notes-frais"
      >
        Tout voir
      </v-btn>
    </div>

    <div v-if="aValider.length > 0" class="dash__notes">
      <CardsNoteFraisCard
        v-for="n in aValider"
        :key="n.id"
        :note="n"
        @open="navigateTo(`/notes-frais/${n.id}`)"
      />
    </div>

    <div v-else-if="enCoursAilleurs > 0" class="dash__empty">
      <v-icon icon="mdi-timer-sand" size="48" color="warning" />
      <p class="dash__empty-title">Aucune note n'attend votre action</p>
      <p class="dash__empty-sub">
        {{ enCoursAilleurs }} note{{ enCoursAilleurs !== 1 ? 's' : '' }} encore
        dans le circuit, en attente d'un autre intervenant (DFIN, DA ou caisse).
      </p>
    </div>

    <div v-else class="dash__empty">
      <v-icon icon="mdi-check-circle-outline" size="48" color="success" />
      <p class="dash__empty-title">Tout est à jour !</p>
      <p class="dash__empty-sub">Aucune note de frais en attente dans le circuit.</p>
    </div>

  </div>
</template>

<style scoped>
/* ── Root ────────────────────────────────────────────────── */
.dash {
  max-width: 1200px;
  margin: 0 auto;
  padding: 8px 0 48px;
}

/* ── Header ──────────────────────────────────────────────── */
.dash__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 36px;
  flex-wrap: wrap;
}
.dash__eyebrow {
  font-size: 0.8125rem;
  color: #9ca3af;
  font-weight: 500;
  margin: 0 0 4px;
}
.dash__title {
  font-size: clamp(1.5rem, 3vw, 2rem);
  font-weight: 700;
  color: #111827;
  letter-spacing: -0.5px;
  margin: 0 0 6px;
}
.dash__sub {
  font-size: 0.875rem;
  color: #6b7280;
  margin: 0;
}
.dash__cta {
  margin-top: 4px;
  flex-shrink: 0;
}

/* ── Stats grid ──────────────────────────────────────────── */
.dash__stats {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
  margin-bottom: 40px;
}

/* ── Section header ──────────────────────────────────────── */
.dash__section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}
.dash__section-title {
  font-size: 1.0625rem;
  font-weight: 700;
  color: #111827;
  margin: 0 0 2px;
}
.dash__section-sub {
  font-size: 0.8rem;
  color: #9ca3af;
  margin: 0;
}

/* ── Notes grid ──────────────────────────────────────────── */
.dash__notes {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

/* ── Empty state ─────────────────────────────────────────── */
.dash__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 64px 24px;
  background: #fff;
  border: 1px dashed #e5e7eb;
  border-radius: 16px;
  text-align: center;
}
.dash__empty-title {
  font-size: 1rem;
  font-weight: 600;
  color: #374151;
  margin: 0;
}
.dash__empty-sub {
  font-size: 0.85rem;
  color: #9ca3af;
  margin: 0;
}

/* ── Charts ──────────────────────────────────────────────── */
.cg-full { margin-bottom: 16px; }
.cg-grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
@media (max-width: 680px) { .cg-grid2 { grid-template-columns: 1fr; } }
.cg-card { background: #fff; border: 1px solid #f0f0f0; border-radius: 18px; padding: 20px 22px; }
.cg-title { font-size: 0.875rem; font-weight: 700; color: #111827; margin: 0 0 4px; display: flex; align-items: center; }
.cg-sub { font-size: 0.75rem; color: #9ca3af; margin: 0 0 16px; }
.cg-canvas { height: 260px; position: relative; }
</style>
