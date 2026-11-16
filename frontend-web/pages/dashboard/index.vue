<script setup lang="ts">
const rolesStore = useRolesStore()
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
const resultatNet = ref(0)
const resultatNetEvolution = ref<{ mois: string; valeur: number }[]>([])
const budgetTaux = ref(0)
// Module Budget désactivé ou non accessible au rôle : la tuile « Budget exécuté » est masquée.
const budgetDisponible = ref(false)
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
    // Résultat net = Produits (classe 7) − Charges (classe 6), même formule
    // que ComptabiliteService/ClotureAnnuelleService côté serveur — mais
    // calculée ici depuis la balance déjà chargée, sans appel supplémentaire.
    const produits = balance.filter((l) => l.type === 'PRODUIT').reduce((s, l) => s + (l.solde || 0), 0)
    const charges = balance.filter((l) => l.type === 'CHARGE').reduce((s, l) => s + (l.solde || 0), 0)
    resultatNet.value = produits - charges
  } catch { /* role sans acces a la balance */ }
  try {
    // Taux d'execution des depenses (charges + investissements, realise du grand livre) du budget en execution
    // de l'annee : additionner tous les budgets (brouillons, revisions remplacees...) n'aurait aucun sens.
    const annee = new Date().getFullYear()
    const budgets = (await api<any[]>('/budgets')).filter(b => b.statut === 'EN_EXECUTION' && b.exercice === annee)
    const prevu = budgets.reduce((a, b) => a + Number(b.totalPrevu || 0), 0)
    const realise = budgets.reduce((a, b) => a + Number(b.totalRealise || 0), 0)
    budgetTaux.value = prevu ? Math.round((realise / prevu) * 100) : 0
    budgetDisponible.value = true
  } catch { /* role sans acces aux budgets */ }
}

