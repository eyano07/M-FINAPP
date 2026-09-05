<script setup lang="ts">
definePageMeta({ roles: ['ADMIN', 'DFIN'] })

interface Rapport {
  simulation: boolean
  lignesLues: number
  piecesDetectees: number
  piecesImportees: number
  totalDebit: number
  totalCredit: number
  erreurs: string[]
  avertissements: string[]
  references: string[]
  suggestions: Suggestion[]
}

interface Suggestion {
  type: string
  valeurActuelle: string
  valeurProposee: string | null
  libelle: string | null
  raison: string
  genereParIa: boolean
}

const api = useApi()
const config = useRuntimeConfig()
const auth = useAuthStore()

const fichier = ref<File | null>(null)
const rapport = ref<Rapport | null>(null)
const analyse = ref(false)
const importation = ref(false)
const erreur = ref('')
const succes = ref('')
/** Substitutions de comptes retenues par l'administrateur (ancien -> nouveau). */
const substitutions = ref<Record<string, string>>({})
const regroupement = ref<'REFERENCE' | 'JOUR' | 'FICHIER'>('REFERENCE')
const modeImport = ref<'REMPLACER' | 'AJOUTER'>('REMPLACER')
const MODES_IMPORT = [
  { value: 'REMPLACER', title: 'Remplacer — efface les écritures existantes' },
  { value: 'AJOUTER', title: 'Ajouter — conserve les écritures existantes' },
]
const confirmation = ref(false)
/** Devise dans laquelle le fichier est libellé, pas celle du grand livre. */
const devise = ref<'CDF' | 'USD'>('USD')
const DEVISES = [
  { value: 'USD', title: 'Dollars (USD) — montants déjà en devise de base, enregistrés tels quels' },
  { value: 'CDF', title: 'Francs congolais (FC) — converti au taux du jour de chaque écriture' },
]
const MODES = [
  { value: 'REFERENCE', title: 'Par N° de pièce (recommandé)' },
  { value: 'JOUR', title: 'Par jour — si la référence numérote chaque ligne' },
  { value: 'FICHIER', title: 'Une seule pièce — reprise d’à-nouveaux' },
]

const equilibre = computed(() =>
  rapport.value ? Number(rapport.value.totalDebit) === Number(rapport.value.totalCredit) : false)
const pretAImporter = computed(() =>
  !!rapport.value && rapport.value.simulation && rapport.value.erreurs.length === 0 && equilibre.value)

function onFichier(f: File | File[] | null) {
  fichier.value = Array.isArray(f) ? (f[0] ?? null) : f
  rapport.value = null
  succes.value = ''
  erreur.value = ''
  substitutions.value = {}
}

async function envoyer(simulation: boolean) {
  if (!fichier.value) {
    erreur.value = 'Choisissez un fichier .xlsx ou .csv.'
    return
  }
  simulation ? (analyse.value = true) : (importation.value = true)
  erreur.value = ''
  succes.value = ''
  try {
    const body = new FormData()
    body.append('fichier', fichier.value)
    const subs = Object.entries(substitutions.value).map(([a, b]) => `${a}:${b}`).join(',')
    const url = `/admin/import/journal?simulation=${simulation}&regroupement=${regroupement.value}`
      + `&devise=${devise.value}`
      + (subs ? `&substitutions=${encodeURIComponent(subs)}` : '')
    const r = await api<Rapport>(url, { method: 'POST', body })
    rapport.value = r
    if (!simulation) {
      succes.value = `${r.piecesImportees} pièce(s) importée(s) — le bilan, la balance et le compte de résultat sont à jour.`
    }
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'import.")
  } finally {
    analyse.value = false
    importation.value = false
  }
}

const exportJournal = ref(false)

/**
 * Exporte le seul journal deja enregistre dans l'application : sert a repartir
 * des donnees en place pour les corriger hors ligne, puis les reimporter. Les
 * etats financiers sont exclus a dessein — ils sont recalcules depuis le grand
 * livre et n'ont pas leur place dans un fichier destine a etre reinjecte.
 */
