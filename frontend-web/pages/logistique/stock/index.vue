<script setup lang="ts">
definePageMeta({ module: 'LOGISTIQUE' })

interface Niveau {
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

interface Article { id: number; code: string; libelle: string; type: 'MARCHANDISE' | 'SERVICE' }
interface Entrepot { id: number; code: string; nom: string; actif: boolean }

const api = useApi()
const auth = useAuthStore()
const loading = ref(false)
const erreur = ref('')
const niveaux = ref<Niveau[]>([])

const canWrite = computed(() => auth.hasAnyRole(['LOGISTIQUE']))

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    niveaux.value = await api<Niveau[]>('/logistique/stock')
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger l\'état du stock.'
  } finally {
    loading.value = false
  }
}
onMounted(charger)

const valeurTotale = computed(() => niveaux.value.reduce((s, n) => s + (n.valeurTotale || 0), 0))
const nbAlertes = computed(() => niveaux.value.filter(n => n.sousSeuil).length)

function fmt(v: number) {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(v)
}

// ── Ajouter du stock : entrée directe, sans passer par la page Mouvements ──
// L'article n'a plus d'entrepôt d'affectation propre (retiré de sa fiche) :
// c'est ici, au moment d'ajouter une quantité, que l'entrepôt se choisit.
const dialog = ref(false)
const saving = ref(false)
const erreurDialog = ref('')
const articles = ref<Article[]>([])
const entrepots = ref<Entrepot[]>([])
const chargementRef = ref(false)

const form = reactive({
  articleId: null as number | null,
  entrepotId: null as number | null,
  quantite: null as number | null,
  coutUnitaire: null as number | null,
  compteContrepartieNumero: '' as string | null,
  dateMouvement: new Date().toISOString().slice(0, 10),
  libelle: '',
})

const articlesOptions = computed(() =>
  articles.value.filter(a => a.type === 'MARCHANDISE').map(a => ({ title: `${a.code} — ${a.libelle}`, value: a.id })))
const entrepotsOptions = computed(() =>
  entrepots.value.filter(e => e.actif).map(e => ({ title: `${e.code} — ${e.nom}`, value: e.id })))

async function ouvrirAjout() {
  Object.assign(form, {
    articleId: null, entrepotId: null, quantite: null, coutUnitaire: null,
    compteContrepartieNumero: '', dateMouvement: new Date().toISOString().slice(0, 10), libelle: '',
  })
  erreurDialog.value = ''
  dialog.value = true
  if (articles.value.length === 0 || entrepots.value.length === 0) {
    chargementRef.value = true
    try {
      const [arts, ents] = await Promise.all([
        api<Article[]>('/logistique/articles'),
        api<Entrepot[]>('/logistique/entrepots'),
      ])
      articles.value = arts
      entrepots.value = ents
    } catch (e: any) {
      erreurDialog.value = e?.data?.message || 'Impossible de charger articles et entrepôts.'
    } finally {
      chargementRef.value = false
    }
  }
}

