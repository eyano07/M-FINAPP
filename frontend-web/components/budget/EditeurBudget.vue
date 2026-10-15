<script setup lang="ts">
/**
 * Éditeur d'un budget prévisionnel (brouillon) : en-tête (intitulé, exercice, période couverte), puis lignes par
 * section (produits, charges, investissements). Un budget couvre l'exercice entier, un semestre, un trimestre ou un
 * seul mois : seuls les mois de la période se saisissent. Chaque ligne porte un compte SYSCOHADA et ses montants
 * mensuels ; le montant de la période se répartit à parts égales ou selon la saisonnalité réelle de l'année
 * précédente, et chaque mois reste modifiable. Trimestres, semestres et année sont toujours des totaux exacts.
 *
 * Budget par service : chaque service (avec son responsable) a son onglet et ses lignes de dépenses ; les lignes
 * générales (sans service) portent les recettes et les dépenses communes. L'onglet « Consolidé » montre le budget
 * unique, tous services confondus.
 */
interface CompteBudgetable { numero: string, libelle: string, section: string, imputable?: boolean }
interface ServiceRef { id: number, nom: string, responsable: string, actif: boolean }
interface LigneEdition {
  cle: number
  compteNumero: string | null
  compteLibelle: string
  section: string
  annuel: number | null
  mensuel: number[]
  mode: 'UNIFORME' | 'SAISONNALITE' | 'MANUEL'
  commentaire: string
  ouverte: boolean
  serviceId: number | null
  justificationIa?: string
  realiseReference?: number | null
}

const props = defineProps<{ budgetId?: number | null }>()
const emit = defineEmits<{ (e: 'enregistre', id: number): void, (e: 'annule'): void }>()

const api = useApi()
const auth = useAuthStore()
const chargement = ref(false)
const enregistrement = ref(false)
const erreur = ref('')
const avertissementRepartition = ref('')
const information = ref('')

const erreurEnregistrement = ref('')
const zoneErreur = ref<HTMLElement | null>(null)

const maintenant = new Date()
const entete = reactive({
  intitule: '',
  exercice: maintenant.getFullYear() + (maintenant.getMonth() >= 9 ? 1 : 0),
  observation: '',
  duree: 'ANNUEL' as DureeBudget,
  periode: 1,
})
/** Une révision garde la période du budget qu'elle révise. */
const estRevision = ref(false)
const lignes = ref<LigneEdition[]>([])
let compteur = 0

// Détection des modifications non enregistrées (avertissement avant de quitter la page).
const instantane = ref('')
const etatCourant = () => JSON.stringify([entete, lignes.value.map(l => [l.compteNumero, l.mensuel, l.commentaire, l.serviceId]), servicesDuBudget.value])
const modifie = computed(() => instantane.value !== '' && instantane.value !== etatCourant())
const memoriserEtat = () => { instantane.value = etatCourant() }

// Boîte de confirmation commune aux actions destructrices.
const confirmation = reactive({ ouverte: false, titre: '', texte: '', bouton: 'Confirmer', action: (() => {}) as () => void, refus: (() => {}) as () => void })
function demanderConfirmation(titre: string, texte: string, bouton: string, action: () => void, refus: () => void = () => {}) {
  Object.assign(confirmation, { ouverte: true, titre, texte, bouton, action, refus })
}
function validerConfirmation() { confirmation.ouverte = false; confirmation.action() }
function refuserConfirmation() { confirmation.ouverte = false; confirmation.refus() }

const avantFermeture = (e: BeforeUnloadEvent) => { if (modifie.value) { e.preventDefault(); e.returnValue = '' } }
onMounted(() => window.addEventListener('beforeunload', avantFermeture))
onBeforeUnmount(() => window.removeEventListener('beforeunload', avantFermeture))
onBeforeRouteLeave(() => {
  if (modifie.value && !window.confirm('Des modifications ne sont pas enregistrées. Quitter quand même ?')) return false
})

// ---------------------------------------------------------------------------------------------
// Période couverte
// ---------------------------------------------------------------------------------------------
const bornes = computed(() => moisDeLaPeriode(entete.duree, entete.periode))
const debut = computed(() => bornes.value[0])
const fin = computed(() => bornes.value[1])
const libelle = computed(() => libellePeriode(entete.duree, entete.periode, entete.exercice))
const moisDeSaisie = computed(() => MOIS_COURTS.map((m, i) => ({ m, i })).filter(x => dansLaPeriode(x.i + 1, debut.value, fin.value)))
const periodes = computed(() => periodesDe(entete.duree))
const libelleMontant = computed(() => (entete.duree === 'ANNUEL' ? 'Montant annuel (USD)' : entete.duree === 'MENSUEL' ? `Montant ${MOIS_LONGS[debut.value - 1].toLowerCase()} (USD)` : 'Montant de la période (USD)'))

/** Colonnes de détail : trimestres d'un budget annuel, mois d'une période plus courte (aucune pour un seul mois). */
const colonnes = computed(() => (entete.duree === 'ANNUEL' ? ['T1', 'T2', 'T3', 'T4'] : entete.duree === 'MENSUEL' ? [] : MOIS_COURTS.slice(debut.value - 1, fin.value)))
function valeursColonnes(mensuel: number[]): number[] {
  if (entete.duree === 'ANNUEL') return regrouper(mensuel, 'TRIMESTRIEL')
  if (entete.duree === 'MENSUEL') return []
  return mensuel.slice(debut.value - 1, fin.value).map(v => centimes(Number(v) || 0))
}
const largeurTable = computed(() => 720 + colonnes.value.length * 100)

const horsPeriode = (l: LigneEdition) => l.mensuel.some((v, i) => Number(v) && !dansLaPeriode(i + 1, debut.value, fin.value))

/**
 * Changement de durée ou de période : les montants hors de la nouvelle période seraient perdus. Après confirmation,
 * chaque ligne garde son total, réparti à parts égales sur les mois de la nouvelle période.
 */
