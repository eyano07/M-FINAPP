<script setup lang="ts">
import { Bar, Line, Doughnut } from 'vue-chartjs'
import { Chart as ChartJS } from 'chart.js'

definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] })

const route = useRoute()
const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()
const id = computed(() => Number(route.params.id))

const budget = ref<any | null>(null)
const suivi = ref<any | null>(null)
const loading = ref(false)
const action = ref('')
const erreur = ref('')
const succes = ref('')

const estDfin = computed(() => auth.hasAnyRole(['DFIN', 'ADMIN']))
const estDa = computed(() => auth.hasAnyRole(['DA', 'ADMIN']))

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [b, s] = await Promise.all([api<any>(`/budgets/${id.value}`), api<any>(`/budgets/${id.value}/suivi`)])
    budget.value = b
    suivi.value = s
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Budget introuvable.')
  } finally {
    loading.value = false
  }
}
// Chart.js ne se redimensionne pas de lui-même pour le papier : avant l'impression, chaque graphique est ramené
// à une demi-largeur de page A4 paysage (deux graphiques par rangée), puis rendu à sa taille d'écran après.
function graphiquesPourImpression() {
  for (const c of Object.values(ChartJS.instances)) c.resize(440, 165)
}
function graphiquesPourEcran() {
  for (const c of Object.values(ChartJS.instances)) c.resize()
}
onMounted(() => {
  charger()
  parametresStore.charger()
  document.documentElement.classList.add('print-paysage')
  window.addEventListener('beforeprint', graphiquesPourImpression)
  window.addEventListener('afterprint', graphiquesPourEcran)
})
onUnmounted(() => {
  document.documentElement.classList.remove('print-paysage')
  window.removeEventListener('beforeprint', graphiquesPourImpression)
  window.removeEventListener('afterprint', graphiquesPourEcran)
})

// ---------------------------------------------------------------------------------------------
// Circuit
// ---------------------------------------------------------------------------------------------
const dialogueMotif = ref<'' | 'rejeter' | 'approuver'>('')
const motif = ref('')

async function transition(chemin: string, corps: any = {}) {
  action.value = chemin
  erreur.value = ''
  succes.value = ''
  try {
    const b = await api<any>(`/budgets/${id.value}/${chemin}`, { method: 'POST', body: corps })
    if (chemin === 'reviser') {
      await navigateTo(`/budgets/${b.id}/modifier`)
      return
    }
    succes.value = {
      soumettre: 'Budget soumis au DA pour approbation.',
      approuver: 'Budget approuvé : le DFIN peut le mettre en exécution.',
      rejeter: 'Budget rejeté : le DFIN peut le reprendre et le corriger.',
      reprendre: 'Budget repris en brouillon : il peut être modifié puis soumis à nouveau.',
      demarrer: 'Budget en exécution : il contrôle désormais les notes de frais de l’exercice.',
      cloturer: 'Budget clôturé : il reste consultable.',
    }[chemin] || ''
    dialogueMotif.value = ''
    motif.value = ''
    await charger()
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Action impossible.')
  } finally {
    action.value = ''
  }
}

const confirmationSuppression = ref(false)
async function supprimer() {
  action.value = 'supprimer'
  try {
    await api(`/budgets/${id.value}`, { method: 'DELETE' })
    await navigateTo('/budgets')
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Suppression impossible.')
  } finally {
    action.value = ''
    confirmationSuppression.value = false
  }
}

const pdfEnCours = ref(false)
async function telechargerPdf() {
  pdfEnCours.value = true
  try {
    await telechargerFichier(api, `/budgets/${id.value}/pdf`, `budget-${budget.value?.reference || id.value}.pdf`)
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Le PDF n’a pas pu être généré.')
  } finally {
    pdfEnCours.value = false
  }
}
const imprimer = () => window.print()
const dateImpression = new Date().toLocaleDateString('fr-FR')
const fmtDate = (d?: string | null) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')

// ---------------------------------------------------------------------------------------------
// Indicateurs
// ---------------------------------------------------------------------------------------------
const section = (code: string) => suivi.value?.sections?.find((s: any) => s.code === code) || null
const somme = (l: any[] | undefined, k: string) => centimes((l || []).reduce((t: number, x: any) => t + Number(x[k] || 0), 0))
const horsBudgetCharges = computed(() => somme(suivi.value?.horsBudget?.filter((h: any) => h.section !== 'PRODUITS'), 'realiseAnnuel'))
const moisEcoules = computed(() => suivi.value?.moisEcoules ?? 0)