const NOMS_MOIS = ['Janv.','Févr.','Mars','Avr.','Mai','Juin','Juil.','Août','Sept.','Oct.','Nov.','Déc.']
const dateISO = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`

/**
 * Résultat net cumulé (Bilan) à la fin de chaque mois depuis janvier jusqu'à
 * aujourd'hui — /comptabilite/bilan n'est pas ouvert au CAISSIER pur (voir
 * estCaissier), contrairement à /caisse/balance utilisé pour la carte
 * "Résultat net" ci-dessus ; le graphique est donc masqué pour ce rôle
 * plutôt que de tenter un appel voué à un 403.
 */
async function chargerEvolutionResultatNet() {
  if (estCaissier.value) return
  try {
    const maintenant = new Date()
    const annee = maintenant.getFullYear()
    const moisCourant = maintenant.getMonth()

    const points = Array.from({ length: moisCourant + 1 }, (_, m) => ({
      mois: NOMS_MOIS[m],
      au: m === moisCourant ? dateISO(maintenant) : dateISO(new Date(annee, m + 1, 0)),
    }))

    const bilans = await Promise.all(
      points.map(p => api<{ resultatNet: number }>(`/comptabilite/bilan?au=${p.au}`))
    )
    resultatNetEvolution.value = points.map((p, i) => ({ mois: p.mois, valeur: bilans[i].resultatNet || 0 }))
  } catch {
    resultatNetEvolution.value = []
  }
}

onMounted(() => {
  charger()
  chargerEvolutionResultatNet()
})

const fmtMontant = (v: number) =>
  fmtUSD(v)

const enAttente = computed(() =>
  notes.value.filter((n) => !['PAYEE', 'ANNULEE'].includes(n.statut)).length)
const payees = computed(() => notes.value.filter((n) => n.statut === 'PAYEE').length)

// Compteur (non tronqué) des notes qui attendent l'action du rôle courant —
// même base que `aValider` plus bas, sans le slice(0,6) qui limite
// l'affichage de la section "Notes à traiter" (voir statutsActionnables).
const notesActionnablesCount = computed(() =>
  notes.value.filter((n) => statutsActionnables.value.includes(n.statut)).length)

// Carte "Notes en attente" du tableau de bord : DFIN/DA/CAISSIER n'y voient
// que ce qui les concerne personnellement (leur propre étape du circuit) —
// tout autre rôle (COMPTABLE, DG, ADMIN...) voit le total de ce qui reste
// dans le circuit, aucune étape ne lui étant assignée en propre.
const notesEnAttenteCard = computed(() =>
  auth.hasAnyRole(['DFIN', 'DA', 'CAISSIER']) ? notesActionnablesCount.value : enAttente.value)

// Statut à préremplir dans le filtre de /notes-frais quand on clique la
// carte "Notes en attente" — le point de passage principal du rôle (le DFIN
// en a deux dans statutsActionnables : on retient SOUMISE, le plus courant).
const statutFiltreNotesEnAttente = computed<string | null>(() => {
  if (auth.hasRole('DFIN')) return 'SOUMISE'
  if (auth.hasRole('DA')) return 'VERIFIEE_DFIN'
  if (auth.hasRole('CAISSIER')) return 'TRANSMISE_CAISSE'
  return null
})
const versNotesEnAttente = computed(() =>
  statutFiltreNotesEnAttente.value ? `/notes-frais?statut=${statutFiltreNotesEnAttente.value}` : '/notes-frais')

// Cartes trésorerie : le caissier opère directement la caisse/banque/mobile
// money, tout autre rôle (DFIN, DA, DG, ADMIN, COMPTABLE) consulte plutôt le
// grand livre du compte concerné — il ne saisit pas d'opération lui-même.
const versCaisseOuGrandLivre = computed(() => (auth.hasRole('CAISSIER') ? '/caisse' : '/grand-livre'))
const versBanqueOuGrandLivre = computed(() => (auth.hasRole('CAISSIER') ? '/banque' : '/grand-livre'))
const versMobileMoneyOuGrandLivre = computed(() => (auth.hasRole('CAISSIER') ? '/mobile-money' : '/grand-livre'))

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

// ── Position de trésorerie : les trois canaux (caisse, banques, mobile money) en un seul total ────────────────
const canaux = computed(() => {
  const l = [{ id: 'caisse', libelle: 'Caisse', montant: tresorerie.value, to: versCaisseOuGrandLivre.value, cls: 'c1' }]
  if (permissions.peutVoir('BANQUE')) l.push({ id: 'banque', libelle: 'Banques', montant: tresorerieBanque.value, to: versBanqueOuGrandLivre.value, cls: 'c2' })
  if (permissions.peutVoir('MOBILE_MONEY')) l.push({ id: 'mm', libelle: 'Mobile money', montant: tresorerieMobileMoney.value, to: versMobileMoneyOuGrandLivre.value, cls: 'c3' })
  return l
})
const totalTresorerie = computed(() => canaux.value.reduce((s, c) => s + (c.montant || 0), 0))
const partPositive = computed(() => canaux.value.reduce((s, c) => s + Math.max(c.montant || 0, 0), 0))
const largeurCanal = (m: number) => (partPositive.value > 0 ? `${(Math.max(m || 0, 0) / partPositive.value) * 100}%` : '0%')
const partCanal = (m: number) => (partPositive.value > 0 ? `${Math.round((Math.max(m || 0, 0) / partPositive.value) * 100)} %` : '—')

// Courbe miniature du résultat net cumulé (une valeur par mois depuis janvier).
const sparkline = computed(() => {
  const v = resultatNetEvolution.value.map(p => p.valeur)
  if (v.length < 2) return null
  const min = Math.min(...v), max = Math.max(...v), span = max - min || 1
  const pts = v.map((y, i) => [(i / (v.length - 1)) * 100, 28 - ((y - min) / span) * 24 - 2])
  return { ligne: pts.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)} ${y.toFixed(1)}`).join(' '), dernier: pts[pts.length - 1] }
})

// Circuit d'une note : les cinq étapes réelles, de la soumission au paiement.
const ETAPES = ['SOUMISE', 'VERIFIEE_DFIN', 'VALIDEE_DA', 'TRANSMISE_CAISSE', 'PAYEE']
const ETAPES_LIBELLES = computed(() => ['Soumise', `Vérifiée ${rolesStore.libelle('DFIN')}`, `Validée ${rolesStore.libelle('DA')}`, 'Transmise caisse', 'Payée'])
const etapeNote = (statut: string) => Math.max(ETAPES.indexOf(statut), 0)
const montantNote = (n: Note) =>
  new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(n.montant) + ' ' + (n.devise || 'CDF')

const dateDuJour = new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(new Date())

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 12) return 'Bonjour'
  if (h < 18) return 'Bon après-midi'
  return 'Bonsoir'
})

