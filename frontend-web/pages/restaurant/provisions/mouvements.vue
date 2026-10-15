<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT', roles: ['RESP_RESTAURANT', 'RESP_CUISINE', 'DFIN', 'DG', 'DA', 'COMPTABLE', 'ADMIN'] })

/**
 * Entrées et sorties des provisions.
 *
 * Les ENTRÉES ne se saisissent pas ici : une provision n'entre en stock que par l'achat (page Achat de
 * provisions), qui suit le circuit des notes de frais — DFIN, DA, puis la caisse — et fait entrer la
 * marchandise au paiement. Cette page en montre seulement le résultat dans l'historique.
 *
 * Les SORTIES (utilisation en cuisine, casse, péremption...) se saisissent ici : un type choisi dans une liste
 * et une observation libre. Sortie de stock au coût moyen, avec pièce comptable — même mécanique que pour une
 * vente, sans la notion de casier/consigne qui ne s'applique pas à ces articles.
 */
interface Provision { id: number; code: string; libelle: string; uniteMesure?: string }
interface Entrepot { id: number; code: string; nom: string }
/** Forme exacte de MouvementProvisionResponse (backend) : un solde courant, pas un journal à motif libre. */
interface LigneGrandLivre {
  id: number
  dateEcriture: string
  articleCode: string
  entrepotCode: string
  mouvementReference?: string
  qteEntree: number
  qteSortie: number
  qteApres: number
  valeurUnitaire: number
  valeurApres: number
  mouvementId?: number
  mouvementStatut?: 'BROUILLON' | 'VALIDE' | 'ANNULE'
  /** Calculé par le serveur : réception directe ou sortie saisie ici, pas une production ni un achat par note. */
  annulable: boolean
  /** Type de la sortie saisie ici (nom de TypeSortieProvision) ; absent pour une entrée ou une sortie plus ancienne. */
  typeSortie?: string | null
  observation?: string | null
}

/** Types de sortie : miroir de TypeSortieProvision côté serveur. « Autre » exige une observation. */
const TYPES_SORTIE = [
  { value: 'UTILISATION_CUISINE', title: 'Utilisation en cuisine', couleur: 'primary' },
  { value: 'CASSE', title: 'Casse / renversé', couleur: 'error' },
  { value: 'PEREMPTION', title: 'Périmé', couleur: 'warning' },
  { value: 'AVARIE', title: 'Moisi / avarié', couleur: 'deep-orange' },
  { value: 'PERTE', title: 'Perte / manquant', couleur: 'grey' },
  { value: 'REPAS_PERSONNEL', title: 'Repas du personnel', couleur: 'teal' },
  { value: 'DON', title: 'Don / offert', couleur: 'purple' },
  { value: 'AUTRE', title: 'Autre', couleur: 'grey' },
]
const typeSortie = (code?: string | null) => TYPES_SORTIE.find(t => t.value === code)

const api = useApi()
/** Devise d'affichage et formatage des montants et quantités (store du restaurant). */
const parametresRestaurant = useRestaurantParametresStore()
onMounted(() => { parametresRestaurant.charger() })
const auth = useAuthStore()
const loading = ref(false)
const savingSortie = ref(false)
const erreur = ref('')
const succes = ref('')
const provisions = ref<Provision[]>([])
const entrepots = ref<Entrepot[]>([])
const historique = ref<LigneGrandLivre[]>([])

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT', 'RESP_CUISINE', 'ADMIN']))

const formSortie = reactive({
  articleId: null as number | null,
  quantite: null as number | null,
  entrepotId: null as number | null,
  typeSortie: null as string | null,
  observation: '',
  dateMouvement: new Date().toISOString().slice(0, 10),
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [provs, ents, hist] = await Promise.all([
      api<Provision[]>('/restaurant/provisions'),
      api<Entrepot[]>('/restaurant/entrepots').catch(() => []),
      api<LigneGrandLivre[]>('/restaurant/provisions/mouvements').catch(() => []),
    ])
    provisions.value = provs
    entrepots.value = ents
    historique.value = hist
    if (ents.length === 1) {
      formSortie.entrepotId = ents[0].id
    }
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible de charger l'historique.")
  } finally {
    loading.value = false
  }
}
onMounted(charger)

