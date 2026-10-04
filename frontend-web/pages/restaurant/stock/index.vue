<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Stock des boissons par lot : date d'achat, fournisseur et prix de chaque
 * réception encore en stock — un même article peut apparaître sur plusieurs
 * lignes s'il a été acheté à plusieurs dates, fournisseurs ou prix.
 *
 * Suivi de gestion (traçabilité), séparé de la comptabilité : le grand livre
 * reste au coût moyen pondéré quelle que soit la méthode de sortie des lots
 * choisie dans les paramètres (FIFO ou CMP) — voir /restaurant/stock/consolide
 * pour la vue agrégée (une ligne par article, sans tenir compte des lots).
 *
 * Un plat n'a pas de lot d'achat (il se produit) : il ne figure que sur la
 * page consolidée.
 */
interface LotStock {
  id: number
  articleId: number
  articleCode: string
  articleLibelle: string
  uniteMesure?: string
  entrepotCode: string
  dateEntree: string
  fournisseur?: string | null
  quantiteRestante: number
  prixAchatUnitaire: number
  prixTransportUnitaire: number
  coutUnitaire: number
  valeur: number
}

const api = useApi()
const parametres = useRestaurantParametresStore()
const loading = ref(false)
const erreur = ref('')
const lots = ref<LotStock[]>([])

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [donnees] = await Promise.all([
      api<LotStock[]>('/restaurant/stock/lots'),
      parametres.charger(),
    ])
    lots.value = donnees
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le stock par lot.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

const valeurTotale = computed(() => lots.value.reduce((s, l) => s + (l.valeur || 0), 0))
const nbFournisseurs = computed(() => new Set(lots.value.map(l => l.fournisseur).filter(Boolean)).size)

const fmtQte = (q: number, u?: string) => `${parametres.fmtQuantite(q)}${u ? ' ' + u : ''}`
const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Stock cuisine &amp; bar</h1>
        <p class="page-sub">Un lot par achat — date, fournisseur, prix. Un même article peut avoir plusieurs lots.</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-view-list-outline" to="/restaurant/stock/consolide">
          Stock consolidé
        </v-btn>
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-silverware-fork-knife" to="/restaurant/carte">
          La carte
        </v-btn>
      </div>
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
        <v-icon icon="mdi-clipboard-list-outline" size="18" />
        <span class="rst-stat__val">{{ lots.length }}</span>
        <span class="rst-stat__lbl">Lots actifs</span>
      </div>
      <div class="rst-stat rst-stat--ok">
        <v-icon icon="mdi-truck-outline" size="18" />
        <span class="rst-stat__val">{{ nbFournisseurs }}</span>
        <span class="rst-stat__lbl">Fournisseurs</span>
      </div>
    </div>

    <p class="text-caption text-medium-emphasis mb-4">
      <strong>Sortie des lots : FIFO</strong> — la livraison la plus ancienne part d'abord, les lots restent en bouteilles entières.
      Suivi de gestion, sans effet sur la comptabilité, qui reste au coût moyen pondéré.
    </p>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Code', key: 'articleCode' },
          { title: 'Libellé', key: 'articleLibelle' },
          { title: 'Entrepôt', key: 'entrepotCode' },
          { title: 'Date d\'achat', key: 'dateEntree' },
          { title: 'Fournisseur', key: 'fournisseur' },
          { title: 'Quantité', key: 'quantiteRestante', align: 'end' },
          { title: 'Prix d\'achat', key: 'prixAchatUnitaire', align: 'end' },
          { title: 'Transport et manutention', key: 'prixTransportUnitaire', align: 'end' },
          { title: 'Coût unitaire', key: 'coutUnitaire', align: 'end' },
          { title: 'Valeur', key: 'valeur', align: 'end' },
        ]"
        :items="lots"
        :loading="loading"
        items-per-page="25"
      >
        <template #item.dateEntree="{ item }">{{ fmtDate(item.dateEntree) }}</template>
        <template #item.fournisseur="{ item }">
          <span v-if="!item.fournisseur" class="text-medium-emphasis">—</span>
          <span v-else>{{ item.fournisseur }}</span>
        </template>
        <template #item.quantiteRestante="{ item }">{{ fmtQte(item.quantiteRestante, item.uniteMesure) }}</template>
        <template #item.prixAchatUnitaire="{ item }">{{ parametres.fmtMontant(item.prixAchatUnitaire) }}</template>
        <template #item.prixTransportUnitaire="{ item }">
          <span v-if="!(item.prixTransportUnitaire > 0.000001)" class="text-medium-emphasis">—</span>
          <span v-else>{{ parametres.fmtMontant(item.prixTransportUnitaire) }}</span>
        </template>
        <template #item.coutUnitaire="{ item }">{{ parametres.fmtMontant(item.coutUnitaire) }}</template>
        <template #item.valeur="{ item }">{{ parametres.fmtMontant(item.valeur) }}</template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">
            Aucun lot actif. Les lots apparaissent après le paiement d'une note d'achat de boissons.
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
.rst-stat--ok     { background: linear-gradient(135deg,var(--color-primary-lighter),var(--color-primary-light)); color: var(--color-primary-dark); }
.rst-stat__val { font-size: 1.1rem; font-weight: 800; letter-spacing: -0.5px; }
.rst-stat__lbl { font-weight: 500; opacity: 0.75; }
</style>
