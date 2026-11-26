<script setup lang="ts">
definePageMeta({ module: 'PATRIMOINE' })

interface Immo {
  id: number
  reference: string
  libelle: string
  categorie: string
  compteImmobilisation: string | null
  dateAcquisition: string
  dateMiseService: string
  valeurAcquisition: number
  valeurResiduelle: number
  dureeMois: number
  statut: string
  localisation: string | null
  responsableNom: string | null
  fournisseur: string | null
  numeroSerie: string | null
  cumulAmortissements: number
  valeurNetteComptable: number
  description?: string | null
  codeInventaire?: string | null
  dotationsComptabilisees?: boolean
  modeAmortissement?: 'LINEAIRE' | 'DEGRESSIF'
}

const CATEGORIES = [
  { value: 'LOGICIEL', title: 'Logiciel' },
  { value: 'BATIMENT', title: 'Bâtiment' },
  { value: 'MATERIEL_INDUSTRIEL', title: 'Matériel industriel' },
  { value: 'MATERIEL_BUREAU', title: 'Matériel de bureau' },
  { value: 'MATERIEL_INFORMATIQUE', title: 'Matériel informatique' },
  { value: 'MOBILIER', title: 'Mobilier' },
  { value: 'MATERIEL_TRANSPORT', title: 'Matériel de transport' },
  { value: 'AUTRE', title: 'Autre' },
]

const statutMeta: Record<string, { label: string; bg: string; color: string }> = {
  EN_SERVICE: { label: 'En service', bg: 'var(--color-primary-light)', color: 'var(--color-primary-dark)' },
  CEDE: { label: 'Cédé', bg: '#dbeafe', color: '#1d4ed8' },
  REBUT: { label: 'Rebut', bg: '#f3f4f6', color: '#6b7280' },
}

const api = useApi()
const auth = useAuthStore()
// LOGISTIQUE saisit les biens et definit leur amortissement (createur reel du
// materiel au quotidien) mais ne cede/ne met pas au rebut : cette decision
// reste a GEST_PATRIMOINE — voir les memes roles cote serveur (creer() vs
// sortir() dans PatrimoineService).
// Mêmes rôles que PatrimoineService.creer (administrateur compris).
const canCreer = computed(() => auth.hasAnyRole(['GEST_PATRIMOINE', 'LOGISTIQUE', 'ADMIN']))
const canSortir = computed(() => auth.hasAnyRole(['GEST_PATRIMOINE', 'ADMIN']))

// Le grand livre est tenu en dollars (devise de base) : la saisie et l'affichage se font en USD, sans conversion.
// (19/11/2026 : la page multipliait la saisie par le taux du jour, comme si le grand livre était en francs.)
const tauxChange = ref(0)
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const biens = ref<Immo[]>([])
const dialog = ref(false)
const dialogSortie = ref(false)
const bienSortie = ref<Immo | null>(null)

const form = reactive({
  libelle: '', categorie: 'MATERIEL_BUREAU', description: '',
  dateAcquisition: new Date().toISOString().slice(0, 10),
  dateMiseService: new Date().toISOString().slice(0, 10),
  valeurAcquisition: null as number | null,
  valeurResiduelle: 0,
  dureeMois: 60,
  localisation: '', fournisseur: '', numeroSerie: '',
  comptabiliserAcquisition: false,
  compteContrepartieNumero: '4812',
  // Bien principal dont celui-ci est un composant, amorti sur sa propre durée (audit SYSCOHADA, C-18).
  parentId: null as number | null,
})

// La cession crée une créance sur l'acheteur (4851) ; l'encaissement passe ensuite par la caisse ou la banque :
// jamais un compte de trésorerie ici (C-08). Cession courante (654/754) ou hors activités ordinaires (812/822).
const formSortie = reactive({
  dateSortie: new Date().toISOString().slice(0, 10),
  valeurCession: 0,
  compteContrepartieNumero: '4851',
  typeCession: 'HAO' as 'COURANTE' | 'HAO',
})
const TYPES_CESSION = [
  { title: 'Hors activités ordinaires (812 / 822)', value: 'HAO' },
  { title: 'Cession courante, renouvellement (6542 / 7542)', value: 'COURANTE' },
]
const biensPrincipaux = computed(() => biens.value
  .filter((b: any) => !b.parentId && b.statut === 'EN_SERVICE')
  .map((b: any) => ({ title: `${b.reference} — ${b.libelle}`, value: b.id })))