const indicateurs = computed(() => {
  const p = section('PRODUITS')
  const c = section('CHARGES')
  const i = section('INVESTISSEMENTS')
  const r = suivi.value?.resultat
  return [
    { titre: 'Produits', prevu: p?.prevuAnnuel, realise: p?.realiseAnnuel, taux: p?.tauxExecution, couleur: SECTIONS.PRODUITS.color, icone: 'mdi-trending-up' },
    { titre: 'Charges', prevu: c?.prevuAnnuel, realise: c?.realiseAnnuel, engage: c?.engageAnnuel, taux: c?.tauxExecution, couleur: SECTIONS.CHARGES.color, icone: 'mdi-trending-down' },
    { titre: 'Résultat', prevu: r?.prevuAnnuel, realise: r?.realiseAnnuel, couleur: '#7c3aed', icone: 'mdi-scale-balance' },
    { titre: 'Investissements', prevu: i?.prevuAnnuel, realise: i?.realiseAnnuel, engage: i?.engageAnnuel, taux: i?.tauxExecution, couleur: SECTIONS.INVESTISSEMENTS.color, icone: 'mdi-domain' },
  ]
})

// ---------------------------------------------------------------------------------------------
// Graphiques
// ---------------------------------------------------------------------------------------------
const sectionGraphique = ref('CHARGES')
const sectionsDisponibles = computed(() => (suivi.value?.sections || []).map((s: any) => ({ value: s.code, title: s.libelle })))
watch(sectionsDisponibles, (liste) => {
  if (liste.length && !liste.some((s: any) => s.value === sectionGraphique.value)) sectionGraphique.value = liste[0].value
})

const dataMensuel = computed(() => {
  const s = section(sectionGraphique.value)
  return {
    labels: MOIS_COURTS,
    datasets: [
      { label: 'Prévu', data: (s?.prevu || []).map(Number), backgroundColor: 'rgba(148, 163, 184, 0.55)', borderRadius: 4 },
      { label: 'Réalisé', data: (s?.realise || []).map(Number), backgroundColor: SECTIONS[sectionGraphique.value]?.color || '#2563eb', borderRadius: 4 },
      { label: 'Engagé', data: (s?.engage || []).map(Number), backgroundColor: 'rgba(245, 158, 11, 0.75)', borderRadius: 4 },
    ],
  }
})

const dataCumul = computed(() => {
  const s = section(sectionGraphique.value)
  const cumuler = (m: any[]) => m.map((_: any, i: number) => cumul(m, i + 1))
  const realise = cumuler(s?.realise || [])
  return {
    labels: MOIS_COURTS,
    datasets: [
      { label: 'Prévu cumulé', data: cumuler(s?.prevu || []), borderColor: '#94a3b8', backgroundColor: 'rgba(148,163,184,0.15)', fill: true, tension: 0.25, pointRadius: 2 },
      // Le réalisé s'arrête au mois en cours : au-delà, rien n'est encore réalisé.
      { label: 'Réalisé cumulé', data: realise.map((v: number, i: number) => (i < Math.max(moisEcoules.value, 1) ? v : null)), borderColor: SECTIONS[sectionGraphique.value]?.color || '#2563eb', tension: 0.25, pointRadius: 3 },
    ],
  }
})

const PALETTE = ['#2563eb', '#16a34a', '#dc2626', '#f59e0b', '#7c3aed', '#0891b2', '#db2777', '#65a30d', '#ea580c', '#475569', '#0d9488', '#9333ea']
const dataNatures = computed(() => {
  const natures = (suivi.value?.natures || []).filter((n: any) => n.section === sectionGraphique.value && Number(n.prevuAnnuel) > 0)
  return {
    labels: natures.map((n: any) => `${n.code} ${n.libelle}`),
    datasets: [{ data: natures.map((n: any) => Number(n.prevuAnnuel)), backgroundColor: natures.map((_: any, i: number) => PALETTE[i % PALETTE.length]) }],
  }
})

