<script setup lang="ts">
// Rapport de caisse du jour, de la semaine (lundi au dimanche) ou du mois : solde d'ouverture, chaque entrée et chaque
// sortie avec sa contrepartie, résumé par jour et par nature, solde de clôture. Tiré du compte de caisse du grand livre
// (comme le journal de caisse), converti par le serveur dans la devise choisie (USD / FC), imprimable en PDF.
definePageMeta({ module: 'CAISSE' })

type Periode = 'JOUR' | 'SEMAINE' | 'MOIS'

interface Mouvement {
  date: string
  heure?: string | null
  pieceReference?: string | null
  numeroRecu?: string | null
  libelle?: string | null
  compteNumero?: string | null
  compteLibelle?: string | null
  autresComptes?: string | null
  caissierNom?: string | null
  annulee: boolean
  montant: number
}

interface Jour {
  date: string
  entrees: number
  sorties: number
  net: number
  soldeCloture: number
  nombreEntrees: number
  nombreSorties: number
}

interface Nature {
  compteNumero: string
  compteLibelle?: string | null
  entrees: number
  sorties: number
  nombre: number
}

interface Rapport {
  periode: Periode
  date: string
  du: string
  au: string
  devise: DeviseCaisse
  taux?: number | null
  dateTaux?: string | null
  soldeOuverture: number
  totalEntrees: number
  totalSorties: number
  soldeCloture: number
  jours: Jour[]
  entrees: Mouvement[]
  sorties: Mouvement[]
  natures: Nature[]
}

const PERIODES: { value: Periode, titre: string, icone: string }[] = [
  { value: 'JOUR', titre: 'Jour', icone: 'mdi-calendar-today' },
  { value: 'SEMAINE', titre: 'Semaine', icone: 'mdi-calendar-week' },
  { value: 'MOIS', titre: 'Mois', icone: 'mdi-calendar-month' },
]
const TYPE_DOCUMENT: Record<Periode, string> = { JOUR: 'journalier', SEMAINE: 'hebdomadaire', MOIS: 'mensuel' }

const route = useRoute()
const router = useRouter()
const api = useApi()
const parametresStore = useParametresStore()
const rolesStore = useRolesStore()
const { devise, choisir } = useDeviseCaisse()

// Jour LOCAL au format AAAA-MM-JJ (toISOString donnerait le jour UTC : la veille juste après minuit en RDC).
function dateLocale(d = new Date()): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const versDate = (iso: string) => new Date(`${iso}T00:00:00`)
const periodeValide = (v: unknown): v is Periode => v === 'JOUR' || v === 'SEMAINE' || v === 'MOIS'
const dateValide = (v: unknown): v is string =>
  typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) && !Number.isNaN(Date.parse(v))

const periode = ref<Periode>(periodeValide(route.query.periode) ? route.query.periode : 'JOUR')
const date = ref<string>(dateValide(route.query.date) ? route.query.date : dateLocale())
const loading = ref(false)
const erreur = ref('')
const rapport = ref<Rapport | null>(null)
const dateImpression = ref('')

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const params = new URLSearchParams({ periode: periode.value, date: date.value, devise: devise.value })
    rapport.value = await api<Rapport>(`/caisse/rapport?${params}`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le rapport de caisse.')
    rapport.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  charger()
  parametresStore.charger()
})

// L'adresse garde la période et la date (lien partageable, retour arrière du navigateur) ; la devise reste un réglage
// personnel mémorisé.
watch([periode, date], () => {
  router.replace({ query: { periode: periode.value, date: date.value } })
  charger()
})
watch(() => [route.query.periode, route.query.date], ([p, d]) => {
  if (periodeValide(p) && p !== periode.value) periode.value = p
  if (dateValide(d) && d !== date.value) date.value = d
})

// Passer en francs demande au serveur un rapport converti ; sans taux défini il refuse : on revient alors à la devise
// précédente en gardant son message.
async function changerDevise(nouvelle: DeviseCaisse) {
  if (nouvelle === devise.value) return
  const avant = devise.value
  choisir(nouvelle)
  await charger()
  if (!rapport.value || rapport.value.devise !== nouvelle) {
    const message = erreur.value
    choisir(avant)
    await charger()
    erreur.value = message
  }
}

