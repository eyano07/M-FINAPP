<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Entrées (réception) et sorties (utilisation en cuisine, casse, péremption)
 * des provisions. Sortie de stock au coût moyen, avec pièce comptable — même
 * mécanique que pour une vente, sans la notion de casier/consigne qui ne
 * s'applique pas à ces articles.
 *
 * Le coût d'achat se saisit dans la devise réellement quotée (USD ou FC) et
 * n'est jamais converti côté client : si FC, le serveur résout le taux du
 * jour une seule fois, au moment de cette réception (immédiate, sans délai
 * d'approbation), pour figer la valeur en USD — un taux qui change ensuite
 * n'affecte jamais une réception déjà enregistrée.
 */
interface Provision { id: number; code: string; libelle: string; uniteMesure?: string }
interface Entrepot { id: number; code: string; nom: string }
/** Forme exacte de StockGrandLivreResponse (backend) : un solde couranu, pas un journal à motif libre. */
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
}

const api = useApi()
const auth = useAuthStore()
const loading = ref(false)
const savingEntree = ref(false)
const savingSortie = ref(false)
const erreur = ref('')
const succes = ref('')
const provisions = ref<Provision[]>([])
const entrepots = ref<Entrepot[]>([])
const historique = ref<LigneGrandLivre[]>([])
const tauxChange = ref(0)

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT']))

const COMPTE_FOURNISSEUR = '4011'

const formEntree = reactive({
  articleId: null as number | null,
  quantite: null as number | null,
  deviseCout: 'USD' as 'USD' | 'CDF',
  coutUnitaire: null as number | null,
  entrepotId: null as number | null,
  compteContrepartieNumero: COMPTE_FOURNISSEUR as string | null,
  dateReception: new Date().toISOString().slice(0, 10),
})

/** Équivalent indicatif dans l'autre devise, au taux du jour — n'influence ni la saisie ni l'enregistrement. */
const coutEquivalent = computed(() => {
  if (!formEntree.coutUnitaire || tauxChange.value <= 0) return null
  return formEntree.deviseCout === 'USD'
    ? `≈ ${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(formEntree.coutUnitaire * tauxChange.value)} FC`
    : `≈ ${new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD' }).format(formEntree.coutUnitaire / tauxChange.value)}`
})

const formSortie = reactive({
  articleId: null as number | null,
  quantite: null as number | null,
  entrepotId: null as number | null,
  motif: '',
  dateMouvement: new Date().toISOString().slice(0, 10),
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [provs, ents, hist, tauxData] = await Promise.all([
      api<Provision[]>('/restaurant/provisions'),
      api<Entrepot[]>('/restaurant/entrepots').catch(() => []),
      api<LigneGrandLivre[]>('/restaurant/provisions/mouvements').catch(() => []),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
    ])
    provisions.value = provs
    entrepots.value = ents
    historique.value = hist
    tauxChange.value = tauxData.taux || 0
    if (ents.length === 1) {
      formEntree.entrepotId = ents[0].id
      formSortie.entrepotId = ents[0].id
    }
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible de charger l'historique.")
  } finally {
    loading.value = false
  }
}
onMounted(charger)

async function enregistrerEntree() {
  if (!formEntree.articleId || !formEntree.quantite || !formEntree.entrepotId) {
    erreur.value = 'Provision, quantité et entrepôt sont obligatoires.'
    return
  }
  if (!formEntree.coutUnitaire || formEntree.coutUnitaire <= 0) {
    erreur.value = "Le coût d'achat est obligatoire : sans lui, le coût de revient resterait nul."
    return
  }
  savingEntree.value = true
  erreur.value = ''
  succes.value = ''
  try {
    await api('/restaurant/provisions/entrees', {
      method: 'POST',
      body: {
        articleId: formEntree.articleId,
        quantite: formEntree.quantite,
        coutUnitaire: formEntree.coutUnitaire,
        devise: formEntree.deviseCout,
        entrepotId: formEntree.entrepotId,
        compteContrepartieNumero: formEntree.compteContrepartieNumero,
        dateReception: formEntree.dateReception,
      },
    })
    succes.value = 'Entrée enregistrée.'
    formEntree.quantite = null
    formEntree.coutUnitaire = null
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement de l'entrée.")
  } finally {
    savingEntree.value = false
  }
}

async function enregistrerSortie() {
  if (!formSortie.articleId || !formSortie.quantite || !formSortie.entrepotId) {
    erreur.value = 'Provision, quantité et entrepôt sont obligatoires.'
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
        motif: formSortie.motif || null,
        dateMouvement: formSortie.dateMouvement,
      },
    })
    succes.value = 'Sortie enregistrée.'
    formSortie.quantite = null
    formSortie.motif = ''
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement de la sortie.")
  } finally {
    savingSortie.value = false
  }
}