async function enregistrerSortie() {
  if (!formSortie.articleId || !formSortie.quantite || !formSortie.entrepotId) {
    erreur.value = 'Provision, quantité et entrepôt sont obligatoires.'
    return
  }
  if (!formSortie.typeSortie) {
    erreur.value = 'Choisissez le type de sortie.'
    return
  }
  if (formSortie.typeSortie === 'AUTRE' && !formSortie.observation.trim()) {
    erreur.value = "Précisez la raison de la sortie dans l'observation : le type « Autre » ne l'explique pas."
    return
  }
  savingSortie.value = true
  erreur.value = ''
  succes.value = ''
  try {
    await api('/restaurant/provisions/sorties', {
      method: 'POST',
      body: {
        articleId: formSortie.articleId,
        quantite: formSortie.quantite,
        entrepotId: formSortie.entrepotId,
        typeSortie: formSortie.typeSortie,
        observation: formSortie.observation.trim() || null,
        dateMouvement: formSortie.dateMouvement,
      },
    })
    succes.value = 'Sortie enregistrée.'
    formSortie.quantite = null
    formSortie.typeSortie = null
    formSortie.observation = ''
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement de la sortie.")
  } finally {
    savingSortie.value = false
  }
}

// ── Annulation d'un mouvement saisi par erreur ─────────────────────────────
// Le bouton n'apparaît que sur la première ligne du mouvement : une ligne du
// grand livre de stock est un solde, et un mouvement annulé en ajoute une
// seconde (la contre-passation) sous la même référence.
const premiereLigneDuMouvement = computed(() => {
  const vues = new Set<number>()
  const premieres = new Set<number>()
  for (const l of historique.value) {
    if (l.mouvementId == null || vues.has(l.mouvementId)) continue
    vues.add(l.mouvementId)
    premieres.add(l.id)
  }
  return premieres
})
const peutAnnuler = (l: LigneGrandLivre) => canWrite.value && l.annulable && premiereLigneDuMouvement.value.has(l.id)

const aAnnuler = ref<LigneGrandLivre | null>(null)
const annulation = ref(false)

async function confirmerAnnulation() {
  const ligne = aAnnuler.value
  if (!ligne?.mouvementId) return
  annulation.value = true
  erreur.value = ''
  succes.value = ''
  try {
    await api(`/restaurant/provisions/mouvements/${ligne.mouvementId}/annuler`, { method: 'POST' })
    succes.value = `Mouvement ${ligne.mouvementReference} annulé : le stock est rétabli et ses écritures sont extournées.`
    aAnnuler.value = null
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'annulation du mouvement.")
    aAnnuler.value = null
  } finally {
    annulation.value = false
  }
}

const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
const fmtQte = (q: number) => parametresRestaurant.fmtQuantite(q)
/**
 * valeurUnitaire/valeurApres (StockGrandLivreResponse) sont en USD, devise de
 * base du grand livre : affichés dans la devise du restaurant (FC par défaut),
 * au taux du jour, comme sur les autres écrans du module.
 */
