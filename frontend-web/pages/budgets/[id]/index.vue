<script setup lang="ts">
import { useDisplay } from 'vuetify'
import { Bar, Line, Doughnut } from 'vue-chartjs'
import { Chart as ChartJS } from 'chart.js'

definePageMeta({ module: 'BUDGET', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] })

const route = useRoute()
const api = useApi()
const auth = useAuthStore()
// Libellés des rôles choisis par l'administrateur (ex. « DA » affiché « DAF ») : textes, boutons et confirmations.
const rolesStore = useRolesStore()
const lDA = computed(() => rolesStore.libelle('DA'))
const lDFIN = computed(() => rolesStore.libelle('DFIN'))
const parametresStore = useParametresStore()
const id = computed(() => Number(route.params.id))

const budget = ref<any | null>(null)
const suivi = ref<any | null>(null)
const loading = ref(false)
const action = ref('')
const erreur = ref('')
const succes = ref('')

const { xs, smAndDown } = useDisplay()
useHead({ title: computed(() => (budget.value ? `Budget ${budget.value.reference} — ${budget.value.intitule}` : 'Budget')) })

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
// Actions lourdes de conséquences : confirmation préalable.
const CONFIRMATIONS = computed<Record<string, { titre: string, texte: string, bouton: string, couleur: string }>>(() => ({
  soumettre: { titre: `Soumettre au ${lDA.value} ?`, texte: `Le budget ne sera plus modifiable tant que le ${lDA.value} ne l’aura pas approuvé ou rejeté.`, bouton: 'Soumettre', couleur: 'blue' },
  demarrer: { titre: 'Mettre ce budget en exécution ?', texte: `Il contrôlera désormais les notes de frais de « ${budget.value?.libellePeriode || ''} » : toute dépense non budgétée ou en dépassement (y compris d’un service sur son enveloppe) sera signalée.`, bouton: 'Mettre en exécution', couleur: 'teal' },
  cloturer: { titre: 'Clôturer ce budget ?', texte: 'Il ne contrôlera plus les notes de frais et restera consultable.', bouton: 'Clôturer', couleur: 'brown' },
}))
const actionAConfirmer = ref('')
function confirmerAction() {
  const chemin = actionAConfirmer.value
  actionAConfirmer.value = ''
  if (chemin) transition(chemin)
}

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
      soumettre: `Budget soumis au ${lDA.value} pour approbation.`,
      approuver: `Budget approuvé : le ${lDFIN.value} peut le mettre en exécution.`,
      rejeter: `Budget rejeté : le ${lDFIN.value} peut le reprendre et le corriger.`,
      reprendre: 'Budget repris en brouillon : il peut être modifié puis soumis à nouveau.',
      demarrer: `Budget en exécution : il contrôle désormais les notes de frais de « ${budget.value?.libellePeriode || ''} ».`,
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
// Période couverte
// ---------------------------------------------------------------------------------------------
const estAnnuel = computed(() => !budget.value?.duree || budget.value.duree === 'ANNUEL')
const moisDebut = computed(() => suivi.value?.moisDebut || budget.value?.moisDebut || 1)
const moisFin = computed(() => suivi.value?.moisFin || budget.value?.moisFin || 12)
const libellePeriodeBudget = computed(() => budget.value?.libellePeriode || `Exercice ${budget.value?.exercice}`)
const motDuree = computed(() => motPeriode(budget.value?.duree))
/** La période est terminée (lendemain de son dernier jour) : le budget peut être clôturé. */
const periodeTerminee = computed(() => !!budget.value && new Date() > new Date(budget.value.exercice, moisFin.value, 0, 23, 59, 59))
/** Mois de la période (indices 0-11), pour les graphiques. */
const moisPeriode = computed(() => Array.from({ length: moisFin.value - moisDebut.value + 1 }, (_, i) => moisDebut.value - 1 + i))

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
  const garder = (m: any[]) => moisPeriode.value.map(i => Number(m?.[i] || 0))
  return {
    labels: moisPeriode.value.map(i => MOIS_COURTS[i]),
    datasets: [
      { label: 'Prévu', data: garder(s?.prevu || []), backgroundColor: 'rgba(148, 163, 184, 0.55)', borderRadius: 4 },
      { label: 'Réalisé', data: garder(s?.realise || []), backgroundColor: SECTIONS[sectionGraphique.value]?.color || '#2563eb', borderRadius: 4 },
      { label: 'Engagé', data: garder(s?.engage || []), backgroundColor: 'rgba(245, 158, 11, 0.75)', borderRadius: 4 },
    ],
  }
})

