<script setup lang="ts">
// Le caissier a LOGISTIQUE en LECTURE pour charger le catalogue depuis
// « Nouvelle vente » (V29), pas pour consulter les ecrans du domaine : la
// garde par roles l'exclut sans retirer la permission de module.
definePageMeta({ module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'GEST_PATRIMOINE', 'ADMIN'] })

interface Article {
  id: number
  code: string
  libelle: string
  uniteMesure?: string
  type: 'MARCHANDISE' | 'SERVICE' | 'CONSOMMABLE' | 'PLAT' | 'BOISSON' | 'PROVISION'
  compteStockNumero?: string
  compteChargeNumero?: string
  compteProduitNumero?: string
  prixVente?: number
  soumisTva: boolean
  stockMin: number
  actif: boolean
  vendable: boolean
}

const api = useApi()
const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const articles = ref<Article[]>([])
const tauxChange = ref(1)
const dialog = ref(false)
const editId = ref<number | null>(null)

const canWrite = computed(() => auth.hasAnyRole(['LOGISTIQUE', 'DFIN']))

// Le catalogue est partagé : il contient aussi les consommables du patrimoine
// et la carte du restaurant. Sans ce dictionnaire, tout ce qui n'était pas un
// service s'affichait « Marchandise », y compris un plat.
const META_TYPE: Record<string, { label: string; couleur: string }> = {
  MARCHANDISE: { label: 'Marchandise', couleur: 'teal' },
  SERVICE: { label: 'Service', couleur: 'purple' },
  CONSOMMABLE: { label: 'Consommable', couleur: 'blue-grey' },
  PLAT: { label: 'Plat', couleur: 'deep-orange' },
  BOISSON: { label: 'Boisson', couleur: 'indigo' },
  PROVISION: { label: 'Provision', couleur: 'brown' },
}
// Types créés depuis un autre module (Patrimoine, Restaurant) : on les affiche
// mais on ne laisse pas cet écran les rebasculer silencieusement en
// marchandise, ce qui leur ferait perdre leurs comptes d'imputation.
const TYPES_AUTRES_MODULES = ['PLAT', 'BOISSON', 'PROVISION']
const typeExterne = computed(() => TYPES_AUTRES_MODULES.includes(form.type))

const form = reactive({
  code: '',
  libelle: '',
  uniteMesure: '',
  type: 'MARCHANDISE' as 'MARCHANDISE' | 'SERVICE' | 'CONSOMMABLE' | 'PLAT' | 'BOISSON' | 'PROVISION',
  compteStockNumero: '' as string | null,
  compteChargeNumero: '' as string | null,
  compteProduitNumero: '' as string | null,
  prixVenteUSD: null as number | null,
  soumisTva: true,
  stockMin: 0,
  actif: true,
})

const estService = computed(() => form.type === 'SERVICE')

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [arts, tauxData] = await Promise.all([
      api<Article[]>('/logistique/articles'),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })) /* 0 et non 1 : un repli a 1 affichait les
        montants FC tels quels comme des USD (surevaluation d'un facteur
        egal au taux, ~2800x) sans que rien ne le signale. A 0, les
        convertisseurs (tous gardes par `taux > 0`) renvoient 0, valeur
        manifestement fausse plutot que plausible. */,
    ])
    articles.value = arts
    // 0 et non 1 : evite d'enregistrer un prix FC egal au prix USD.
    tauxChange.value = tauxData.taux || 0
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les articles.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function ouvrirCreation() {
  editId.value = null
  Object.assign(form, {
    code: '', libelle: '', uniteMesure: '', type: 'MARCHANDISE',
    compteStockNumero: '', compteChargeNumero: '', compteProduitNumero: '',
    prixVenteUSD: null, soumisTva: true, stockMin: 0, actif: true,
  })
  dialog.value = true
}

