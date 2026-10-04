<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Provisions de cuisine : vivres, épices, charbon...
 *
 * Stockées et consommées en interne (préparation des plats), jamais vendues —
 * contrairement à la carte, pas de prix de vente ni de compte produit.
 *
 * Le tableau affiche une ligne par LOT actif (date d'achat, fournisseur,
 * prix) plutôt qu'une ligne par provision : une même provision peut avoir
 * été reçue à plusieurs dates, fournisseurs ou prix — voir
 * /restaurant/provisions/consolide pour la vue agrégée (une ligne par
 * provision, sans tenir compte des lots). Suivi de gestion, sans effet sur
 * la comptabilité (toujours au coût moyen pondéré).
 */
interface Provision {
  id: number
  code: string
  libelle: string
  uniteMesure?: string
  compteStockNumero?: string
  compteChargeNumero?: string
  compteAchatNumero?: string
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
  /** Prix d'achat moyen HORS transport et manutention ; coutMoyen les inclut. */
  prixAchatMoyen: number
  stockMin: number
  sousSeuil: boolean
}
interface LotProvision {
  id: number
  articleId: number
  dateEntree: string
  fournisseur?: string | null
  quantiteRestante: number
  prixAchatUnitaire: number
  prixTransportUnitaire: number
  coutUnitaire: number
  valeur: number
}

const api = useApi()
const auth = useAuthStore()
const parametres = useRestaurantParametresStore()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const provisions = ref<Provision[]>([])
const stock = ref<StockNiveau[]>([])
const lots = ref<LotProvision[]>([])
const dialog = ref(false)
const editId = ref<number | null>(null)

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT', 'ADMIN']))

/**
 * Racines des comptes d'une provision, les mêmes que celles sous lesquelles
 * le serveur crée ses comptes dédiés (RestaurantService.creerProvision) :
 * 331 Matières consommables / 6032 Variations des stocks de matières
 * premières / 6021 Achats de matières premières. Une provision n'est pas
 * une marchandise revendue : ni 6011 ni 6033 — vérifiés actifs et
 * imputables. Ne sert qu'à compléter une ancienne provision sans compte.
 */
const COMPTE_STOCK_DEFAUT = '331'
const COMPTE_CHARGE_DEFAUT = '6032'
const COMPTE_ACHAT_DEFAUT = '6021'

const form = reactive({
  code: '',
  libelle: '',
  uniteMesure: '',
  compteStockNumero: COMPTE_STOCK_DEFAUT as string | null,
  compteChargeNumero: COMPTE_CHARGE_DEFAUT as string | null,
  compteAchatNumero: COMPTE_ACHAT_DEFAUT as string | null,
  stockMin: 0,
  actif: true,
})

const valeurTotale = computed(() => stock.value.reduce((s, l) => s + (l.valeurTotale || 0), 0))
const nbSousSeuil = computed(() => stock.value.filter(l => l.sousSeuil).length)

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [provs, niveaux, lotsProv] = await Promise.all([
      api<Provision[]>('/restaurant/provisions'),
      api<StockNiveau[]>('/restaurant/provisions/stock'),
      api<LotProvision[]>('/restaurant/provisions/lots'),
      parametres.charger(),
    ])
    provisions.value = provs
    stock.value = niveaux
    lots.value = lotsProv
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les provisions.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

/**
 * Une ligne par lot actif d'une provision (date d'achat, fournisseur, prix) ;
 * une ligne unique avec tirets si elle n'en a aucun (jamais reçue, ou stock
 * épuisé) — pour que le catalogue reste complet même sans lot.
 */
const lignes = computed(() => {
  const parProvision = new Map<number, LotProvision[]>()
  for (const l of lots.value) {
    const arr = parProvision.get(l.articleId)
    if (arr) arr.push(l)
    else parProvision.set(l.articleId, [l])
  }
  return provisions.value.flatMap(p => {
    const sousSeuil = stock.value.find(s => s.articleId === p.id)?.sousSeuil ?? false
    const lotsDeP = parProvision.get(p.id) ?? []
    if (lotsDeP.length === 0) {
      return [{
        ...p, sousSeuil, rowKey: `p${p.id}`,
        dateEntree: null as string | null, fournisseur: null as string | null,
        quantite: 0, prixAchatUnitaire: 0, prixTransportUnitaire: 0, coutUnitaire: 0, valeur: 0,
      }]
    }
    return lotsDeP.map(l => ({
      ...p, sousSeuil, rowKey: `l${l.id}`,
      dateEntree: l.dateEntree, fournisseur: l.fournisseur ?? null,
      quantite: l.quantiteRestante, prixAchatUnitaire: l.prixAchatUnitaire,
      prixTransportUnitaire: l.prixTransportUnitaire, coutUnitaire: l.coutUnitaire, valeur: l.valeur,
    }))
  })
})

