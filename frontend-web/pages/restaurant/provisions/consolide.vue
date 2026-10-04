<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * État consolidé du stock des provisions : une ligne par provision, au coût
 * moyen pondéré — sans tenir compte des lots (fournisseur, date d'achat,
 * prix) qui le composent. Pour cette traçabilité par lot (et créer/modifier
 * une provision), voir /restaurant/provisions.
 *
 * Lecture seule.
 */
interface Provision {
  id: number
  code: string
  libelle: string
  uniteMesure?: string
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

const api = useApi()
const parametres = useRestaurantParametresStore()
const loading = ref(false)
const erreur = ref('')
const provisions = ref<Provision[]>([])
const stock = ref<StockNiveau[]>([])

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
    return {
      ...p,
      quantite: s?.quantite ?? 0,
      prixAchatMoyen: s?.prixAchatMoyen ?? 0,
      coutMoyen: s?.coutMoyen ?? 0,
      valeurTotale: s?.valeurTotale ?? 0,
      sousSeuil: s?.sousSeuil ?? false,
    }
  })
)

const fmtQte = (q: number, u?: string) => `${parametres.fmtQuantite(q)}${u ? ' ' + u : ''}`

/**
 * Part de transport et manutention incorporée au coût moyen d'une unité
 * (coutMoyen - prixAchatMoyen) : 0 si la provision n'en a reçu aucun (tout
 * le stock antérieur à cette fonctionnalité).
 */
function fraisApproche(item: { coutMoyen: number; prixAchatMoyen: number }): number {
  const f = (item.coutMoyen || 0) - (item.prixAchatMoyen || 0)
  return f > 0.000001 ? f : 0
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Provisions — stock consolidé</h1>
        <p class="page-sub">Une ligne par provision, quels que soient ses lots</p>
      </div>
      <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-format-list-bulleted" to="/restaurant/provisions">
        Stock par lot
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
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
      Transport et manutention : part de ces frais incorporée à chaque unité — le coût moyen et la valeur du stock les incluent déjà.
    </p>
    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Code', key: 'code' },
          { title: 'Libellé', key: 'libelle' },
          { title: 'Unité', key: 'uniteMesure' },
          { title: 'Quantité en stock', key: 'quantite', align: 'end' },
          { title: 'Transport et manutention', key: 'fraisApprocheUnitaire', align: 'end', sortable: false },
          { title: 'Coût moyen', key: 'coutMoyen', align: 'end' },
          { title: 'Valeur', key: 'valeurTotale', align: 'end' },
          { title: 'Statut', key: 'actif' },
        ]"
        :items="lignes"
        :loading="loading"
        items-per-page="15"
      >
        <template #item.uniteMesure="{ item }">{{ item.uniteMesure || '—' }}</template>
        <template #item.quantite="{ item }">
          <span :class="item.sousSeuil ? 'font-weight-bold text-error' : ''">{{ fmtQte(item.quantite, item.uniteMesure) }}</span>
        </template>
        <template #item.fraisApprocheUnitaire="{ item }">{{ fraisApproche(item) ? parametres.fmtMontant(fraisApproche(item)) : '—' }}</template>
        <template #item.coutMoyen="{ item }">
          <span :title="fraisApproche(item) ? 'Prix d\'achat + transport et manutention' : undefined">
            {{ item.coutMoyen > 0 ? parametres.fmtMontant(item.coutMoyen) : '—' }}
          </span>
        </template>
        <template #item.valeurTotale="{ item }">{{ parametres.fmtMontant(item.valeurTotale) }}</template>
        <template #item.actif="{ item }">
          <v-chip :color="item.actif ? 'success' : 'grey'" size="small" variant="tonal">{{ item.actif ? 'Actif' : 'Inactif' }}</v-chip>
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">
            Aucune provision référencée.
          </div>
        </template>
      </v-data-table>
    </v-card>
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