function ouvrirEdition(a: Article) {
  editId.value = a.id
  Object.assign(form, {
    code: a.code,
    libelle: a.libelle,
    uniteMesure: a.uniteMesure || '',
    type: a.type || 'MARCHANDISE',
    compteStockNumero: a.compteStockNumero || '',
    compteChargeNumero: a.compteChargeNumero || '',
    compteProduitNumero: a.compteProduitNumero || '',
    prixVenteUSD: a.prixVente != null && tauxChange.value > 0 ? a.prixVente / tauxChange.value : null,
    soumisTva: a.soumisTva,
    stockMin: a.stockMin,
    actif: a.actif,
  })
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
    // Les prix sont saisis en USD et stockés en FC — un choix independant
    // de la devise de base du grand livre (article.prixVente n'est jamais
    // touche par ConversionDeviseService), gere cote client de bout en bout.
    const body = {
      code: form.code,
      libelle: form.libelle,
      uniteMesure: form.uniteMesure,
      type: form.type,
      compteStockNumero: estService.value ? null : form.compteStockNumero,
      compteChargeNumero: estService.value ? null : form.compteChargeNumero,
      compteProduitNumero: form.compteProduitNumero,
      prixVente: form.prixVenteUSD != null ? form.prixVenteUSD * tauxChange.value : null,
      soumisTva: form.soumisTva,
      stockMin: estService.value ? 0 : form.stockMin,
      actif: form.actif,
    }
    if (editId.value) {
      await api(`/logistique/articles/${editId.value}`, { method: 'PUT', body })
    } else {
      await api('/logistique/articles', { method: 'POST', body })
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Enregistrement impossible.')
  } finally {
    saving.value = false
  }
}

