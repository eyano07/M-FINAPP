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
  categorie?: string | null
  societe?: string | null
  compteStockNumero?: string
  compteChargeNumero?: string
  compteProduitNumero?: string
  compteAchatNumero?: string
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
const dialogCard = ref<{ $el: HTMLElement } | null>(null)
/** Ramene le haut de la boite de dialogue en vue : sans ca, une erreur
 * affichee sous le titre reste hors champ si l'utilisateur avait defile
 * plus bas dans ce long formulaire pour atteindre "Enregistrer". */
function remonterEnHautDialogue() {
  nextTick(() => dialogCard.value?.$el?.scrollTo?.({ top: 0, behavior: 'smooth' }))
}
const editId = ref<number | null>(null)
const filtreType = ref<'TOUS' | 'PLAT' | 'BOISSON'>('TOUS')
const filtreCategorie = ref<string | null>(null)
/** Recherche libre : code, libellé, société, catégorie ou unité, sans tenir compte des accents ni de la casse. */
const recherche = ref('')
const page = ref(1)

/** Suggestions de depart pour la categorie d'un article ; le champ reste en saisie libre. */
const CATEGORIES_BOISSON_SUGGEREES = [
  'Bière', 'Vin', 'Champagne', 'Whisky', 'Vodka', 'Alcool', 'Jus', 'Soda', 'Eau',
]
const CATEGORIES_PLAT_SUGGEREES = [
  'Entrée', 'Plat principal', 'Accompagnement', 'Dessert', 'Petit-déjeuner', 'Grillade',
]
const categoriesSuggerees = computed(() =>
  form.type === 'BOISSON' ? CATEGORIES_BOISSON_SUGGEREES : CATEGORIES_PLAT_SUGGEREES)

// Creer/modifier un plat ou une boisson (prix, imputation comptable) est
// reserve a l'administrateur (voir RestaurantService.ECRITURE_CARTE) : le
// responsable restaurant reste en lecture seule sur la definition de la carte.
const canWrite = computed(() => auth.hasRole('ADMIN'))

/**
 * Comptes d'imputation par défaut, vérifiés actifs et imputables au plan
 * comptable. Attention : 6012 a été réaffecté par la migration V23 en « Achats
 * de marchandises hors Région » — le déstockage passe par 6031. Et 3211
 * (matières premières) a été désactivé, d'où 361 pour les plats.
 */
const COMPTES_DEFAUT = {
  PLAT: { stock: '361', charge: '736', produit: '7021', achat: '6011' },
  BOISSON: { stock: '3111', charge: '6031', produit: '7011', achat: '6011' },
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
  categorie: null as string | null,
  societe: '',
  compteStockNumero: '' as string | null,
  compteChargeNumero: '' as string | null,
  compteProduitNumero: '' as string | null,
  compteAchatNumero: '' as string | null,
  devisePrixVente: 'CDF' as 'USD' | 'CDF',
  prixVenteSaisi: null as number | null,
  soumisTva: true,
  stockMin: 0,
  actif: true,
})

/** Équivalent indicatif dans l'autre devise, au taux du jour — n'influence ni la saisie ni l'enregistrement. */
const prixVenteEquivalent = computed(() => {
  if (!form.prixVenteSaisi || tauxChange.value <= 0) return null
  return form.devisePrixVente === 'USD'
    ? `≈ ${parametres.fmtDans(form.prixVenteSaisi * tauxChange.value, 'CDF')}`
    : `≈ ${parametres.fmtDans(form.prixVenteSaisi / tauxChange.value, 'USD')}`
})

const articlesFiltres = computed(() => {
  const parType = filtreType.value === 'TOUS'
    ? articles.value
    : articles.value.filter(a => a.type === filtreType.value)
  const parCategorie = filtreType.value !== 'TOUS' && filtreCategorie.value
    ? parType.filter(a => a.categorie === filtreCategorie.value)
    : parType
  return parCategorie.filter(a =>
    correspondRecherche([a.code, a.libelle, a.societe, a.categorie, a.uniteMesure], recherche.value))
})

const filtreActif = computed(() =>
  !!recherche.value.trim() || filtreType.value !== 'TOUS' || !!filtreCategorie.value)

