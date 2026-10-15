<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * État consolidé du stock de la carte (plats et boissons) : une ligne par
 * article, au coût moyen pondéré — sans tenir compte des lots (fournisseur,
 * date d'achat, prix) qui le composent. Pour cette traçabilité par lot, voir
 * la page « Stock cuisine & bar » (/restaurant/stock).
 *
 * Lecture seule : les entrées et sorties de stock passent par le module
 * Logistique (réception fournisseur) et par les ventes (déstockage au coût
 * moyen). Cet écran sert à voir ce qui reste et ce qui manque.
 *
 * Chaque ligne dit si l'article est un plat ou une boisson (colonne Type) ; la
 * bascule Tous / Plats / Boissons et la recherche (code, libellé, entrepôt, type
 * — insensible à la casse et aux accents, plusieurs mots cumulés) se combinent.
 * Les trois compteurs du haut suivent la bascule, pas la recherche : ils restent
 * la valeur du stock de la catégorie affichée.
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
  /** Prix d'achat moyen HORS transport et manutention ; coutMoyen les inclut. */
  prixAchatMoyen: number
  stockMin: number
  sousSeuil: boolean
}
interface ArticleCarte { id: number; type: 'PLAT' | 'BOISSON' }
type TypeArticle = 'PLAT' | 'BOISSON'

/** Même présentation que la carte du restaurant (pages/restaurant/carte.vue) : une puce par type. */
const META_TYPE: Record<TypeArticle, { label: string; couleur: string; icone: string }> = {
  PLAT: { label: 'Plat', couleur: 'deep-orange', icone: 'mdi-food-outline' },
  BOISSON: { label: 'Boisson', couleur: 'indigo', icone: 'mdi-bottle-soda-classic-outline' },
}

const api = useApi()
const auth = useAuthStore()
const parametres = useRestaurantParametresStore()
/** La production est ouverte au responsable restaurant, au responsable cuisine et à l'administrateur (écriture sur le module) : pas de lien mort pour les autres. */
const peutProduire = computed(() => auth.hasAnyRole(['RESP_RESTAURANT', 'RESP_CUISINE', 'ADMIN']))
const loading = ref(false)
const erreur = ref('')
const stock = ref<StockNiveau[]>([])
const typesParArticle = ref<Record<number, string>>({})
const filtreType = ref<'TOUS' | 'PLAT' | 'BOISSON'>('TOUS')
/**
 * Le serveur ne renvoie à un responsable cuisine que les plats, à un responsable bar que les boissons (voir
 * PerimetreRestaurant) : la bascule Tous / Plats / Boissons n'a alors rien à départager. Les autres rôles gardent les deux.
 */
const unSeulDomaine = computed(() => auth.restaurantCuisine !== auth.restaurantBar)
const recherche = ref('')
const page = ref(1)

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

/** Les lignes de la catégorie choisie, chacune avec son type (inconnu si la carte n'a pas pu être lue). */
const lignes = computed(() => stock.value
  .map(s => ({ ...s, type: (typesParArticle.value[s.articleId] ?? null) as TypeArticle | null }))
  .filter(s => filtreType.value === 'TOUS' || s.type === filtreType.value)
)

/** Ce que le tableau affiche : la catégorie choisie, restreinte par la recherche. */
const lignesAffichees = computed(() => lignes.value.filter(l =>
  correspondRecherche([l.articleCode, l.articleLibelle, l.entrepotCode, l.type ? META_TYPE[l.type].label : null], recherche.value)))

const rechercheActive = computed(() => !!recherche.value.trim())

// Un filtre plus strict peut laisser moins de pages que celle affichée : retour à la première.
watch([filtreType, recherche], () => { page.value = 1 })

const valeurTotale = computed(() => lignes.value.reduce((s, l) => s + (l.valeurTotale || 0), 0))
const nbSousSeuil = computed(() => lignes.value.filter(l => l.sousSeuil).length)

/**
 * Plats en stock dont le coût moyen est nul : leur entrée en stock a été passée
 * sans coût (module Logistique), donc la vente ne constatera aucun coût des
 * ventes et la marge affichée sera de 100 %. Produire le plat depuis l'écran
 * Production lui donne au contraire le coût réel de ses ingrédients.
 */
const platsSansCout = computed(() =>
  lignes.value.filter(l => l.type === 'PLAT' && l.quantite > 0 && (!l.coutMoyen || l.coutMoyen <= 0))
)

const fmtQte = (q: number) => parametres.fmtQuantite(q)