function decaler(sens: -1 | 1) {
  const d = versDate(date.value)
  if (periode.value === 'JOUR') d.setDate(d.getDate() + sens)
  else if (periode.value === 'SEMAINE') d.setDate(d.getDate() + 7 * sens)
  else { d.setDate(1); d.setMonth(d.getMonth() + sens) }
  date.value = dateLocale(d)
}
// On ne feuillette pas le futur : la caisse n'y a encore rien fait.
const peutAvancer = computed(() => !rapport.value || rapport.value.au < dateLocale())

function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  window.print()
}

// ── Affichage ───────────────────────────────────────────────────────────
// Les montants sont ceux de la réponse du serveur, dans SA devise (et non celle du bouton, qui peut avoir changé
// pendant qu'une réponse est en route).
const deviseAffichee = computed<DeviseCaisse>(() => rapport.value?.devise ?? devise.value)
const fmt = (montant: number) => fmtMontantCaisse(montant, deviseAffichee.value)
const unite = computed(() => uniteCaisse(deviseAffichee.value))
const fmtDate = (iso: string) => versDate(iso).toLocaleDateString('fr-FR')
const fmtHeure = (instant?: string | null) =>
  instant ? new Date(instant).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) : ''
const fmtTaux = (t: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 4 }).format(t)
const fmtJourSemaine = (iso: string) => versDate(iso).toLocaleDateString('fr-FR', { weekday: 'short', day: '2-digit', month: '2-digit' })

const libellePeriode = computed(() => {
  const r = rapport.value
  if (!r) return ''
  const du = versDate(r.du)
  const au = versDate(r.au)
  const jourMois = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long' })
  const complet = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })
  if (r.periode === 'JOUR') {
    return new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(du)
  }
  if (r.periode === 'MOIS') {
    return new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' }).format(du)
  }
  if (du.getFullYear() !== au.getFullYear()) return `du ${complet.format(du)} au ${complet.format(au)}`
  if (du.getMonth() !== au.getMonth()) return `du ${jourMois.format(du)} au ${complet.format(au)}`
  return `du ${du.getDate()} au ${complet.format(au)}`
})
const titreDocument = computed(() => (rapport.value ? `Rapport de caisse ${TYPE_DOCUMENT[rapport.value.periode]}` : 'Rapport de caisse'))

// Le titre de l'onglet devient le nom proposé au fichier quand on enregistre au format PDF.
useHead({ title: computed(() => (rapport.value ? `${titreDocument.value} - ${libellePeriode.value}` : 'Rapport de caisse')) })

const contrepartie = (m: Mouvement) =>
  m.compteNumero ? `${m.compteNumero}${m.compteLibelle ? ` — ${m.compteLibelle}` : ''}` : '—'
const sansMouvement = computed(() => !!rapport.value && rapport.value.entrees.length === 0 && rapport.value.sorties.length === 0)
</script>

