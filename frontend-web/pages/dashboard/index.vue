<script setup lang="ts">
const rolesStore = useRolesStore()
import { useAuthStore } from '~/stores/auth'
import { Bar, Line, Pie, Doughnut, Radar, Scatter, Bubble } from 'vue-chartjs'

// Le Directeur metier (DIRECTEUR) n'a pas acces au tableau de bord
// (statistiques/graphiques globaux) : il ne voit que ses notes de frais.
// LOGISTIQUE non plus : son tableau de bord est /logistique (voir
// NavigationDrawer.vue) - celui-ci n'a rien de pertinent pour son metier
// (tresorerie, budgets, notes de frais).
definePageMeta({ titre: 'Tableau de bord', roles: ['ADMIN', 'DG', 'DA', 'DFIN', 'CAISSIER', 'COMPTABLE'] })

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

// Raccourcis Vente / Caisse : réservés au caissier. L'administrateur configure l'application mais ne vend ni n'encaisse
// (séparation des tâches, voir SeparationDesTachesTest) : le serveur et les pages Caisse et Nouvelle vente le lui refusent.
const peutOperer = computed(() => auth.hasRole('CAISSIER'))

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
    const balance = await api<any[]>(auth.hasAnyRole(['COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN']) ? '/caisse/balance' : '/caisse/soldes-tresorerie')
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
  const l = [{ id: 'caisse', libelle: 'Caisse', montant: tresorerie.value, to: versCaisseOuGrandLivre.value, cls: 'c1', icone: 'mdi-cash-register' }]
  if (permissions.peutVoir('BANQUE')) l.push({ id: 'banque', libelle: 'Banques', montant: tresorerieBanque.value, to: versBanqueOuGrandLivre.value, cls: 'c2', icone: 'mdi-bank-outline' })
  if (permissions.peutVoir('MOBILE_MONEY')) l.push({ id: 'mm', libelle: 'Mobile money', montant: tresorerieMobileMoney.value, to: versMobileMoneyOuGrandLivre.value, cls: 'c3', icone: 'mdi-cellphone-nfc' })
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
      <div class="dash__actions">
        <v-btn v-if="peutOperer && permissions.peutVoir('VENTES')" color="primary" variant="tonal" prepend-icon="mdi-cart-outline"
          elevation="0" to="/ventes/nouvelle" class="dash__cta">
          Vente
        </v-btn>
        <v-btn v-if="peutOperer && permissions.peutVoir('CAISSE')" color="primary" variant="tonal" prepend-icon="mdi-cash-register"
          elevation="0" to="/caisse" class="dash__cta">
          Caisse
        </v-btn>
        <v-btn color="primary" prepend-icon="mdi-plus" elevation="0" to="/notes-frais" class="dash__cta">
          Nouvelle note
        </v-btn>
      </div>
    </header>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-6" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <!-- ── Position de trésorerie + indicateurs ────────────── -->
    <section class="board" aria-label="Situation financière">
      <div class="treso">
        <p class="treso__label">Trésorerie disponible</p>
        <p class="treso__total">{{ fmtUSD(totalTresorerie) }}</p>

        <!-- 17/11 : un compartiment par canal (montant, part du total, jauge propre) plutôt qu'une barre fusionnée. -->
        <ul class="treso__canaux" :style="{ '--nb': canaux.length }">
          <li v-for="c in canaux" :key="c.id">
            <NuxtLink :to="c.to" :class="['treso__canal', `treso__canal--${c.cls}`, { 'treso__canal--neg': c.montant < 0 }]">
              <span class="treso__canal-tete">
                <span class="treso__canal-icone"><v-icon :icon="c.icone" size="18" /></span>
                <span class="treso__canal-nom">{{ c.libelle }}</span>
                <span class="treso__canal-part">{{ partCanal(c.montant) }}</span>
              </span>
              <span class="treso__canal-montant">{{ fmtUSD(c.montant) }}</span>
              <span class="treso__jauge" aria-hidden="true"><span :style="{ width: largeurCanal(c.montant) }" /></span>
            </NuxtLink>
          </li>
        </ul>
      </div>

      <div class="side">
        <NuxtLink :to="versNotesEnAttente" class="tile tile--action">
          <span class="tile__label"><span class="tile__pastille"><v-icon icon="mdi-file-document-multiple-outline" size="16" /></span>{{ auth.hasAnyRole(['DFIN', 'DA', 'CAISSIER']) ? 'À traiter par vous' : 'Notes dans le circuit' }}</span>
          <span class="tile__value">{{ notesEnAttenteCard }}</span>
          <span class="tile__hint">{{ payees }} note{{ payees !== 1 ? 's' : '' }} payée{{ payees !== 1 ? 's' : '' }} à ce jour</span>
        </NuxtLink>

        <NuxtLink v-if="!estCaissier" to="/balance" :class="['tile', 'tile--resultat', { 'tile--perte': resultatNet < 0 }]">
          <span class="tile__label"><span class="tile__pastille"><v-icon icon="mdi-chart-line-variant" size="16" /></span>Résultat net</span>
          <span :class="['tile__value', { 'tile__value--neg': resultatNet < 0 }]">{{ fmtUSD(resultatNet) }}</span>
          <svg v-if="sparkline" class="tile__spark" viewBox="0 0 100 30" preserveAspectRatio="none" aria-hidden="true">
            <defs>
              <linearGradient id="spark-trait" x1="0" x2="1" y1="0" y2="0">
                <stop offset="0" stop-color="var(--spark-a)" /><stop offset="1" stop-color="var(--spark-b)" />
              </linearGradient>
            </defs>
            <path :d="sparkline.ligne" fill="none" stroke="url(#spark-trait)" stroke-width="2.2" stroke-linecap="round"
              vector-effect="non-scaling-stroke" />
          </svg>
        </NuxtLink>

        <NuxtLink v-if="!estCaissier && budgetDisponible" to="/budgets" class="tile tile--budget">
          <span class="tile__label"><span class="tile__pastille"><v-icon icon="mdi-target" size="16" /></span>Budget exécuté en {{ new Date().getFullYear() }}</span>
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
              :class="['queue__step', `queue__step--e${i}`, { 'queue__step--done': i < etapeNote(n.statut), 'queue__step--now': i === etapeNote(n.statut) }]">
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
.dash {
  /* Palette (21/11/2026) : un accent par information, sur un fond d'aurore flou que les cartes en verre laissent voir. */
  --emeraude: #10b981; --menthe: #34d399; --ciel: #38bdf8; --indigo: #6366f1; --violet: #a855f7; --ambre: #f59e0b;
  --corail: #fb7185;
  position: relative; isolation: isolate;
  max-width: 1240px; margin: 0 auto; padding: 4px 0 56px;
}