// Un filtre plus strict peut laisser moins de pages que celle affichée : retour à la première.
watch([filtreType, filtreCategorie, recherche], () => { page.value = 1 })

const nbPlats = computed(() => articles.value.filter(a => a.type === 'PLAT').length)
const nbBoissons = computed(() => articles.value.filter(a => a.type === 'BOISSON').length)

/** Categories reellement utilisees par le type actuellement affiche, pour le filtre. */
const categoriesUtilisees = computed(() => {
  if (filtreType.value === 'TOUS') return []
  const set = new Set(
    articles.value.filter(a => a.type === filtreType.value && a.categorie).map(a => a.categorie as string))
  return Array.from(set).sort((a, b) => a.localeCompare(b))
})

// Changer d'onglet type invalide le filtre categorie (propre aux boissons) :
// sinon il resterait actif, invisible, sur "Plats"/"Tous".
watch(filtreType, () => { filtreCategorie.value = null })

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
  if (!form.compteAchatNumero) form.compteAchatNumero = d.achat
}

/**
 * Prix de vente déjà enregistré (en FC), conservé à l'ouverture de l'édition
 * pour ne JAMAIS l'écraser si le taux du jour est indisponible : le champ
 * est alors désactivé et prixVenteSaisi reste null, mais ce null signifiait
 * jusqu'ici "effacer le prix" à l'enregistrement — rendant l'article
 * invendable pour une simple correction de libellé faite sans taux dispo.
 */
const prixVenteActuelFC = ref<number | null>(null)

function ouvrirCreation(type: 'PLAT' | 'BOISSON') {
  editId.value = null
  prixVenteActuelFC.value = null
  const d = COMPTES_DEFAUT[type]
  Object.assign(form, {
    code: '', libelle: '', uniteMesure: type === 'PLAT' ? 'portion' : 'bouteille',
    type, categorie: null, societe: '',
    compteStockNumero: d.stock, compteChargeNumero: d.charge, compteProduitNumero: d.produit,
    compteAchatNumero: d.achat,
    devisePrixVente: 'CDF', prixVenteSaisi: null, soumisTva: true, stockMin: 0, actif: true,
  })
  erreur.value = ''
  dialog.value = true
}

function ouvrirEdition(a: ArticleCarte) {
  editId.value = a.id
  prixVenteActuelFC.value = a.prixVente ?? null
  Object.assign(form, {
    code: a.code,
    libelle: a.libelle,
    uniteMesure: a.uniteMesure || '',
    type: a.type,
    categorie: a.categorie || null,
    societe: a.societe || '',
    compteStockNumero: a.compteStockNumero || '',
    compteChargeNumero: a.compteChargeNumero || '',
    compteProduitNumero: a.compteProduitNumero || '',
    compteAchatNumero: a.compteAchatNumero || '',
    // En FC, la devise dans laquelle le prix est enregistré : il s'affiche
    // exact, sans passer par un équivalent en dollars recalculé.
    devisePrixVente: 'CDF',
    prixVenteSaisi: a.prixVente ?? null,
    soumisTva: a.soumisTva,
    stockMin: a.stockMin,
    actif: a.actif,
  })
  erreur.value = ''
  dialog.value = true
}

// ── Code d'un nouvel article ──────────────────────────────────────────────────────────
// Il n'est pas saisi : le serveur le compose d'après le libellé et, pour une boisson, la société
// (ex. « Primus 55CL » de « Bracongo » : BRAC-PRIM-55CL ; un plat reçoit le préfixe PLAT :
// « Omelette » donne PLAT-OMEL) et s'assure qu'il est libre. Un article déjà créé garde un code
// modifiable à la main.
const codeAutomatique = computed(() => editId.value === null)
const codeEnCours = ref(false)
let numeroSuggestion = 0
let minuterieCode: ReturnType<typeof setTimeout> | undefined