const aValoriser = ref(0)

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [data, taux, inv] = await Promise.all([
      api<Immo[]>('/patrimoine/immobilisations'),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
      api<{ statut: string }[]>('/patrimoine/inventaire').catch(() => []),
    ])
    biens.value = data
    // Biens relevés à l'inventaire sans valeur : ils n'entrent au registre qu'une fois valorisés.
    aValoriser.value = inv.filter(x => x.statut === 'A_VALORISER').length
    tauxChange.value = taux.taux || 0
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le registre des immobilisations.')
  } finally {
    loading.value = false
  }
}
// Pas de saisie directe d'un bien (25/11/2026) : toute acquisition passe par une note de frais (DFIN, DA, caisse) ;
// les biens existants entrent par l'inventaire (Biens et étiquettes › Valoriser).
onMounted(charger)

function ouvrirAjout() {
  Object.assign(form, {
    libelle: '', categorie: 'MATERIEL_BUREAU', description: '',
    dateAcquisition: new Date().toISOString().slice(0, 10),
    dateMiseService: new Date().toISOString().slice(0, 10),
    valeurAcquisition: null, valeurResiduelle: 0, dureeMois: 60,
    localisation: '', fournisseur: '', numeroSerie: '',
    comptabiliserAcquisition: false, compteContrepartieNumero: '4812', parentId: null,
  })
  erreur.value = ''
  dialog.value = true
}

async function enregistrer() {
  if (!form.libelle || !form.valeurAcquisition || !form.dureeMois) {
    erreur.value = 'Libellé, valeur d’acquisition et durée sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    await api('/patrimoine/immobilisations', {
      method: 'POST',
      body: {
        ...form,
        valeurAcquisition: form.valeurAcquisition ?? 0,
        valeurResiduelle: form.valeurResiduelle ?? 0,
      },
    })
    succes.value = 'Bien enregistré et plan d’amortissement généré.'
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

function ouvrirSortie(b: Immo) {
  bienSortie.value = b
  formSortie.dateSortie = new Date().toISOString().slice(0, 10)
  formSortie.valeurCession = 0
  formSortie.compteContrepartieNumero = '4851'
  formSortie.typeCession = 'HAO'
  erreur.value = ''
  dialogSortie.value = true
}

async function confirmerSortie() {
  if (!bienSortie.value) return
  saving.value = true
  erreur.value = ''
  try {
    await api(`/patrimoine/immobilisations/${bienSortie.value.id}/sortie`, {
      method: 'POST',
      body: { ...formSortie, valeurCession: formSortie.valeurCession ?? 0 },
    })
    succes.value = formSortie.valeurCession > 0 ? 'Cession comptabilisée.' : 'Mise au rebut comptabilisée.'
    dialogSortie.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la sortie.')
  } finally {
    saving.value = false
  }
}

// Le grand livre est tenu en dollars (ConversionDeviseService.DEVISE_BASE) : les montants s'affichent tels quels.
const toUSD = (usd: number) => Number(usd) || 0
/** Montants affiches en dollars, convertis depuis la valeur FC enregistree. */
const fmt = (fc: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 })
    .format(toUSD(fc))
const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')

const totalBrut = computed(() => biens.value.filter(b => b.statut === 'EN_SERVICE')
  .reduce((s, b) => s + Number(b.valeurAcquisition), 0))
const totalVnc = computed(() => biens.value.filter(b => b.statut === 'EN_SERVICE')
  .reduce((s, b) => s + Number(b.valeurNetteComptable), 0))

// ── Modification de la fiche d'amortissement (responsable logistique, 19/11/2026) ──
const fiche = reactive({ ouvert: false, bien: null as Immo | null, libelle: '', categorie: '', description: '', localisation: '', fournisseur: '',
  numeroSerie: '', dureeMois: 60, modeAmortissement: 'LINEAIRE', valeurResiduelle: 0, valeurAcquisition: 0, dateAcquisition: '',
  dateMiseService: '', motif: '', enCours: false, erreur: '' })