function ouvrirCreation() {
  editId.value = null
  Object.assign(form, {
    code: '', libelle: '', uniteMesure: '',
    compteStockNumero: COMPTE_STOCK_DEFAUT, compteChargeNumero: COMPTE_CHARGE_DEFAUT,
    compteAchatNumero: COMPTE_ACHAT_DEFAUT,
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
    compteAchatNumero: p.compteAchatNumero || COMPTE_ACHAT_DEFAUT,
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
      compteAchatNumero: form.compteAchatNumero,
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

const fmtQte = (q: number, u?: string) => `${parametres.fmtQuantite(q)}${u ? ' ' + u : ''}`
const fmtDate = (d: string | null) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Provisions de cuisine</h1>
        <p class="page-sub">Vivres, épices, charbon — stockés et consommés en interne</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-view-list-outline" to="/restaurant/provisions/consolide">
          Stock consolidé
        </v-btn>
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

    <p class="text-caption text-medium-emphasis mb-2">
      Une ligne par lot actif (date d'achat, fournisseur, prix).
      <strong>Sortie des lots : CMP</strong> — chaque sortie prélève sur tous les lots au prorata, d'où des quantités parfois décimales.
      Suivi de gestion, sans effet sur la comptabilité, qui reste au coût moyen pondéré.
    </p>
    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Code', key: 'code' },
          { title: 'Libellé', key: 'libelle' },
          { title: 'Unité', key: 'uniteMesure' },
          { title: 'Date d\'achat', key: 'dateEntree' },
          { title: 'Fournisseur', key: 'fournisseur' },
          { title: 'Quantité', key: 'quantite', align: 'end' },
          { title: 'Prix d\'achat', key: 'prixAchatUnitaire', align: 'end' },
          { title: 'Transport et manutention', key: 'prixTransportUnitaire', align: 'end' },
          { title: 'Coût unitaire', key: 'coutUnitaire', align: 'end' },
          { title: 'Valeur', key: 'valeur', align: 'end' },
          { title: 'Statut', key: 'actif' },
          { title: 'Actions', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="lignes"
        item-value="rowKey"
        :loading="loading"
        items-per-page="15"
      >
        <template #item.uniteMesure="{ item }">{{ item.uniteMesure || '—' }}</template>
        <template #item.dateEntree="{ item }">{{ fmtDate(item.dateEntree) }}</template>
        <template #item.fournisseur="{ item }">
          <span v-if="!item.fournisseur" class="text-medium-emphasis">—</span>
          <span v-else>{{ item.fournisseur }}</span>
        </template>
        <template #item.quantite="{ item }">
          <span :class="item.sousSeuil ? 'font-weight-bold text-error' : ''">{{ fmtQte(item.quantite, item.uniteMesure) }}</span>
        </template>
        <template #item.prixAchatUnitaire="{ item }">{{ item.prixAchatUnitaire > 0 ? parametres.fmtMontant(item.prixAchatUnitaire) : '—' }}</template>
        <template #item.prixTransportUnitaire="{ item }">
          <span v-if="!(item.prixTransportUnitaire > 0.000001)" class="text-medium-emphasis">—</span>
          <span v-else>{{ parametres.fmtMontant(item.prixTransportUnitaire) }}</span>
        </template>
        <template #item.coutUnitaire="{ item }">{{ item.coutUnitaire > 0 ? parametres.fmtMontant(item.coutUnitaire) : '—' }}</template>
        <template #item.valeur="{ item }">{{ item.valeur > 0 ? parametres.fmtMontant(item.valeur) : '—' }}</template>
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

        <v-card-text class="pa-0">
        <v-alert v-if="erreur" type="error" variant="tonal" density="compact" rounded="lg" class="mb-4">{{ erreur }}</v-alert>

        <v-text-field v-model="form.code" label="Code" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.libelle" label="Libellé" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.uniteMesure" label="Unité (kg, sac, litre...)" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model.number="form.stockMin" type="number" label="Seuil de réapprovisionnement" variant="outlined" density="comfortable" class="mb-3" />

        <v-alert v-if="!editId" type="info" variant="tonal" density="compact" rounded="lg" class="mb-4">
          <v-icon icon="mdi-information-outline" size="14" class="mr-1" />
          Un compte d'achat, de stock et de charge dédié à cette provision sera créé automatiquement.
        </v-alert>
        <template v-else>
          <ComptabiliteSelecteurCompte v-model="form.compteAchatNumero" label="Compte d'achat (602x)" class="mb-3" />
          <ComptabiliteSelecteurCompte v-model="form.compteStockNumero" label="Compte de stock (33x)" class="mb-3" />
          <ComptabiliteSelecteurCompte v-model="form.compteChargeNumero" label="Compte de variation de stock (603x)" class="mb-3" />
        </template>

        <v-switch v-model="form.actif" label="Actif" color="success" density="compact" hide-details class="mb-4" />
        </v-card-text>

        <div class="d-flex justify-end ga-2 mt-4">
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
.rst-stat--ok     { background: linear-gradient(135deg,var(--color-primary-lighter),var(--color-primary-light)); color: var(--color-primary-dark); }
.rst-stat__val { font-size: 1.1rem; font-weight: 800; letter-spacing: -0.5px; }
.rst-stat__lbl { font-weight: 500; opacity: 0.75; }
</style>