<template>
  <div class="rc-page">
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Rapport de caisse</h1>
        <p class="page-sub">Entrées, sorties et solde de la caisse sur le jour, la semaine ou le mois</p>
      </div>
      <div class="rc-actions">
        <v-btn variant="outlined" color="primary" rounded="lg" prepend-icon="mdi-notebook-outline" to="/caisse/journal">
          Journal de caisse
        </v-btn>
        <v-btn v-if="rapport" color="error" variant="tonal" rounded="lg" prepend-icon="mdi-file-pdf-box" @click="imprimer">
          Imprimer / PDF
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <!-- Choix de la période et de la devise (masqué à l'impression) -->
    <v-card class="classroom-card pa-4 mb-4 no-print">
      <div class="rc-controles">
        <v-btn-toggle
          v-model="periode"
          mandatory
          divided
          density="comfortable"
          color="primary"
          variant="outlined"
          rounded="lg"
          aria-label="Période du rapport"
        >
          <v-btn v-for="p in PERIODES" :key="p.value" :value="p.value" :prepend-icon="p.icone">{{ p.titre }}</v-btn>
        </v-btn-toggle>

        <div class="rc-navigation">
          <v-btn icon="mdi-chevron-left" variant="text" aria-label="Période précédente" @click="decaler(-1)" />
          <v-text-field
            v-model="date"
            type="date"
            label="Date de référence"
            variant="outlined"
            density="comfortable"
            hide-details
            class="rc-date"
          />
          <v-btn icon="mdi-chevron-right" variant="text" aria-label="Période suivante" :disabled="!peutAvancer" @click="decaler(1)" />
          <v-btn variant="text" color="primary" class="rc-aujourdhui" @click="date = dateLocale()">Aujourd'hui</v-btn>
        </div>

        <v-spacer />
        <CaisseBasculeDevise :model-value="devise" :disabled="loading" @update:model-value="changerDevise" />
      </div>
    </v-card>

    <v-progress-linear v-if="loading" indeterminate color="primary" class="mb-4 no-print" />

    <template v-if="rapport">
      <!-- En-tête d'impression : papier à en-tête de l'entreprise (visible seulement sur le document imprimé) -->
      <div class="etat-print-header print-entete">
        <div class="etat-print-header__brand print-entete__marque">
          <div class="etat-print-header__logo print-entete__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
            <v-icon v-else icon="mdi-finance" size="16" color="white" />
          </div>
          <div>
            <span class="etat-print-header__company print-entete__nom">{{ parametresStore.parametres.nom }}</span>
            <span class="etat-print-header__doc">{{ titreDocument }}</span>
          </div>
        </div>
        <div class="etat-print-header__meta print-entete__meta">
          <span>Période : <strong>{{ libellePeriode }}</strong></span>
          <span>Devise : <strong>{{ unite }}</strong><template v-if="rapport.taux"> (1 USD = {{ fmtTaux(rapport.taux) }} FC)</template></span>
          <span>Imprimé le : {{ dateImpression }}</span>
        </div>
      </div>

      <h2 class="rc-periode no-print">{{ libellePeriode }}</h2>

      <v-alert v-if="rapport.devise === 'CDF' && rapport.taux" type="info" variant="tonal" density="compact" class="mb-4 rc-note"
        icon="mdi-swap-horizontal">
        Montants convertis en francs congolais au taux de <strong>{{ fmtTaux(rapport.taux) }} FC pour 1 USD</strong>
        (taux du {{ rapport.dateTaux ? fmtDate(rapport.dateTaux) : '' }}). La comptabilité est tenue en USD : de petits écarts
        d'arrondi sont possibles.
      </v-alert>

      <!-- Synthèse -->
      <div class="rc-kpis">
        <v-card class="classroom-card pa-4 text-center">
          <div class="kpi-label">Solde d'ouverture</div>
          <div class="kpi-value">{{ fmt(rapport.soldeOuverture) }}</div>
        </v-card>
        <v-card class="classroom-card pa-4 text-center rc-kpi--entrees">
          <div class="kpi-label">Entrées ({{ rapport.entrees.length }})</div>
          <div class="kpi-value">{{ fmt(rapport.totalEntrees) }}</div>
        </v-card>
        <v-card class="classroom-card pa-4 text-center rc-kpi--sorties">
          <div class="kpi-label">Sorties ({{ rapport.sorties.length }})</div>
          <div class="kpi-value">{{ fmt(rapport.totalSorties) }}</div>
        </v-card>
        <v-card class="classroom-card pa-4 text-center rc-kpi--cloture">
          <div class="kpi-label">Solde de clôture</div>
          <div class="kpi-value">{{ fmt(rapport.soldeCloture) }}</div>
        </v-card>
      </div>

      <v-card v-if="sansMouvement" class="classroom-card pa-8 text-center mb-4">
        <v-icon icon="mdi-cash-register" size="42" color="grey" class="mb-2" />
        <div class="text-medium-emphasis">Aucun mouvement de caisse sur cette période.</div>
      </v-card>

      <!-- Résumé par jour (semaine et mois) -->
      <v-card v-if="rapport.periode !== 'JOUR' && rapport.jours.length" class="classroom-card mb-4 rc-section">
        <v-card-title class="rc-titre"><v-icon icon="mdi-calendar-range" color="primary" /> Résumé par jour</v-card-title>
        <v-divider />
        <v-table density="compact" class="table-impression-compacte table-cartes">
          <thead>
            <tr>
              <th>Jour</th>
              <th class="text-right">Entrées</th>
              <th class="text-right">Sorties</th>
              <th class="text-right">Variation</th>
              <th class="text-right">Solde en fin de journée</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="j in rapport.jours" :key="j.date">
              <td class="text-capitalize">{{ fmtJourSemaine(j.date) }}</td>
              <td class="text-right text-no-wrap cellule-montant" data-label="Entrées">{{ j.nombreEntrees ? fmt(j.entrees) : '—' }}</td>
              <td class="text-right text-no-wrap cellule-montant" data-label="Sorties">{{ j.nombreSorties ? fmt(j.sorties) : '—' }}</td>
              <td class="text-right text-no-wrap cellule-montant" data-label="Variation" :class="j.net < 0 ? 'rc-negatif' : ''">{{ fmt(j.net) }}</td>
              <td class="text-right text-no-wrap font-weight-medium cellule-montant" data-label="Solde en fin de journée">{{ fmt(j.soldeCloture) }}</td>
            </tr>
          </tbody>
          <tfoot>
            <tr class="font-weight-bold">
              <td>Total</td>
              <td class="text-right text-no-wrap cellule-montant" data-label="Entrées">{{ fmt(rapport.totalEntrees) }}</td>
              <td class="text-right text-no-wrap cellule-montant" data-label="Sorties">{{ fmt(rapport.totalSorties) }}</td>
              <td class="text-right text-no-wrap cellule-montant" data-label="Variation">{{ fmt(rapport.totalEntrees - rapport.totalSorties) }}</td>
              <td class="text-right text-no-wrap cellule-montant" data-label="Solde en fin de journée">{{ fmt(rapport.soldeCloture) }}</td>
            </tr>
          </tfoot>
        </v-table>
      </v-card>

      <!-- Entrées puis sorties : chaque mouvement -->
      <template v-for="bloc in [
        { cle: 'entrees', titre: 'Entrées de caisse', libelleTotal: 'Total des entrées', icone: 'mdi-cash-plus', couleur: 'success', lignes: rapport.entrees, total: rapport.totalEntrees },
        { cle: 'sorties', titre: 'Sorties de caisse', libelleTotal: 'Total des sorties', icone: 'mdi-cash-minus', couleur: 'error', lignes: rapport.sorties, total: rapport.totalSorties },
      ]" :key="bloc.cle">
        <v-card v-if="bloc.lignes.length" class="classroom-card mb-4 rc-section">
          <v-card-title class="rc-titre">
            <v-icon :icon="bloc.icone" :color="bloc.couleur" />
            {{ bloc.titre }} ({{ bloc.lignes.length }})
            <v-spacer />
            <span class="font-weight-bold">{{ fmt(bloc.total) }}</span>
          </v-card-title>
          <v-divider />
          <v-table density="compact" class="table-impression-compacte table-cartes">
            <thead>
              <tr>
                <th>Date</th>
                <th>Pièce / reçu</th>
                <th>Libellé</th>
                <th>Contrepartie</th>
                <th class="text-right text-no-wrap">Montant ({{ unite }})</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(m, i) in bloc.lignes" :key="i" :class="{ 'rc-annulee': m.annulee }">
                <td class="text-no-wrap">
                  {{ fmtDate(m.date) }}
                  <div v-if="m.heure" class="rc-secondaire">{{ fmtHeure(m.heure) }}</div>
                </td>
                <td class="text-no-wrap cellule-titre">
                  <span class="font-weight-medium">{{ m.pieceReference || '—' }}</span>
                  <v-chip v-if="m.annulee" size="x-small" color="error" variant="tonal" class="ml-1">Annulée</v-chip>
                  <div v-if="m.numeroRecu" class="rc-secondaire">{{ m.numeroRecu }}</div>
                </td>
                <td class="cellule-large">
                  {{ m.libelle || '—' }}
                  <div v-if="m.caissierNom" class="rc-secondaire">{{ m.caissierNom }}</div>
                </td>
                <td data-label="Contrepartie">
                  {{ contrepartie(m) }}
                  <div v-if="m.autresComptes" class="rc-secondaire">+ {{ m.autresComptes }}</div>
                </td>
                <td class="text-right text-no-wrap font-weight-medium cellule-montant" data-label="Montant">{{ fmt(m.montant) }}</td>
              </tr>
            </tbody>
            <tfoot>
              <tr class="font-weight-bold">
                <td colspan="4">{{ bloc.libelleTotal }}</td>
                <td class="text-right text-no-wrap cellule-montant" data-label="Total">{{ fmt(bloc.total) }}</td>
              </tr>
            </tfoot>
          </v-table>
        </v-card>
      </template>

      <!-- Résumé par nature : le compte de contrepartie principal de chaque mouvement -->
      <v-card v-if="rapport.natures.length" class="classroom-card mb-4 rc-section">
        <v-card-title class="rc-titre"><v-icon icon="mdi-shape-outline" color="deep-purple" /> Résumé par nature</v-card-title>
        <v-divider />
        <v-table density="compact" class="table-impression-compacte table-cartes">
          <thead>
            <tr>
              <th>Compte</th>
              <th>Libellé</th>
              <th class="text-right">Mouvements</th>
              <th class="text-right">Entrées</th>
              <th class="text-right">Sorties</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="n in rapport.natures" :key="n.compteNumero">
              <td class="font-weight-medium">{{ n.compteNumero || '—' }}</td>
              <td class="cellule-titre">{{ n.compteNumero ? (n.compteLibelle || '') : 'Sans contrepartie identifiée' }}</td>
              <td class="text-right" data-label="Mouvements">{{ n.nombre }}</td>
              <td class="text-right text-no-wrap cellule-montant" data-label="Entrées">{{ n.entrees ? fmt(n.entrees) : '—' }}</td>
              <td class="text-right text-no-wrap cellule-montant" data-label="Sorties">{{ n.sorties ? fmt(n.sorties) : '—' }}</td>
            </tr>
          </tbody>
          <tfoot>
            <tr class="font-weight-bold">
              <td colspan="2" class="cellule-titre">Total</td>
              <td class="text-right" data-label="Mouvements">{{ rapport.entrees.length + rapport.sorties.length }}</td>
              <td class="text-right text-no-wrap cellule-montant" data-label="Entrées">{{ fmt(rapport.totalEntrees) }}</td>
              <td class="text-right text-no-wrap cellule-montant" data-label="Sorties">{{ fmt(rapport.totalSorties) }}</td>
            </tr>
          </tfoot>
        </v-table>
      </v-card>

      <!-- Visas (visibles seulement sur le document imprimé) -->
      <div class="rc-signatures">
        <div class="rc-signature">
          <span class="rc-signature__titre">Établi par — {{ rolesStore.libelle('CAISSIER') }}</span>
          <div class="rc-signature__ligne" />
          <span class="rc-signature__aide">Nom, date et signature</span>
        </div>
        <div class="rc-signature">
          <span class="rc-signature__titre">Vérifié par — {{ rolesStore.libelle('DFIN') }}</span>
          <div class="rc-signature__ligne" />
          <span class="rc-signature__aide">Nom, date et signature</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.rc-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.rc-controles { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.rc-navigation { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.rc-date { width: 190px; }
.rc-periode { font-size: 1.15rem; font-weight: 700; color: #111827; margin: 0 0 12px; }
.rc-periode::first-letter { text-transform: uppercase; }
.rc-kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.kpi-label { font-size: 0.72rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.25rem; font-weight: 700; color: #111827; margin-top: 4px; overflow-wrap: anywhere; }
.rc-kpi--entrees .kpi-value { color: #15803d; }
.rc-kpi--sorties .kpi-value { color: #b91c1c; }
.rc-kpi--cloture { border: 1px solid #99f6e4; background: #f0fdfa; }
.rc-titre { display: flex; align-items: center; gap: 8px; font-size: 1rem; font-weight: 600; }
.rc-secondaire { font-size: 0.72rem; color: #6b7280; }
.rc-negatif { color: #b91c1c; }
.rc-annulee td { color: #9ca3af; text-decoration: line-through; }
.rc-annulee td .v-chip, .rc-annulee td .rc-secondaire { text-decoration: none; }
.rc-signatures { display: none; }

@media (max-width: 900px) {
  .rc-kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 600px) {
  .rc-controles { gap: 12px; }
  .rc-navigation { width: 100%; justify-content: space-between; }
  .rc-date { flex: 1 1 120px; width: auto; min-width: 0; }
  .rc-aujourdhui { flex-basis: 100%; }
}

@media print {
  .rc-kpis { grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
  .rc-kpis .v-card { padding: 8px !important; border: 1px solid #e5e7eb; }
  .rc-kpis .kpi-value { font-size: 1rem; }
  .rc-periode { display: none; }
  .rc-section { break-inside: auto; border: 1px solid #e5e7eb; margin-bottom: 12px !important; }
  .rc-section tr { break-inside: avoid; }
  .rc-section thead { display: table-header-group; }
  .rc-signatures { display: flex; gap: 40px; margin-top: 36px; break-inside: avoid; }
  .rc-signature { flex: 1; display: flex; flex-direction: column; gap: 4px; }
  .rc-signature__titre { font-size: 0.8rem; font-weight: 700; color: #111827; }
  .rc-signature__ligne { height: 56px; border-bottom: 1px solid #111827; }
  .rc-signature__aide { font-size: 0.68rem; color: #6b7280; }
}
</style>