async function telechargerJournal() {
  exportJournal.value = true
  erreur.value = ''
  try {
    const annee = new Date().getFullYear()
    await telechargerFichier(api,
      `/comptabilite/journal/export?du=${annee}-01-01&au=${annee}-12-31`,
      `Journal_${annee}.xlsx`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de télécharger le journal.')
  } finally {
    exportJournal.value = false
  }
}

async function telechargerModele() {
  try {
    await telechargerFichier(api, '/admin/import/journal/modele', 'Modele_import_journal.xlsx')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de télécharger le modèle.')
  }
}

const telechargementCorrige = ref(false)
const creationEnCours = ref('')
const correctionAuto = ref(false)

/**
 * Prépare le plan comptable pour ce fichier : crée les comptes absents,
 * retient les sous-comptes de saisie, puis relance l'analyse. N'importe rien.
 */
async function corrigerTout() {
  if (!fichier.value) return
  correctionAuto.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const body = new FormData()
    body.append('fichier', fichier.value)
    const subs = await api<Record<string, string>>(
      `/admin/import/journal/correction-auto?regroupement=${regroupement.value}`,
      { method: 'POST', body })
    const n = Object.keys(subs).length
    substitutions.value = { ...substitutions.value, ...subs }
    succes.value = n > 0
      ? `${n} compte(s) préparé(s) : comptes manquants créés et sous-comptes retenus.`
      : 'Aucune correction automatique applicable — les comptes restants demandent votre arbitrage.'
    await envoyer(true)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la correction automatique.')
  } finally {
    correctionAuto.value = false
  }
}

const creations = computed(() =>
  (rapport.value?.suggestions ?? []).filter((s) => s.type === 'CREATION' && s.valeurProposee))

/**
 * Cree le compte manquant dans le plan comptable, puis substitue l'ancien
 * numero par le nouveau et relance l'analyse.
 */
async function creerCompte(s: Suggestion) {
  if (!s.valeurProposee) return
  creationEnCours.value = s.valeurActuelle
  erreur.value = ''
  try {
    const [parent, suffixe] = s.valeurProposee.split(/\.(.+)/)
    await api('/admin/comptes', {
      method: 'POST',
      body: { parentNumero: parent, suffixe, libelle: s.libelle || `Compte ${s.valeurActuelle}` },
    })
    substitutions.value[s.valeurActuelle] = s.valeurProposee
    succes.value = `Compte ${s.valeurProposee} créé et rattaché aux écritures ${s.valeurActuelle}.`
    await envoyer(true)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, `Impossible de créer le compte ${s.valeurProposee}.`)
  } finally {
    creationEnCours.value = ''
  }
}

/**
 * Telecharge le fichier corrige des substitutions proposees, sans rien
 * importer : il sert de piece justificative et peut etre relu, archive, puis
 * reimporte tel quel.
 */
async function telechargerCorrige() {
  if (!fichier.value) return
  telechargementCorrige.value = true
  erreur.value = ''
  try {
    const subs = suggestionsApplicables.value
      .map((s) => `${s.valeurActuelle}:${s.valeurProposee}`).join(',')
    const body = new FormData()
    body.append('fichier', fichier.value)
    const url = '/admin/import/journal/correction' + (subs ? `?substitutions=${encodeURIComponent(subs)}` : '')
    const blob = await api<Blob>(url, { method: 'POST', body, responseType: 'blob' })
    const href = URL.createObjectURL(blob as Blob)
    const a = document.createElement('a')
    a.href = href
    a.download = 'Journal_corrige.xlsx'
    document.body.appendChild(a); a.click(); a.remove()
    URL.revokeObjectURL(href)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de générer le fichier corrigé.')
  } finally {
    telechargementCorrige.value = false
  }
}

const suggestionsApplicables = computed(() =>
  (rapport.value?.suggestions ?? []).filter((s) => s.type === 'COMPTE' && s.valeurProposee))