function ouvrirFiche(b: Immo) {
  Object.assign(fiche, { ouvert: true, bien: b, libelle: b.libelle, categorie: b.categorie, description: b.description ?? '',
    localisation: b.localisation ?? '', fournisseur: b.fournisseur ?? '', numeroSerie: b.numeroSerie ?? '', dureeMois: b.dureeMois,
    modeAmortissement: b.modeAmortissement ?? 'LINEAIRE', valeurResiduelle: Number(b.valeurResiduelle) || 0,
    valeurAcquisition: Number(b.valeurAcquisition), dateAcquisition: b.dateAcquisition, dateMiseService: b.dateMiseService,
    motif: '', enCours: false, erreur: '' })
}
async function enregistrerFiche() {
  if (!fiche.bien) return
  fiche.enCours = true
  fiche.erreur = ''
  try {
    await api(`/patrimoine/immobilisations/${fiche.bien.id}`, { method: 'PUT', body: {
      libelle: fiche.libelle, categorie: fiche.categorie, description: fiche.description || null, localisation: fiche.localisation || null,
      fournisseur: fiche.fournisseur || null, numeroSerie: fiche.numeroSerie || null, dureeMois: fiche.dureeMois,
      modeAmortissement: fiche.modeAmortissement, valeurResiduelle: fiche.valeurResiduelle, valeurAcquisition: fiche.valeurAcquisition,
      dateAcquisition: fiche.dateAcquisition, dateMiseService: fiche.dateMiseService, motif: fiche.motif || null,
    } })
    fiche.ouvert = false
    succes.value = `Fiche ${fiche.bien.reference} modifiée : le plan d'amortissement est recalculé.`
    await charger()
  } catch (e) {
    fiche.erreur = messageErreurApi(e, "La fiche n'a pas été modifiée.")
  } finally {
    fiche.enCours = false
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Immobilisations</h1>
        <p class="page-sub">Registre des biens, valeur nette et plan d’amortissement</p>
      </div>
    </div>

    <v-alert v-if="aValoriser" type="info" variant="tonal" class="mb-4" icon="mdi-tag-text-outline">
      <strong>{{ aValoriser }} bien(s) de l’inventaire attendent leur valeur.</strong>
      Ils entrent au registre (fiche, plan d’amortissement et écriture) dès que leur valeur et leur date d’acquisition sont saisies.
      <template #append><v-btn to="/patrimoine/inventaire" variant="flat" color="primary" size="small">Valoriser</v-btn></template>
    </v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur && !dialog && !dialogSortie" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-row class="mb-2">
      <v-col cols="6" md="3">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Valeur brute en service</div>
          <div class="kpi-value">{{ fmt(totalBrut) }}</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="3">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Valeur nette comptable</div>
          <div class="kpi-value text-success">{{ fmt(totalVnc) }}</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="3">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Amortissements cumulés</div>
          <div class="kpi-value">{{ fmt(totalBrut - totalVnc) }}</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="3">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Biens en service</div>
          <div class="kpi-value">{{ biens.filter(b => b.statut === 'EN_SERVICE').length }}</div>
        </v-card>
      </v-col>
    </v-row>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Référence', key: 'reference' },
          { title: 'Libellé', key: 'libelle' },
          { title: 'Catégorie', key: 'categorie' },
          { title: 'Mise en service', key: 'dateMiseService' },
          { title: 'Valeur brute', key: 'valeurAcquisition', align: 'end' },
          { title: 'VNC', key: 'valeurNetteComptable', align: 'end' },
          { title: 'Statut', key: 'statut' },
          { title: 'Actions', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="biens"
        :loading="loading"
        items-per-page="25"
      >
        <template #item.libelle="{ item }">
          {{ item.libelle }}
          <div v-if="item.codeInventaire" class="text-caption text-medium-emphasis"><code>{{ item.codeInventaire }}</code></div>
        </template>
        <template #item.dateMiseService="{ item }">{{ fmtDate(item.dateMiseService) }}</template>
        <template #item.valeurAcquisition="{ item }">{{ fmt(item.valeurAcquisition) }}</template>
        <template #item.valeurNetteComptable="{ item }">{{ fmt(item.valeurNetteComptable) }}</template>
        <template #item.categorie="{ item }">
          {{ CATEGORIES.find(c => c.value === item.categorie)?.title ?? item.categorie }}
        </template>
        <template #item.statut="{ item }">
          <span class="chip-soft" :style="{ background: statutMeta[item.statut]?.bg, color: statutMeta[item.statut]?.color }">
            {{ statutMeta[item.statut]?.label ?? item.statut }}
          </span>
        </template>
        <template #item.actions="{ item }">
          <v-btn v-if="canCreer && item.statut === 'EN_SERVICE'" size="small" variant="text" icon="mdi-pencil"
            title="Modifier la fiche d'amortissement" @click="ouvrirFiche(item)" />
          <v-btn size="small" variant="text" icon="mdi-chart-timeline-variant-shimmer"
            :to="`/patrimoine/amortissements?bien=${item.id}`" title="Plan d'amortissement" />
          <v-btn v-if="canSortir && item.statut === 'EN_SERVICE'" size="small" variant="text"
            color="error" icon="mdi-logout" title="Céder / mettre au rebut" @click="ouvrirSortie(item)" />
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">
            {{ aValoriser ? 'Aucun bien valorisé pour l’instant : commencez par l’inventaire (bouton « Valoriser » ci-dessus).' : 'Aucun bien enregistré. Les biens entrent par une note de frais d’acquisition ou par l’inventaire.' }}
          </div>
        </template>
      </v-data-table>
    </v-card>

    <!-- Création -->
    <v-dialog v-model="dialog" max-width="720" scrollable>
      <v-card>
        <v-card-title>Nouveau bien immobilisé</v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>
          <v-row>
            <v-col cols="12" md="8"><v-text-field v-model="form.libelle" label="Libellé *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-select v-model="form.categorie" :items="CATEGORIES" label="Catégorie *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.dateAcquisition" type="date" label="Date d'acquisition *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.dateMiseService" type="date" label="Mise en service *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model.number="form.valeurAcquisition" type="number" label="Valeur d'acquisition (USD) *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model.number="form.valeurResiduelle" type="number" label="Valeur résiduelle (USD)" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model.number="form.dureeMois" type="number" label="Durée (mois) *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.localisation" label="Localisation" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.fournisseur" label="Fournisseur" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.numeroSerie" label="N° de série" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12">
              <v-checkbox v-model="form.comptabiliserAcquisition" hide-details
                label="Générer l'écriture d'acquisition (débit classe 2 / crédit contrepartie)" />
            </v-col>
            <v-col v-if="form.comptabiliserAcquisition" cols="12" md="6">
              <ComptabiliteSelecteurCompte v-model="form.compteContrepartieNumero" label="Compte de contrepartie (ex. 4812)"
                exclure-prefixe="5" />
            </v-col>
            <v-col cols="12" md="6">
              <v-select v-model="form.parentId" :items="biensPrincipaux" clearable variant="outlined" density="comfortable"
                label="Composant du bien (facultatif)"
                hint="Un composant (moteur, toiture...) a sa propre durée d'amortissement" persistent-hint />
            </v-col>
          </v-row>
          <p class="text-caption text-medium-emphasis mt-2">
            Le plan d'amortissement linéaire est calculé automatiquement à partir de la mise en service (prorata au jour)
            et de la durée.
          </p>
        </v-card-text>
        <v-divider />
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="saving" @click="dialog = false">Annuler</v-btn>
          <v-btn color="success" variant="flat" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Sortie -->
    <v-dialog v-model="dialogSortie" max-width="520">
      <v-card>
        <v-card-title>Sortie du patrimoine</v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>
          <p class="mb-3 text-body-2">
            <strong>{{ bienSortie?.reference }}</strong> — {{ bienSortie?.libelle }}<br>
            Valeur nette comptable : <strong>{{ fmt(bienSortie?.valeurNetteComptable ?? 0) }}</strong>
          </p>
          <v-text-field v-model="formSortie.dateSortie" type="date" label="Date de sortie" variant="outlined" density="comfortable" />
          <v-text-field v-model.number="formSortie.valeurCession" type="number"
            label="Prix de cession (USD) — 0 pour une mise au rebut" variant="outlined" density="comfortable" />
          <v-select v-model="formSortie.typeCession" :items="TYPES_CESSION" label="Nature de la sortie"
            variant="outlined" density="comfortable" />
          <ComptabiliteSelecteurCompte v-if="formSortie.valeurCession > 0"
            v-model="formSortie.compteContrepartieNumero" label="Créance sur l'acheteur (4851 par défaut)" exclure-prefixe="5" />
          <p class="text-caption text-medium-emphasis">
            L'amortissement restant jusqu'au jour de la sortie est passé automatiquement avant la sortie. Le prix est
            ensuite encaissé par la caisse ou la banque, qui soldent la créance.
          </p>
        </v-card-text>
        <v-divider />
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="saving" @click="dialogSortie = false">Annuler</v-btn>
          <v-btn color="error" variant="flat" :loading="saving" @click="confirmerSortie">Confirmer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
    <v-dialog v-model="fiche.ouvert" max-width="640" scrollable>
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">Fiche {{ fiche.bien?.reference }}</v-card-title>
        <v-card-text>
          <v-alert v-if="fiche.erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ fiche.erreur }}</v-alert>
          <v-alert v-if="fiche.bien?.dotationsComptabilisees" type="info" variant="tonal" density="compact" class="mb-3">
            Des dotations sont déjà comptabilisées : la valeur et les dates sont figées ; un changement de durée ou de mode ne recalcule que
            les échéances à venir (changement d'estimation, sans effet rétroactif).
          </v-alert>
          <v-row dense>
            <v-col cols="12"><v-text-field v-model="fiche.libelle" label="Libellé" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" sm="6"><v-select v-model="fiche.categorie" :items="CATEGORIES" label="Catégorie" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="fiche.localisation" label="Emplacement" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" sm="4"><v-text-field v-model.number="fiche.valeurAcquisition" type="number" label="Valeur (USD)" variant="outlined" density="comfortable" :disabled="fiche.bien?.dotationsComptabilisees" /></v-col>
            <v-col cols="6" sm="4"><v-text-field v-model="fiche.dateAcquisition" type="date" label="Acquisition" variant="outlined" density="comfortable" :disabled="fiche.bien?.dotationsComptabilisees" /></v-col>
            <v-col cols="6" sm="4"><v-text-field v-model="fiche.dateMiseService" type="date" label="Mise en service" variant="outlined" density="comfortable" :disabled="fiche.bien?.dotationsComptabilisees" /></v-col>
            <v-col cols="6" sm="4"><v-text-field v-model.number="fiche.dureeMois" type="number" min="1" label="Durée (mois)" variant="outlined" density="comfortable"
              :hint="`${fiche.dureeMois ? (1200 / fiche.dureeMois).toFixed(2) : '—'} % par an`" persistent-hint /></v-col>
            <v-col cols="6" sm="4"><v-select v-model="fiche.modeAmortissement" :items="[{ value: 'LINEAIRE', title: 'Linéaire' }, { value: 'DEGRESSIF', title: 'Dégressif' }]"
              label="Mode" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" sm="4"><v-text-field v-model.number="fiche.valeurResiduelle" type="number" min="0" label="Valeur résiduelle (USD)" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="fiche.fournisseur" label="Fournisseur" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="fiche.numeroSerie" label="N° de série" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12"><v-textarea v-model="fiche.description" label="Description" rows="2" auto-grow variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12"><v-text-field v-model="fiche.motif" label="Motif de la modification (journal d'audit)" variant="outlined" density="comfortable" /></v-col>
          </v-row>
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="fiche.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="fiche.enCours" @click="enregistrerFiche">Enregistrer</v-btn></v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.kpi-label { font-size: 0.72rem; color: var(--ink-soft); text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.25rem; font-weight: 700; color: var(--ink); margin-top: 4px; }
</style>