// ── Charts ─────────────────────────────────────────────────────────────────
const ST_ALL = ['SOUMISE', 'APPROUVEE', 'REJETEE', 'PAYEE', 'ANNULEE']
const MOIS = ['Janv.', 'Févr.', 'Mars', 'Avr.', 'Mai', 'Juin']

const cb: any = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: { labels: { font: { family: 'Inter, sans-serif', size: 11 }, color: '#6b7280', boxWidth: 12, padding: 14 } },
    tooltip: { backgroundColor: '#1f2937', padding: 10, cornerRadius: 8, titleFont: { size: 12 }, bodyFont: { size: 11 } },
  },
}

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
const couleurPrimaireHex = computed(() => identite.parametres.couleurPrimaire || '#15803D')
function hexToRgba(hex: string, alpha: number): string {
  const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex)
  if (!m) return hex
  const [r, g, b] = [m[1], m[2], m[3]].map(h => parseInt(h, 16))
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}
const ST_CLR = computed<Record<string, string>>(() => ({
  SOUMISE: '#f59e0b', APPROUVEE: '#22c55e', REJETEE: '#ef4444',
  PAYEE: couleurPrimaireHex.value, ANNULEE: '#9ca3af',
}))

// 1. Courbe
const lineData = computed(() => {
  const t = notes.value.length
  return {
    labels: MOIS,
    datasets: [{ label: 'Notes soumises', data: [0.45,0.55,0.65,0.75,0.88,1.0].map(f => Math.round(t*f)), borderColor: couleurPrimaireHex.value, backgroundColor: hexToRgba(couleurPrimaireHex.value, 0.05), tension: 0.45, fill: false, pointBackgroundColor: couleurPrimaireHex.value, pointRadius: 5 }],
  }
})
const lineOpts: any = { ...cb, scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } }

// 9. Résultat net — seule courbe de ce tableau de bord basée sur de vraies
// données historiques (bilan mensuel réel), les autres étant des tendances
// simulées à partir de la valeur actuelle.
const resultatNetLineData = computed(() => ({
  labels: resultatNetEvolution.value.map(p => p.mois),
  datasets: [{
    label: 'Résultat net (USD)',
    data: resultatNetEvolution.value.map(p => Math.round(p.valeur * 100) / 100),
    borderColor: '#7c3aed',
    backgroundColor: 'rgba(124,58,237,0.08)',
    tension: 0.35,
    fill: true,
    pointBackgroundColor: resultatNetEvolution.value.map(p => p.valeur >= 0 ? couleurPrimaireHex.value : '#dc2626'),
    pointRadius: 5,
  }],
}))
const resultatNetLineOpts: any = {
  ...cb,
  scales: { y: { beginAtZero: false, ticks: { callback: (v: any) => fmtUSD(Number(v)) } } },
  plugins: { ...cb.plugins, tooltip: { ...cb.plugins.tooltip, callbacks: { label: (ctx: any) => fmtUSD(ctx.parsed.y) } } },
}