function appliquerToutes() {
  for (const s of suggestionsApplicables.value) {
    if (s.valeurProposee) substitutions.value[s.valeurActuelle] = s.valeurProposee
  }
  envoyer(true)
}

const fmt = (v: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(v || 0)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Importer un journal</h1>
        <p class="page-sub">Reprise d’historique — peuple le grand livre, et donc tous les états financiers</p>
      </div>
      <div class="d-flex ga-2 flex-wrap">
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-file-download-outline"
          @click="telechargerModele">
          Modèle Excel
        </v-btn>
        <v-btn variant="tonal" color="success" rounded="lg" prepend-icon="mdi-microsoft-excel"
          :loading="exportJournal" @click="telechargerJournal">
          Télécharger le journal
        </v-btn>
      </div>
    </div>

    <v-alert type="info" variant="tonal" class="mb-4">
      Le bilan, la balance de vérification et le compte de résultat ne sont pas saisis :
      ils sont <strong>recalculés à partir du grand livre</strong>. Importer le journal les
      alimente donc tous en une seule fois, sans risque d’écart entre un état et les écritures
      qui le justifient.
    </v-alert>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-6 mb-4">
      <v-file-input
        label="Fichier .xlsx ou .csv"
        accept=".xlsx,.xls,.csv"
        variant="outlined"
        density="comfortable"
        prepend-icon="mdi-paperclip"
        show-size
        @update:model-value="onFichier"
      />
      <v-select v-model="devise" :items="DEVISES" label="Devise du fichier"
        variant="outlined" density="comfortable" class="mt-2" hide-details />
      <p class="text-caption text-medium-emphasis mt-2 mb-0">
        Un journal en dollars est enregistré tel quel, sans conversion : la devise est mémorisée
        sur chaque écriture. Convertir puis réafficher ferait subir aux montants un aller-retour
        par le taux.
      </p>

      <v-select v-model="modeImport" :items="MODES_IMPORT" label="Écritures déjà présentes"
        variant="outlined" density="comfortable" class="mt-2" hide-details />
      <p class="text-caption text-medium-emphasis mt-2 mb-0">
        Rejouer un import sans remplacer empile un second jeu d’écritures et double les soldes.
        Le remplacement ne touche pas aux écritures adossées à un mouvement réel de caisse,
        de banque ou de mobile money.
      </p>

      <v-select v-model="regroupement" :items="MODES" label="Regroupement des lignes en pièces"
        variant="outlined" density="comfortable" class="mt-2" hide-details />
      <p class="text-caption text-medium-emphasis mt-2 mb-0">
        Une pièce comptable doit être équilibrée. Si votre fichier incrémente le n° de pièce à
        chaque ligne, le regroupement par jour ou en pièce unique est nécessaire.
      </p>

      <div class="d-flex ga-2 flex-wrap mt-3">
        <v-btn color="primary" variant="flat" :loading="analyse" :disabled="!fichier"
          prepend-icon="mdi-magnify" @click="envoyer(true)">
          Analyser (sans rien écrire)
        </v-btn>
        <v-btn color="success" variant="flat" :loading="importation" :disabled="!pretAImporter"
          prepend-icon="mdi-database-import"
          @click="modeImport === 'REMPLACER' ? (confirmation = true) : envoyer(false)">
          Importer réellement
        </v-btn>
      </div>
      <p class="text-caption text-medium-emphasis mt-3 mb-0">
        L’analyse est obligatoire avant l’import : une reprise d’historique est lourde à défaire.
        L’import est tout ou rien — à la moindre anomalie, aucune écriture n’est conservée.
      </p>
    </v-card>

    <template v-if="rapport">
      <v-row class="mb-2">
        <v-col cols="6" md="3">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">Lignes lues</div>
            <div class="kpi-value">{{ rapport.lignesLues }}</div>
          </v-card>
        </v-col>
        <v-col cols="6" md="3">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">Pièces détectées</div>
            <div class="kpi-value">{{ rapport.piecesDetectees }}</div>
          </v-card>
        </v-col>
        <v-col cols="6" md="3">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">Total débit</div>
            <div class="kpi-value">{{ fmt(rapport.totalDebit) }}</div>
          </v-card>
        </v-col>
        <v-col cols="6" md="3">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">Total crédit</div>
            <div class="kpi-value" :class="equilibre ? 'text-success' : 'text-error'">
              {{ fmt(rapport.totalCredit) }}
            </div>
          </v-card>
        </v-col>
      </v-row>

      <v-alert :type="equilibre ? 'success' : 'error'" variant="tonal" class="mb-4">
        {{ equilibre
          ? 'Fichier équilibré : total débit = total crédit.'
          : 'Fichier déséquilibré — l’import est impossible tant que débit et crédit diffèrent.' }}
      </v-alert>

      <v-alert v-if="rapport.avertissements.length" type="warning" variant="tonal" class="mb-4">
        <ul class="pl-4 mb-0">
          <li v-for="(a, i) in rapport.avertissements" :key="i" class="text-body-2">{{ a }}</li>
        </ul>
      </v-alert>

      <v-alert v-if="rapport.erreurs.length" type="error" variant="tonal" class="mb-4">
        <div class="font-weight-bold mb-2">{{ rapport.erreurs.length }} anomalie(s) à corriger :</div>
        <ul class="pl-4 mb-0">
          <li v-for="(e, i) in rapport.erreurs.slice(0, 30)" :key="i" class="text-body-2">{{ e }}</li>
        </ul>
        <p v-if="rapport.erreurs.length > 30" class="text-caption mb-0 mt-2">
          … et {{ rapport.erreurs.length - 30 }} autre(s).
        </p>
      </v-alert>

      <v-alert v-else-if="rapport.simulation" type="success" variant="tonal" class="mb-4">
        Aucune anomalie détectée. Vous pouvez lancer l’import réel.
      </v-alert>

      <v-card v-if="rapport.suggestions.length" class="classroom-card pa-5 mb-4 sugg-card">
        <div class="d-flex align-center ga-2 mb-1">
          <v-icon icon="mdi-creation" color="deep-purple" size="20" />
          <span class="text-subtitle-2">Corrections proposées</span>
        </div>
        <p class="text-caption text-medium-emphasis mb-3">
          Les sous-comptes possibles sont lus dans votre plan comptable ; l’IA n’intervient que
          pour trancher entre plusieurs candidats, d’après le libellé de l’écriture.
          Rien n’est appliqué sans votre validation.
        </p>

        <div v-for="(s, i) in rapport.suggestions" :key="i" class="sugg-item">
          <div class="sugg-item__ligne">
            <span class="sugg-avant">{{ s.valeurActuelle }}</span>
            <v-btn v-if="s.type === 'CREATION' && s.valeurProposee" size="x-small" color="deep-purple"
              variant="flat" rounded="lg" class="ml-1" prepend-icon="mdi-plus"
              :loading="creationEnCours === s.valeurActuelle" @click="creerCompte(s)">
              Créer {{ s.valeurProposee }}
            </v-btn>
            <template v-if="s.valeurProposee">
              <v-icon icon="mdi-arrow-right" size="16" color="#9ca3af" />
              <span class="sugg-apres">{{ s.valeurProposee }}</span>
              <span v-if="s.libelle" class="sugg-libelle">{{ s.libelle }}</span>
            </template>
            <span v-if="s.genereParIa" class="sugg-badge">IA</span>
          </div>
          <p class="sugg-raison">{{ s.raison }}</p>
        </div>

        <div class="d-flex ga-2 flex-wrap mt-3">
          <v-btn color="deep-purple" variant="flat" rounded="lg" :loading="correctionAuto"
            prepend-icon="mdi-auto-fix" @click="corrigerTout">
            Tout corriger automatiquement
          </v-btn>
        </div>
        <p class="text-caption text-medium-emphasis mt-2 mb-0">
          Crée les comptes absents du plan comptable et retient les sous-comptes de saisie.
          Ne lance aucun import : l’analyse est relancée pour que vous vérifiiez le résultat.
        </p>

        <div class="d-flex ga-2 flex-wrap mt-3">
          <v-btn v-if="suggestionsApplicables.length" color="deep-purple" variant="flat" rounded="lg"
            :loading="analyse" prepend-icon="mdi-auto-fix" @click="appliquerToutes">
            Appliquer {{ suggestionsApplicables.length }} correction(s) et réanalyser
          </v-btn>
          <v-btn color="deep-purple" variant="tonal" rounded="lg"
            :loading="telechargementCorrige" prepend-icon="mdi-file-download-outline"
            @click="telechargerCorrige">
            Télécharger le journal corrigé
          </v-btn>
        </div>
        <p v-if="suggestionsApplicables.length" class="text-caption text-medium-emphasis mt-2 mb-0">
          Le fichier corrigé conserve le compte d’origine de chaque ligne modifiée : la correction
          reste vérifiable, et le fichier est réimportable tel quel.
        </p>
      </v-card>

      <v-card v-if="rapport.references.length" class="classroom-card pa-5">
        <div class="text-subtitle-2 mb-2">
          {{ rapport.simulation ? 'Pièces qui seront créées' : 'Pièces créées' }}
          ({{ rapport.references.length }})
        </div>
        <div class="d-flex flex-wrap ga-2">
          <span v-for="r in rapport.references.slice(0, 60)" :key="r" class="chip-soft"
            style="background:#f3f4f6;color:#374151">{{ r }}</span>
        </div>
        <p v-if="rapport.references.length > 60" class="text-caption text-medium-emphasis mt-2 mb-0">
          … et {{ rapport.references.length - 60 }} autre(s).
        </p>
      </v-card>
    </template>

    <v-dialog v-model="confirmation" max-width="520">
      <v-card>
        <v-card-title class="text-error">Remplacer les écritures existantes ?</v-card-title>
        <v-divider />
        <v-card-text>
          <p class="mb-3">
            Les écritures comptables actuelles vont être <strong>définitivement effacées</strong>
            avant l’import : imports précédents, pièces saisies à la main et soldes d’ouverture.
          </p>
          <p class="mb-0 text-body-2 text-medium-emphasis">
            Les écritures adossées à un encaissement ou un décaissement réel (caisse, banque,
            mobile money) sont conservées : les supprimer laisserait ces mouvements sans
            contrepartie comptable.
          </p>
        </v-card-text>
        <v-divider />
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="confirmation = false">Annuler</v-btn>
          <v-btn color="error" variant="flat" :loading="importation"
            @click="confirmation = false; envoyer(false)">
            Effacer et importer
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.kpi-label { font-size: 0.72rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.25rem; font-weight: 700; color: #111827; margin-top: 4px; }
.sugg-card { border-left: 3px solid #7c3aed !important; }
.sugg-item { padding: 10px 0; border-bottom: 1px solid #f6f6f6; }
.sugg-item:last-of-type { border-bottom: none; }
.sugg-item__ligne { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sugg-avant { font-family: monospace; font-weight: 700; color: #b91c1c; background: #fee2e2; padding: 1px 8px; border-radius: 6px; }
.sugg-apres { font-family: monospace; font-weight: 700; color: #15803d; background: #dcfce7; padding: 1px 8px; border-radius: 6px; }
.sugg-libelle { font-size: 0.8rem; color: #6b7280; }
.sugg-badge { font-size: 0.6rem; font-weight: 800; letter-spacing: 0.5px; color: #7c3aed; background: #ede9fe; padding: 2px 7px; border-radius: 100px; }
.sugg-raison { font-size: 0.78rem; color: #6b7280; margin: 4px 0 0; }
</style>