function changerPeriode(duree: DureeBudget, periode: number) {
  const ancienne = { duree: entete.duree, periode: entete.periode }
  const [d, f] = moisDeLaPeriode(duree, periode)
  const touchees = lignes.value.filter(l => l.mensuel.some((v, i) => Number(v) && !dansLaPeriode(i + 1, d, f)))
  const appliquer = () => {
    entete.duree = duree
    entete.periode = periode
    for (const l of touchees) {
      l.annuel = total(l.mensuel)
      l.mensuel = repartirUniformement(l.annuel, d, f)
      l.mode = 'UNIFORME'
    }
  }
  if (!touchees.length) { appliquer(); return }
  demanderConfirmation(
    'Changer la période du budget ?',
    `${touchees.length} ligne(s) ont des montants hors de « ${libellePeriode(duree, periode, entete.exercice)} » : chacune garde son total, réparti à parts égales sur les mois de la nouvelle période.`,
    'Changer la période',
    appliquer,
    () => { entete.duree = ancienne.duree; entete.periode = ancienne.periode },
  )
}
function choisirDuree(d: DureeBudget) {
  // Un nouveau budget de semestre, trimestre ou mois porte d'abord sur l'année en cours (l'exercice proposé par
  // défaut est l'année suivante dès octobre, pour préparer un budget annuel), et sur la période du mois courant.
  if (d !== 'ANNUEL' && !props.budgetId && entete.exercice === maintenant.getFullYear() + 1) {
    entete.exercice = maintenant.getFullYear()
  }
  const nombreMois = DUREES.find(x => x.value === d)?.mois || 12
  const parDefaut = d === 'ANNUEL' ? 1 : Math.ceil((maintenant.getMonth() + 1) / nombreMois)
  changerPeriode(d, parDefaut)
}

// ---------------------------------------------------------------------------------------------
// Services : un onglet par service du budget, plus les lignes générales et la vue consolidée
// ---------------------------------------------------------------------------------------------
const catalogue = ref<ServiceRef[]>([])
const servicesDuBudget = ref<number[]>([])
const onglet = ref<string>('GENERAL')
const peutGererServices = computed(() => auth.hasAnyRole(['DFIN', 'ADMIN']))

async function chargerServices() {
  try {
    catalogue.value = await api<ServiceRef[]>('/services-budgetaires')
  } catch {
    catalogue.value = []
  }
}

const serviceActif = computed<number | null>(() => (onglet.value.startsWith('S:') ? Number(onglet.value.slice(2)) : null))
const vueConsolidee = computed(() => onglet.value === 'CONSOLIDE')
const avecServices = computed(() => catalogue.value.length > 0 || servicesDuBudget.value.length > 0)
const service = (id: number | null) => catalogue.value.find(s => s.id === id) || null
const nomService = (id: number | null) => (id === null ? 'Lignes générales' : service(id)?.nom || `Service n° ${id}`)
const servicesAjoutables = computed(() => catalogue.value.filter(s => s.actif && !servicesDuBudget.value.includes(s.id)))

function ajouterService(id: number) {
  if (!servicesDuBudget.value.includes(id)) servicesDuBudget.value.push(id)
  onglet.value = `S:${id}`
  if (!lignes.value.some(l => l.serviceId === id)) lignes.value.push(nouvelleLigne('CHARGES', id))
}

function retirerService(id: number) {
  const siennes = lignes.value.filter(l => l.serviceId === id)
  const retirer = () => {
    lignes.value = lignes.value.filter(l => l.serviceId !== id)
    servicesDuBudget.value = servicesDuBudget.value.filter(s => s !== id)
    onglet.value = 'GENERAL'
  }
  if (!siennes.some(l => l.compteNumero || total(l.mensuel))) { retirer(); return }
  demanderConfirmation('Retirer ce service du budget ?', `Les ${siennes.length} ligne(s) du service « ${nomService(id)} » seront retirées du budget.`, 'Retirer', retirer)
}

const depensesDe = (id: number | null) => centimes(lignes.value.filter(l => l.serviceId === id && l.section !== 'PRODUITS').reduce((t, l) => t + total(l.mensuel), 0))

// ---------------------------------------------------------------------------------------------
// Lignes
// ---------------------------------------------------------------------------------------------
function nouvelleLigne(section = 'CHARGES', serviceId: number | null = serviceActif.value): LigneEdition {
  return { cle: ++compteur, compteNumero: null, compteLibelle: '', section, annuel: null, mensuel: Array(12).fill(0), mode: 'UNIFORME', commentaire: '', ouverte: false, serviceId }
}

async function charger() {
  await chargerServices()
  if (!props.budgetId) {
    lignes.value = [nouvelleLigne('PRODUITS', null), nouvelleLigne('CHARGES', null)]
    memoriserEtat()
    return
  }
  chargement.value = true
  try {
    const b = await api<any>(`/budgets/${props.budgetId}`)
    entete.intitule = b.intitule
    entete.exercice = b.exercice
    entete.observation = b.observation || ''
    entete.duree = (b.duree || 'ANNUEL') as DureeBudget
    entete.periode = b.periode || 1
    estRevision.value = !!b.revisionDeId
    lignes.value = (b.lignes || []).map((l: any) => ({
      cle: ++compteur,
      compteNumero: l.compteNumero,
      compteLibelle: l.compteLibelle,
      section: l.section,
      annuel: Number(l.montantPrevu),
      mensuel: (l.mensuel || []).map((v: any) => Number(v)),
      mode: 'MANUEL',
      commentaire: l.commentaire || '',
      ouverte: false,
      serviceId: l.serviceId ?? null,
    }))
    // Services du budget, y compris ceux désactivés depuis (leurs lignes restent).
    for (const l of b.lignes || []) {
      if (l.serviceId && !servicesDuBudget.value.includes(l.serviceId)) {
        servicesDuBudget.value.push(l.serviceId)
        if (!catalogue.value.some(s => s.id === l.serviceId)) {
          catalogue.value.push({ id: l.serviceId, nom: l.serviceNom, responsable: l.serviceResponsable, actif: false })
        }
      }
    }
    lignes.value.forEach(l => memoriserCompte({ numero: l.compteNumero!, libelle: l.compteLibelle, section: l.section }))
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le budget.')
  } finally {
    chargement.value = false
    memoriserEtat()
  }
}
onMounted(charger)

