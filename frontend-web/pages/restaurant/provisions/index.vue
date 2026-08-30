<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Provisions de cuisine : vivres, épices, charbon...
 *
 * Stockées et consommées en interne (préparation des plats), jamais vendues —
 * contrairement à la carte, pas de prix de vente ni de compte produit.
 */
interface Provision {
  id: number
  code: string
  libelle: string
  uniteMesure?: string
  compteStockNumero?: string
  compteChargeNumero?: string
  stockMin: number
  actif: boolean
}
interface StockNiveau {
  articleId: number
  articleCode: string
  articleLibelle: string
  uniteMesure?: string
  entrepotCode: string
  quantite: number
  valeurTotale: number
  coutMoyen: number
  stockMin: number
  sousSeuil: boolean
}

const api = useApi()
const auth = useAuthStore()
const parametres = useRestaurantParametresStore()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const provisions = ref<Provision[]>([])
const stock = ref<StockNiveau[]>([])
const dialog = ref(false)
const editId = ref<number | null>(null)

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT']))

/** 331 Matières consommables / 6033 Variations des stocks d'autres approvisionnements — vérifiés actifs et imputables. */
const COMPTE_STOCK_DEFAUT = '331'
const COMPTE_CHARGE_DEFAUT = '6033'

const form = reactive({
  code: '',
  libelle: '',
  uniteMesure: '',
  compteStockNumero: COMPTE_STOCK_DEFAUT as string | null,
  compteChargeNumero: COMPTE_CHARGE_DEFAUT as string | null,
  stockMin: 0,
  actif: true,
})

const valeurTotale = computed(() => stock.value.reduce((s, l) => s + (l.valeurTotale || 0), 0))
const nbSousSeuil = computed(() => stock.value.filter(l => l.sousSeuil).length)

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [provs, niveaux] = await Promise.all([
      api<Provision[]>('/restaurant/provisions'),
      api<StockNiveau[]>('/restaurant/provisions/stock'),
      parametres.charger(),
    ])
    provisions.value = provs
    stock.value = niveaux
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les provisions.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

/** Fusionne la fiche article et son niveau de stock pour l'affichage. */
const lignes = computed(() =>
  provisions.value.map(p => {
    const s = stock.value.find(x => x.articleId === p.id)
    return { ...p, quantite: s?.quantite ?? 0, coutMoyen: s?.coutMoyen ?? 0, valeurTotale: s?.valeurTotale ?? 0, sousSeuil: s?.sousSeuil ?? false }
  })
)

function ouvrirCreation() {
  editId.value = null
  Object.assign(form, {
    code: '', libelle: '', uniteMesure: '',
    compteStockNumero: COMPTE_STOCK_DEFAUT, compteChargeNumero: COMPTE_CHARGE_DEFAUT,
    stockMin: 0, actif: true,
  })
  erreur.value = ''
  dialog.value = true
}

