<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Carte du restaurant : plats et boissons.
 *
 * Un plat ou une boisson est un article du catalogue partagé, ce qui lui donne
 * gratuitement le moteur de stock (quantités par entrepôt, coût moyen pondéré,
 * écritures OHADA). Cette page n'appelle pourtant jamais /logistique/* : le
 * responsable restaurant n'a pas ce module et le serveur lui renverrait un 403.
 */
interface ArticleCarte {
  id: number
  code: string
  libelle: string
  uniteMesure?: string
  type: 'PLAT' | 'BOISSON'
  compteStockNumero?: string
  compteChargeNumero?: string
  compteProduitNumero?: string
  prixVente?: number
  soumisTva: boolean
  stockMin: number
  actif: boolean
}

const api = useApi()
const auth = useAuthStore()
const parametres = useRestaurantParametresStore()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const articles = ref<ArticleCarte[]>([])
const tauxChange = ref(0)
const dialog = ref(false)
const editId = ref<number | null>(null)
const filtreType = ref<'TOUS' | 'PLAT' | 'BOISSON'>('TOUS')

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT']))

/**
 * Comptes d'imputation par défaut, vérifiés actifs et imputables au plan
 * comptable. Attention : 6012 a été réaffecté par la migration V23 en « Achats
 * de marchandises hors Région » — le déstockage passe par 6031. Et 3211
 * (matières premières) a été désactivé, d'où 361 pour les plats.
 */
const COMPTES_DEFAUT = {
  PLAT: { stock: '361', charge: '736', produit: '7021' },
  BOISSON: { stock: '3111', charge: '6031', produit: '7011' },
}

const META_TYPE: Record<string, { label: string; couleur: string; icone: string }> = {
  PLAT: { label: 'Plat', couleur: 'deep-orange', icone: 'mdi-food-outline' },
  BOISSON: { label: 'Boisson', couleur: 'indigo', icone: 'mdi-bottle-soda-classic-outline' },
}

const form = reactive({
  code: '',
  libelle: '',
  uniteMesure: '',
  type: 'PLAT' as 'PLAT' | 'BOISSON',
  compteStockNumero: '' as string | null,
  compteChargeNumero: '' as string | null,
  compteProduitNumero: '' as string | null,
  prixVenteUSD: null as number | null,
  soumisTva: true,
  stockMin: 0,
  actif: true,
})

const articlesFiltres = computed(() =>
  filtreType.value === 'TOUS'
    ? articles.value
    : articles.value.filter(a => a.type === filtreType.value)
)

const nbPlats = computed(() => articles.value.filter(a => a.type === 'PLAT').length)
const nbBoissons = computed(() => articles.value.filter(a => a.type === 'BOISSON').length)

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [arts, tauxData] = await Promise.all([
      api<ArticleCarte[]>('/restaurant/carte'),
      // Repli a 0 et non 1 : a 1, les montants FC s'afficheraient tels quels
      // comme des USD, surevalues d'un facteur egal au taux, sans alerte.
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
      parametres.charger(),
    ])
    articles.value = arts
    tauxChange.value = tauxData.taux || 0
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger la carte.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

/** Applique les comptes conseillés du type choisi, sans écraser une saisie manuelle. */
function appliquerComptesDefaut() {
  const d = COMPTES_DEFAUT[form.type]
  if (!form.compteStockNumero) form.compteStockNumero = d.stock
  if (!form.compteChargeNumero) form.compteChargeNumero = d.charge
  if (!form.compteProduitNumero) form.compteProduitNumero = d.produit
}

function ouvrirCreation(type: 'PLAT' | 'BOISSON') {
  editId.value = null
  const d = COMPTES_DEFAUT[type]
  Object.assign(form, {
    code: '', libelle: '', uniteMesure: type === 'PLAT' ? 'portion' : 'bouteille',
    type,
    compteStockNumero: d.stock, compteChargeNumero: d.charge, compteProduitNumero: d.produit,
    prixVenteUSD: null, soumisTva: true, stockMin: 0, actif: true,
  })
  erreur.value = ''
  dialog.value = true
}