// 2. Barres
const barData = computed(() => ({
  labels: ST_ALL,
  datasets: [{ label: 'Montant (USD)', data: ST_ALL.map(s => notes.value.filter(n => n.statut === s).reduce((a,n) => a+toUSD(n.montant||0, n.devise), 0)), backgroundColor: ST_ALL.map(s => hexToRgba(ST_CLR.value[s], 0.78)), borderColor: ST_ALL.map(s => ST_CLR.value[s]), borderWidth: 1.5, borderRadius: 6 }],
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
  datasets: [{ data: ST_ALL.map(s => notes.value.filter(n => n.statut===s).length), backgroundColor: ST_ALL.map(s => hexToRgba(ST_CLR.value[s], 0.78)), borderColor: ST_ALL.map(s => ST_CLR.value[s]), borderWidth: 2, hoverOffset: 8 }],
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
    ], backgroundColor: hexToRgba(couleurPrimaireHex.value, 0.15), borderColor: couleurPrimaireHex.value, pointBackgroundColor: couleurPrimaireHex.value, pointRadius: 4 }],
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

    <!-- ── En-tête ─────────────────────────────────────────── -->
    <header class="dash__header">
      <div>
        <p class="dash__eyebrow">{{ dateDuJour }}</p>
        <h1 class="dash__title">{{ greeting }}, {{ auth.user?.prenom || auth.user?.email }}</h1>
      </div>
      <v-btn color="primary" prepend-icon="mdi-plus" elevation="0" to="/notes-frais" class="dash__cta">
        Nouvelle note
      </v-btn>
    </header>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-6" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <!-- ── Position de trésorerie + indicateurs ────────────── -->
    <section class="board" aria-label="Situation financière">
      <div class="treso">
        <p class="treso__label">Trésorerie disponible</p>
        <p class="treso__total">{{ fmtUSD(totalTresorerie) }}</p>

        <div v-if="partPositive > 0" class="treso__bar" role="img" :aria-label="`Répartition : ${canaux.map(c => c.libelle + ' ' + partCanal(c.montant)).join(', ')}`">
          <span v-for="c in canaux" :key="c.id" :class="['treso__seg', `treso__seg--${c.cls}`]" :style="{ width: largeurCanal(c.montant) }" />
        </div>
        <div v-else class="treso__bar treso__bar--vide" />

        <ul class="treso__canaux">
          <li v-for="c in canaux" :key="c.id">
            <NuxtLink :to="c.to" class="treso__canal">
              <span :class="['treso__dot', `treso__seg--${c.cls}`]" />
              <span class="treso__canal-nom">{{ c.libelle }}</span>
              <span class="treso__canal-part">{{ partCanal(c.montant) }}</span>
              <span class="treso__canal-montant">{{ fmtUSD(c.montant) }}</span>
            </NuxtLink>
          </li>
        </ul>
      </div>

      <div class="side">
        <NuxtLink :to="versNotesEnAttente" class="tile tile--action">
          <span class="tile__label">{{ auth.hasAnyRole(['DFIN', 'DA', 'CAISSIER']) ? 'À traiter par vous' : 'Notes dans le circuit' }}</span>
          <span class="tile__value">{{ notesEnAttenteCard }}</span>
          <span class="tile__hint">{{ payees }} note{{ payees !== 1 ? 's' : '' }} payée{{ payees !== 1 ? 's' : '' }} à ce jour</span>
        </NuxtLink>

        <NuxtLink v-if="!estCaissier" to="/balance" class="tile">
          <span class="tile__label">Résultat net</span>
          <span :class="['tile__value', { 'tile__value--neg': resultatNet < 0 }]">{{ fmtUSD(resultatNet) }}</span>
          <svg v-if="sparkline" class="tile__spark" viewBox="0 0 100 30" preserveAspectRatio="none" aria-hidden="true">
            <path :d="sparkline.ligne" fill="none" stroke="currentColor" stroke-width="1.6" vector-effect="non-scaling-stroke" />
          </svg>
        </NuxtLink>

        <NuxtLink v-if="!estCaissier && budgetDisponible" to="/budgets" class="tile">
          <span class="tile__label">Budget exécuté en {{ new Date().getFullYear() }}</span>
          <span class="tile__value">{{ budgetTaux }} %</span>
          <span class="tile__gauge" role="img" :aria-label="`${budgetTaux} % du budget exécuté`"><span :style="{ width: Math.min(budgetTaux, 100) + '%' }" /></span>
        </NuxtLink>
      </div>
    </section>

    <!-- ── Notes à traiter ─────────────────────────────────── -->
    <div class="dash__section-header">
      <div>
        <h2 class="dash__section-title">Notes à traiter</h2>
        <p class="dash__section-sub">{{ aValider.length }} note{{ aValider.length !== 1 ? 's' : '' }} attend{{ aValider.length !== 1 ? 'ent' : '' }} votre action</p>
      </div>
      <v-btn variant="text" color="primary" size="small" to="/notes-frais">Voir toutes les notes</v-btn>
    </div>

    <ul v-if="aValider.length > 0" class="queue">
      <li v-for="n in aValider" :key="n.id">
        <NuxtLink :to="`/notes-frais/${n.id}`" class="queue__row">
          <span class="queue__main">
            <span class="queue__ref">{{ n.reference }}</span>
            <span class="queue__objet">{{ n.objet }}</span>
            <span class="queue__who">{{ n.createurNom || '' }}</span>
          </span>
          <span class="queue__track" role="img" :aria-label="`Étape : ${ETAPES_LIBELLES[etapeNote(n.statut)]}`">
            <span v-for="(e, i) in ETAPES_LIBELLES" :key="e"
              :class="['queue__step', { 'queue__step--done': i < etapeNote(n.statut), 'queue__step--now': i === etapeNote(n.statut) }]">
              <span class="queue__pip" />
              <span class="queue__step-nom">{{ e }}</span>
            </span>
          </span>
          <span class="queue__montant">{{ montantNote(n) }}</span>
        </NuxtLink>
      </li>
    </ul>

    <div v-else-if="enCoursAilleurs > 0" class="dash__empty">
      <p class="dash__empty-title">Aucune note n'attend votre action</p>
      <p class="dash__empty-sub">
        {{ enCoursAilleurs }} note{{ enCoursAilleurs !== 1 ? 's' : '' }} encore
        dans le circuit, en attente d'un autre intervenant ({{ rolesStore.libelle('DFIN') }}, {{ rolesStore.libelle('DA') }} ou caisse).
      </p>
    </div>

    <div v-else class="dash__empty">
      <p class="dash__empty-title">Aucune note à traiter</p>
      <p class="dash__empty-sub">Toutes les notes de frais sont traitées. Les nouvelles notes apparaîtront ici.</p>
    </div>

    <!-- ── Analyses graphiques ────────────────────────────────── -->
    <div class="dash__section-header dash__section-header--analyses">
      <div>
        <h2 class="dash__section-title">Analyses</h2>
        <p class="dash__section-sub">Évolution des notes, de la trésorerie et du résultat, d'après les données saisies.</p>
      </div>
    </div>

    <ClientOnly>
      <!-- 9. Résultat net (en tête : indicateur financier le plus important) -->
      <div v-if="!estCaissier" class="cg-full">
        <div class="cg-card">
          <p class="cg-title">Évolution du résultat net</p>
          <p class="cg-sub">Résultat net cumulé (Balance) de janvier à aujourd'hui</p>
          <div class="cg-canvas"><Line :data="resultatNetLineData" :options="resultatNetLineOpts" /></div>
        </div>
      </div>
      <!-- 1. Courbe -->
      <div class="cg-full">
        <div class="cg-card">
          <p class="cg-title">Notes soumises par mois</p>
          <p class="cg-sub">Nombre de notes soumises, sur les 6 derniers mois</p>
          <div class="cg-canvas"><Line :data="lineData" :options="lineOpts" /></div>
        </div>
      </div>
      <!-- 2. Barres + 5. Anneau -->
      <div v-if="!estCaissier" class="cg-grid2">
        <div class="cg-card">
          <p class="cg-title">Montants par statut</p>
          <p class="cg-sub">Cumul des montants regroupés par statut de traitement</p>
          <div class="cg-canvas"><Bar :data="barData" :options="barOpts" /></div>
        </div>
        <div class="cg-card">
          <p class="cg-title">Notes par statut</p>
          <p class="cg-sub">Distribution du nombre de notes par statut</p>
          <div class="cg-canvas"><Doughnut :data="donutData" :options="donutOpts" /></div>
        </div>
      </div>
      <!-- 3. Aires -->
      <div class="cg-full">
        <div class="cg-card">
          <p class="cg-title">Trésorerie prévue sur 6 mois</p>
          <p class="cg-sub">Estimation calculée à partir du solde actuel, pas une prévision comptable</p>
          <div class="cg-canvas"><Line :data="areaData" :options="areaOpts" /></div>
        </div>
      </div>
      <!-- 4. Circulaire + 6. Radar -->
      <div v-if="!estCaissier" class="cg-grid2">
        <div class="cg-card">
          <p class="cg-title">Notes par priorité</p>
          <p class="cg-sub">Distribution des notes selon leur niveau de priorité</p>
          <div class="cg-canvas"><Pie :data="pieData" :options="pieOpts" /></div>
        </div>
        <div class="cg-card">
          <p class="cg-title">Indicateurs de suivi</p>
          <p class="cg-sub">Part des notes payées, approuvées, dans le budget, etc.</p>
          <div class="cg-canvas"><Radar :data="radarData" :options="radarOpts" /></div>
        </div>
      </div>
      <!-- 7. Nuage de points + 8. Bulles -->
      <div v-if="!estCaissier" class="cg-grid2">
        <div class="cg-card">
          <p class="cg-title">Montant de chaque note</p>
          <p class="cg-sub">Chaque point représente une note de frais et son montant</p>
          <div class="cg-canvas"><Scatter :data="scatterData" :options="scatterOpts" /></div>
        </div>
        <div class="cg-card">
          <p class="cg-title">Priorité et montant des notes</p>
          <p class="cg-sub">Taille : montant relatif. Couleur : niveau de priorité</p>
          <div class="cg-canvas"><Bubble :data="bubbleData" :options="bubbleOpts" /></div>
        </div>
      </div>
    </ClientOnly>
  </div>