function ouvrirEdition(p: Provision) {
  editId.value = p.id
  Object.assign(form, {
    code: p.code,
    libelle: p.libelle,
    uniteMesure: p.uniteMesure || '',
    compteStockNumero: p.compteStockNumero || COMPTE_STOCK_DEFAUT,
    compteChargeNumero: p.compteChargeNumero || COMPTE_CHARGE_DEFAUT,
    stockMin: p.stockMin,
    actif: p.actif,
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
    const body = {
      code: form.code,
      libelle: form.libelle,
      uniteMesure: form.uniteMesure,
      type: 'PROVISION',
      compteStockNumero: form.compteStockNumero,
      compteChargeNumero: form.compteChargeNumero,
      compteProduitNumero: null,
      prixVente: null,
      soumisTva: false,
      stockMin: form.stockMin,
      actif: form.actif,
    }
    if (editId.value) {
      await api(`/restaurant/provisions/${editId.value}`, { method: 'PUT', body })
    } else {
      await api('/restaurant/provisions', { method: 'POST', body })
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

const fmtQte = (q: number, u?: string) => `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(q || 0)}${u ? ' ' + u : ''}`
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Provisions de cuisine</h1>
        <p class="page-sub">Vivres, épices, charbon — stockés et consommés en interne</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-swap-horizontal-bold" to="/restaurant/provisions/mouvements">
          Entrées / sorties
        </v-btn>
        <v-btn v-if="canWrite" color="success" variant="flat" rounded="lg" prepend-icon="mdi-plus" @click="ouvrirCreation">
          Nouvelle provision
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <div class="rst-stats mb-4">
      <div class="rst-stat rst-stat--valeur">
        <v-icon icon="mdi-cash-multiple" size="18" />
        <span class="rst-stat__val">{{ parametres.fmtMontant(valeurTotale) }}</span>
        <span class="rst-stat__lbl">Valeur du stock</span>
      </div>
      <div class="rst-stat rst-stat--lignes">
        <v-icon icon="mdi-sack" size="18" />
        <span class="rst-stat__val">{{ provisions.length }}</span>
        <span class="rst-stat__lbl">Provisions référencées</span>
      </div>
      <div class="rst-stat" :class="nbSousSeuil ? 'rst-stat--alerte' : 'rst-stat--ok'">
        <v-icon :icon="nbSousSeuil ? 'mdi-alert-outline' : 'mdi-check-circle-outline'" size="18" />
        <span class="rst-stat__val">{{ nbSousSeuil }}</span>
        <span class="rst-stat__lbl">Sous le seuil</span>
      </div>
    </div>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Code', key: 'code' },
          { title: 'Libellé', key: 'libelle' },
          { title: 'Unité', key: 'uniteMesure' },
          { title: 'Quantité en stock', key: 'quantite', align: 'end' },
          { title: 'Coût moyen', key: 'coutMoyen', align: 'end' },
          { title: 'Valeur', key: 'valeurTotale', align: 'end' },
          { title: 'Statut', key: 'actif' },
          { title: 'Actions', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="lignes"
        :loading="loading"
        items-per-page="15"
      >
        <template #item.uniteMesure="{ item }">{{ item.uniteMesure || '—' }}</template>
        <template #item.quantite="{ item }">
          <span :class="item.sousSeuil ? 'font-weight-bold text-error' : ''">{{ fmtQte(item.quantite, item.uniteMesure) }}</span>
        </template>
        <template #item.coutMoyen="{ item }">{{ item.coutMoyen > 0 ? parametres.fmtMontant(item.coutMoyen) : '—' }}</template>
        <template #item.valeurTotale="{ item }">{{ parametres.fmtMontant(item.valeurTotale) }}</template>
        <template #item.actif="{ item }">
          <v-chip :color="item.actif ? 'success' : 'grey'" size="small" variant="tonal">{{ item.actif ? 'Actif' : 'Inactif' }}</v-chip>
        </template>
        <template #item.actions="{ item }">
          <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil-outline" title="Modifier" @click="ouvrirEdition(item)" />
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">
            Aucune provision référencée. Créez-en une pour commencer le suivi (riz, tomates, sel, charbon...).
          </div>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="560" scrollable>
      <v-card class="pa-6">
        <h2 class="text-h6 mb-4">{{ editId ? 'Modifier la' : 'Nouvelle' }} provision</h2>

        <v-alert v-if="erreur" type="error" variant="tonal" density="compact" rounded="lg" class="mb-4">{{ erreur }}</v-alert>

        <v-text-field v-model="form.code" label="Code" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.libelle" label="Libellé" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.uniteMesure" label="Unité (kg, sac, litre...)" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model.number="form.stockMin" type="number" label="Seuil de réapprovisionnement" variant="outlined" density="comfortable" class="mb-3" />

        <ComptabiliteSelecteurCompte v-model="form.compteStockNumero" label="Compte de stock" class="mb-3" />
        <ComptabiliteSelecteurCompte v-model="form.compteChargeNumero" label="Compte de charge (sortie)" class="mb-3" />

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
.rst-stat--valeur { background: linear-gradient(135deg,#eff6ff,#dbeafe); color: #1d4ed8; }
.rst-stat--lignes { background: linear-gradient(135deg,#fdf4ff,#f3e8ff); color: #7e22ce; }
.rst-stat--alerte { background: linear-gradient(135deg,#fef2f2,#fee2e2); color: #b91c1c; }
.rst-stat--ok     { background: linear-gradient(135deg,#f0fdf4,#dcfce7); color: #15803d; }
.rst-stat__val { font-size: 1.1rem; font-weight: 800; letter-spacing: -0.5px; }
.rst-stat__lbl { font-weight: 500; opacity: 0.75; }
</style>
