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
}

const CATEGORIES = [
  { value: 'LOGICIEL', title: 'Logiciel' },
  { value: 'BATIMENT', title: 'Bâtiment' },
  { value: 'MATERIEL_INDUSTRIEL', title: 'Matériel industriel' },
  { value: 'MATERIEL_BUREAU', title: 'Matériel de bureau' },
  { value: 'MOBILIER', title: 'Mobilier' },
  { value: 'MATERIEL_TRANSPORT', title: 'Matériel de transport' },
  { value: 'AUTRE', title: 'Autre' },
]

const statutMeta: Record<string, { label: string; bg: string; color: string }> = {
  EN_SERVICE: { label: 'En service', bg: '#dcfce7', color: '#15803d' },
  CEDE: { label: 'Cédé', bg: '#dbeafe', color: '#1d4ed8' },
  REBUT: { label: 'Rebut', bg: '#f3f4f6', color: '#6b7280' },
}

const api = useApi()
const auth = useAuthStore()
const canWrite = computed(() => auth.hasAnyRole(['GEST_PATRIMOINE']))

// Le montant est stocke en devise de base (FC) au grand livre ; la saisie et
// l'affichage se font en USD, comme sur le catalogue articles.
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
})

const formSortie = reactive({
  dateSortie: new Date().toISOString().slice(0, 10),
  valeurCession: 0,
  compteContrepartieNumero: '571',
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [data, taux] = await Promise.all([
      api<Immo[]>('/patrimoine/immobilisations'),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
    ])
    biens.value = data
    tauxChange.value = taux.taux || 0
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le registre des immobilisations.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function ouvrirAjout() {
  Object.assign(form, {
    libelle: '', categorie: 'MATERIEL_BUREAU', description: '',
    dateAcquisition: new Date().toISOString().slice(0, 10),
    dateMiseService: new Date().toISOString().slice(0, 10),
    valeurAcquisition: null, valeurResiduelle: 0, dureeMois: 60,
    localisation: '', fournisseur: '', numeroSerie: '',
    comptabiliserAcquisition: false, compteContrepartieNumero: '4812',
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
    if (!(tauxChange.value > 0)) {
      erreur.value = "Aucun taux de change n'est défini : impossible de convertir les montants saisis "
        + "en dollars. Demandez à un administrateur d'enregistrer le taux du jour."
      saving.value = false
      return
    }
    await api('/patrimoine/immobilisations', {
      method: 'POST',
      body: {
        ...form,
        valeurAcquisition: (form.valeurAcquisition ?? 0) * tauxChange.value,
        valeurResiduelle: (form.valeurResiduelle ?? 0) * tauxChange.value,
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
      body: { ...formSortie, valeurCession: (formSortie.valeurCession ?? 0) * tauxChange.value },
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

const toUSD = (fc: number) => (tauxChange.value > 0 ? (fc || 0) / tauxChange.value : 0)
/** Montants affiches en dollars, convertis depuis la valeur FC enregistree. */
const fmt = (fc: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 })
    .format(toUSD(fc))
const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')

const totalBrut = computed(() => biens.value.filter(b => b.statut === 'EN_SERVICE')
  .reduce((s, b) => s + Number(b.valeurAcquisition), 0))
const totalVnc = computed(() => biens.value.filter(b => b.statut === 'EN_SERVICE')
  .reduce((s, b) => s + Number(b.valeurNetteComptable), 0))
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Immobilisations</h1>
        <p class="page-sub">Registre des biens, valeur nette et plan d’amortissement</p>
      </div>
      <v-btn v-if="canWrite" color="success" variant="flat" rounded="lg"
        prepend-icon="mdi-plus" @click="ouvrirAjout">
        Ajouter un bien
      </v-btn>
    </div>

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
          <v-btn size="small" variant="text" icon="mdi-chart-timeline-variant-shimmer"
            :to="`/patrimoine/amortissements?bien=${item.id}`" title="Plan d'amortissement" />
          <v-btn v-if="canWrite && item.statut === 'EN_SERVICE'" size="small" variant="text"
            color="error" icon="mdi-logout" title="Céder / mettre au rebut" @click="ouvrirSortie(item)" />
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">
            Aucun bien enregistré. Utilisez « Ajouter un bien » pour créer le premier.
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
              <ComptabiliteSelecteurCompte v-model="form.compteContrepartieNumero" label="Compte de contrepartie (ex. 4812)" />
            </v-col>
          </v-row>
          <p class="text-caption text-medium-emphasis mt-2">
            Le plan d'amortissement linéaire est calculé automatiquement à partir de la mise en service et de la durée.
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
          <ComptabiliteSelecteurCompte v-if="formSortie.valeurCession > 0"
            v-model="formSortie.compteContrepartieNumero" label="Encaissement sur le compte" />
        </v-card-text>
        <v-divider />
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="saving" @click="dialogSortie = false">Annuler</v-btn>
          <v-btn color="error" variant="flat" :loading="saving" @click="confirmerSortie">Confirmer</v-btn>
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
</style>