function ouvrirEdition(a: ArticleCarte) {
  editId.value = a.id
  Object.assign(form, {
    code: a.code,
    libelle: a.libelle,
    uniteMesure: a.uniteMesure || '',
    type: a.type,
    compteStockNumero: a.compteStockNumero || '',
    compteChargeNumero: a.compteChargeNumero || '',
    compteProduitNumero: a.compteProduitNumero || '',
    prixVenteUSD: a.prixVente != null && tauxChange.value > 0 ? a.prixVente / tauxChange.value : null,
    soumisTva: a.soumisTva,
    stockMin: a.stockMin,
    actif: a.actif,
  })
  erreur.value = ''
  dialog.value = true
}

async function enregistrer() {
  if (!form.code.trim() || !form.libelle.trim()) {
    erreur.value = 'Code et libellé sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    // Prix saisis en USD, stockes en FC : convention de toute l'application.
    const body = {
      code: form.code,
      libelle: form.libelle,
      uniteMesure: form.uniteMesure,
      type: form.type,
      compteStockNumero: form.compteStockNumero,
      compteChargeNumero: form.compteChargeNumero,
      compteProduitNumero: form.compteProduitNumero,
      prixVente: form.prixVenteUSD != null ? form.prixVenteUSD * tauxChange.value : null,
      soumisTva: form.soumisTva,
      stockMin: form.stockMin,
      actif: form.actif,
    }
    if (editId.value) {
      await api(`/restaurant/carte/${editId.value}`, { method: 'PUT', body })
    } else {
      await api('/restaurant/carte', { method: 'POST', body })
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Carte du restaurant</h1>
        <p class="page-sub">Plats et boissons — prix de vente et imputation comptable</p>
      </div>
      <div v-if="canWrite" class="d-flex ga-2">
        <v-btn color="deep-orange" variant="tonal" rounded="lg" prepend-icon="mdi-food-outline" @click="ouvrirCreation('PLAT')">
          Nouveau plat
        </v-btn>
        <v-btn color="indigo" variant="flat" rounded="lg" prepend-icon="mdi-bottle-soda-classic-outline" @click="ouvrirCreation('BOISSON')">
          Nouvelle boisson
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-alert v-if="tauxChange <= 0" type="warning" variant="tonal" rounded="lg" density="compact" class="mb-4">
      Aucun taux de change n'est défini : les prix ne peuvent pas être affichés ni saisis en USD.
      Demandez à un administrateur d'enregistrer le taux du jour.
    </v-alert>

    <div class="rst-stats mb-4">
      <div class="rst-stat rst-stat--plats">
        <v-icon icon="mdi-food-outline" size="18" />
        <span class="rst-stat__val">{{ nbPlats }}</span>
        <span class="rst-stat__lbl">Plats</span>
      </div>
      <div class="rst-stat rst-stat--boissons">
        <v-icon icon="mdi-bottle-soda-classic-outline" size="18" />
        <span class="rst-stat__val">{{ nbBoissons }}</span>
        <span class="rst-stat__lbl">Boissons</span>
      </div>
    </div>

    <v-btn-toggle v-model="filtreType" mandatory density="comfortable" variant="outlined" rounded="lg" class="mb-4">
      <v-btn value="TOUS">Tous</v-btn>
      <v-btn value="PLAT">Plats</v-btn>
      <v-btn value="BOISSON">Boissons</v-btn>
    </v-btn-toggle>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Code', key: 'code' },
          { title: 'Libellé', key: 'libelle' },
          { title: 'Type', key: 'type' },
          { title: 'Unité', key: 'uniteMesure' },
          { title: 'Prix de vente', key: 'prixVente', align: 'end' },
          { title: 'Seuil', key: 'stockMin', align: 'end' },
          { title: 'Statut', key: 'actif' },
          { title: 'Actions', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="articlesFiltres"
        :loading="loading"
        items-per-page="15"
      >
        <template #item.type="{ item }">
          <v-chip :color="META_TYPE[item.type]?.couleur" size="small" variant="tonal">
            <v-icon :icon="META_TYPE[item.type]?.icone" size="14" class="mr-1" />
            {{ META_TYPE[item.type]?.label }}
          </v-chip>
        </template>
        <template #item.uniteMesure="{ item }">{{ item.uniteMesure || '—' }}</template>
        <template #item.prixVente="{ item }">{{ parametres.fmtMontantDepuisFC(item.prixVente) }}</template>
        <template #item.actif="{ item }">
          <v-chip :color="item.actif ? 'success' : 'grey'" size="small" variant="tonal">
            {{ item.actif ? 'Actif' : 'Inactif' }}
          </v-chip>
        </template>
        <template #item.actions="{ item }">
          <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil-outline" title="Modifier" @click="ouvrirEdition(item)" />
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">
            Aucun article sur la carte. Commencez par créer un plat ou une boisson.
          </div>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="560" scrollable>
      <v-card class="pa-6">
        <h2 class="text-h6 mb-4">
          {{ editId ? 'Modifier' : 'Nouveau' }} {{ META_TYPE[form.type]?.label?.toLowerCase() }}
        </h2>

        <v-alert v-if="erreur" type="error" variant="tonal" density="compact" rounded="lg" class="mb-4">{{ erreur }}</v-alert>

        <v-alert v-if="form.type === 'PLAT'" type="info" variant="tonal" density="compact" rounded="lg" class="mb-4">
          Un plat est suivi en stock : la production du jour l'y fait entrer, la vente l'en sort au coût moyen.
          Si l'entrée de production est saisie sans coût, le coût de revient reste nul et la marge affichée sera de 100 %.
        </v-alert>

        <v-btn-toggle
          v-model="form.type"
          mandatory
          color="primary"
          variant="outlined"
          rounded="lg"
          class="mb-4 w-100"
          :disabled="editId !== null"
          @update:model-value="appliquerComptesDefaut"
        >
          <v-btn value="PLAT" class="flex-1-1">
            <v-icon icon="mdi-food-outline" class="mr-1" size="18" />Plat
          </v-btn>
          <v-btn value="BOISSON" class="flex-1-1">
            <v-icon icon="mdi-bottle-soda-classic-outline" class="mr-1" size="18" />Boisson
          </v-btn>
        </v-btn-toggle>

        <v-text-field v-model="form.code" label="Code" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.libelle" label="Libellé" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.uniteMesure" label="Unité (portion, bouteille...)" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field
          v-model.number="form.prixVenteUSD"
          type="number"
          label="Prix de vente HT (USD)"
          variant="outlined"
          density="comfortable"
          class="mb-3"
          :disabled="tauxChange <= 0"
        />
        <v-text-field v-model.number="form.stockMin" type="number" label="Seuil de réapprovisionnement" variant="outlined" density="comfortable" class="mb-3" />

        <ComptabiliteSelecteurCompte v-model="form.compteStockNumero" label="Compte de stock" class="mb-3" />
        <ComptabiliteSelecteurCompte v-model="form.compteChargeNumero" label="Compte de charge (déstockage)" class="mb-3" />
        <ComptabiliteSelecteurCompte v-model="form.compteProduitNumero" label="Compte de produit (vente)" class="mb-3" />

        <v-switch v-model="form.soumisTva" label="Soumis à la TVA" color="primary" density="compact" hide-details class="mb-2" />
        <v-switch v-model="form.actif" label="Actif" color="success" density="compact" hide-details class="mb-4" />

        <div class="d-flex justify-end ga-2">
          <v-btn variant="text" :disabled="saving" @click="dialog = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.rst-stats { display: flex; gap: 12px; flex-wrap: wrap; }
.rst-stat {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 18px;
  border-radius: 12px;
  font-size: 0.875rem;
}
.rst-stat--plats    { background: linear-gradient(135deg,#fff7ed,#ffedd5); color: #c2410c; }
.rst-stat--boissons { background: linear-gradient(135deg,#eef2ff,#e0e7ff); color: #4338ca; }
.rst-stat__val { font-size: 1.1rem; font-weight: 800; letter-spacing: -0.5px; }
.rst-stat__lbl { font-weight: 500; opacity: 0.75; }
</style>