const fmtUSD = (fc?: number) =>
  fc == null ? '—'
    : new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 })
        .format(tauxChange.value > 0 ? fc / tauxChange.value : 0)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Articles</h1>
        <p class="page-sub">Catalogue : marchandises stockées et services vendables</p>
      </div>
      <v-btn v-if="canWrite" color="primary" prepend-icon="mdi-plus" rounded="lg" @click="ouvrirCreation">
        Nouvel article
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Code', key: 'code' },
          { title: 'Libellé', key: 'libelle' },
          { title: 'Type', key: 'type' },
          { title: 'Unité', key: 'uniteMesure' },
          { title: 'Prix de vente', key: 'prixVente', align: 'end' },
          { title: 'TVA', key: 'soumisTva', align: 'center' },
          { title: 'Compte produit', key: 'compteProduitNumero' },
          { title: 'Actif', key: 'actif' },
          { title: '', key: 'actions', sortable: false },
        ]"
        :items="articles"
        :loading="loading"
        items-per-page="15"
      >
        <template #item.type="{ item }">
          <v-chip :color="META_TYPE[item.type]?.couleur ?? 'grey'" size="small" variant="tonal">
            {{ META_TYPE[item.type]?.label ?? item.type }}
          </v-chip>
        </template>
        <template #item.prixVente="{ item }">
          <span :class="item.prixVente ? 'font-weight-medium' : 'text-medium-emphasis'">
            {{ fmtUSD(item.prixVente) }}
          </span>
        </template>
        <template #item.soumisTva="{ item }">
          <v-chip :color="item.soumisTva ? 'primary' : 'grey'" size="x-small" variant="tonal">
            {{ item.soumisTva ? 'Soumis' : 'Exonéré' }}
          </v-chip>
        </template>
        <template #item.compteProduitNumero="{ item }">
          <code v-if="item.compteProduitNumero" class="text-caption">{{ item.compteProduitNumero }}</code>
          <v-tooltip v-else text="Sans compte de produit, l'article ne peut pas être vendu" location="top">
            <template #activator="{ props }">
              <v-icon v-bind="props" icon="mdi-alert-outline" size="16" color="warning" />
            </template>
          </v-tooltip>
        </template>
        <template #item.actif="{ item }">
          <v-chip :color="item.actif ? 'success' : 'grey'" size="small" variant="flat">
            {{ item.actif ? 'Oui' : 'Non' }}
          </v-chip>
        </template>
        <template #item.actions="{ item }">
          <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil" @click="ouvrirEdition(item)" />
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="560" scrollable>
      <v-card class="pa-6">
        <h2 class="text-h6 mb-4">{{ editId ? 'Modifier' : 'Nouvel' }} article</h2>

        <v-alert
          v-if="typeExterne"
          type="info"
          variant="tonal"
          density="compact"
          rounded="lg"
          class="mb-4"
        >
          Article de type « {{ META_TYPE[form.type]?.label }} » : il se crée et se modifie depuis le module Restaurant,
          avec ses comptes d'imputation propres. Le type n'est pas modifiable ici.
        </v-alert>
        <v-btn-toggle
          v-else
          v-model="form.type"
          mandatory
          color="primary"
          variant="outlined"
          rounded="lg"
          class="mb-4 w-100"
        >
          <v-btn value="MARCHANDISE" class="flex-1-1">
            <v-icon icon="mdi-package-variant-closed" class="mr-1" size="18" />Marchandise
          </v-btn>
          <v-btn value="SERVICE" class="flex-1-1">
            <v-icon icon="mdi-hand-extended-outline" class="mr-1" size="18" />Service
          </v-btn>
          <v-btn value="CONSOMMABLE" class="flex-1-1">
            <v-icon icon="mdi-package-variant" class="mr-1" size="18" />Consommable
          </v-btn>
        </v-btn-toggle>

        <v-text-field v-model="form.code" label="Code" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.libelle" label="Libellé" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.uniteMesure" label="Unité de mesure" variant="outlined" density="comfortable" class="mb-3" />

        <!-- ── Vente ─────────────────────────────────────────────── -->
        <p class="art-section">Vente</p>
        <v-text-field
          v-model.number="form.prixVenteUSD"
          label="Prix de vente HT (USD)"
          type="number"
          min="0"
          step="0.01"
          prepend-inner-icon="mdi-currency-usd"
          variant="outlined"
          density="comfortable"
          :hint="form.prixVenteUSD ? `≈ ${new Intl.NumberFormat('fr-FR').format(Math.round(form.prixVenteUSD * tauxChange))} FC` : 'Sans prix, l\'article ne peut pas être vendu'"
          persistent-hint
          class="mb-3"
        />
        <ComptabiliteSelecteurCompte
          v-model="form.compteProduitNumero"
          :label="estService ? 'Compte de produit (ex. 7061)' : 'Compte de produit (ex. 7011)'"
          class="mb-1"
        />
        <v-switch v-model="form.soumisTva" label="Soumis à la TVA" color="primary" density="compact" class="mb-2" />

        <!-- ── Stock (marchandise uniquement) ────────────────────── -->
        <template v-if="!estService">
          <p class="art-section">Stock</p>
          <v-alert type="info" variant="tonal" density="compact" class="mb-3">
            La quantité en stock s'ajoute depuis
            <NuxtLink to="/logistique/stock" class="text-primary">État du stock</NuxtLink>,
            une fois l'article créé.
          </v-alert>
          <ComptabiliteSelecteurCompte v-model="form.compteStockNumero" label="Compte de stock (ex. 3111)" class="mb-3" />
          <!-- 6031 et non 6012 : V23 a réaffecté 6012 en « Achats de
               marchandises hors Région », le déstockage passe par 6031. -->
          <ComptabiliteSelecteurCompte v-model="form.compteChargeNumero" label="Compte de charge (ex. 6031)" class="mb-3" />
          <v-text-field v-model.number="form.stockMin" label="Stock minimum" type="number" variant="outlined" density="comfortable" class="mb-3" />
        </template>
        <v-alert v-else type="info" variant="tonal" density="compact" class="mb-3">
          Un service n'est pas stocké : ni entrepôt, ni coût des ventes.
        </v-alert>

        <v-switch v-model="form.actif" label="Actif" color="primary" />

        <div class="d-flex justify-end ga-3 mt-2">
          <v-btn variant="text" @click="dialog = false">Annuler</v-btn>
          <v-btn color="primary" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.art-section {
  font-size: 0.72rem;
  font-weight: 700;
  color: #9ca3af;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 4px 0 10px;
}
</style>