// ---------------------------------------------------------------------------------------------
// Recherche de comptes (plan comptable SYSCOHADA, classes 2, 6, 7, 8)
// ---------------------------------------------------------------------------------------------
const comptesConnus = reactive<Record<string, CompteBudgetable>>({})
const resultats = ref<CompteBudgetable[]>([])
const rechercheEnCours = ref(false)
let minuterie: ReturnType<typeof setTimeout> | null = null

function memoriserCompte(c: CompteBudgetable) {
  if (c?.numero) comptesConnus[c.numero] = c
}

function chercherComptes(q: string | null) {
  if (minuterie) clearTimeout(minuterie)
  minuterie = setTimeout(async () => {
    rechercheEnCours.value = true
    try {
      const liste = await api<CompteBudgetable[]>('/budgets/comptes', { query: { q: q || '' } })
      liste.forEach(memoriserCompte)
      resultats.value = liste
    } catch {
      resultats.value = []
    } finally {
      rechercheEnCours.value = false
    }
  }, 250)
}
onMounted(() => chercherComptes(''))

function itemsPour(l: LigneEdition): CompteBudgetable[] {
  const courant = l.compteNumero && comptesConnus[l.compteNumero]
  const liste = l.serviceId ? resultats.value.filter(r => r.section !== 'PRODUITS') : resultats.value
  return courant && !liste.some(r => r.numero === courant.numero) ? [courant, ...liste] : liste
}

function choisirCompte(l: LigneEdition, numero: string | null) {
  l.compteNumero = numero
  const c = numero ? comptesConnus[numero] : null
  if (c) {
    l.compteLibelle = c.libelle
    l.section = c.section
    // Les recettes ne se rattachent pas à un service : la ligne rejoint les lignes générales.
    if (c.section === 'PRODUITS' && l.serviceId !== null) {
      information.value = `Le compte ${c.numero} est une recette : la ligne passe dans les lignes générales (seules les dépenses se suivent par service).`
      l.serviceId = null
    }
  }
  if (l.annuel && l.mode === 'SAISONNALITE') appliquerMode(l)
}

// ---------------------------------------------------------------------------------------------
// Montants : période <-> mois
// ---------------------------------------------------------------------------------------------
async function appliquerMode(l: LigneEdition) {
  avertissementRepartition.value = ''
  const montant = Number(l.annuel) || 0
  if (l.mode === 'UNIFORME') {
    l.mensuel = repartirUniformement(montant, debut.value, fin.value)
  } else if (l.mode === 'SAISONNALITE') {
    if (!l.compteNumero) {
      l.mensuel = repartirUniformement(montant, debut.value, fin.value)
      return
    }
    try {
      const r = await api<{ mensuel: number[], mode: string, explication: string }>('/budgets/repartition', {
        method: 'POST',
        body: { montantAnnuel: montant, mode: 'SAISONNALITE', compteNumero: l.compteNumero, exercice: entete.exercice, moisDebut: debut.value, moisFin: fin.value },
      })
      l.mensuel = r.mensuel.map(Number)
      if (r.mode !== 'SAISONNALITE') avertissementRepartition.value = `${l.compteNumero} : ${r.explication}`
    } catch (e) {
      l.mensuel = repartirUniformement(montant, debut.value, fin.value)
      avertissementRepartition.value = messageErreurApi(e, 'Saisonnalité indisponible : parts égales appliquées.')
    }
  }
}

function changerAnnuel(l: LigneEdition) {
  const montant = Number(l.annuel) || 0
  if (l.mode === 'MANUEL' && moisDeSaisie.value.length > 1) {
    const ancien = total(l.mensuel)
    if (montant === ancien) return
    demanderConfirmation(
      'Remplacer la ventilation mensuelle ?',
      `Les montants saisis mois par mois (total ${fmtMontant(ancien)} USD) seront remplacés par une répartition en parts égales de ${fmtMontant(montant)} USD.`,
      'Remplacer',
      () => { l.mode = 'UNIFORME'; appliquerMode(l) },
      () => { l.annuel = ancien },
    )
    return
  }
  if (l.mode === 'MANUEL') l.mode = 'UNIFORME'
  appliquerMode(l)
}

function changerMois(l: LigneEdition, i: number, v: string | number) {
  l.mensuel[i] = Math.max(0, Number(v) || 0)
  l.mode = 'MANUEL'
  l.annuel = total(l.mensuel)
}

const MODES = [
  { value: 'UNIFORME', title: 'Parts égales' },
  { value: 'SAISONNALITE', title: 'Saisonnalité N-1' },
  { value: 'MANUEL', title: 'Mois par mois' },
]

// ---------------------------------------------------------------------------------------------
// Sections et totaux
// ---------------------------------------------------------------------------------------------
const ORDRE = ['PRODUITS', 'CHARGES', 'INVESTISSEMENTS']
const sectionsVisibles = computed(() => (serviceActif.value === null ? ORDRE : ['CHARGES', 'INVESTISSEMENTS']))
const lignesPar = (s: string) => lignes.value.filter(l => l.section === s && (l.serviceId ?? null) === serviceActif.value)
const totalSection = (s: string) => total(lignesPar(s).map(l => total(l.mensuel)))
const mensuelSection = (s: string) => Array.from({ length: 12 }, (_, i) => centimes(lignesPar(s).reduce((t, l) => t + (Number(l.mensuel[i]) || 0), 0)))
const totalConsolide = (s: string) => total(lignes.value.filter(l => l.section === s).map(l => total(l.mensuel)))
const resultat = computed(() => centimes(totalConsolide('PRODUITS') - totalConsolide('CHARGES')))

function ajouterLigne(section: string) {
  lignes.value.push(nouvelleLigne(section))
}
function retirerLigne(l: LigneEdition) {
  lignes.value = lignes.value.filter(x => x.cle !== l.cle)
}

/** Budget consolidé : un compte par ligne, tous services confondus (lecture seule). */
const consolide = computed(() => {
  const parCompte = new Map<string, { compteNumero: string, compteLibelle: string, section: string, mensuel: number[], services: string[] }>()
  for (const l of lignes.value) {
    if (!l.compteNumero) continue
    const c = parCompte.get(l.compteNumero) || { compteNumero: l.compteNumero, compteLibelle: l.compteLibelle, section: l.section, mensuel: Array(12).fill(0), services: [] }
    c.mensuel = c.mensuel.map((v, i) => centimes(v + (Number(l.mensuel[i]) || 0)))
    const nom = nomService(l.serviceId)
    if (!c.services.includes(nom)) c.services.push(nom)
    parCompte.set(l.compteNumero, c)
  }
  return [...parCompte.values()].sort((a, b) => a.compteNumero.localeCompare(b.compteNumero))
})
const consolideDe = (s: string) => consolide.value.filter(c => c.section === s)