/**
 * Part de transport et manutention incorporée au coût moyen d'une unité
 * (coutMoyen - prixAchatMoyen) : 0 si l'article n'en a reçu aucun (tout le
 * stock antérieur à cette fonctionnalité, ou un plat, dont le coût moyen est
 * son coût de production).
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
        <h1 class="page-title">Stock consolidé</h1>
        <p class="page-sub">Une ligne par article, quels que soient ses lots — quantités et valorisation au coût moyen pondéré</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-format-list-bulleted" to="/restaurant/stock">
          Stock par lot
        </v-btn>
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-silverware-fork-knife" to="/restaurant/carte">
          La carte
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-alert v-if="platsSansCout.length" type="warning" variant="tonal" rounded="lg" class="mb-4">
      {{ platsSansCout.length }} plat(s) en stock ont un coût moyen nul :
      {{ platsSansCout.map(p => p.articleCode).join(', ') }}.
      Leur entrée en stock a été saisie sans coût de production — à la vente, aucun coût ne sera constaté
      et la marge affichée sera de 100 %.
      <template #append>
        <v-btn v-if="peutProduire" size="small" variant="tonal" color="warning" rounded="lg" to="/restaurant/production">
          Produire
        </v-btn>
      </template>
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

    <p class="text-caption text-medium-emphasis mb-2">
      Transport et manutention : part de ces frais incorporée à chaque unité — le coût moyen et la valeur du stock les incluent déjà. Pour un plat, le coût moyen est son coût de production.
    </p>
    <div class="d-flex flex-wrap align-center ga-3 mb-4">
      <!-- Densité par défaut = 48 px, la hauteur du champ de recherche voisin. -->
      <v-btn-toggle v-if="!unSeulDomaine" v-model="filtreType" mandatory variant="outlined" rounded="lg">
        <v-btn value="TOUS">Tous</v-btn>
        <v-btn value="PLAT">Plats</v-btn>
        <v-btn value="BOISSON">Boissons</v-btn>
      </v-btn-toggle>
      <v-text-field
        v-model="recherche"
        prepend-inner-icon="mdi-magnify"
        placeholder="Rechercher : code, libellé, entrepôt, plat ou boisson…"
        aria-label="Rechercher un article du stock"
        clearable
        hide-details
        variant="outlined"
        density="comfortable"
        rounded="lg"
        class="flex-grow-1 rst-recherche"
        style="max-width: 460px; min-width: 240px"
      />
      <span v-if="rechercheActive" class="text-body-2 text-medium-emphasis rst-recherche-compte">
        {{ lignesAffichees.length }} sur {{ lignes.length }}
      </span>
    </div>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Code', key: 'articleCode' },
          { title: 'Libellé', key: 'articleLibelle' },
          { title: 'Type', key: 'type' },
          { title: 'Entrepôt', key: 'entrepotCode' },
          { title: 'Quantité', key: 'quantite', align: 'end' },
          { title: 'Transport et manutention', key: 'fraisApprocheUnitaire', align: 'end', sortable: false },
          { title: 'Coût moyen', key: 'coutMoyen', align: 'end' },
          { title: 'Valeur', key: 'valeurTotale', align: 'end' },
          { title: 'Seuil', key: 'stockMin', align: 'end' },
        ]"
        :items="lignesAffichees"
        :loading="loading"
        v-model:page="page"
        items-per-page="25"
      >
        <!-- Un code composé (BRAL-PRIM-55CL) reste sur une ligne : coupé aux tirets, il ferait grandir chaque rangée. -->
        <template #item.articleCode="{ item }"><span class="text-no-wrap">{{ item.articleCode }}</span></template>
        <!-- Plat ou boisson : même puce que sur la carte. Inconnu seulement si la carte n'a pas pu être lue. -->
        <template #item.type="{ item }">
          <v-chip v-if="item.type" :color="META_TYPE[item.type].couleur" size="small" variant="tonal">
            <v-icon :icon="META_TYPE[item.type].icone" size="14" class="mr-1" />
            {{ META_TYPE[item.type].label }}
          </v-chip>
          <span v-else class="text-medium-emphasis">—</span>
        </template>
        <template #item.quantite="{ item }">
          <span :class="item.sousSeuil ? 'font-weight-bold text-error' : ''">
            {{ fmtQte(item.quantite) }} {{ item.uniteMesure || '' }}
          </span>
        </template>
        <!-- Un plat n'a pas de frais d'approche : son coût est celui de sa production. -->
        <template #item.fraisApprocheUnitaire="{ item }">
          <span v-if="item.type === 'PLAT' || !fraisApproche(item)" class="text-medium-emphasis">—</span>
          <span v-else>{{ parametres.fmtMontant(fraisApproche(item)) }}</span>
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
            {{ rechercheActive
              ? `Aucun article ne correspond à « ${recherche.trim()} ».`
              : 'Aucun stock enregistré pour la carte. Les quantités apparaîtront après une entrée en stock.' }}
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
.rst-stat--lignes { background: linear-gradient(135deg,var(--color-primary-lighter),var(--color-primary-light)); color: var(--color-primary-dark); }
.rst-stat--alerte { background: linear-gradient(135deg,#fef2f2,#fee2e2); color: #b91c1c; }
.rst-stat--ok     { background: linear-gradient(135deg,var(--color-primary-lighter),var(--color-primary-light)); color: var(--color-primary-dark); }
.rst-stat__val { font-size: 1.1rem; font-weight: 800; letter-spacing: -0.5px; }
.rst-stat__lbl { font-weight: 500; opacity: 0.75; }
</style>