async function suggererCode() {
  const numero = ++numeroSuggestion
  if (!form.libelle.trim()) {
    form.code = ''
    codeEnCours.value = false
    return
  }
  codeEnCours.value = true
  try {
    const query: Record<string, string> = { libelle: form.libelle.trim(), type: form.type }
    if (form.type === 'BOISSON' && form.societe.trim()) query.societe = form.societe.trim()
    const reponse = await api<{ code: string }>('/restaurant/carte/code-suggere', { query })
    // Une saisie plus récente a pu relancer la demande entre-temps : seule la dernière compte.
    if (numero === numeroSuggestion) form.code = reponse.code || ''
  } catch {
    if (numero === numeroSuggestion) form.code = ''
  } finally {
    if (numero === numeroSuggestion) codeEnCours.value = false
  }
}

// Après une courte pause dans la frappe, pour ne pas interroger le serveur à chaque lettre.
watch([() => form.libelle, () => form.societe, () => form.type, dialog, editId], () => {
  clearTimeout(minuterieCode)
  if (!dialog.value || !codeAutomatique.value) return
  if (!form.libelle.trim()) {
    numeroSuggestion++
    form.code = ''
    codeEnCours.value = false
    return
  }
  codeEnCours.value = true
  minuterieCode = setTimeout(suggererCode, 300)
})
onBeforeUnmount(() => clearTimeout(minuterieCode))

