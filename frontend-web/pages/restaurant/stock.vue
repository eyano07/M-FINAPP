<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * État du stock de la carte (plats et boissons).
 *
 * Lecture seule : les entrées et sorties de stock passent par le module
 * Logistique (réception fournisseur) et par les ventes (déstockage au coût
 * moyen). Cet écran sert à voir ce qui reste et ce qui manque.
 */
interface StockNiveau {
  articleId: number
  articleCode: string
  articleLibelle: string
  uniteMesure?: string
  entrepotId: number
  // Attention : le DTO expose entrepotCode et non entrepotNom — s'appuyer sur
  // le second donne une colonne vide en permanence.
  entrepotCode: string
  quantite: number
  valeurTotale: number
  coutMoyen: number
  stockMin: number
  sousSeuil: boolean
}
interface ArticleCarte { id: number; type: 'PLAT' | 'BOISSON' }

const api = useApi()
const parametres = useRestaurantParametresStore()
const loading = ref(false)
const erreur = ref('')
const stock = ref<StockNiveau[]>([])
const typesParArticle = ref<Record<number, string>>({})
const filtreType = ref<'TOUS' | 'PLAT' | 'BOISSON'>('TOUS')

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [niveaux, carte] = await Promise.all([
      api<StockNiveau[]>('/restaurant/stock'),
      api<ArticleCarte[]>('/restaurant/carte').catch(() => []),
      parametres.charger(),
    ])
    stock.value = niveaux
    typesParArticle.value = Object.fromEntries(carte.map(a => [a.id, a.type]))
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le stock.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

const lignes = computed(() =>
  filtreType.value === 'TOUS'
    ? stock.value
    : stock.value.filter(s => typesParArticle.value[s.articleId] === filtreType.value)
)

const valeurTotale = computed(() => lignes.value.reduce((s, l) => s + (l.valeurTotale || 0), 0))
const nbSousSeuil = computed(() => lignes.value.filter(l => l.sousSeuil).length)

/**
 * Plats en stock dont le coût moyen est nul : leur entrée de production a été
 * saisie sans coût, donc la vente ne constatera aucun coût des ventes et la
 * marge affichée sera de 100 %. L'alerte reste visible en permanence, car
 * l'entrée peut aussi avoir été passée depuis le module Logistique.
 */
const platsSansCout = computed(() =>
  lignes.value.filter(l =>
    typesParArticle.value[l.articleId] === 'PLAT' && l.quantite > 0 && (!l.coutMoyen || l.coutMoyen <= 0))
)

const fmtQte = (q: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(q || 0)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Stock cuisine &amp; bar</h1>
        <p class="page-sub">Quantités et valorisation au coût moyen pondéré</p>
      </div>
      <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-silverware-fork-knife" to="/restaurant/carte">
        La carte
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-alert v-if="platsSansCout.length" type="warning" variant="tonal" rounded="lg" class="mb-4">
      {{ platsSansCout.length }} plat(s) en stock ont un coût moyen nul :
      {{ platsSansCout.map(p => p.articleCode).join(', ') }}.
      Leur entrée en stock a été saisie sans coût de production — à la vente, aucun coût ne sera constaté
      et la marge affichée sera de 100 %.
    </v-alert>

    <div class="rst-stats mb-4">
      <div class="rst-stat rst-stat--valeur">
        <v-icon icon="mdi-cash-multiple" size="18" />
        <span class="rst-stat__val">{{ parametres.fmtMontant(valeurTotale) }}</span>
        <span class="rst-stat__lbl">Valeur du stock</span>
      </div>
      <div class="rst-stat rst-stat--lignes">
        <v-icon icon="mdi-clipboard-list-outline" size="18" />
        <span class="rst-stat__val">{{ lignes.length }}</span>
        <span class="rst-stat__lbl">Lignes de stock</span>
      </div>
      <div class="rst-stat" :class="nbSousSeuil ? 'rst-stat--alerte' : 'rst-stat--ok'">
        <v-icon :icon="nbSousSeuil ? 'mdi-alert-outline' : 'mdi-check-circle-outline'" size="18" />
        <span class="rst-stat__val">{{ nbSousSeuil }}</span>
        <span class="rst-stat__lbl">Sous le seuil</span>
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
          { title: 'Code', key: 'articleCode' },
          { title: 'Libellé', key: 'articleLibelle' },
          { title: 'Entrepôt', key: 'entrepotCode' },
          { title: 'Quantité', key: 'quantite', align: 'end' },
          { title: 'Coût moyen', key: 'coutMoyen', align: 'end' },
          { title: 'Valeur', key: 'valeurTotale', align: 'end' },
          { title: 'Seuil', key: 'stockMin', align: 'end' },
        ]"
        :items="lignes"
        :loading="loading"
        items-per-page="25"
      >
        <template #item.quantite="{ item }">
          <span :class="item.sousSeuil ? 'font-weight-bold text-error' : ''">
            {{ fmtQte(item.quantite) }} {{ item.uniteMesure || '' }}
          </span>
        </template>
        <template #item.coutMoyen="{ item }">
          <span :class="item.coutMoyen > 0 ? '' : 'text-warning font-weight-medium'">
            {{ item.coutMoyen > 0 ? parametres.fmtMontant(item.coutMoyen) : 'nul' }}
          </span>
        </template>
        <template #item.valeurTotale="{ item }">{{ parametres.fmtMontant(item.valeurTotale) }}</template>
        <template #item.stockMin="{ item }">{{ fmtQte(item.stockMin) }}</template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">
            Aucun stock enregistré pour la carte. Les quantités apparaîtront après une entrée en stock.
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
.rst-stat--lignes { background: linear-gradient(135deg,#f0fdf4,#dcfce7); color: #15803d; }
.rst-stat--alerte { background: linear-gradient(135deg,#fef2f2,#fee2e2); color: #b91c1c; }
.rst-stat--ok     { background: linear-gradient(135deg,#f0fdf4,#dcfce7); color: #15803d; }
.rst-stat__val { font-size: 1.1rem; font-weight: 800; letter-spacing: -0.5px; }
.rst-stat__lbl { font-weight: 500; opacity: 0.75; }
</style>