/**
 * Lignes refusées par le serveur, signalées ici : un compte deux fois dans un même service (ou dans les lignes
 * générales), un compte et l'un de ses sous-comptes dans le budget, même dans des services différents.
 */
const chevauchements = computed(() => {
  const conflits: string[] = []
  const vus = new Set<string>()
  for (const l of lignes.value) {
    if (!l.compteNumero) continue
    const cle = `${l.serviceId ?? ''}|${l.compteNumero}`
    if (vus.has(cle)) conflits.push(`${l.compteNumero} en double (${nomService(l.serviceId)})`)
    vus.add(cle)
  }
  const numeros = [...new Set(lignes.value.map(l => l.compteNumero).filter(Boolean))] as string[]
  for (let i = 0; i < numeros.length; i++) {
    for (let j = i + 1; j < numeros.length; j++) {
      const [a, b] = numeros[i].length <= numeros[j].length ? [numeros[i], numeros[j]] : [numeros[j], numeros[i]]
      if (b.startsWith(a) && a !== b) conflits.push(`${a} couvre déjà ${b}`)
    }
  }
  return conflits
})

// ---------------------------------------------------------------------------------------------
// Proposition par l'IA
// ---------------------------------------------------------------------------------------------
const dialogueIa = ref(false)
const hypotheses = ref('')
const propositionEnCours = ref(false)
const proposition = ref<any | null>(null)

/** Mois de la proposition (annuelle) gardés pour la période du budget. */
const restreindre = (mensuel: number[]) => mensuel.map((v, i) => (dansLaPeriode(i + 1, debut.value, fin.value) ? Number(v) || 0 : 0))

async function proposer() {
  propositionEnCours.value = true
  proposition.value = null
  erreur.value = ''
  try {
    proposition.value = await api<any>('/budgets/proposition', {
      method: 'POST', body: { exercice: entete.exercice, hypotheses: hypotheses.value || null },
    })
  } catch (e) {
    erreur.value = messageErreurApi(e, 'La proposition n’a pas pu être établie.')
  } finally {
    propositionEnCours.value = false
  }
}

function utiliserProposition() {
  if (!proposition.value) return
  const cible = serviceActif.value
  const saisies = lignes.value.filter(l => (l.serviceId ?? null) === cible && (l.compteNumero || Number(l.annuel)))
  if (saisies.length) {
    demanderConfirmation(
      'Remplacer les lignes actuelles ?',
      `Les ${saisies.length} ligne(s) déjà saisie(s) dans « ${nomService(cible)} » seront remplacées par la proposition.`,
      'Remplacer',
      appliquerProposition,
    )
    return
  }
  appliquerProposition()
}

function appliquerProposition() {
  const cible = serviceActif.value
  const proposees: LigneEdition[] = proposition.value.lignes
    .map((l: any) => {
      memoriserCompte({ numero: l.compteNumero, libelle: l.compteLibelle, section: l.section })
      const mensuel = restreindre(l.mensuel.map(Number))
      return {
        cle: ++compteur,
        compteNumero: l.compteNumero,
        compteLibelle: l.compteLibelle,
        section: l.section,
        annuel: total(mensuel),
        mensuel,
        mode: 'MANUEL',
        commentaire: l.justification || '',
        ouverte: false,
        // Les recettes restent dans les lignes générales.
        serviceId: l.section === 'PRODUITS' ? null : cible,
        justificationIa: l.justification,
        realiseReference: l.realiseReference,
      } as LigneEdition
    })
    .filter((l: LigneEdition) => total(l.mensuel) > 0)
  const remplaceesIci = (l: LigneEdition) => (l.serviceId ?? null) === cible || (cible !== null && l.serviceId === null && l.section === 'PRODUITS' && proposees.some(p => p.compteNumero === l.compteNumero))
  lignes.value = [...lignes.value.filter(l => !remplaceesIci(l)), ...proposees]
  if (!entete.intitule) entete.intitule = `Budget ${libelle.value}`
  if (!entete.observation && proposition.value.synthese) {
    entete.observation = (proposition.value.source === 'IA' ? 'Proposition de l’IA : ' : 'Proposition calculée : ') + proposition.value.synthese
  }
  dialogueIa.value = false
}

// ---------------------------------------------------------------------------------------------
// Enregistrement
// ---------------------------------------------------------------------------------------------
async function enregistrer() {
  erreur.value = ''
  erreurEnregistrement.value = ''
  const echec = (m: string) => {
    erreurEnregistrement.value = m
    nextTick(() => zoneErreur.value?.scrollIntoView({ behavior: 'smooth', block: 'center' }))
  }
  const valides = lignes.value.filter(l => l.compteNumero)
  const sansCompte = lignes.value.filter(l => !l.compteNumero && (Number(l.annuel) || total(l.mensuel)))
  if (!entete.intitule.trim()) return echec('Indiquez l’intitulé du budget.')
  if (sansCompte.length) return echec(`${sansCompte.length} ligne(s) ont un montant mais aucun compte : choisissez un compte ou retirez la ligne.`)
  if (!valides.length) return echec('Ajoutez au moins une ligne avec un compte.')
  if (chevauchements.value.length) return echec(`Lignes en recouvrement : ${chevauchements.value.join(', ')}.`)
  enregistrement.value = true
  try {
    const corps = {
      intitule: entete.intitule.trim(),
      exercice: Number(entete.exercice),
      observation: entete.observation || null,
      duree: entete.duree,
      periode: entete.duree === 'ANNUEL' ? 1 : entete.periode,
      lignes: valides.map(l => ({
        compteNumero: l.compteNumero,
        mensuel: l.mensuel.map((v, i) => (dansLaPeriode(i + 1, debut.value, fin.value) ? centimes(Number(v) || 0) : 0)),
        commentaire: l.commentaire || null,
        serviceId: l.serviceId,
      })),
    }
    const b = props.budgetId
      ? await api<any>(`/budgets/${props.budgetId}`, { method: 'PUT', body: corps })
      : await api<any>('/budgets', { method: 'POST', body: corps })
    memoriserEtat()
    emit('enregistre', b.id)
  } catch (e) {
    echec(messageErreurApi(e, 'L’enregistrement a échoué.'))
  } finally {
    enregistrement.value = false
  }
}
</script>