const fmtUSD = (montant?: number | null) =>
  parametresRestaurant.fmtMontant(montant)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Entrées / sorties de provisions</h1>
        <p class="page-sub">Les entrées viennent des achats ; utilisation en cuisine, casse et péremption se saisissent ici</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn v-if="canWrite" variant="tonal" color="success" rounded="lg" prepend-icon="mdi-truck-delivery-outline" to="/restaurant/provisions/receptions">
          Achat de provisions
        </v-btn>
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-sack" to="/restaurant/provisions">
          Les provisions
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" class="mb-4" closable @click:close="succes = ''">
      {{ succes }}
    </v-alert>
    <v-alert v-if="!loading && !provisions.length" type="info" variant="tonal" rounded="lg" class="mb-4">
      Aucune provision référencée. Créez-en une avant d'enregistrer un mouvement.
    </v-alert>

    <div v-if="canWrite" class="rst-forms mb-5">
      <v-card class="classroom-card pa-5">
        <p class="rst-form-title"><v-icon icon="mdi-truck-delivery-outline" size="16" class="mr-1" />Entrée en stock</p>
        <p class="text-body-2 mb-4">
          Une provision n'entre en stock que par l'achat : la demande suit le circuit des notes de frais
          (DFIN, DA, puis la caisse) et le stock augmente au paiement, avec son écriture comptable.
        </p>
        <v-btn color="success" variant="flat" rounded="lg" block prepend-icon="mdi-cart-plus" to="/restaurant/provisions/receptions">
          Acheter des provisions
        </v-btn>
      </v-card>

      <v-card class="classroom-card pa-5">
        <p class="rst-form-title"><v-icon icon="mdi-arrow-up-bold-outline" size="16" class="mr-1" />Sortie (cuisine, casse, péremption...)</p>
        <v-select v-model="formSortie.articleId" :items="provisions.map(p => ({ title: p.libelle, value: p.id }))"
          label="Provision *" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model.number="formSortie.quantite" type="number" label="Quantité *" variant="outlined" density="comfortable" class="mb-3" />
        <v-select v-model="formSortie.entrepotId" :items="entrepots.map(e => ({ title: `${e.code} — ${e.nom}`, value: e.id }))"
          label="Entrepôt *" variant="outlined" density="comfortable" class="mb-3" />
        <v-select v-model="formSortie.typeSortie" :items="TYPES_SORTIE" item-title="title" item-value="value"
          label="Type de sortie *" variant="outlined" density="comfortable" class="mb-3" />
        <v-textarea v-model="formSortie.observation" :label="formSortie.typeSortie === 'AUTRE' ? 'Observation *' : 'Observation'" rows="2"
          :placeholder="formSortie.typeSortie === 'AUTRE' ? 'Précisez la raison de la sortie' : 'Précision utile : lot, circonstances, bénéficiaire...'"
          variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="formSortie.dateMouvement" type="date" label="Date" variant="outlined" density="comfortable" class="mb-4" />
        <v-btn color="error" variant="tonal" rounded="lg" block :loading="savingSortie" :disabled="!provisions.length" @click="enregistrerSortie">
          Enregistrer la sortie
        </v-btn>
      </v-card>
    </div>

    <v-card class="classroom-card">
      <div class="rst-card-head">
        <v-icon icon="mdi-history" size="18" class="mr-2" />
        Historique
      </div>
      <v-data-table
        :headers="[
          { title: 'Date', key: 'dateEcriture' },
          { title: 'Provision', key: 'articleCode' },
          { title: 'Entrepôt', key: 'entrepotCode' },
          { title: 'Mouvement', key: 'mouvementReference' },
          { title: 'Type de sortie', key: 'typeSortie', sortable: false },
          { title: 'Entrée', key: 'qteEntree', align: 'end' },
          { title: 'Sortie', key: 'qteSortie', align: 'end' },
          { title: 'Solde qté', key: 'qteApres', align: 'end' },
          { title: 'CMP', key: 'valeurUnitaire', align: 'end' },
          { title: 'Valeur stock', key: 'valeurApres', align: 'end' },
          { title: '', key: 'actions', align: 'end', sortable: false },
        ]"
        :items="historique"
        :loading="loading"
        items-per-page="15"
      >
        <template #item.dateEcriture="{ item }">{{ fmtDate(item.dateEcriture) }}</template>
        <template #item.articleCode="{ item }">
          {{ provisions.find(p => p.code === item.articleCode)?.libelle || item.articleCode }}
        </template>
        <template #item.typeSortie="{ item }">
          <template v-if="item.typeSortie">
            <v-chip :color="typeSortie(item.typeSortie)?.couleur || 'grey'" size="x-small" variant="tonal">
              {{ typeSortie(item.typeSortie)?.title || item.typeSortie }}
            </v-chip>
            <div v-if="item.observation" class="rst-observation">{{ item.observation }}</div>
          </template>
          <span v-else class="text-medium-emphasis">—</span>
        </template>
        <template #item.qteEntree="{ item }">
          <span v-if="item.qteEntree" class="text-success font-weight-medium">+{{ fmtQte(item.qteEntree) }}</span>
          <span v-else class="text-medium-emphasis">—</span>
        </template>
        <template #item.qteSortie="{ item }">
          <span v-if="item.qteSortie" class="text-error font-weight-medium">−{{ fmtQte(item.qteSortie) }}</span>
          <span v-else class="text-medium-emphasis">—</span>
        </template>
        <template #item.qteApres="{ item }">{{ fmtQte(item.qteApres) }}</template>
        <template #item.valeurUnitaire="{ item }">{{ fmtUSD(item.valeurUnitaire) }}</template>
        <template #item.valeurApres="{ item }">{{ fmtUSD(item.valeurApres) }}</template>
        <template #item.mouvementReference="{ item }">
          {{ item.mouvementReference || '—' }}
          <v-chip v-if="item.mouvementStatut === 'ANNULE'" size="x-small" variant="tonal" color="grey" class="ml-1">Annulé</v-chip>
        </template>
        <template #item.actions="{ item }">
          <v-btn
            v-if="peutAnnuler(item)"
            icon="mdi-undo-variant"
            size="small"
            variant="text"
            color="error"
            :title="`Annuler le mouvement ${item.mouvementReference}`"
            :aria-label="`Annuler le mouvement ${item.mouvementReference}`"
            @click="aAnnuler = item"
          />
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">Aucun mouvement enregistré.</div>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog :model-value="!!aAnnuler" max-width="480" @update:model-value="v => { if (!v && !annulation) aAnnuler = null }">
      <v-card v-if="aAnnuler" rounded="lg">
        <v-card-title class="text-subtitle-1 font-weight-bold">Annuler le mouvement {{ aAnnuler.mouvementReference }} ?</v-card-title>
        <v-card-text>
          <p v-if="aAnnuler.qteEntree" class="mb-2">
            La réception de {{ fmtQte(aAnnuler.qteEntree) }} sort du stock, et ses deux écritures sont extournées :
            l'entrée en stock et l'achat au fournisseur.
          </p>
          <p v-else class="mb-2">
            Les {{ fmtQte(aAnnuler.qteSortie) }} sorties reviennent en stock, et l'écriture de consommation est extournée.
          </p>
          <p class="text-medium-emphasis mb-0">Vous pourrez ensuite saisir le bon mouvement.</p>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="annulation" @click="aAnnuler = null">Garder</v-btn>
          <v-btn color="error" variant="flat" :loading="annulation" @click="confirmerAnnulation">Annuler le mouvement</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.rst-forms { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; align-items: start; }
@media (max-width: 900px) { .rst-forms { grid-template-columns: 1fr; } }
.rst-form-title { font-size: 0.85rem; font-weight: 700; color: #374151; margin: 0 0 16px; display: flex; align-items: center; }
.rst-observation { margin-top: 2px; font-size: 0.72rem; line-height: 1.3; color: #6b7280; max-width: 260px; white-space: normal; }
.rst-card-head {
  display: flex;
  align-items: center;
  font-size: 0.8rem;
  font-weight: 700;
  letter-spacing: 0.3px;
  text-transform: uppercase;
  color: #6b7280;
  padding: 16px 20px 12px;
}
</style>