async function enregistrer() {
  if (codeAutomatique.value) {
    // Code redemandé au dernier moment : il tient compte de tout ce qui a été créé entre-temps.
    clearTimeout(minuterieCode)
    await suggererCode()
  }
  if (!form.code.trim() || !form.libelle.trim()) {
    erreur.value = codeAutomatique.value
      ? (form.libelle.trim()
        ? "Le code n'a pas pu être généré : réessayez."
        : 'Le libellé est obligatoire : le code en est déduit.')
      : 'Code et libellé sont obligatoires.'
    remonterEnHautDialogue()
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    // Prix saisi dans la devise choisie (USD ou FC), toujours stocke en FC.
    const body = {
      code: form.code,
      libelle: form.libelle,
      uniteMesure: form.uniteMesure,
      type: form.type,
      categorie: form.categorie || null,
      // La société n'existe que pour une boisson ; vide, elle est retirée.
      societe: form.type === 'BOISSON' ? (form.societe.trim() || null) : null,
      compteStockNumero: form.compteStockNumero,
      compteChargeNumero: form.compteChargeNumero,
      compteProduitNumero: form.compteProduitNumero,
      compteAchatNumero: form.compteAchatNumero,
      prixVente: form.prixVenteSaisi != null
        ? (form.devisePrixVente === 'USD' ? form.prixVenteSaisi * tauxChange.value : form.prixVenteSaisi)
        // Champ vide SANS taux disponible en édition : on ne touche pas au prix
        // existant plutôt que de l'effacer (voir prixVenteActuelFC ci-dessus).
        : (editId.value ? prixVenteActuelFC.value : null),
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
    remonterEnHautDialogue()
  } finally {
    saving.value = false
  }
}

/** Prix de vente : toujours affiché en FC (devise de saisie native de l'article), quelle que
 * soit la préférence d'affichage du module (qui, elle, ne s'applique qu'aux montants tenus en USD). */
function fmtPrixVenteFC(montant?: number | null): string {
  if (montant == null) return '—'
  return parametres.fmtDans(montant, 'CDF')
}

// ── Activer / désactiver rapidement, sans ouvrir le formulaire complet ────
const togglingId = ref<number | null>(null)
async function toggleActif(item: ArticleCarte) {
  togglingId.value = item.id
  erreur.value = ''
  try {
    await api(`/restaurant/carte/${item.id}`, {
      method: 'PUT',
      body: {
        code: item.code,
        libelle: item.libelle,
        uniteMesure: item.uniteMesure,
        type: item.type,
        categorie: item.categorie || null,
        societe: item.societe || null,
        compteStockNumero: item.compteStockNumero,
        compteChargeNumero: item.compteChargeNumero,
        compteProduitNumero: item.compteProduitNumero,
        compteAchatNumero: item.compteAchatNumero,
        prixVente: item.prixVente ?? null,
        soumisTva: item.soumisTva,
        stockMin: item.stockMin,
        actif: !item.actif,
      },
    })
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec du changement de statut.')
  } finally {
    togglingId.value = null
  }
}

// ── Suppression définitive (admin) : uniquement si l'article n'est lié à
// aucune opération — voir RestaurantService.supprimerArticleCarte, qui
// rejette sinon avec un message explicite repris tel quel ici.
const dialogSuppression = ref(false)
const articleASupprimer = ref<ArticleCarte | null>(null)
const suppressionEnCours = ref(false)
const erreurSuppression = ref('')

function ouvrirConfirmationSuppression(item: ArticleCarte) {
  articleASupprimer.value = item
  erreurSuppression.value = ''
  dialogSuppression.value = true
}

async function confirmerSuppression() {
  if (!articleASupprimer.value) return
  suppressionEnCours.value = true
  erreurSuppression.value = ''
  try {
    await api(`/restaurant/carte/${articleASupprimer.value.id}`, { method: 'DELETE' })
    dialogSuppression.value = false
    await charger()
  } catch (e: any) {
    erreurSuppression.value = messageErreurApi(e, 'Échec de la suppression.')
  } finally {
    suppressionEnCours.value = false
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

    <div class="d-flex flex-wrap align-center ga-3 mb-4">
      <!-- Densité par défaut = 48 px, la hauteur du champ de recherche voisin. -->
      <v-btn-toggle v-model="filtreType" mandatory variant="outlined" rounded="lg">
        <v-btn value="TOUS">Tous</v-btn>
        <v-btn value="PLAT">Plats</v-btn>
        <v-btn value="BOISSON">Boissons</v-btn>
      </v-btn-toggle>
      <v-text-field
        v-model="recherche"
        prepend-inner-icon="mdi-magnify"
        placeholder="Rechercher : code, libellé, société, catégorie…"
        aria-label="Rechercher un article de la carte"
        clearable
        hide-details
        variant="outlined"
        density="comfortable"
        rounded="lg"
        class="flex-grow-1"
        style="max-width: 460px; min-width: 240px"
      />
    </div>

    <div v-if="filtreType !== 'TOUS' && categoriesUtilisees.length" class="d-flex flex-wrap ga-2 mb-4">
      <v-chip
        :variant="!filtreCategorie ? 'flat' : 'outlined'"
        :color="!filtreCategorie ? 'indigo' : undefined"
        size="small"
        @click="filtreCategorie = null"
      >
        Toutes
      </v-chip>
      <v-chip
        v-for="c in categoriesUtilisees"
        :key="c"
        :variant="filtreCategorie === c ? 'flat' : 'outlined'"
        :color="filtreCategorie === c ? 'indigo' : undefined"
        size="small"
        @click="filtreCategorie = c"
      >
        {{ c }}
      </v-chip>
    </div>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Code', key: 'code' },
          { title: 'Libellé', key: 'libelle' },
          { title: 'Société', key: 'societe' },
          { title: 'Type', key: 'type' },
          { title: 'Catégorie', key: 'categorie' },
          { title: 'Unité', key: 'uniteMesure' },
          { title: 'Prix de vente', key: 'prixVente', align: 'end' },
          { title: 'Seuil', key: 'stockMin', align: 'end' },
          { title: 'Statut', key: 'actif' },
          { title: 'Actions', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="articlesFiltres"
        :loading="loading"
        v-model:page="page"
        items-per-page="15"
      >
        <!-- Un code composé (BRAC-PRIM-55CL) reste sur une ligne : coupé aux tirets, il ferait grandir chaque rangée. -->
        <template #item.code="{ item }"><span class="text-no-wrap">{{ item.code }}</span></template>
        <template #item.type="{ item }">
          <v-chip :color="META_TYPE[item.type]?.couleur" size="small" variant="tonal">
            <v-icon :icon="META_TYPE[item.type]?.icone" size="14" class="mr-1" />
            {{ META_TYPE[item.type]?.label }}
          </v-chip>
        </template>
        <template #item.categorie="{ item }">
          <v-chip v-if="item.categorie" color="indigo" variant="tonal" size="small">{{ item.categorie }}</v-chip>
          <span v-else class="text-medium-emphasis">—</span>
        </template>
        <!-- Un nom très long (100 caractères permis) est tronqué à l'affichage pour ne pas élargir le tableau ; le nom complet apparaît au survol. -->
        <template #item.societe="{ item }">
          <span
            v-if="item.societe" class="d-inline-block text-truncate align-middle"
            style="max-width: 200px" :title="item.societe"
          >{{ item.societe }}</span>
          <span v-else class="text-medium-emphasis">—</span>
        </template>
        <template #item.uniteMesure="{ item }">{{ item.uniteMesure || '—' }}</template>
        <template #item.prixVente="{ item }">{{ fmtPrixVenteFC(item.prixVente) }}</template>
        <template #item.actif="{ item }">
          <v-chip :color="item.actif ? 'success' : 'grey'" size="small" variant="tonal">
            {{ item.actif ? 'Actif' : 'Inactif' }}
          </v-chip>
        </template>
        <template #item.actions="{ item }">
          <div class="d-flex justify-end flex-nowrap">
          <v-btn
            v-if="item.type === 'PLAT'"
            size="small" variant="text" icon="mdi-clipboard-text-outline"
            title="Fiche technique" to="/restaurant/recettes"
          />
          <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil-outline" title="Modifier" @click="ouvrirEdition(item)" />
          <v-btn
            v-if="canWrite"
            size="small" variant="text"
            :icon="item.actif ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
            :title="item.actif ? 'Désactiver (masquer)' : 'Activer'"
            :loading="togglingId === item.id"
            @click="toggleActif(item)"
          />
          <v-btn
            v-if="canWrite"
            size="small" variant="text" color="error"
            icon="mdi-delete-outline"
            title="Supprimer définitivement"
            @click="ouvrirConfirmationSuppression(item)"
          />
          </div>
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">
            {{ filtreActif
              ? 'Aucun article ne correspond à votre recherche ou à vos filtres.'
              : 'Aucun article sur la carte. Commencez par créer un plat ou une boisson.' }}
          </div>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="560" scrollable>
      <v-card class="pa-6">
        <h2 class="text-h6 mb-4">
          {{ editId ? 'Modifier' : (form.type === 'BOISSON' ? 'Nouvelle' : 'Nouveau') }} {{ META_TYPE[form.type]?.label?.toLowerCase() }}
        </h2>

        <v-card-text ref="dialogCard" class="pa-0">
        <v-alert v-if="erreur" type="error" variant="tonal" density="compact" rounded="lg" class="mb-4">{{ erreur }}</v-alert>

        <v-alert v-if="form.type === 'PLAT'" type="info" variant="tonal" density="compact" rounded="lg" class="mb-4">
          Un plat est suivi en stock : la production du jour l'y fait entrer, la vente l'en sort au coût moyen.
          Définissez sa fiche technique puis passez par l'écran Production : le coût de revient sera calculé
          depuis les provisions réellement consommées.
        </v-alert>

        <v-alert v-if="!editId" type="info" variant="tonal" density="compact" rounded="lg" class="mb-4">
          <v-icon icon="mdi-information-outline" size="14" class="mr-1" />
          Un compte d'achat, de stock, de charge et de vente dédié à cet article sera créé automatiquement.
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

        <!-- Nouvel article : le code se déduit du libellé (et de la société d'une boisson), il vient donc après eux. -->
        <template v-if="codeAutomatique">
          <v-text-field v-model="form.libelle" label="Libellé" variant="outlined" density="comfortable" class="mb-3" />
          <v-text-field
            v-if="form.type === 'BOISSON'"
            v-model="form.societe" label="Société (facultatif)" maxlength="100"
            hint="Brasserie, fabricant ou fournisseur, ex. Bracongo" persistent-hint
            variant="outlined" density="comfortable" class="mb-3"
          />
          <v-text-field
            :model-value="form.code" label="Code" readonly :loading="codeEnCours"
            prepend-inner-icon="mdi-auto-fix" bg-color="grey-lighten-5"
            :placeholder="form.type === 'BOISSON' ? 'Généré à partir du libellé et de la société' : 'Généré à partir du libellé'"
            :hint="form.type === 'BOISSON' ? 'Généré automatiquement à partir du libellé et de la société' : 'Généré automatiquement à partir du libellé (préfixe PLAT)'"
            persistent-hint
            variant="outlined" density="comfortable" class="mb-3"
          />
        </template>
        <template v-else>
          <v-text-field v-model="form.code" label="Code" variant="outlined" density="comfortable" class="mb-3" />
          <v-text-field v-model="form.libelle" label="Libellé" variant="outlined" density="comfortable" class="mb-3" />
          <v-text-field
            v-if="form.type === 'BOISSON'"
            v-model="form.societe" label="Société (facultatif)" maxlength="100"
            hint="Brasserie, fabricant ou fournisseur, ex. Bracongo" persistent-hint
            variant="outlined" density="comfortable" class="mb-3"
          />
        </template>
        <v-text-field v-model="form.uniteMesure" label="Unité (portion, bouteille...)" variant="outlined" density="comfortable" class="mb-3" />
        <v-combobox
          v-model="form.categorie"
          :items="categoriesSuggerees"
          :label="form.type === 'BOISSON' ? 'Catégorie (Alcool, Vin, Whisky...)' : 'Catégorie (Entrée, Plat principal, Dessert...)'"
          hint="Choisissez une catégorie existante ou saisissez-en une nouvelle"
          persistent-hint
          clearable
          variant="outlined"
          density="comfortable"
          class="mb-3"
        />
        <div class="d-flex ga-2 mb-1 align-start">
          <v-text-field
            v-model.number="form.prixVenteSaisi"
            type="number"
            :label="`Prix de vente HT (${form.devisePrixVente === 'USD' ? 'USD' : 'FC'})`"
            variant="outlined"
            density="comfortable"
            class="flex-grow-1"
            :disabled="tauxChange <= 0"
          />
          <v-btn-toggle v-model="form.devisePrixVente" mandatory density="comfortable" variant="outlined" rounded="lg" style="margin-top: 4px">
            <v-btn value="USD" size="small">$US</v-btn>
            <v-btn value="CDF" size="small">FC</v-btn>
          </v-btn-toggle>
        </div>
        <p class="text-caption text-medium-emphasis mb-3" style="min-height: 1.2em">{{ prixVenteEquivalent }}</p>
        <v-text-field v-model.number="form.stockMin" type="number" label="Seuil de réapprovisionnement" variant="outlined" density="comfortable" class="mb-3" />

        <template v-if="editId">
          <ComptabiliteSelecteurCompte v-model="form.compteAchatNumero" label="Compte d'achat (601x)" class="mb-3" />
          <ComptabiliteSelecteurCompte v-model="form.compteStockNumero" label="Compte de stock" class="mb-3" />
          <ComptabiliteSelecteurCompte v-model="form.compteChargeNumero" label="Compte de charge (déstockage)" class="mb-3" />
          <ComptabiliteSelecteurCompte v-model="form.compteProduitNumero" label="Compte de produit (vente)" class="mb-3" />
        </template>

        <v-switch v-model="form.soumisTva" label="Soumis à la TVA" color="primary" density="compact" hide-details class="mb-2" />
        <v-switch v-model="form.actif" label="Actif" color="success" density="compact" hide-details class="mb-4" />
        </v-card-text>

        <div class="d-flex justify-end ga-2 mt-4">
          <v-btn variant="text" :disabled="saving" @click="dialog = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
        </div>
      </v-card>
    </v-dialog>

    <v-dialog v-model="dialogSuppression" max-width="440">
      <v-card class="pa-6">
        <h2 class="text-h6 mb-4">Supprimer définitivement ?</h2>

        <v-alert v-if="erreurSuppression" type="error" variant="tonal" density="compact" rounded="lg" class="mb-4">
          {{ erreurSuppression }}
        </v-alert>

        <p class="text-body-2 mb-4">
          <strong>{{ articleASupprimer?.libelle }}</strong> sera définitivement supprimé de la carte, ainsi que
          ses comptes comptables dédiés (qui redeviendront disponibles pour un prochain article). Cette action
          est irréversible et n'est possible que si l'article n'est lié à aucune opération (vente, achat,
          mouvement de stock, fiche technique, production, conditionnement) — désactivez-le plutôt sinon.
        </p>

        <div class="d-flex justify-end ga-2">
          <v-btn variant="text" :disabled="suppressionEnCours" @click="dialogSuppression = false">Annuler</v-btn>
          <v-btn color="error" variant="flat" :loading="suppressionEnCours" @click="confirmerSuppression">
            Supprimer définitivement
          </v-btn>
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