const dataCumul = computed(() => {
  const s = section(sectionGraphique.value)
  const cumuler = (m: any[]) => moisPeriode.value.map(i => cumul(m, i + 1))
  const realise = cumuler(s?.realise || [])
  return {
    labels: moisPeriode.value.map(i => MOIS_COURTS[i]),
    datasets: [
      { label: 'Prévu cumulé', data: cumuler(s?.prevu || []), borderColor: '#94a3b8', backgroundColor: 'rgba(148,163,184,0.15)', fill: true, tension: 0.25, pointRadius: 2 },
      // Le réalisé s'arrête au mois en cours : au-delà, rien n'est encore réalisé.
      { label: 'Réalisé cumulé', data: realise.map((v: number, k: number) => (moisPeriode.value[k] < Math.max(moisEcoules.value, 1) ? v : null)), borderColor: SECTIONS[sectionGraphique.value]?.color || '#2563eb', tension: 0.25, pointRadius: 3 },
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
onMounted(() => { if (window.innerWidth < 960) periode.value = 'ANNUEL' })
const vue = ref<'prevu' | 'realise' | 'ecart'>('prevu')
const VUES = [
  { value: 'prevu', title: 'Prévu' },
  { value: 'realise', title: 'Réalisé' },
  { value: 'ecart', title: 'Écart (réalisé − prévu)' },
]
// Budget annuel : découpage au choix (mois, trimestres, semestres, année). Budget d'une période plus courte : ses
// mois (aucune colonne de détail pour un seul mois, le total suffit).
const colonnes = computed(() => (estAnnuel.value ? colonnesPeriode(periode.value)
  : moisFin.value > moisDebut.value ? moisPeriode.value.map(i => MOIS_COURTS[i]) : []))
function decouper(m: any[]): number[] {
  if (estAnnuel.value) return regrouper(m, periode.value)
  return moisFin.value > moisDebut.value ? moisPeriode.value.map(i => centimes(Number(m?.[i] || 0))) : []
}

function serie(x: any): number[] {
  if (vue.value === 'prevu') return decouper(x.prevu)
  if (vue.value === 'realise') return decouper(x.realise)
  const p = decouper(x.prevu)
  return decouper(x.realise).map((v, i) => centimes(v - p[i]))
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

// ---------------------------------------------------------------------------------------------
// Services et dépassements
// ---------------------------------------------------------------------------------------------
const services = computed<any[]>(() => suivi.value?.services || [])
const serviceOuvert = ref<number | string | null>(null)
const cleService = (s: any) => s.serviceId ?? 'GENERAL'
function basculerService(s: any) {
  serviceOuvert.value = serviceOuvert.value === cleService(s) ? null : cleService(s)
}
const depassementsServices = computed<any[]>(() => (suivi.value?.depassements || []).filter((d: any) => d.niveau === 'SERVICE'))
const depassementsConsolides = computed<any[]>(() => (suivi.value?.depassements || []).filter((d: any) => d.niveau === 'CONSOLIDE'))
const montantDepassementConsolide = computed(() => somme(depassementsConsolides.value, 'montant'))
const servicesEnDepassement = computed(() => services.value.filter((s: any) => s.serviceId !== null && Number(s.depassement) > 0))
const nomEnveloppe = (d: any) => (d.niveau === 'CONSOLIDE' ? 'Budget consolidé' : d.serviceId === null ? 'Lignes générales' : d.serviceNom)
function allerAuxDepassements() {
  document.getElementById('bs-depassements')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
const classeTaux = (section: string, t: unknown) => (t !== null && t !== undefined && Number(t) > 100 && section !== 'PRODUITS' ? 'bs-defavorable' : '')
const classeEcart = (section: string, v: number) => {
  const f = ecartFavorable(section, v)
  return f === null ? '' : f ? 'bs-favorable' : 'bs-defavorable'
}
</script>

<template>
  <div>
    <!-- En-tête imprimé -->
    <div class="etat-print-header print-entete">
      <div class="etat-print-header__brand print-entete__marque">
        <div class="etat-print-header__logo print-entete__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-finance" size="16" color="white" />
        </div>
        <div>
          <span class="etat-print-header__company print-entete__nom">{{ parametresStore.parametres.nom }}</span>
          <span class="etat-print-header__doc">Budget prévisionnel — {{ libellePeriodeBudget }}</span>
        </div>
      </div>
      <div class="etat-print-header__meta print-entete__meta">
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
            {{ budget.reference }} · <v-icon icon="mdi-calendar-range" size="14" /> {{ libellePeriodeBudget }}<template v-if="!estAnnuel"> ({{ DUREES.find(d => d.value === budget.duree)?.title.split(' ')[0].toLowerCase() }})</template>
            <template v-if="budget.parService"> · budget par service</template>
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
        <v-btn v-if="estDfin && budget.statut === 'BROUILLON'" color="blue" prepend-icon="mdi-send" :loading="action === 'soumettre'" @click="actionAConfirmer = 'soumettre'">Soumettre au {{ lDA }}</v-btn>
        <v-btn v-if="estDa && budget.statut === 'SOUMIS'" color="success" prepend-icon="mdi-check-decagram" @click="dialogueMotif = 'approuver'">Approuver</v-btn>
        <v-btn v-if="estDa && budget.statut === 'SOUMIS'" color="error" variant="tonal" prepend-icon="mdi-close-octagon" @click="dialogueMotif = 'rejeter'">Rejeter</v-btn>
        <v-btn v-if="estDfin && budget.statut === 'REJETE'" color="primary" variant="tonal" prepend-icon="mdi-undo" :loading="action === 'reprendre'" @click="transition('reprendre')">Reprendre en brouillon</v-btn>
        <v-btn v-if="estDfin && budget.statut === 'APPROUVE'" color="teal" prepend-icon="mdi-play" :loading="action === 'demarrer'" @click="actionAConfirmer = 'demarrer'">Mettre en exécution</v-btn>
        <v-btn v-if="estDfin && budget.statut === 'EN_EXECUTION'" color="indigo" variant="tonal" prepend-icon="mdi-file-replace-outline" :loading="action === 'reviser'" @click="transition('reviser')">Réviser</v-btn>
        <v-btn v-if="estDfin && budget.statut === 'EN_EXECUTION' && periodeTerminee" color="brown" variant="tonal" prepend-icon="mdi-archive" :loading="action === 'cloturer'" @click="actionAConfirmer = 'cloturer'">Clôturer</v-btn>
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
          <div><span>Réalisé calculé au</span><strong>{{ fmtDate(suivi.calculeLe) }}</strong><small>{{ moisEcoules }} mois écoulé(s) de l’exercice</small></div>
          <div><span>Période couverte</span><strong>{{ libellePeriodeBudget }}</strong><small>{{ MOIS_LONGS[moisDebut - 1] }}<template v-if="moisFin !== moisDebut"> à {{ MOIS_LONGS[moisFin - 1].toLowerCase() }}</template> {{ budget.exercice }}</small></div>
        </div>
        <p v-if="budget.observation" class="bs-observation"><v-icon icon="mdi-text" size="16" /> {{ budget.observation }}</p>
        <v-alert v-if="budget.motifRejet" type="error" variant="tonal" density="compact" class="mt-3" icon="mdi-close-octagon-outline">
          Motif du dernier rejet : {{ budget.motifRejet }}
        </v-alert>
        <v-alert v-for="(a, i) in suivi.avertissements" :key="i" type="warning" variant="tonal" density="compact" class="mt-2">{{ a }}</v-alert>
      </v-card>

      <v-alert v-if="suivi.depassements?.length" type="error" variant="tonal" class="mb-4" icon="mdi-alert-octagon-outline" border="start">
        <div class="font-weight-bold mb-1">Dépassement budgétaire</div>
        <div v-if="montantDepassementConsolide > 0">
          Budget consolidé : {{ fmtMontant(montantDepassementConsolide) }} USD de dépassement sur {{ depassementsConsolides.length }}
          ligne{{ depassementsConsolides.length > 1 ? 's' : '' }} (réalisé + engagé au-delà du prévu de la période).
        </div>
        <div v-if="servicesEnDepassement.length">
          Service{{ servicesEnDepassement.length > 1 ? 's' : '' }} en dépassement :
          <template v-for="(sv, i) in servicesEnDepassement" :key="sv.serviceId">{{ i ? ', ' : '' }}<strong>{{ sv.nom }}</strong> ({{ sv.responsable }}, {{ fmtMontant(sv.depassement) }} USD)</template>.
        </div>
        <v-btn size="small" variant="text" color="error" class="mt-1 no-print" prepend-icon="mdi-arrow-down" @click="allerAuxDepassements">Voir le détail</v-btn>
      </v-alert>

      <!-- Indicateurs -->
      <div class="bs-kpis mb-4">
        <div v-for="k in indicateurs" :key="k.titre" class="bs-kpi" :style="{ borderTopColor: k.couleur }">
          <div class="bs-kpi__tete"><v-icon :icon="k.icone" size="18" :color="k.couleur" /> {{ k.titre }}</div>
          <div class="bs-kpi__ligne"><span>Prévu</span><strong>{{ fmtEntier(k.prevu) }}</strong></div>
          <div class="bs-kpi__ligne"><span>Réalisé</span><strong>{{ fmtEntier(k.realise) }}</strong></div>
          <div v-if="k.engage !== undefined" class="bs-kpi__ligne"><span>Engagé</span><strong>{{ fmtEntier(k.engage) }}</strong></div>
          <div v-if="k.taux !== undefined" class="bs-kpi__ligne"><span>Exécution</span><strong :class="Number(k.taux || 0) > 100 && k.titre !== 'Produits' ? 'bs-defavorable' : ''">{{ fmtTaux(k.taux) }}</strong></div>
          <v-progress-linear v-if="k.taux !== undefined" :model-value="Math.min(100, Number(k.taux || 0))" :color="Number(k.taux || 0) > 100 ? 'error' : 'primary'" height="6" rounded class="mt-2" />
        </div>
        <div class="bs-kpi" style="border-top-color: #f59e0b">
          <div class="bs-kpi__tete"><v-icon icon="mdi-alert-octagon-outline" size="18" color="#f59e0b" /> Hors budget</div>
          <div class="bs-kpi__ligne"><span>Dépenses non budgétées</span><strong>{{ fmtEntier(horsBudgetCharges) }}</strong></div>
          <div class="bs-kpi__ligne"><span>Notes justifiées hors budget</span><strong>{{ suivi.notesHorsBudget.length }}</strong></div>
        </div>
      </div>

      <!-- Exécution par service -->
      <v-card v-if="services.length" class="classroom-card mb-4">
        <div class="pa-4 pb-2 d-flex align-center flex-wrap ga-2">
          <span class="bs-titre"><v-icon icon="mdi-account-group-outline" size="18" class="mr-1" />Exécution par service (dépenses, USD)</span>
          <span class="text-caption text-medium-emphasis">Enveloppe de chaque service sur « {{ libellePeriodeBudget }} » ; cliquez sur un service pour ses lignes et ses notes de frais.</span>
        </div>
        <div class="bs-table-wrap">
          <table class="bs-table bs-services">
            <thead>
              <tr>
                <th>Service</th>
                <th>Responsable</th>
                <th class="num">Prévu</th>
                <th class="num">Réalisé</th>
                <th class="num">Engagé</th>
                <th class="num">Disponible</th>
                <th class="num">Exécution</th>
                <th class="num">Non budgété</th>
                <th class="num">Dépassement</th>
                <th class="no-print" />
              </tr>
            </thead>
            <tbody>
              <template v-for="sv in services" :key="cleService(sv)">
                <tr class="bs-service-ligne" :class="{ 'bs-ligne-depassement': Number(sv.depassement) > 0, 'bs-sous-total': sv.serviceId === null }"
                  tabindex="0" role="button" :aria-expanded="serviceOuvert === cleService(sv)" @click="basculerService(sv)" @keydown.enter="basculerService(sv)">
                  <td><strong>{{ sv.nom }}</strong><v-chip v-if="!sv.actif" size="x-small" variant="tonal" class="ml-1">désactivé</v-chip></td>
                  <td>{{ sv.responsable || '—' }}</td>
                  <td class="num">{{ fmtMontant(sv.prevu) }}</td>
                  <td class="num">{{ fmtMontant(sv.realise) }}</td>
                  <td class="num">{{ fmtMontant(sv.engage) }}</td>
                  <td class="num" :class="Number(sv.disponible) < 0 ? 'bs-defavorable' : ''">{{ fmtMontant(sv.disponible) }}</td>
                  <td class="num">
                    <div class="bs-jauge"><v-progress-linear :model-value="Math.min(100, Number(sv.tauxExecution || 0))" :color="Number(sv.tauxExecution || 0) > 100 ? 'error' : 'primary'" height="6" rounded /></div>
                    <span :class="Number(sv.tauxExecution || 0) > 100 ? 'bs-defavorable' : ''">{{ fmtTaux(sv.tauxExecution) }}</span>
                  </td>
                  <td class="num" :class="Number(sv.nonBudgete) > 0 ? 'bs-defavorable' : ''">{{ fmtMontant(sv.nonBudgete) }}</td>
                  <td class="num" :class="Number(sv.depassement) > 0 ? 'bs-defavorable' : ''">
                    <template v-if="Number(sv.depassement) > 0"><v-icon icon="mdi-alert-octagon" size="14" color="error" /> {{ fmtMontant(sv.depassement) }}</template>
                    <template v-else>—</template>
                  </td>
                  <td class="no-print"><v-icon :icon="serviceOuvert === cleService(sv) ? 'mdi-chevron-up' : 'mdi-chevron-down'" size="18" /></td>
                </tr>
                <tr v-if="serviceOuvert === cleService(sv)" class="bs-service-detail">
                  <td colspan="10">
                    <p v-if="!sv.lignes.length" class="text-caption mb-2">
                      Aucune ligne de budget pour ce service : ses dépenses s’imputent sur les lignes générales, ou sont « non budgétées ».
                    </p>
                    <table v-else class="bs-table bs-table--interne">
                      <thead>
                        <tr><th>Compte</th><th>Libellé</th><th class="num">Prévu</th><th class="num">Réalisé</th><th class="num">Engagé</th><th class="num">Disponible</th><th class="num">Taux</th><th class="num">Dépassement</th></tr>
                      </thead>
                      <tbody>
                        <tr v-for="l in sv.lignes" :key="l.id" :class="{ 'bs-ligne-depassement': Number(l.depassement) > 0 }">
                          <td class="mono">{{ l.compteNumero }}</td>
                          <td>{{ l.compteLibelle }}<div v-if="l.commentaire" class="bs-commentaire">{{ l.commentaire }}</div></td>
                          <td class="num">{{ fmtMontant(l.prevuAnnuel) }}</td>
                          <td class="num">{{ fmtMontant(l.realiseAnnuel) }}</td>
                          <td class="num">{{ fmtMontant(l.engageAnnuel) }}</td>
                          <td class="num" :class="Number(l.disponibleAnnuel) < 0 && l.section !== 'PRODUITS' ? 'bs-defavorable' : ''">{{ fmtMontant(l.disponibleAnnuel) }}</td>
                          <td class="num" :class="classeTaux(l.section, l.tauxExecution)">{{ fmtTaux(l.tauxExecution) }}</td>
                          <td class="num" :class="Number(l.depassement) > 0 ? 'bs-defavorable' : ''">{{ Number(l.depassement) > 0 ? fmtMontant(l.depassement) : '—' }}</td>
                        </tr>
                      </tbody>
                    </table>
                    <div v-if="sv.horsBudget.length" class="mt-3">
                      <div class="bs-sous-titre text-error"><v-icon icon="mdi-alert-outline" size="15" /> Dépenses non budgétées pour ce service</div>
                      <table class="bs-table bs-table--interne">
                        <thead><tr><th>Compte</th><th>Libellé</th><th class="num">Réalisé</th><th class="num">Engagé</th></tr></thead>
                        <tbody>
                          <tr v-for="h in sv.horsBudget" :key="h.compteNumero">
                            <td class="mono">{{ h.compteNumero }}</td>
                            <td>{{ h.compteLibelle }}</td>
                            <td class="num">{{ fmtMontant(h.realiseAnnuel) }}</td>
                            <td class="num">{{ fmtMontant(h.engageAnnuel) }}</td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                    <p v-if="Number(sv.imputeGeneral) > 0" class="text-caption mt-2 mb-0">
                      <v-icon icon="mdi-information-outline" size="14" /> {{ fmtMontant(sv.imputeGeneral) }} USD de dépenses du service sont imputés sur les lignes
                      générales (le service n’a pas de ligne pour ces comptes) : ils ne comptent pas dans son enveloppe.
                    </p>
                    <div v-if="sv.notes.length" class="mt-3">
                      <div class="bs-sous-titre">Notes de frais du service sur la période ({{ sv.notes.length }})</div>
                      <table class="bs-table bs-table--interne">
                        <thead><tr><th>Note</th><th>Objet</th><th class="num">Montant</th><th>Statut</th><th>Contrôle</th></tr></thead>
                        <tbody>
                          <tr v-for="n in sv.notes" :key="n.id">
                            <td class="text-no-wrap"><NuxtLink :to="`/notes-frais/${n.id}`" @click.stop>{{ n.reference }}</NuxtLink></td>
                            <td>{{ n.objet }}</td>
                            <td class="num">{{ fmtMontant(n.montant) }} {{ n.devise }}</td>
                            <td class="text-caption">{{ statutNoteMeta(n.statut).label }}</td>
                            <td>
                              <v-chip v-if="n.statutBudget" size="x-small" :color="statutControle(n.statutBudget).color" variant="tonal">{{ statutControle(n.statutBudget).label }}</v-chip>
                              <span v-else class="text-caption text-medium-emphasis">—</span>
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
        <p class="bs-legende">
          Le réalisé et l’engagé d’un service proviennent de ses notes de frais (payées : grand livre ; approuvées non payées : engagé).
          Une dépense d’un service sur un compte qu’il n’a pas budgété s’impute sur la ligne générale du compte si elle existe, sinon elle
          est « non budgétée ». Dépassement = dépassements de ses lignes (réalisé + engagé au-delà du prévu) + dépenses non budgétées.
        </p>
      </v-card>

      <!-- Dépassements -->
      <v-card v-if="suivi.depassements?.length" id="bs-depassements" class="classroom-card mb-4 bs-carte-depassements">
        <div class="pa-4 pb-2 bs-titre text-error"><v-icon icon="mdi-alert-octagon-outline" size="18" class="mr-1" />Dépassements budgétaires de la période</div>
        <div class="bs-table-wrap">
          <table class="bs-table">
            <thead>
              <tr><th>Enveloppe</th><th>Compte</th><th>Libellé</th><th>Nature</th><th class="num">Prévu</th><th class="num">Réalisé</th><th class="num">Engagé</th><th class="num">Dépassement</th></tr>
            </thead>
            <tbody>
              <tr v-if="depassementsServices.length" class="bs-section-ligne"><td colspan="8" style="border-left-color: #dc2626">Enveloppes des services</td></tr>
              <tr v-for="(d, i) in depassementsServices" :key="`s-${i}`">
                <td><strong>{{ nomEnveloppe(d) }}</strong><div v-if="d.responsable" class="bs-commentaire">{{ d.responsable }}</div></td>
                <td class="mono">{{ d.compteNumero }}</td>
                <td>{{ d.compteLibelle }}</td>
                <td><v-chip size="x-small" :color="d.nature === 'NON_BUDGETE' ? 'warning' : 'error'" variant="tonal">{{ d.nature === 'NON_BUDGETE' ? 'Non budgété' : 'Dépassement' }}</v-chip></td>
                <td class="num">{{ fmtMontant(d.prevu) }}</td>
                <td class="num">{{ fmtMontant(d.realise) }}</td>
                <td class="num">{{ fmtMontant(d.engage) }}</td>
                <td class="num bs-defavorable">{{ fmtMontant(d.montant) }}</td>
              </tr>
              <tr v-if="depassementsConsolides.length" class="bs-section-ligne"><td colspan="8" style="border-left-color: #dc2626">Budget consolidé</td></tr>
              <tr v-for="(d, i) in depassementsConsolides" :key="`c-${i}`">
                <td><strong>Budget consolidé</strong></td>
                <td class="mono">{{ d.compteNumero }}</td>
                <td>{{ d.compteLibelle }}</td>
                <td><v-chip size="x-small" color="error" variant="tonal">Dépassement</v-chip></td>
                <td class="num">{{ fmtMontant(d.prevu) }}</td>
                <td class="num">{{ fmtMontant(d.realise) }}</td>
                <td class="num">{{ fmtMontant(d.engage) }}</td>
                <td class="num bs-defavorable">{{ fmtMontant(d.montant) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="bs-legende">
          Dépassement = réalisé + engagé − prévu sur la période, pour une dépense. « Non budgété » : dépense d’un service sur un compte
          qu’aucune de ses lignes (ni ligne générale) ne couvre.
        </p>
      </v-card>

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
            <div class="bs-graphe__zone" role="img" :aria-label="`Histogramme du prévu, du réalisé et de l’engagé par mois, section ${libelleSection(sectionGraphique)}`"><Bar :data="dataMensuel" :options="optsBarres" /></div>
          </div>
          <div class="bs-graphe">
            <p class="bs-graphe__titre">Exécution cumulée {{ estAnnuel ? 'depuis janvier' : 'sur la période' }}</p>
            <div class="bs-graphe__zone" role="img" :aria-label="`Courbes du prévu et du réalisé cumulés ${estAnnuel ? 'depuis janvier' : 'sur la période'}, section ${libelleSection(sectionGraphique)}`"><Line :data="dataCumul" :options="optsCourbe" /></div>
          </div>
          <div class="bs-graphe">
            <p class="bs-graphe__titre">Répartition du prévu par nature SYSCOHADA</p>
            <div class="bs-graphe__zone" role="img" :aria-label="`Répartition du prévu par nature SYSCOHADA, section ${libelleSection(sectionGraphique)}`"><Doughnut v-if="dataNatures.labels.length" :data="dataNatures" :options="optsAnneau" /><p v-else class="bs-vide">Rien de prévu dans cette section.</p></div>
          </div>
          <div class="bs-graphe">
            <p class="bs-graphe__titre">Principaux écarts à date (réalisé − prévu)</p>
            <div class="bs-graphe__zone" role="img" :aria-label="`Principaux écarts à date entre réalisé et prévu, section ${libelleSection(sectionGraphique)}`"><Bar v-if="dataEcarts.labels.length" :data="dataEcarts" :options="optsEcarts" /><p v-else class="bs-vide">Aucun écart à date.</p></div>
          </div>
        </div>
      </v-card>

      <!-- Tableau détaillé -->
      <v-card class="classroom-card mb-4">
        <div class="d-flex align-center flex-wrap ga-3 pa-4 pb-2">
          <span class="bs-titre">Budget détaillé (USD)</span>
          <v-btn-toggle v-if="estAnnuel" v-model="periode" mandatory density="compact" color="primary" variant="outlined" class="no-print">
            <v-btn v-for="p in PERIODES" :key="p.value" :value="p.value" size="small">{{ p.title }}</v-btn>
          </v-btn-toggle>
          <v-btn-toggle v-model="vue" mandatory density="compact" color="primary" variant="outlined" class="no-print">
            <v-btn v-for="v in VUES" :key="v.value" :value="v.value" size="small">{{ v.title }}</v-btn>
          </v-btn-toggle>
          <span class="bs-print-seul text-caption">
            <template v-if="estAnnuel">Découpage {{ PERIODES.find(p => p.value === periode)?.title.toLowerCase() }} · </template>{{ VUES.find(v => v.value === vue)?.title }}
          </span>
        </div>
        <div v-if="xs" class="bs-cartes">
          <template v-for="b in blocs" :key="b.section.code">
            <div class="bs-cartes__section" :style="{ borderLeftColor: SECTIONS[b.section.code]?.color }">{{ b.section.libelle }}</div>
            <div v-for="l in b.natures.flatMap((n: any) => n.lignes)" :key="l.id" class="bs-carte">
              <div class="bs-carte__tete"><span class="mono">{{ l.compteNumero }}</span> {{ l.compteLibelle }}
                <v-chip v-if="Number(l.depassement) > 0" size="x-small" color="error" variant="flat" class="ml-1">Dépassement {{ fmtEntier(l.depassement) }}</v-chip>
              </div>
              <div v-if="l.services?.length" class="bs-commentaire"><v-icon icon="mdi-account-group-outline" size="11" /> {{ l.services.join(', ') }}</div>
              <div v-if="l.commentaire" class="bs-commentaire">{{ l.commentaire }}</div>
              <dl class="bs-carte__grille">
                <div><dt>Prévu {{ motDuree }}</dt><dd>{{ fmtMontant(l.prevuAnnuel) }}</dd></div>
                <div><dt>Réalisé</dt><dd>{{ fmtMontant(l.realiseAnnuel) }}</dd></div>
                <div><dt>Engagé</dt><dd>{{ fmtMontant(l.engageAnnuel) }}</dd></div>
                <div><dt>Disponible</dt><dd :class="Number(l.disponibleAnnuel) < 0 && l.section !== 'PRODUITS' ? 'bs-defavorable' : ''">{{ fmtMontant(l.disponibleAnnuel) }}</dd></div>
                <div><dt>Taux</dt><dd :class="classeTaux(l.section, l.tauxExecution)">{{ fmtTaux(l.tauxExecution) }}</dd></div>
              </dl>
            </div>
            <div class="bs-carte bs-carte--total">
              <div class="bs-carte__tete">Total {{ b.section.libelle.toLowerCase() }}</div>
              <dl class="bs-carte__grille">
                <div><dt>Prévu {{ motDuree }}</dt><dd>{{ fmtMontant(b.section.prevuAnnuel) }}</dd></div>
                <div><dt>Réalisé</dt><dd>{{ fmtMontant(b.section.realiseAnnuel) }}</dd></div>
                <div><dt>Disponible</dt><dd>{{ fmtMontant(disponible(b.section)) }}</dd></div>
                <div><dt>Taux</dt><dd :class="classeTaux(b.section.code, b.section.tauxExecution)">{{ fmtTaux(b.section.tauxExecution) }}</dd></div>
              </dl>
            </div>
          </template>
        </div>
        <div v-else class="bs-table-wrap">
          <table class="bs-table">
            <thead>
              <tr>
                <th>Compte</th>
                <th>Libellé</th>
                <th v-for="c in colonnes" :key="c" class="num">{{ c }}</th>
                <th class="num">Prévu {{ motDuree }}</th>
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
                      <v-chip v-if="Number(l.depassement) > 0" size="x-small" color="error" variant="flat" class="ml-1">Dépassement {{ fmtEntier(l.depassement) }}</v-chip>
                      <div v-if="l.services?.length" class="bs-commentaire"><v-icon icon="mdi-account-group-outline" size="11" /> {{ l.services.join(', ') }}</div>
                      <div v-if="l.commentaire" class="bs-commentaire">{{ l.commentaire }}</div>
                    </td>
                    <td v-for="(v, i) in serie(l)" :key="i" class="num" :class="vue === 'ecart' ? classeEcart(l.section, v) : ''">{{ fmtMontant(v) }}</td>
                    <td class="num">{{ fmtMontant(l.prevuAnnuel) }}</td>
                    <td class="num">{{ fmtMontant(l.realiseAnnuel) }}</td>
                    <td class="num">{{ fmtMontant(l.engageAnnuel) }}</td>
                    <td class="num" :class="Number(l.disponibleAnnuel) < 0 && l.section !== 'PRODUITS' ? 'bs-defavorable' : ''">{{ fmtMontant(l.disponibleAnnuel) }}</td>
                    <td class="num" :class="classeTaux(l.section, l.tauxExecution)">{{ fmtTaux(l.tauxExecution) }}</td>
                  </tr>
                  <tr class="bs-sous-total">
                    <td class="mono">{{ n.nature.code }}</td>
                    <td>Total {{ n.nature.libelle }}</td>
                    <td v-for="(v, i) in serie(n.nature)" :key="i" class="num">{{ fmtMontant(v) }}</td>
                    <td class="num">{{ fmtMontant(n.nature.prevuAnnuel) }}</td>
                    <td class="num">{{ fmtMontant(n.nature.realiseAnnuel) }}</td>
                    <td class="num">{{ fmtMontant(n.nature.engageAnnuel) }}</td>
                    <td class="num">{{ fmtMontant(disponible(n.nature)) }}</td>
                    <td class="num" :class="classeTaux(n.nature.section, n.nature.tauxExecution)">{{ fmtTaux(n.nature.tauxExecution) }}</td>
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
                  <td class="num" :class="classeTaux(b.section.code, b.section.tauxExecution)">{{ fmtTaux(b.section.tauxExecution) }}</td>
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
          Budget consolidé (tous services confondus). Le réalisé provient du grand livre (pièces comptabilisées des mois de la période,
          hors à-nouveaux et clôture) ; l’engagé correspond aux notes de frais approuvées non encore payées ; disponible = prévu − réalisé
          − engagé. Sur une charge, un écart positif est un dépassement ; sur un produit, une recette supérieure à la prévision.
        </p>
      </v-card>

      <!-- Non rattaché à un service -->
      <v-card v-if="suivi.nonRattache?.length" class="classroom-card mb-4">
        <div class="pa-4 pb-2 bs-titre">Dépenses non rattachées à un service</div>
        <p class="px-4 text-caption text-medium-emphasis">
          Dépenses sans service passées sur des comptes que seuls des services ont budgétés : elles comptent dans le budget consolidé, mais
          dans l’enveloppe d’aucun service (écritures hors note de frais, ou notes de frais sans service). Rattachez les notes à leur service.
        </p>
        <v-table density="compact">
          <thead><tr><th>Compte</th><th>Libellé</th><th class="text-end">Réalisé (USD)</th></tr></thead>
          <tbody>
            <tr v-for="h in suivi.nonRattache" :key="h.compteNumero">
              <td class="mono text-no-wrap">{{ h.compteNumero }}</td>
              <td>{{ h.compteLibelle }}</td>
              <td class="text-end text-no-wrap">{{ fmtMontant(h.realiseAnnuel) }}</td>
            </tr>
          </tbody>
        </v-table>
      </v-card>

      <!-- Hors budget -->
      <v-card v-if="suivi.horsBudget.length" class="classroom-card mb-4">
        <div class="pa-4 pb-2 bs-titre">Dépenses et recettes hors budget</div>
        <p class="px-4 text-caption text-medium-emphasis">Comptes mouvementés ou engagés pendant la période qu’aucune ligne du budget ne couvre.</p>
        <v-table density="compact">
          <thead><tr><th>Compte</th><th>Libellé</th><th>Section</th><th class="text-end">Réalisé (USD)</th><th class="text-end">Engagé (USD)</th></tr></thead>
          <tbody>
            <tr v-for="h in suivi.horsBudget" :key="h.compteNumero">
              <td class="mono text-no-wrap">{{ h.compteNumero }}</td>
              <td>{{ h.compteLibelle }}</td>
              <td>{{ libelleSection(h.section) }}</td>
              <td class="text-end text-no-wrap">{{ fmtMontant(h.realiseAnnuel) }}</td>
              <td class="text-end text-no-wrap">{{ fmtMontant(h.engageAnnuel) }}</td>
            </tr>
          </tbody>
        </v-table>
      </v-card>

      <v-card v-if="suivi.notesHorsBudget.length" class="classroom-card mb-4">
        <div class="pa-4 pb-2 bs-titre">Notes de frais hors budget ou en dépassement</div>
        <v-table density="compact">
          <thead><tr><th>Note</th><th>Objet</th><th>Service</th><th class="text-end">Montant</th><th>Contrôle</th><th>Justification</th><th>Statut</th></tr></thead>
          <tbody>
            <tr v-for="n in suivi.notesHorsBudget" :key="n.id">
              <td class="text-no-wrap"><NuxtLink :to="`/notes-frais/${n.id}`">{{ n.reference }}</NuxtLink></td>
              <td>{{ n.objet }}</td>
              <td class="text-caption">{{ n.serviceNom || '—' }}</td>
              <td class="text-end text-no-wrap">{{ fmtMontant(n.montant) }} {{ n.devise }}</td>
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

    <v-dialog :model-value="!!actionAConfirmer" max-width="480" @update:model-value="(v: boolean) => { if (!v) actionAConfirmer = '' }">
      <v-card v-if="CONFIRMATIONS[actionAConfirmer]">
        <v-card-title>{{ CONFIRMATIONS[actionAConfirmer].titre }}</v-card-title>
        <v-card-text>{{ CONFIRMATIONS[actionAConfirmer].texte }}</v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="actionAConfirmer = ''">Annuler</v-btn>
          <v-btn :color="CONFIRMATIONS[actionAConfirmer].couleur" variant="flat" @click="confirmerAction">{{ CONFIRMATIONS[actionAConfirmer].bouton }}</v-btn>
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
.bs-cartes { padding: 0 12px 12px; }
.bs-cartes__section { background: #eef2ff; border-left: 4px solid; font-weight: 800; text-transform: uppercase; font-size: 0.74rem; letter-spacing: 0.04em; padding: 8px 10px; margin-top: 10px; border-radius: 4px; }
.bs-carte { border: 1px solid #eef0f3; border-radius: 10px; padding: 10px; margin-top: 8px; }
.bs-carte--total { background: #f1f5f9; font-weight: 700; }
.bs-carte__tete { font-weight: 600; font-size: 0.86rem; }
.bs-carte .mono { font-family: ui-monospace, monospace; font-size: 0.78rem; color: #4b5563; }
.bs-carte__grille { display: grid; grid-template-columns: repeat(2, 1fr); gap: 6px 12px; margin: 8px 0 0; }
.bs-carte__grille dt { font-size: 0.68rem; color: #6b7280; font-weight: 400; }
.bs-carte__grille dd { margin: 0; font-size: 0.86rem; font-variant-numeric: tabular-nums; }
.bs-signatures { display: none; }
.bs-service-ligne { cursor: pointer; }
.bs-service-ligne:hover td { background: #f8fafc; }
.bs-service-ligne:focus-visible { outline: 2px solid var(--color-primary); outline-offset: -2px; }
.bs-ligne-depassement td { background: #fef2f2; }
.bs-service-detail > td { background: #fbfbff; padding: 10px 14px !important; }
.bs-table--interne { font-size: 0.78rem; background: #fff; border: 1px solid #eef0f3; border-radius: 8px; }
.bs-sous-titre { font-weight: 700; font-size: 0.8rem; margin-bottom: 4px; display: flex; gap: 4px; align-items: center; }
.bs-jauge { width: 70px; display: inline-block; vertical-align: middle; margin-right: 6px; }
.bs-carte-depassements { border-left: 4px solid #dc2626; }
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