async function confirmerAjout() {
  if (!form.articleId || !form.entrepotId || !form.quantite || form.quantite <= 0) {
    erreurDialog.value = 'Article, entrepôt et quantité (positive) sont obligatoires.'
    return
  }
  if (!form.compteContrepartieNumero) {
    erreurDialog.value = "Une entrée de stock nécessite un compte de contrepartie (ex. fournisseur, caisse, capital)."
    return
  }
  saving.value = true
  erreurDialog.value = ''
  try {
    const article = articles.value.find(a => a.id === form.articleId)
    const cree = await api<{ id: number }>('/logistique/mouvements', {
      method: 'POST',
      body: {
        type: 'ENTREE',
        dateMouvement: form.dateMouvement,
        libelle: form.libelle || `Entrée en stock — ${article?.libelle ?? ''}`,
        compteContrepartieNumero: form.compteContrepartieNumero,
        lignes: [{
          articleId: form.articleId,
          entrepotCibleId: form.entrepotId,
          quantite: form.quantite,
          coutUnitaire: form.coutUnitaire || 0,
        }],
      },
    })
    // Comptabilisation immédiate : sans ce second appel, le mouvement reste
    // en brouillon et la quantité n'apparaît pas encore dans l'état du stock,
    // ce qui contredirait le geste "ajouter" attendu ici.
    await api(`/logistique/mouvements/${cree.id}/valider`, { method: 'POST' })
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreurDialog.value = e?.data?.message || "L'entrée en stock a échoué."
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">État du stock</h1>
        <p class="page-sub">Quantités et valorisation au coût moyen pondéré (CMP)</p>
      </div>
      <v-btn v-if="canWrite" color="primary" variant="flat" rounded="lg"
             prepend-icon="mdi-plus-box-outline" @click="ouvrirAjout">
        Ajouter du stock
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-row class="mb-2">
      <v-col cols="6" md="4">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Valeur totale du stock</div>
          <div class="kpi-value">{{ fmt(valeurTotale) }} USD</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="4">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Alertes seuil</div>
          <div class="kpi-value">{{ nbAlertes }}</div>
        </v-card>
      </v-col>
    </v-row>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Article', key: 'articleCode' },
          { title: 'Libellé', key: 'articleLibelle' },
          { title: 'Entrepôt', key: 'entrepotCode' },
          { title: 'Quantité', key: 'quantite', align: 'end' },
          { title: 'CMP', key: 'coutMoyen', align: 'end' },
          { title: 'Valeur', key: 'valeurTotale', align: 'end' },
          { title: 'Seuil', key: 'sousSeuil' },
        ]"
        :items="niveaux"
        :loading="loading"
        items-per-page="25"
        no-data-text="Aucun article en stock. Utilisez « Ajouter du stock » pour la première entrée."
      >
        <template #item.quantite="{ item }">{{ fmt(item.quantite) }} {{ item.uniteMesure || '' }}</template>
        <template #item.coutMoyen="{ item }">{{ fmt(item.coutMoyen) }}</template>
        <template #item.valeurTotale="{ item }">{{ fmt(item.valeurTotale) }}</template>
        <template #item.sousSeuil="{ item }">
          <v-chip :color="item.sousSeuil ? 'warning' : 'success'" size="small" variant="flat">
            {{ item.sousSeuil ? 'Sous seuil' : 'OK' }}
          </v-chip>
        </template>
      </v-data-table>
    </v-card>

    <!-- ── Ajouter du stock ──────────────────────────────────────── -->
    <v-dialog v-model="dialog" max-width="520">
      <v-card class="classroom-card pa-6">
        <div class="text-h6 font-weight-bold mb-4">Ajouter du stock</div>

        <v-alert v-if="erreurDialog" type="error" variant="tonal" density="compact" class="mb-4">
          {{ erreurDialog }}
        </v-alert>

        <v-select
          v-model="form.articleId"
          :items="articlesOptions"
          :loading="chargementRef"
          label="Article *"
          variant="outlined"
          density="comfortable"
          class="mb-3"
        />
        <v-select
          v-model="form.entrepotId"
          :items="entrepotsOptions"
          :loading="chargementRef"
          label="Entrepôt *"
          variant="outlined"
          density="comfortable"
          class="mb-3"
        />
        <v-row dense>
          <v-col cols="6">
            <v-text-field v-model.number="form.quantite" type="number" label="Quantité *"
                          variant="outlined" density="comfortable" />
          </v-col>
          <v-col cols="6">
            <v-text-field v-model.number="form.coutUnitaire" type="number" label="Coût unitaire (USD)"
                          variant="outlined" density="comfortable" />
          </v-col>
        </v-row>
        <ComptabiliteSelecteurCompte v-model="form.compteContrepartieNumero"
                                     label="Compte de contrepartie (fournisseur, caisse, capital…) *" class="mb-3" />
        <v-text-field v-model="form.libelle" label="Libellé (optionnel)"
                      variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.dateMouvement" type="date" label="Date"
                      variant="outlined" density="comfortable" class="mb-4" />

        <div class="d-flex ga-3 justify-end">
          <v-btn variant="text" @click="dialog = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="saving" @click="confirmerAjout">
            Ajouter
          </v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.kpi-label { font-size: 0.72rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.25rem; font-weight: 700; color: #111827; margin-top: 4px; }
</style>