/** Écarts à date (réalisé - prévu cumulés jusqu'au mois en cours), les plus importants en valeur absolue. */
const dataEcarts = computed(() => {
  const lignes = (suivi.value?.lignes || [])
    .filter((l: any) => l.section === sectionGraphique.value && Number(l.ecartADate) !== 0)
    .sort((a: any, b: any) => Math.abs(Number(b.ecartADate)) - Math.abs(Number(a.ecartADate)))
    .slice(0, 8)
  return {
    labels: lignes.map((l: any) => `${l.compteNumero} ${l.compteLibelle}`.slice(0, 38)),
    datasets: [{
      label: 'Écart à date (réalisé − prévu)',
      data: lignes.map((l: any) => Number(l.ecartADate)),
      backgroundColor: lignes.map((l: any) => (ecartFavorable(l.section, Number(l.ecartADate)) ? '#16a34a' : '#dc2626')),
      borderRadius: 4,
      maxBarThickness: 26,
    }],
  }
})

const optsBarres = { responsive: true, maintainAspectRatio: false, plugins: { legend: { position: 'bottom' as const } }, scales: { y: { ticks: { callback: (v: any) => fmtEntier(v) } } } }
const optsCourbe = { responsive: true, maintainAspectRatio: false, plugins: { legend: { position: 'bottom' as const } }, scales: { y: { ticks: { callback: (v: any) => fmtEntier(v) } } } }
const optsAnneau = { responsive: true, maintainAspectRatio: false, plugins: { legend: { position: 'right' as const, labels: { boxWidth: 12, font: { size: 11 } } } } }
const optsEcarts = { responsive: true, maintainAspectRatio: false, indexAxis: 'y' as const, plugins: { legend: { display: false } }, scales: { x: { ticks: { callback: (v: any) => fmtEntier(v) } } } }

// ---------------------------------------------------------------------------------------------
// Tableau détaillé
// ---------------------------------------------------------------------------------------------
const periode = ref<Periode>('TRIMESTRIEL')
const vue = ref<'prevu' | 'realise' | 'ecart'>('prevu')
const VUES = [
  { value: 'prevu', title: 'Prévu' },
  { value: 'realise', title: 'Réalisé' },
  { value: 'ecart', title: 'Écart (réalisé − prévu)' },
]
const colonnes = computed(() => colonnesPeriode(periode.value))

function serie(x: any): number[] {
  if (vue.value === 'prevu') return regrouper(x.prevu, periode.value)
  if (vue.value === 'realise') return regrouper(x.realise, periode.value)
  const p = regrouper(x.prevu, periode.value)
  return regrouper(x.realise, periode.value).map((v, i) => centimes(v - p[i]))
}

/** Lignes du tableau : section, puis pour chaque nature ses lignes et son sous-total. */
const blocs = computed(() => {
  if (!suivi.value) return []
  return suivi.value.sections.map((s: any) => ({
    section: s,
    natures: suivi.value.natures
      .filter((n: any) => n.section === s.code)
      .map((n: any) => ({ nature: n, lignes: suivi.value.lignes.filter((l: any) => l.section === s.code && l.nature === n.code) })),
  }))
})

const disponible = (x: any) => centimes(Number(x.prevuAnnuel) - Number(x.realiseAnnuel) - Number(x.engageAnnuel || 0))
const classeEcart = (section: string, v: number) => {
  const f = ecartFavorable(section, v)
  return f === null ? '' : f ? 'bs-favorable' : 'bs-defavorable'
}
</script>