<template>
  <div class="eb">
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-skeleton-loader v-if="chargement" type="card, table" />

    <template v-else>
      <!-- En-tête -->
      <v-card class="classroom-card pa-4 mb-4">
        <div class="eb-entete">
          <v-text-field v-model="entete.intitule" label="Intitulé du budget *" placeholder="Ex. Budget 2027 – Exploitation" hide-details="auto" class="eb-entete__titre" />
          <v-text-field v-model.number="entete.exercice" label="Exercice *" type="number" hide-details="auto" class="eb-entete__exercice" :disabled="estRevision" />
          <v-select
            :model-value="entete.duree"
            :items="DUREES"
            item-title="title"
            item-value="value"
            label="Période couverte *"
            hide-details
            class="eb-entete__duree"
            :disabled="estRevision"
            @update:model-value="(d: DureeBudget) => choisirDuree(d)"
          />
          <v-select
            v-if="entete.duree !== 'ANNUEL'"
            :model-value="entete.periode"
            :items="periodes"
            item-title="title"
            item-value="value"
            :label="entete.duree === 'MENSUEL' ? 'Mois *' : entete.duree === 'TRIMESTRIEL' ? 'Trimestre *' : 'Semestre *'"
            hide-details
            class="eb-entete__periode"
            :disabled="estRevision"
            @update:model-value="(p: number) => changerPeriode(entete.duree, p)"
          />
          <v-btn color="purple" variant="tonal" prepend-icon="mdi-robot-outline" height="48" @click="dialogueIa = true">
            Proposer avec l’IA
          </v-btn>
        </div>
        <p class="eb-periode">
          <v-icon icon="mdi-calendar-range" size="16" />
          Budget « <strong>{{ libelle }}</strong> » : il contrôlera les notes de frais de
          {{ entete.duree === 'MENSUEL' ? 'ce mois' : entete.duree === 'ANNUEL' ? 'tout l’exercice' : 'ces mois' }}
          ({{ MOIS_LONGS[debut - 1].toLowerCase() }}<template v-if="fin !== debut"> à {{ MOIS_LONGS[fin - 1].toLowerCase() }}</template> {{ entete.exercice }}).
          <span v-if="estRevision"> Une révision garde la période du budget révisé.</span>
        </p>
        <v-textarea v-model="entete.observation" label="Observation, hypothèses générales" rows="2" auto-grow hide-details class="mt-2" />
      </v-card>

      <v-alert v-if="avertissementRepartition" type="info" variant="tonal" density="compact" class="mb-3" closable @click:close="avertissementRepartition = ''">
        {{ avertissementRepartition }}
      </v-alert>
      <v-alert v-if="information" type="info" variant="tonal" density="compact" class="mb-3" closable @click:close="information = ''">
        {{ information }}
      </v-alert>
      <v-alert v-if="chevauchements.length" type="warning" variant="tonal" density="compact" class="mb-3">
        Lignes en recouvrement (une dépense y serait comptée deux fois) : {{ chevauchements.join(', ') }}.
        Gardez soit le compte général, soit ses sous-comptes — le même dans tous les services.
      </v-alert>

      <!-- Services -->
      <v-card v-if="avecServices" class="classroom-card mb-4 eb-services">
        <div class="eb-services__tete">
          <div>
            <span class="eb-services__titre"><v-icon icon="mdi-account-group-outline" size="18" class="mr-1" />Budget par service</span>
            <span class="eb-services__aide">Chaque service a ses lignes de dépenses ; le budget reste unique (onglet « Consolidé »). Les recettes se budgètent dans les lignes générales.</span>
          </div>
          <div class="d-flex flex-wrap ga-2">
            <v-menu v-if="servicesAjoutables.length" @update:model-value="(o: boolean) => { if (o) chargerServices() }">
              <template #activator="{ props: p }">
                <v-btn v-bind="p" size="small" color="primary" variant="tonal" prepend-icon="mdi-plus">Ajouter un service</v-btn>
              </template>
              <v-list density="compact">
                <v-list-item v-for="s in servicesAjoutables" :key="s.id" :title="s.nom" :subtitle="`Responsable : ${s.responsable}`" @click="ajouterService(s.id)" />
              </v-list>
            </v-menu>
            <v-btn v-if="peutGererServices" size="small" variant="text" prepend-icon="mdi-cog-outline" to="/budgets/services" target="_blank">Gérer les services</v-btn>
          </div>
        </div>
        <v-tabs v-model="onglet" show-arrows density="comfortable" color="primary" class="eb-onglets">
          <v-tab value="GENERAL">
            Lignes générales
            <span class="eb-onglet-montant">{{ fmtEntier(depensesDe(null)) }}</span>
          </v-tab>
          <v-tab v-for="id in servicesDuBudget" :key="id" :value="`S:${id}`">
            {{ nomService(id) }}
            <span class="eb-onglet-montant">{{ fmtEntier(depensesDe(id)) }}</span>
          </v-tab>
          <v-tab value="CONSOLIDE"><v-icon icon="mdi-sigma" size="16" class="mr-1" />Consolidé</v-tab>
        </v-tabs>
        <div v-if="serviceActif !== null" class="eb-service-actif">
          <span><v-icon icon="mdi-account-tie-outline" size="16" class="mr-1" />Responsable : <strong>{{ service(serviceActif)?.responsable || '—' }}</strong></span>
          <span v-if="service(serviceActif)?.actif === false" class="text-warning">Service désactivé : ses lignes existantes restent.</span>
          <span>Dépenses prévues : <strong>{{ fmtMontant(depensesDe(serviceActif)) }} USD</strong></span>
          <v-spacer />
          <v-btn size="small" variant="text" color="error" prepend-icon="mdi-close" @click="retirerService(serviceActif)">Retirer ce service du budget</v-btn>
        </div>
      </v-card>
      <p v-else-if="peutGererServices" class="eb-astuce">
        <v-icon icon="mdi-lightbulb-on-outline" size="16" />
        Astuce : créez des services (avec leur responsable) dans <NuxtLink to="/budgets/services" target="_blank">Budgets › Services</NuxtLink> pour répartir ce budget et suivre les dépenses par service.
        <v-btn size="x-small" variant="text" color="primary" prepend-icon="mdi-refresh" @click="chargerServices">Actualiser la liste des services</v-btn>
      </p>

      <!-- Vue consolidée (lecture seule) -->
      <template v-if="vueConsolidee">
        <v-card v-for="s in ORDRE" :key="`c-${s}`" class="classroom-card mb-4">
          <div class="eb-section-tete" :style="{ borderLeftColor: SECTIONS[s].color }">
            <div>
              <span class="eb-section-tete__titre">{{ SECTIONS[s].label }} — consolidé</span>
              <span class="eb-section-tete__sens">Tous services confondus</span>
            </div>
            <div class="eb-section-tete__total">{{ fmtMontant(totalConsolide(s)) }} <small>USD</small></div>
          </div>
          <div class="eb-table-wrap">
            <table class="eb-table" :style="{ minWidth: `${Math.max(640, 460 + colonnes.length * 100)}px` }">
              <thead>
                <tr>
                  <th class="eb-col-compte">Compte SYSCOHADA</th>
                  <th>Services</th>
                  <th v-for="c in colonnes" :key="c" class="eb-col-num">{{ c }}</th>
                  <th class="eb-col-num">Total {{ motPeriode(entete.duree) }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="c in consolideDe(s)" :key="c.compteNumero">
                  <td class="eb-col-compte"><span class="mono">{{ c.compteNumero }}</span> — {{ c.compteLibelle }}</td>
                  <td><v-chip v-for="n in c.services" :key="n" size="x-small" variant="tonal" class="mr-1 mb-1">{{ n }}</v-chip></td>
                  <td v-for="(v, i) in valeursColonnes(c.mensuel)" :key="i" class="eb-col-num eb-montant">{{ fmtMontant(v) }}</td>
                  <td class="eb-col-num eb-montant font-weight-bold">{{ fmtMontant(total(c.mensuel)) }}</td>
                </tr>
                <tr v-if="!consolideDe(s).length"><td :colspan="3 + colonnes.length" class="eb-vide">Aucune ligne de {{ SECTIONS[s].label.toLowerCase() }}.</td></tr>
              </tbody>
            </table>
          </div>
        </v-card>
      </template>

      <!-- Sections du groupe (lignes générales ou service) -->
      <template v-else>
        <v-card v-for="s in sectionsVisibles" :key="s" class="classroom-card mb-4">
          <div class="eb-section-tete" :style="{ borderLeftColor: SECTIONS[s].color }">
            <div>
              <span class="eb-section-tete__titre">{{ SECTIONS[s].label }}<template v-if="serviceActif !== null"> — {{ nomService(serviceActif) }}</template></span>
              <span class="eb-section-tete__sens">{{ SECTIONS[s].sens }}</span>
            </div>
            <div class="eb-section-tete__total">
              {{ fmtMontant(totalSection(s)) }} <small>USD</small>
            </div>
          </div>

          <div class="eb-table-wrap">
            <table class="eb-table" :style="{ minWidth: `${largeurTable}px` }">
              <thead>
                <tr>
                  <th class="eb-col-compte">Compte SYSCOHADA</th>
                  <th class="eb-col-num eb-col-annuel">{{ libelleMontant }}</th>
                  <th class="eb-col-mode">Répartition</th>
                  <th v-for="t in colonnes" :key="t" class="eb-col-num">{{ t }}</th>
                  <th class="eb-col-act" />
                </tr>
              </thead>
              <tbody>
                <template v-for="l in lignesPar(s)" :key="l.cle">
                  <tr>
                    <td class="eb-col-compte">
                      <v-autocomplete
                        aria-label="Compte SYSCOHADA"
                        :model-value="l.compteNumero"
                        :items="itemsPour(l)"
                        item-value="numero"
                        :item-title="(c: any) => `${c.numero} — ${c.libelle}`"
                        placeholder="Numéro ou libellé du compte"
                        density="compact"
                        hide-details
                        no-filter
                        :loading="rechercheEnCours"
                        @update:search="chercherComptes"
                        @update:model-value="(v: any) => choisirCompte(l, v)"
                      />
                      <div v-if="l.justificationIa" class="eb-ia-note">
                        <v-icon icon="mdi-robot-outline" size="12" /> {{ l.justificationIa }}
                      </div>
                      <div v-if="horsPeriode(l)" class="eb-hors-periode">
                        <v-icon icon="mdi-alert-outline" size="12" /> Montants hors de la période : ils ne seront pas enregistrés.
                      </div>
                    </td>
                    <td class="eb-col-num eb-col-annuel">
                      <v-text-field v-model.number="l.annuel" type="number" min="0" density="compact" hide-details :aria-label="libelleMontant" class="eb-annuel" @change="changerAnnuel(l)" />
                    </td>
                    <td class="eb-col-mode">
                      <v-select v-if="moisDeSaisie.length > 1" v-model="l.mode" :items="MODES" density="compact" hide-details aria-label="Mode de répartition" @update:model-value="appliquerMode(l)" />
                      <span v-else class="text-caption text-medium-emphasis">Un seul mois</span>
                    </td>
                    <td v-for="(v, i) in valeursColonnes(l.mensuel)" :key="i" class="eb-col-num eb-montant">{{ fmtMontant(v) }}</td>
                    <td class="eb-col-act">
                      <v-btn :icon="l.ouverte ? 'mdi-chevron-up' : 'mdi-calendar-month-outline'" size="small" variant="text"
                        :title="l.ouverte ? 'Masquer le détail' : (moisDeSaisie.length > 1 ? 'Voir et modifier les mois' : 'Hypothèse de calcul')" @click="l.ouverte = !l.ouverte" />
                      <v-btn icon="mdi-delete-outline" size="small" variant="text" color="error" title="Retirer la ligne" @click="retirerLigne(l)" />
                    </td>
                  </tr>
                  <tr v-if="l.ouverte" class="eb-detail">
                    <td :colspan="4 + colonnes.length">
                      <div v-if="moisDeSaisie.length > 1" class="eb-mois" :style="{ gridTemplateColumns: `repeat(${Math.min(12, moisDeSaisie.length)}, minmax(70px, 1fr))` }">
                        <div v-for="x in moisDeSaisie" :key="x.m" class="eb-mois__case">
                          <label>{{ x.m }}</label>
                          <input type="number" min="0" step="0.01" :value="l.mensuel[x.i]" @change="(e: any) => changerMois(l, x.i, e.target.value)">
                        </div>
                      </div>
                      <div v-if="entete.duree === 'ANNUEL'" class="eb-detail__bas">
                        <span>S1 : <strong>{{ fmtMontant(regrouper(l.mensuel, 'SEMESTRIEL')[0]) }}</strong></span>
                        <span>S2 : <strong>{{ fmtMontant(regrouper(l.mensuel, 'SEMESTRIEL')[1]) }}</strong></span>
                        <span>Année : <strong>{{ fmtMontant(total(l.mensuel)) }}</strong></span>
                        <span v-if="l.realiseReference">Réalisé de référence : <strong>{{ fmtMontant(l.realiseReference) }}</strong></span>
                      </div>
                      <div v-else class="eb-detail__bas">
                        <span>Total {{ motPeriode(entete.duree) }} : <strong>{{ fmtMontant(total(l.mensuel)) }}</strong></span>
                      </div>
                      <v-text-field v-model="l.commentaire" label="Hypothèse / base de calcul" placeholder="Ex. 2 véhicules × 120 l/mois × 1,6 USD" density="compact" hide-details class="mt-2" />
                    </td>
                  </tr>
                </template>
                <tr v-if="!lignesPar(s).length">
                  <td :colspan="4 + colonnes.length" class="eb-vide">Aucune ligne de {{ SECTIONS[s].label.toLowerCase() }}<template v-if="serviceActif !== null"> pour ce service</template>.</td>
                </tr>
              </tbody>
              <tfoot v-if="lignesPar(s).length">
                <tr>
                  <td>Total {{ SECTIONS[s].label.toLowerCase() }}</td>
                  <td class="eb-col-num eb-montant">{{ fmtMontant(totalSection(s)) }}</td>
                  <td />
                  <td v-for="(v, i) in valeursColonnes(mensuelSection(s))" :key="i" class="eb-col-num eb-montant">{{ fmtMontant(v) }}</td>
                  <td />
                </tr>
              </tfoot>
            </table>
          </div>
          <div class="pa-3">
            <v-btn variant="text" color="primary" prepend-icon="mdi-plus" @click="ajouterLigne(s)">Ajouter une ligne</v-btn>
          </div>
        </v-card>
        <p v-if="serviceActif !== null" class="eb-astuce">
          <v-icon icon="mdi-information-outline" size="16" />
          Les recettes (comptes 7) ne se rattachent pas à un service : budgétez-les dans l’onglet « Lignes générales ».
        </p>
      </template>

      <!-- Résultat et actions -->
      <v-card class="classroom-card pa-4 mb-4 eb-resultat">
        <div>
          <span class="eb-resultat__label">Résultat prévisionnel consolidé (produits − charges)</span>
          <span class="eb-resultat__valeur" :class="resultat >= 0 ? 'text-success' : 'text-error'">{{ fmtMontant(resultat) }} USD</span>
        </div>
        <div>
          <span class="eb-resultat__label">Investissements prévus</span>
          <span class="eb-resultat__valeur">{{ fmtMontant(totalConsolide('INVESTISSEMENTS')) }} USD</span>
        </div>
        <div v-if="servicesDuBudget.length">
          <span class="eb-resultat__label">Services</span>
          <span class="eb-resultat__valeur">{{ servicesDuBudget.length }}</span>
        </div>
        <div v-if="erreurEnregistrement" ref="zoneErreur" class="eb-resultat__erreur">
          <v-alert type="error" variant="tonal" density="compact" closable @click:close="erreurEnregistrement = ''">{{ erreurEnregistrement }}</v-alert>
        </div>
        <div class="eb-resultat__actions">
          <v-btn variant="text" @click="emit('annule')">Annuler</v-btn>
          <v-btn color="primary" :loading="enregistrement" prepend-icon="mdi-content-save-outline" @click="enregistrer">Enregistrer le brouillon</v-btn>
        </div>
      </v-card>
    </template>

    <!-- Confirmation -->
    <v-dialog v-model="confirmation.ouverte" max-width="460" @click:outside="refuserConfirmation">
      <v-card>
        <v-card-title>{{ confirmation.titre }}</v-card-title>
        <v-card-text>{{ confirmation.texte }}</v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="refuserConfirmation">Annuler</v-btn>
          <v-btn color="primary" variant="flat" @click="validerConfirmation">{{ confirmation.bouton }}</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Dialogue IA -->
    <v-dialog v-model="dialogueIa" max-width="860" scrollable>
      <v-card>
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-robot-outline" color="purple" class="mr-2" /> Proposition de budget — {{ libelle }}
        </v-card-title>
        <v-card-text>
          <p class="text-body-2 mb-3">
            L’IA étudie le réalisé du grand livre (exercice précédent, ou à défaut l’exercice en cours annualisé) et propose
            un budget ligne par ligne, ventilé par mois et justifié. <strong>Rien n’est enregistré</strong> : vous relisez et
            corrigez avant d’enregistrer.
            <template v-if="entete.duree !== 'ANNUEL'"> Seuls les mois de « {{ libelle }} » sont repris.</template>
            <template v-if="serviceActif !== null"> Les dépenses proposées vont dans le service « {{ nomService(serviceActif) }} » ; les recettes, dans les lignes générales.</template>
          </p>
          <v-textarea v-model="hypotheses" label="Hypothèses de la direction (facultatif)" placeholder="Ex. +10 % de ventes, recrutement d’un comptable en mars, achat d’un véhicule au 2e semestre" rows="2" auto-grow />
          <v-btn color="purple" :loading="propositionEnCours" prepend-icon="mdi-creation" @click="proposer">Établir la proposition</v-btn>

          <template v-if="proposition">
            <v-divider class="my-4" />
            <div class="d-flex align-center flex-wrap ga-2 mb-2">
              <v-chip :color="proposition.source === 'IA' ? 'purple' : 'blue-grey'" size="small" variant="tonal">
                {{ proposition.source === 'IA' ? 'Proposée par l’IA' : 'Calculée localement' }}
              </v-chip>
              <span class="text-caption text-medium-emphasis">Référence : {{ proposition.periodeReference }}</span>
            </div>
            <p class="text-body-2">{{ proposition.synthese }}</p>
            <v-alert v-for="(a, i) in proposition.avertissements" :key="i" type="info" variant="tonal" density="compact" class="mb-2">{{ a }}</v-alert>
            <v-table v-if="proposition.lignes.length" density="compact" class="mt-2">
              <thead>
                <tr><th>Compte</th><th>Section</th><th class="text-end">Réalisé de référence</th><th class="text-end">Proposé ({{ motPeriode(entete.duree) }})</th><th>Justification</th></tr>
              </thead>
              <tbody>
                <tr v-for="l in proposition.lignes" :key="l.compteNumero">
                  <td>{{ l.compteNumero }} — {{ l.compteLibelle }}</td>
                  <td>{{ libelleSection(l.section) }}</td>
                  <td class="text-end">{{ fmtMontant(l.realiseReference) }}</td>
                  <td class="text-end font-weight-medium">{{ fmtMontant(total(restreindre(l.mensuel))) }}</td>
                  <td class="text-caption">{{ l.justification }}</td>
                </tr>
              </tbody>
            </v-table>
          </template>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="dialogueIa = false">Fermer</v-btn>
          <v-btn color="primary" :disabled="!proposition?.lignes?.length || vueConsolidee" prepend-icon="mdi-check" @click="utiliserProposition">
            Utiliser cette proposition
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.eb-entete { display: flex; gap: 12px; align-items: flex-start; flex-wrap: wrap; }
.eb-entete__titre { flex: 1 1 300px; }
.eb-entete__exercice { flex: 0 0 120px; }
.eb-entete__duree { flex: 0 1 230px; min-width: 200px; }
.eb-entete__periode { flex: 0 1 250px; min-width: 190px; }
.eb-periode { margin: 10px 0 0; font-size: 0.82rem; color: #374151; }
.eb-periode .v-icon { vertical-align: -3px; margin-right: 4px; }
.eb-services__tete { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 12px 16px 4px; }
.eb-services__titre { font-weight: 700; font-size: 0.95rem; display: inline-flex; align-items: center; margin-right: 10px; }
.eb-services__aide { font-size: 0.78rem; color: #6b7280; }
.eb-onglets { border-bottom: 1px solid #eef0f3; }
.eb-onglet-montant { margin-left: 8px; font-size: 0.72rem; color: #6b7280; font-variant-numeric: tabular-nums; }
.eb-service-actif { display: flex; gap: 18px; align-items: center; flex-wrap: wrap; padding: 8px 16px; font-size: 0.84rem; background: #fafbff; }
.eb-astuce { font-size: 0.8rem; color: #4b5563; margin: -4px 0 14px; display: flex; gap: 6px; align-items: baseline; flex-wrap: wrap; }
.eb-section-tete { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; border-left: 4px solid; border-bottom: 1px solid #eef0f3; gap: 12px; flex-wrap: wrap; }
.eb-section-tete__titre { font-weight: 700; font-size: 1rem; margin-right: 10px; }
.eb-section-tete__sens { font-size: 0.78rem; color: #6b7280; }
.eb-section-tete__total { font-weight: 700; font-size: 1.05rem; white-space: nowrap; }
.eb-table-wrap { overflow-x: auto; }
.eb-table { width: 100%; border-collapse: collapse; }
.eb-table th { font-size: 0.72rem; text-transform: uppercase; color: #6b7280; font-weight: 600; text-align: left; padding: 8px 10px; background: #fafbfc; }
.eb-table th.eb-col-num { text-align: right; }
.eb-table td { padding: 6px 10px; border-top: 1px solid #f1f2f4; vertical-align: top; }
.eb-table tfoot td { font-weight: 700; background: #fafbfc; }
.eb-table .mono { font-family: ui-monospace, monospace; font-size: 0.8rem; }
.eb-col-compte { min-width: 340px; }
.eb-col-num { text-align: right; white-space: nowrap; width: 100px; }
.eb-col-mode { min-width: 180px; width: 180px; }
.eb-col-annuel { min-width: 160px; width: 160px; }
.eb-annuel :deep(input) { text-align: right; font-variant-numeric: tabular-nums; }
.eb-col-act { width: 92px; white-space: nowrap; text-align: right; }
.eb-montant { font-variant-numeric: tabular-nums; padding-top: 14px !important; }
.eb-vide { color: #9ca3af; font-size: 0.85rem; text-align: center; padding: 14px !important; }
.eb-ia-note { font-size: 0.72rem; color: #7c3aed; margin-top: 4px; }
.eb-hors-periode { font-size: 0.72rem; color: #b45309; margin-top: 4px; }
.eb-detail td { background: #fbfbff; }
.eb-mois { display: grid; gap: 6px; }
.eb-mois__case { display: flex; flex-direction: column; }
.eb-mois__case label { font-size: 0.7rem; color: #6b7280; }
.eb-mois__case input { border: 1px solid #d1d5db; border-radius: 6px; padding: 5px 6px; font-size: 0.82rem; text-align: right; width: 100%; background: #fff; }
.eb-detail__bas { display: flex; gap: 18px; flex-wrap: wrap; font-size: 0.8rem; color: #4b5563; margin-top: 8px; }
.eb-resultat { display: flex; gap: 24px; align-items: center; flex-wrap: wrap; }
.eb-resultat__label { display: block; font-size: 0.75rem; color: #6b7280; }
.eb-resultat__valeur { font-size: 1.15rem; font-weight: 700; }
.eb-resultat__erreur { flex: 1 1 100%; order: 3; }
.eb-resultat__actions { margin-left: auto; display: flex; gap: 8px; }
@media (max-width: 900px) {
  .eb-mois { grid-template-columns: repeat(4, minmax(70px, 1fr)) !important; }
}
</style>