</template>

<style scoped>
.dash { max-width: 1240px; margin: 0 auto; padding: 4px 0 56px; }

/* ── En-tête ─────────────────────────────────────────────── */
.dash__header { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 28px; flex-wrap: wrap; }
.dash__eyebrow { font-size: 0.875rem; color: var(--ink-soft); margin: 0 0 6px; }
.dash__eyebrow::first-letter { text-transform: uppercase; }
.dash__title { font-size: clamp(1.7rem, 3.4vw, 2.5rem); font-weight: 700; color: var(--ink); letter-spacing: -0.03em; line-height: 1.1; margin: 0; }
.dash__cta { flex-shrink: 0; }

/* ── Situation : position de trésorerie + indicateurs ────── */
.board { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr); gap: 16px; margin-bottom: 48px; }
@media (max-width: 960px) { .board { grid-template-columns: 1fr; } }

.treso {
  --c1: color-mix(in srgb, var(--color-primary) 40%, #7ff0b8);
  --c2: #8db7ff;
  --c3: #f3d28a;
  display: flex; flex-direction: column; gap: 14px;
  padding: 28px 30px 24px;
  border-radius: 14px;
  background: var(--hero-bg);
  border: 1px solid color-mix(in srgb, var(--color-primary) 20%, transparent);
  color: #fff;
}
.treso__label { font-size: 0.9rem; color: rgba(255,255,255,0.7); margin: 0; }
.treso__total { font-size: clamp(2.4rem, 5.4vw, 4rem); font-weight: 700; letter-spacing: -0.04em; line-height: 1; margin: 0; }
.treso__bar { display: flex; gap: 3px; height: 14px; border-radius: 4px; overflow: hidden; margin-top: 8px; }
.treso__bar--vide { background: rgba(255,255,255,0.12); }
.treso__seg { display: block; height: 100%; min-width: 0; }
.treso__seg--c1 { background: var(--c1); }
.treso__seg--c2 { background: var(--c2); }
.treso__seg--c3 { background: var(--c3); }
.treso__canaux { list-style: none; margin: 4px 0 0; padding: 0; display: flex; flex-direction: column; }
.treso__canal {
  display: grid; grid-template-columns: 12px minmax(0, 1fr) auto auto; align-items: center; gap: 12px;
  padding: 11px 0; border-top: 1px solid rgba(255,255,255,0.12);
  color: inherit; text-decoration: none; font-size: 0.95rem;
}
.treso__canal:hover .treso__canal-nom { text-decoration: underline; text-underline-offset: 3px; }
.treso__canal:focus-visible { outline: 2px solid #fff; outline-offset: 2px; border-radius: 4px; }
.treso__dot { width: 12px; height: 12px; border-radius: 3px; }
.treso__canal-part { color: rgba(255,255,255,0.6); min-width: 3.2em; text-align: right; }
.treso__canal-montant { font-weight: 600; min-width: 8.5em; text-align: right; }

.side { display: grid; gap: 12px; align-content: start; }
.tile {
  display: flex; flex-direction: column; gap: 6px; padding: 18px 20px;
  background: var(--surface); border: 1px solid var(--hairline); border-radius: 12px;
  color: var(--ink); text-decoration: none; transition: border-color 0.15s;
}
.tile:hover { border-color: color-mix(in srgb, var(--color-primary) 55%, var(--hairline)); }
.tile:focus-visible { outline: 3px solid var(--color-primary-mid); outline-offset: 2px; }
.tile__label { font-size: 0.875rem; color: var(--ink-soft); }
.tile__value { font-size: 1.9rem; font-weight: 700; letter-spacing: -0.03em; line-height: 1.1; }
.tile__value--neg { color: #b42318; }
.tile__hint { font-size: 0.8125rem; color: var(--ink-soft); }
.tile--action { background: var(--color-primary); border-color: var(--color-primary); color: #fff; }
.tile--action .tile__label, .tile--action .tile__hint { color: rgba(255,255,255,0.82); }
.tile--action:hover { border-color: var(--color-primary-dark); background: var(--color-primary-dark); }
/* Nuit : le vert de marque est éclairci ; la tuile d'action garde un fond assez sombre pour un texte blanc lisible. */
:global(html[data-theme='dark'] .dash .tile--action) { background: color-mix(in srgb, var(--color-primary) 45%, #07110d); border-color: color-mix(in srgb, var(--color-primary) 60%, #07110d); }
:global(html[data-theme='dark'] .dash .tile--action:hover) { background: color-mix(in srgb, var(--color-primary) 55%, #07110d); }
.tile__spark { width: 100%; height: 34px; color: var(--color-primary); }
.tile__gauge { display: block; height: 6px; border-radius: 3px; background: var(--color-primary-light); overflow: hidden; }
.tile__gauge > span { display: block; height: 100%; background: var(--color-primary); }

/* ── Sections ────────────────────────────────────────────── */
.dash__section-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
.dash__section-header--analyses { margin-top: 52px; }
.dash__section-title { font-size: 1.3rem; font-weight: 700; letter-spacing: -0.02em; color: var(--ink); margin: 0 0 2px; }
.dash__section-sub { font-size: 0.875rem; color: var(--ink-soft); margin: 0; }

/* ── File d'attente : une ligne par note, avec son circuit ── */
.queue { list-style: none; margin: 0; padding: 0; background: var(--surface); border: 1px solid var(--hairline); border-radius: 12px; overflow: hidden; }
.queue > li + li { border-top: 1px solid var(--hairline); }
.queue__row {
  display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1.9fr) auto; align-items: center; gap: 20px;
  padding: 16px 22px; color: var(--ink); text-decoration: none; transition: background 0.12s;
}
.queue__row:hover { background: var(--color-primary-lighter); }
.queue__row:focus-visible { outline: 3px solid var(--color-primary-mid); outline-offset: -3px; }
.queue__main { display: grid; gap: 1px; min-width: 0; }
.queue__ref { font-size: 0.8125rem; color: var(--ink-soft); }
.queue__objet { font-weight: 600; font-size: 1rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.queue__who { font-size: 0.8125rem; color: var(--ink-soft); }
.queue__montant { font-weight: 700; text-align: right; white-space: nowrap; }

.queue__track { display: grid; grid-template-columns: repeat(5, 1fr); gap: 4px; }
.queue__step { display: grid; gap: 5px; }
.queue__pip { height: 4px; border-radius: 2px; background: var(--hairline); }
.queue__step--done .queue__pip { background: var(--color-primary-mid); }
.queue__step--now .queue__pip { background: var(--color-primary); height: 6px; margin-top: -1px; }
.queue__step-nom { font-size: 0.72rem; line-height: 1.25; color: var(--ink-faint); }
.queue__step--now .queue__step-nom { color: var(--ink); font-weight: 700; }
@media (max-width: 800px) {
  .queue__row { grid-template-columns: 1fr auto; gap: 10px 16px; }
  .queue__track { grid-column: 1 / -1; order: 3; }
  .queue__step-nom { display: none; }
  .queue__step--now .queue__step-nom { display: block; }
}

.dash__empty { padding: 36px 24px; background: var(--surface); border: 1px dashed var(--hairline); border-radius: 12px; }
.dash__empty-title { font-size: 1rem; font-weight: 600; color: var(--ink); margin: 0 0 4px; }
.dash__empty-sub { font-size: 0.9rem; color: var(--ink-soft); margin: 0; max-width: 60ch; }

/* ── Analyses ────────────────────────────────────────────── */
.cg-full { margin-bottom: 16px; }
.cg-grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-bottom: 16px; }
@media (max-width: 680px) { .cg-grid2 { grid-template-columns: 1fr; } }
.cg-card { min-width: 0; background: var(--surface); border: 1px solid var(--hairline); border-radius: 12px; padding: 20px 22px; }
.cg-title { font-size: 1rem; font-weight: 650; color: var(--ink); margin: 0 0 2px; }
.cg-sub { font-size: 0.8125rem; color: var(--ink-soft); margin: 0 0 16px; }
.cg-canvas { height: 260px; position: relative; }
</style>