const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
const fmtQte = (q: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(q || 0)
/** valeurUnitaire/valeurApres (StockGrandLivreResponse) sont déjà en USD — devise de base du grand livre, aucune conversion à faire. */
const fmtUSD = (montant?: number | null) =>
  montant == null ? '—' : new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD' }).format(montant)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Entrées / sorties de provisions</h1>
        <p class="page-sub">Réceptions, utilisation en cuisine, casse et péremption</p>
      </div>
      <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-sack" to="/restaurant/provisions">
        Les provisions
      </v-btn>
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
        <p class="rst-form-title"><v-icon icon="mdi-truck-delivery-outline" size="16" class="mr-1" />Entrée (réception)</p>
        <v-select v-model="formEntree.articleId" :items="provisions.map(p => ({ title: p.libelle, value: p.id }))"
          label="Provision *" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model.number="formEntree.quantite" type="number" label="Quantité *" variant="outlined" density="comfortable" class="mb-3" />
        <div class="d-flex ga-2 mb-1 align-start">
          <v-text-field
            v-model.number="formEntree.coutUnitaire"
            type="number"
            :label="`Coût unitaire (${formEntree.deviseCout}) *`"
            hint="Sans coût, le coût de revient resterait nul"
            persistent-hint
            variant="outlined"
            density="comfortable"
            class="flex-grow-1"
          />
          <v-btn-toggle v-model="formEntree.deviseCout" mandatory density="comfortable" variant="outlined" rounded="lg" style="margin-top: 4px">
            <v-btn value="USD" size="small">$US</v-btn>
            <v-btn value="CDF" size="small">FC</v-btn>
          </v-btn-toggle>
        </div>
        <p class="text-caption text-medium-emphasis mb-3" style="min-height: 1.2em">{{ coutEquivalent }}</p>
        <v-select v-model="formEntree.entrepotId" :items="entrepots.map(e => ({ title: `${e.code} — ${e.nom}`, value: e.id }))"
          label="Entrepôt *" variant="outlined" density="comfortable" class="mb-3" />
        <ComptabiliteSelecteurCompte v-model="formEntree.compteContrepartieNumero" label="Contrepartie (fournisseur, caisse...)" class="mb-3" />
        <v-text-field v-model="formEntree.dateReception" type="date" label="Date" variant="outlined" density="comfortable" class="mb-4" />
        <v-btn color="success" variant="flat" rounded="lg" block :loading="savingEntree" :disabled="!provisions.length" @click="enregistrerEntree">
          Enregistrer l'entrée
        </v-btn>
      </v-card>

      <v-card class="classroom-card pa-5">
        <p class="rst-form-title"><v-icon icon="mdi-arrow-up-bold-outline" size="16" class="mr-1" />Sortie (cuisine, casse, péremption)</p>
        <v-select v-model="formSortie.articleId" :items="provisions.map(p => ({ title: p.libelle, value: p.id }))"
          label="Provision *" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model.number="formSortie.quantite" type="number" label="Quantité *" variant="outlined" density="comfortable" class="mb-3" />
        <v-select v-model="formSortie.entrepotId" :items="entrepots.map(e => ({ title: `${e.code} — ${e.nom}`, value: e.id }))"
          label="Entrepôt *" variant="outlined" density="comfortable" class="mb-3" />
        <v-textarea v-model="formSortie.motif" label="Motif" rows="2" placeholder="Utilisation cuisine, casse, périmé..." variant="outlined" density="comfortable" class="mb-3" />
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
          { title: 'Entrée', key: 'qteEntree', align: 'end' },
          { title: 'Sortie', key: 'qteSortie', align: 'end' },
          { title: 'Solde qté', key: 'qteApres', align: 'end' },
          { title: 'CMP', key: 'valeurUnitaire', align: 'end' },
          { title: 'Valeur stock', key: 'valeurApres', align: 'end' },
        ]"
        :items="historique"
        :loading="loading"
        items-per-page="15"
      >
        <template #item.dateEcriture="{ item }">{{ fmtDate(item.dateEcriture) }}</template>
        <template #item.articleCode="{ item }">
          {{ provisions.find(p => p.code === item.articleCode)?.libelle || item.articleCode }}
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
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">Aucun mouvement enregistré.</div>
        </template>
      </v-data-table>
    </v-card>
  </div>
</template>

<style scoped>
.rst-forms { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; }
@media (max-width: 900px) { .rst-forms { grid-template-columns: 1fr; } }
.rst-form-title { font-size: 0.85rem; font-weight: 700; color: #374151; margin: 0 0 16px; display: flex; align-items: center; }
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