<template>
  <div>
    <!-- En-tête imprimé -->
    <div class="etat-print-header">
      <div class="etat-print-header__brand">
        <div class="etat-print-header__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-finance" size="16" color="white" />
        </div>
        <div>
          <span class="etat-print-header__company">{{ parametresStore.parametres.nom }}</span>
          <span class="etat-print-header__doc">Budget prévisionnel {{ budget?.exercice }}</span>
        </div>
      </div>
      <div class="etat-print-header__meta">
        <span>Budget : <strong>{{ budget?.reference }} — {{ budget?.intitule }}</strong></span>
        <span>Statut : <strong>{{ statutBudget(budget?.statut).label }}</strong></span>
        <span>Montants : <strong>USD</strong> · réalisé au {{ fmtDate(suivi?.calculeLe) }}</span>
        <span>Imprimé le : {{ dateImpression }}</span>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4 no-print" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-skeleton-loader v-if="loading && !budget" type="heading, card, card" />

    <template v-if="budget && suivi">
      <!-- Titre et actions -->
      <div class="page-head no-print">
        <div style="min-width: 0">
          <h1 class="page-title">{{ budget.intitule }}</h1>
          <p class="page-sub">
            {{ budget.reference }} · exercice {{ budget.exercice }}
            <span v-if="budget.numeroRevision"> · révision n° {{ budget.numeroRevision }} de
              <NuxtLink :to="`/budgets/${budget.revisionDeId}`">{{ budget.revisionDeReference }}</NuxtLink></span>
          </p>
        </div>
        <div class="d-flex flex-wrap ga-2 justify-end">
          <v-chip :color="statutBudget(budget.statut).color" variant="tonal" :prepend-icon="statutBudget(budget.statut).icon">{{ statutBudget(budget.statut).label }}</v-chip>
          <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/budgets">Budgets</v-btn>
        </div>
      </div>

      <v-card class="classroom-card pa-3 mb-4 no-print bs-actions">
        <v-btn v-if="estDfin && budget.statut === 'BROUILLON'" color="primary" variant="tonal" prepend-icon="mdi-pencil" :to="`/budgets/${id}/modifier`">Modifier</v-btn>
        <v-btn v-if="estDfin && budget.statut === 'BROUILLON'" color="blue" prepend-icon="mdi-send" :loading="action === 'soumettre'" @click="transition('soumettre')">Soumettre au DA</v-btn>
        <v-btn v-if="estDa && budget.statut === 'SOUMIS'" color="success" prepend-icon="mdi-check-decagram" @click="dialogueMotif = 'approuver'">Approuver</v-btn>
        <v-btn v-if="estDa && budget.statut === 'SOUMIS'" color="error" variant="tonal" prepend-icon="mdi-close-octagon" @click="dialogueMotif = 'rejeter'">Rejeter</v-btn>
        <v-btn v-if="estDfin && budget.statut === 'REJETE'" color="primary" variant="tonal" prepend-icon="mdi-undo" :loading="action === 'reprendre'" @click="transition('reprendre')">Reprendre en brouillon</v-btn>
        <v-btn v-if="estDfin && budget.statut === 'APPROUVE'" color="teal" prepend-icon="mdi-play" :loading="action === 'demarrer'" @click="transition('demarrer')">Mettre en exécution</v-btn>
        <v-btn v-if="estDfin && budget.statut === 'EN_EXECUTION'" color="indigo" variant="tonal" prepend-icon="mdi-file-replace-outline" :loading="action === 'reviser'" @click="transition('reviser')">Réviser</v-btn>
        <v-btn v-if="estDfin && budget.statut === 'EN_EXECUTION' && budget.exercice < new Date().getFullYear()" color="brown" variant="tonal" prepend-icon="mdi-archive" :loading="action === 'cloturer'" @click="transition('cloturer')">Clôturer</v-btn>
        <v-btn v-if="estDfin && (budget.statut === 'BROUILLON' || budget.statut === 'REJETE')" color="error" variant="text" prepend-icon="mdi-delete-outline" @click="confirmationSuppression = true">Supprimer</v-btn>
        <v-spacer />
        <v-btn variant="tonal" prepend-icon="mdi-printer" @click="imprimer">Imprimer</v-btn>
        <v-btn variant="tonal" color="red-darken-2" prepend-icon="mdi-file-pdf-box" :loading="pdfEnCours" @click="telechargerPdf">Enregistrer en PDF</v-btn>
      </v-card>

      <!-- Informations -->
      <v-card class="classroom-card pa-4 mb-4">
        <div class="bs-infos">
          <div><span>Élaboré par</span><strong>{{ budget.elaboreParNom || '—' }}</strong><small>le {{ fmtDate(budget.dateCreation) }}</small></div>
          <div><span>Soumis</span><strong>{{ fmtDate(budget.dateSoumission) }}</strong></div>
          <div><span>Approuvé par</span><strong>{{ budget.approuveParNom || '—' }}</strong><small v-if="budget.dateApprobation">le {{ fmtDate(budget.dateApprobation) }}</small></div>
          <div><span>En exécution depuis</span><strong>{{ fmtDate(budget.dateExecution) }}</strong></div>
          <div><span>Réalisé calculé au</span><strong>{{ fmtDate(suivi.calculeLe) }}</strong><small>{{ moisEcoules }} mois écoulé(s)</small></div>
        </div>
        <p v-if="budget.observation" class="bs-observation"><v-icon icon="mdi-text" size="16" /> {{ budget.observation }}</p>
        <v-alert v-if="budget.motifRejet" type="error" variant="tonal" density="compact" class="mt-3" icon="mdi-close-octagon-outline">
          Motif du dernier rejet : {{ budget.motifRejet }}
        </v-alert>
        <v-alert v-for="(a, i) in suivi.avertissements" :key="i" type="warning" variant="tonal" density="compact" class="mt-2">{{ a }}</v-alert>
      </v-card>

      <!-- Indicateurs -->
      <div class="bs-kpis mb-4">
        <div v-for="k in indicateurs" :key="k.titre" class="bs-kpi" :style="{ borderTopColor: k.couleur }">
          <div class="bs-kpi__tete"><v-icon :icon="k.icone" size="18" :color="k.couleur" /> {{ k.titre }}</div>
          <div class="bs-kpi__ligne"><span>Prévu</span><strong>{{ fmtEntier(k.prevu) }}</strong></div>
          <div class="bs-kpi__ligne"><span>Réalisé</span><strong>{{ fmtEntier(k.realise) }}</strong></div>
          <div v-if="k.engage !== undefined" class="bs-kpi__ligne"><span>Engagé</span><strong>{{ fmtEntier(k.engage) }}</strong></div>
          <div v-if="k.taux !== undefined" class="bs-kpi__ligne"><span>Exécution</span><strong>{{ fmtTaux(k.taux) }}</strong></div>
          <v-progress-linear v-if="k.taux !== undefined" :model-value="Math.min(100, Number(k.taux || 0))" :color="Number(k.taux || 0) > 100 ? 'error' : 'primary'" height="6" rounded class="mt-2" />
        </div>
        <div class="bs-kpi" style="border-top-color: #f59e0b">
          <div class="bs-kpi__tete"><v-icon icon="mdi-alert-octagon-outline" size="18" color="#f59e0b" /> Hors budget</div>
          <div class="bs-kpi__ligne"><span>Dépenses non budgétées</span><strong>{{ fmtEntier(horsBudgetCharges) }}</strong></div>
          <div class="bs-kpi__ligne"><span>Notes justifiées hors budget</span><strong>{{ suivi.notesHorsBudget.length }}</strong></div>
        </div>
      </div>

      <!-- Graphiques -->
      <v-card class="classroom-card pa-4 mb-4 bs-analyse">
        <div class="d-flex align-center flex-wrap ga-3 mb-3">
          <span class="bs-titre">Analyse du budget</span>
          <v-btn-toggle v-model="sectionGraphique" mandatory density="compact" color="primary" variant="outlined" class="no-print">
            <v-btn v-for="s in sectionsDisponibles" :key="s.value" :value="s.value" size="small">{{ s.title }}</v-btn>
          </v-btn-toggle>
          <span class="text-caption text-medium-emphasis">USD — section : {{ libelleSection(sectionGraphique) }}</span>
        </div>
        <div class="bs-graphes">
          <div class="bs-graphe">
            <p class="bs-graphe__titre">Prévu, réalisé et engagé par mois</p>
            <div class="bs-graphe__zone"><Bar :data="dataMensuel" :options="optsBarres" /></div>
          </div>
          <div class="bs-graphe">
            <p class="bs-graphe__titre">Exécution cumulée depuis janvier</p>
            <div class="bs-graphe__zone"><Line :data="dataCumul" :options="optsCourbe" /></div>
          </div>
          <div class="bs-graphe">
            <p class="bs-graphe__titre">Répartition du prévu par nature SYSCOHADA</p>
            <div class="bs-graphe__zone"><Doughnut v-if="dataNatures.labels.length" :data="dataNatures" :options="optsAnneau" /><p v-else class="bs-vide">Rien de prévu dans cette section.</p></div>
          </div>
          <div class="bs-graphe">
            <p class="bs-graphe__titre">Principaux écarts à date (réalisé − prévu)</p>
            <div class="bs-graphe__zone"><Bar v-if="dataEcarts.labels.length" :data="dataEcarts" :options="optsEcarts" /><p v-else class="bs-vide">Aucun écart à date.</p></div>
          </div>
        </div>
      </v-card>

      <!-- Tableau détaillé -->
      <v-card class="classroom-card mb-4">
        <div class="d-flex align-center flex-wrap ga-3 pa-4 pb-2">
          <span class="bs-titre">Budget détaillé (USD)</span>
          <v-btn-toggle v-model="periode" mandatory density="compact" color="primary" variant="outlined" class="no-print">
            <v-btn v-for="p in PERIODES" :key="p.value" :value="p.value" size="small">{{ p.title }}</v-btn>
          </v-btn-toggle>
          <v-btn-toggle v-model="vue" mandatory density="compact" color="primary" variant="outlined" class="no-print">
            <v-btn v-for="v in VUES" :key="v.value" :value="v.value" size="small">{{ v.title }}</v-btn>
          </v-btn-toggle>
          <span class="bs-print-seul text-caption">
            Découpage {{ PERIODES.find(p => p.value === periode)?.title.toLowerCase() }} · {{ VUES.find(v => v.value === vue)?.title }}
          </span>
        </div>
        <div class="bs-table-wrap">
          <table class="bs-table">
            <thead>
              <tr>
                <th>Compte</th>
                <th>Libellé</th>
                <th v-for="c in colonnes" :key="c" class="num">{{ c }}</th>
                <th class="num">Prévu année</th>
                <th class="num">Réalisé</th>
                <th class="num">Engagé</th>
                <th class="num">Disponible</th>
                <th class="num">Taux</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="b in blocs" :key="b.section.code">
                <tr class="bs-section-ligne">
                  <td :colspan="7 + colonnes.length" :style="{ borderLeftColor: SECTIONS[b.section.code]?.color }">{{ b.section.libelle }}</td>
                </tr>
                <template v-for="n in b.natures" :key="n.nature.code">
                  <tr v-for="l in n.lignes" :key="l.id">
                    <td class="mono">{{ l.compteNumero }}</td>
                    <td>
                      {{ l.compteLibelle }}
                      <div v-if="l.commentaire" class="bs-commentaire">{{ l.commentaire }}</div>
                    </td>
                    <td v-for="(v, i) in serie(l)" :key="i" class="num" :class="vue === 'ecart' ? classeEcart(l.section, v) : ''">{{ fmtMontant(v) }}</td>
                    <td class="num">{{ fmtMontant(l.prevuAnnuel) }}</td>
                    <td class="num">{{ fmtMontant(l.realiseAnnuel) }}</td>
                    <td class="num">{{ fmtMontant(l.engageAnnuel) }}</td>
                    <td class="num" :class="Number(l.disponibleAnnuel) < 0 && l.section !== 'PRODUITS' ? 'bs-defavorable' : ''">{{ fmtMontant(l.disponibleAnnuel) }}</td>
                    <td class="num">{{ fmtTaux(l.tauxExecution) }}</td>
                  </tr>
                  <tr class="bs-sous-total">
                    <td class="mono">{{ n.nature.code }}</td>
                    <td>Total {{ n.nature.libelle }}</td>
                    <td v-for="(v, i) in serie(n.nature)" :key="i" class="num">{{ fmtMontant(v) }}</td>
                    <td class="num">{{ fmtMontant(n.nature.prevuAnnuel) }}</td>
                    <td class="num">{{ fmtMontant(n.nature.realiseAnnuel) }}</td>
                    <td class="num">{{ fmtMontant(n.nature.engageAnnuel) }}</td>
                    <td class="num">{{ fmtMontant(disponible(n.nature)) }}</td>
                    <td class="num">{{ fmtTaux(n.nature.tauxExecution) }}</td>
                  </tr>
                </template>
                <tr class="bs-total">
                  <td />
                  <td>TOTAL {{ b.section.libelle.toUpperCase() }}</td>
                  <td v-for="(v, i) in serie(b.section)" :key="i" class="num">{{ fmtMontant(v) }}</td>
                  <td class="num">{{ fmtMontant(b.section.prevuAnnuel) }}</td>
                  <td class="num">{{ fmtMontant(b.section.realiseAnnuel) }}</td>
                  <td class="num">{{ fmtMontant(b.section.engageAnnuel) }}</td>
                  <td class="num">{{ fmtMontant(disponible(b.section)) }}</td>
                  <td class="num">{{ fmtTaux(b.section.tauxExecution) }}</td>
                </tr>
              </template>
              <tr class="bs-resultat">
                <td />
                <td>RÉSULTAT (produits − charges)</td>
                <td v-for="(v, i) in serie(suivi.resultat)" :key="i" class="num">{{ fmtMontant(v) }}</td>
                <td class="num">{{ fmtMontant(suivi.resultat.prevuAnnuel) }}</td>
                <td class="num">{{ fmtMontant(suivi.resultat.realiseAnnuel) }}</td>
                <td colspan="3" />
              </tr>
            </tbody>
          </table>
        </div>
        <p class="bs-legende">
          Le réalisé provient du grand livre (pièces comptabilisées de l’exercice, hors à-nouveaux et clôture) ; l’engagé correspond
          aux notes de frais approuvées non encore payées ; disponible = prévu − réalisé − engagé. Sur une charge, un écart positif
          est un dépassement ; sur un produit, une recette supérieure à la prévision.
        </p>
      </v-card>

      <!-- Hors budget -->
      <v-card v-if="suivi.horsBudget.length" class="classroom-card mb-4">
        <div class="pa-4 pb-2 bs-titre">Dépenses et recettes hors budget</div>
        <p class="px-4 text-caption text-medium-emphasis">Comptes mouvementés pendant l’exercice qu’aucune ligne du budget ne couvre.</p>
        <v-table density="compact">
          <thead><tr><th>Compte</th><th>Libellé</th><th>Section</th><th class="text-end">Réalisé (USD)</th></tr></thead>
          <tbody>
            <tr v-for="h in suivi.horsBudget" :key="h.compteNumero">
              <td class="mono">{{ h.compteNumero }}</td>
              <td>{{ h.compteLibelle }}</td>
              <td>{{ libelleSection(h.section) }}</td>
              <td class="text-end">{{ fmtMontant(h.realiseAnnuel) }}</td>
            </tr>
          </tbody>
        </v-table>
      </v-card>

      <v-card v-if="suivi.notesHorsBudget.length" class="classroom-card mb-4">
        <div class="pa-4 pb-2 bs-titre">Notes de frais hors budget ou en dépassement</div>
        <v-table density="compact">
          <thead><tr><th>Note</th><th>Objet</th><th class="text-end">Montant</th><th>Contrôle</th><th>Justification</th><th>Statut</th></tr></thead>
          <tbody>
            <tr v-for="n in suivi.notesHorsBudget" :key="n.id">
              <td><NuxtLink :to="`/notes-frais/${n.id}`">{{ n.reference }}</NuxtLink></td>
              <td>{{ n.objet }}</td>
              <td class="text-end">{{ fmtMontant(n.montant) }} {{ n.devise }}</td>
              <td><v-chip size="x-small" :color="statutControle(n.statutBudget).color" variant="tonal">{{ statutControle(n.statutBudget).label }}</v-chip></td>
              <td class="text-caption">{{ n.justification }}</td>
              <td class="text-caption">{{ statutNoteMeta(n.statut).label }}</td>
            </tr>
          </tbody>
        </v-table>
      </v-card>

      <!-- Signatures (impression) -->
      <div class="bs-signatures">
        <div>
          <span>Élaboré par (Direction financière)</span>
          <strong>{{ budget.elaboreParNom || '' }}</strong>
          <div class="bs-signatures__ligne" />
          <small>Signature et cachet</small>
        </div>
        <div>
          <span>Approuvé par (Direction administrative)</span>
          <strong>{{ budget.approuveParNom || '' }}</strong>
          <div class="bs-signatures__ligne" />
          <small>Signature et cachet</small>
        </div>
      </div>
    </template>

    <!-- Approbation / rejet -->
    <v-dialog :model-value="!!dialogueMotif" max-width="520" @update:model-value="(v: boolean) => { if (!v) dialogueMotif = '' }">
      <v-card>
        <v-card-title>{{ dialogueMotif === 'rejeter' ? 'Rejeter le budget' : 'Approuver le budget' }}</v-card-title>
        <v-card-text>
          <v-textarea
            v-model="motif"
            :label="dialogueMotif === 'rejeter' ? 'Motif du rejet *' : 'Observation (facultatif)'"
            rows="3"
            auto-grow
            :hint="dialogueMotif === 'rejeter' ? 'Le DFIN verra ce motif et pourra reprendre le budget pour le corriger.' : ''"
            persistent-hint
          />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="dialogueMotif = ''">Annuler</v-btn>
          <v-btn
            :color="dialogueMotif === 'rejeter' ? 'error' : 'success'"
            :disabled="dialogueMotif === 'rejeter' && !motif.trim()"
            :loading="action === dialogueMotif"
            @click="transition(dialogueMotif, { commentaire: motif.trim() || null })"
          >
            {{ dialogueMotif === 'rejeter' ? 'Rejeter' : 'Approuver' }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="confirmationSuppression" max-width="460">
      <v-card>
        <v-card-title>Supprimer ce budget ?</v-card-title>
        <v-card-text>Le budget {{ budget?.reference }} et ses lignes seront définitivement supprimés.</v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="confirmationSuppression = false">Annuler</v-btn>
          <v-btn color="error" :loading="action === 'supprimer'" @click="supprimer">Supprimer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.bs-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.bs-infos { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 12px; }
.bs-infos span { display: block; font-size: 0.72rem; color: #6b7280; }
.bs-infos strong { display: block; font-size: 0.92rem; }
.bs-infos small { color: #6b7280; }
.bs-observation { margin: 12px 0 0; font-size: 0.86rem; color: #374151; }
.bs-kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 12px; }
.bs-kpi { background: #fff; border: 1px solid #eef0f3; border-top: 4px solid; border-radius: 12px; padding: 12px 14px; }
.bs-kpi__tete { font-weight: 700; font-size: 0.9rem; margin-bottom: 6px; display: flex; gap: 6px; align-items: center; }
.bs-kpi__ligne { display: flex; justify-content: space-between; font-size: 0.82rem; padding: 2px 0; }
.bs-kpi__ligne span { color: #6b7280; }
.bs-kpi__ligne strong { font-variant-numeric: tabular-nums; }
.bs-titre { font-weight: 700; font-size: 1rem; }
.bs-graphes { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.bs-graphe { border: 1px solid #f1f2f4; border-radius: 10px; padding: 10px; }
.bs-graphe__titre { font-size: 0.8rem; font-weight: 600; color: #374151; margin: 0 0 6px; }
.bs-graphe__zone { position: relative; height: 260px; }
.bs-vide { color: #9ca3af; font-size: 0.85rem; text-align: center; padding-top: 100px; }
.bs-table-wrap { overflow-x: auto; }
.bs-table { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
.bs-table th { background: #f8fafc; color: #6b7280; font-size: 0.7rem; text-transform: uppercase; padding: 8px; text-align: left; white-space: nowrap; }
.bs-table td { padding: 6px 8px; border-top: 1px solid #f1f2f4; }
.bs-table .num { text-align: right; white-space: nowrap; font-variant-numeric: tabular-nums; }
.bs-table .mono { font-family: ui-monospace, monospace; font-size: 0.78rem; }
.bs-section-ligne td { background: #eef2ff; font-weight: 800; text-transform: uppercase; font-size: 0.74rem; letter-spacing: 0.04em; border-left: 4px solid; }
.bs-sous-total td { background: #fafbfc; font-weight: 600; }
.bs-total td { background: #f1f5f9; font-weight: 800; }
.bs-resultat td { background: #ede9fe; font-weight: 800; }
.bs-commentaire { font-size: 0.72rem; color: #6b7280; }
.bs-favorable { color: #15803d; }
.bs-defavorable { color: #b91c1c; font-weight: 600; }
.bs-legende { font-size: 0.74rem; color: #6b7280; padding: 10px 16px 14px; margin: 0; }
.bs-signatures { display: none; }
.bs-print-seul { display: none; }

@media (max-width: 960px) {
  .bs-graphes { grid-template-columns: 1fr; }
}

@media print {
  .bs-print-seul { display: inline; }
  .bs-analyse { break-inside: avoid; }
  .bs-graphes { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .bs-graphe { break-inside: avoid; }
  .bs-graphe__zone { height: 170px; }
  .bs-graphe__zone canvas { max-width: 100% !important; }
  .bs-kpis { grid-template-columns: repeat(5, 1fr); }
  .bs-table { font-size: 0.66rem; }
  .bs-table td, .bs-table th { padding: 2px 4px; }
  .bs-table-wrap { overflow: visible; }
  .bs-table tr { break-inside: avoid; }
  .bs-legende { font-size: 0.62rem; }
  .bs-signatures { display: grid; grid-template-columns: 1fr 1fr; gap: 40px; margin-top: 18px; break-inside: avoid; }
  .bs-signatures span { display: block; font-weight: 700; font-size: 0.75rem; }
  .bs-signatures strong { display: block; font-size: 0.75rem; min-height: 1em; }
  .bs-signatures__ligne { border-bottom: 1px solid #9ca3af; height: 46px; }
  .bs-signatures small { font-size: 0.62rem; color: #6b7280; }
}
</style>