/* ── En-tête ─────────────────────────────────────────────── */
.dash__header { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 28px; flex-wrap: wrap; }
.dash__eyebrow { font-size: 0.875rem; color: var(--ink-soft); margin: 0 0 6px; }
.dash__eyebrow::first-letter { text-transform: uppercase; }
.dash__title { font-size: clamp(1.7rem, 3.4vw, 2.5rem); font-weight: 700; color: var(--ink); letter-spacing: -0.03em; line-height: 1.1; margin: 0; }
.dash__actions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; }
.dash__cta { flex-shrink: 0; }

/* ── Situation : position de trésorerie + indicateurs ────── */
.board { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr); gap: 16px; margin-bottom: 48px; }
@media (max-width: 960px) { .board { grid-template-columns: 1fr; } }

.treso {
  --c1: #6ee7b7;
  --c2: #7dd3fc;
  --c3: #fcd34d;
  position: relative; overflow: hidden;
  display: flex; flex-direction: column; gap: 14px;
  padding: 28px 30px 24px;
  border-radius: 24px;
  /* Verre teinté : vert profond vers bleu nuit, avec un reflet en haut à gauche. */
  background:
    radial-gradient(120% 90% at 0% 0%, rgba(255, 255, 255, 0.16), transparent 45%),
    radial-gradient(80% 70% at 100% 100%, rgba(99, 102, 241, 0.45), transparent 60%),
    linear-gradient(135deg, color-mix(in srgb, var(--hero-bg) 88%, #0f766e) 0%, color-mix(in srgb, var(--hero-bg) 70%, #1e3a8a) 100%);
  border: 1px solid rgba(255, 255, 255, 0.16);
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.18) inset, 0 24px 48px -20px rgba(6, 40, 30, 0.55);
  color: #fff;
}
/* Halo lumineux qui traverse la carte : l'éclat du verre. */
.treso::after { content: ''; position: absolute; width: 380px; height: 380px; right: -120px; top: -160px; border-radius: 50%;
  background: radial-gradient(circle, rgba(52, 211, 153, 0.45), transparent 65%); pointer-events: none; }
.treso__label { font-size: 0.9rem; color: rgba(255,255,255,0.7); margin: 0; }
.treso__total { font-size: clamp(2.4rem, 5.4vw, 4rem); font-weight: 700; letter-spacing: -0.04em; line-height: 1; margin: 0; }
.treso__canaux { list-style: none; margin: 10px 0 0; padding: 0; display: grid; gap: 12px;
  grid-template-columns: repeat(var(--nb, 3), minmax(0, 1fr)); }
@media (max-width: 700px) { .treso__canaux { grid-template-columns: 1fr; } }
.treso__canal {
  --c: var(--c1);
  position: relative; display: flex; flex-direction: column; gap: 10px; height: 100%;
  padding: 14px 16px 16px; border-radius: 18px; overflow: hidden; z-index: 1;
  background: linear-gradient(160deg, rgba(255,255,255,0.16), rgba(255,255,255,0.05));
  -webkit-backdrop-filter: blur(14px) saturate(160%); backdrop-filter: blur(14px) saturate(160%);
  border: 1px solid rgba(255,255,255,0.18);
  box-shadow: 0 1px 0 rgba(255,255,255,0.22) inset;
  color: inherit; text-decoration: none;
  transition: transform 0.15s ease, border-color 0.15s ease, background 0.15s ease;
}
.treso__canal::before { content: ''; position: absolute; inset: auto -30% -60% auto; width: 140px; height: 140px; border-radius: 50%;
  background: radial-gradient(circle, color-mix(in srgb, var(--c) 45%, transparent), transparent 70%); pointer-events: none; }
.treso__canal--c2 { --c: var(--c2); }
.treso__canal--c3 { --c: var(--c3); }
.treso__canal:hover { transform: translateY(-2px); border-color: color-mix(in srgb, var(--c) 60%, transparent);
  background: linear-gradient(160deg, rgba(255,255,255,0.14), rgba(255,255,255,0.05)); }
.treso__canal:focus-visible { outline: 2px solid #fff; outline-offset: 2px; }
.treso__canal-tete { display: flex; align-items: center; gap: 8px; font-size: 0.85rem; }
.treso__canal-icone { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border-radius: 9px;
  color: var(--c); background: color-mix(in srgb, var(--c) 18%, transparent); flex-shrink: 0; }
.treso__canal-nom { flex: 1; min-width: 0; color: rgba(255,255,255,0.85); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.treso__canal-part { font-size: 0.72rem; font-weight: 700; padding: 2px 8px; border-radius: 999px;
  color: var(--c); background: color-mix(in srgb, var(--c) 16%, transparent); font-variant-numeric: tabular-nums; }
.treso__canal-montant { font-size: 1.35rem; font-weight: 700; letter-spacing: -0.02em; line-height: 1.1; font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere; }
.treso__canal--neg .treso__canal-montant { color: #ffb4b4; }
.treso__jauge { display: block; height: 5px; border-radius: 999px; background: rgba(255,255,255,0.10); overflow: hidden; margin-top: auto; }
.treso__jauge span { display: block; height: 100%; border-radius: 999px;
  background: linear-gradient(90deg, color-mix(in srgb, var(--c) 70%, #fff), var(--c));
  box-shadow: 0 0 10px color-mix(in srgb, var(--c) 70%, transparent); transition: width 0.6s cubic-bezier(.2,.8,.2,1); }

.side { display: grid; gap: 12px; align-content: start; }
.tile {
  --accent: var(--ciel);
  position: relative; overflow: hidden;
  display: flex; flex-direction: column; gap: 6px; padding: 18px 20px;
  background: var(--verre);
  -webkit-backdrop-filter: var(--flou); backdrop-filter: var(--flou);
  border: 1px solid var(--verre-bord); border-radius: 22px; box-shadow: var(--verre-ombre);
  color: var(--ink); text-decoration: none; transition: transform 0.18s ease, box-shadow 0.18s ease;
}
/* Lueur de la couleur de la tuile, dans le coin. */
.tile::before { content: ''; position: absolute; width: 180px; height: 180px; right: -60px; top: -70px; border-radius: 50%;
  background: radial-gradient(circle, color-mix(in srgb, var(--accent) 38%, transparent), transparent 70%); pointer-events: none; }
.tile:hover { transform: translateY(-2px); box-shadow: var(--verre-ombre), 0 18px 36px -18px color-mix(in srgb, var(--accent) 60%, transparent); }
.tile__pastille { display: inline-flex; align-items: center; justify-content: center; width: 26px; height: 26px; margin-right: 8px;
  border-radius: 8px; color: #fff; vertical-align: middle;
  background: linear-gradient(135deg, color-mix(in srgb, var(--accent) 75%, #fff), var(--accent));
  box-shadow: 0 4px 10px -3px color-mix(in srgb, var(--accent) 70%, transparent); }
.tile--resultat { --accent: var(--indigo); --spark-a: var(--ciel); --spark-b: var(--violet); }
.tile--resultat.tile--perte { --accent: var(--corail); --spark-a: var(--ambre); --spark-b: var(--corail); }
.tile--budget { --accent: var(--ambre); }
.tile:focus-visible { outline: 3px solid var(--color-primary-mid); outline-offset: 2px; }
.tile__label { position: relative; display: flex; align-items: center; font-size: 0.875rem; color: var(--ink-soft); }
.tile__value { position: relative; font-size: 1.9rem; font-weight: 700; letter-spacing: -0.03em; line-height: 1.1; font-variant-numeric: tabular-nums; }
.tile__value--neg { color: #b42318; }
.tile__hint { font-size: 0.8125rem; color: var(--ink-soft); }
.tile--action { --accent: #fff; color: #fff; border-color: rgba(255,255,255,0.28);
  background:
    radial-gradient(120% 100% at 0% 0%, rgba(255,255,255,0.22), transparent 50%),
    linear-gradient(135deg, var(--emeraude), #0d9488 55%, #0891b2); }
.tile--action .tile__label, .tile--action .tile__hint { color: rgba(255,255,255,0.88); }
.tile--action .tile__pastille { background: rgba(255,255,255,0.22); box-shadow: 0 1px 0 rgba(255,255,255,0.35) inset; }
/* Nuit : le vert de marque est éclairci ; la tuile d'action garde un fond assez sombre pour un texte blanc lisible. */
:global(html[data-theme='dark'] .dash .tile--action) { background: linear-gradient(135deg, #047857, #0f766e 55%, #155e75); }
.tile__spark { position: relative; width: 100%; height: 38px; }
.tile__gauge { position: relative; display: block; height: 8px; border-radius: 999px; overflow: hidden;
  background: color-mix(in srgb, var(--accent) 16%, transparent); }
.tile__gauge > span { display: block; height: 100%; border-radius: 999px; background: linear-gradient(90deg, #fcd34d, var(--ambre), #f97316); }

/* ── Sections ────────────────────────────────────────────── */
.dash__section-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
.dash__section-header--analyses { margin-top: 52px; }
.dash__section-title { font-size: 1.3rem; font-weight: 700; letter-spacing: -0.02em; color: var(--ink); margin: 0 0 2px; }
.dash__section-sub { font-size: 0.875rem; color: var(--ink-soft); margin: 0; }

/* ── File d'attente : une ligne par note, avec son circuit ── */
.queue { list-style: none; margin: 0; padding: 0; overflow: hidden;
  background: var(--verre); -webkit-backdrop-filter: var(--flou); backdrop-filter: var(--flou);
  border: 1px solid var(--verre-bord); border-radius: 22px; box-shadow: var(--verre-ombre); }
.queue > li + li { border-top: 1px solid color-mix(in srgb, var(--hairline) 70%, transparent); }
.queue__row {
  display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1.9fr) auto; align-items: center; gap: 20px;
  padding: 16px 22px; color: var(--ink); text-decoration: none; transition: background 0.12s;
}
.queue__row:hover { background: color-mix(in srgb, var(--verre-fort) 70%, transparent); }
.queue__row:focus-visible { outline: 3px solid var(--color-primary-mid); outline-offset: -3px; }
.queue__main { display: grid; gap: 1px; min-width: 0; }
.queue__ref { font-size: 0.8125rem; color: var(--ink-soft); }
.queue__objet { font-weight: 600; font-size: 1rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.queue__who { font-size: 0.8125rem; color: var(--ink-soft); }
.queue__montant { font-weight: 700; text-align: right; white-space: nowrap; }

.queue__track { display: grid; grid-template-columns: repeat(5, 1fr); gap: 4px; }
/* Une couleur par étape du circuit, comme le widget Android : soumise, DFIN, DA, caisse, payée. */
.queue__step { --e: var(--emeraude); display: grid; gap: 5px; }
.queue__step--e0 { --e: var(--violet); }
.queue__step--e1 { --e: #3b82f6; }
.queue__step--e2 { --e: var(--emeraude); }
.queue__step--e3 { --e: var(--ambre); }
.queue__step--e4 { --e: #059669; }
.queue__pip { height: 5px; border-radius: 999px; background: color-mix(in srgb, var(--hairline) 80%, transparent); }
.queue__step--done .queue__pip { background: color-mix(in srgb, var(--e) 55%, transparent); }
.queue__step--now .queue__pip { background: var(--e); height: 7px; margin-top: -1px; box-shadow: 0 0 10px color-mix(in srgb, var(--e) 65%, transparent); }
.queue__step-nom { font-size: 0.72rem; line-height: 1.25; color: var(--ink-faint); }
.queue__step--now .queue__step-nom { color: color-mix(in srgb, var(--e) 70%, var(--ink)); font-weight: 700; }
@media (max-width: 800px) {
  .queue__row { grid-template-columns: 1fr auto; gap: 10px 16px; }
  .queue__track { grid-column: 1 / -1; order: 3; }
  .queue__step-nom { display: none; }
  .queue__step--now .queue__step-nom { display: block; }
}

.dash__empty { padding: 36px 24px; background: var(--verre); -webkit-backdrop-filter: var(--flou); backdrop-filter: var(--flou);
  border: 1px dashed var(--verre-bord); border-radius: 22px; box-shadow: var(--verre-ombre); }
.dash__empty-title { font-size: 1rem; font-weight: 600; color: var(--ink); margin: 0 0 4px; }
.dash__empty-sub { font-size: 0.9rem; color: var(--ink-soft); margin: 0; max-width: 60ch; }

/* ── Analyses ────────────────────────────────────────────── */
.cg-full { margin-bottom: 16px; }
.cg-grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-bottom: 16px; }
@media (max-width: 680px) { .cg-grid2 { grid-template-columns: 1fr; } }
.cg-card { min-width: 0; padding: 20px 22px; background: var(--verre); -webkit-backdrop-filter: var(--flou); backdrop-filter: var(--flou);
  border: 1px solid var(--verre-bord); border-radius: 22px; box-shadow: var(--verre-ombre); }
.cg-title { font-size: 1rem; font-weight: 650; color: var(--ink); margin: 0 0 2px; }
.cg-sub { font-size: 0.8125rem; color: var(--ink-soft); margin: 0 0 16px; }
.cg-canvas { height: 260px; position: relative; }
</style>
