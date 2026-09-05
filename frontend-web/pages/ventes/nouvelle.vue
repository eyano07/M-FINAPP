<script setup lang="ts">
definePageMeta({ module: 'VENTES', niveau: 'ECRITURE' })

interface Article {
  id: number
  code: string
  libelle: string
  type: 'MARCHANDISE' | 'SERVICE'
  minerais?: boolean
  prixVente?: number
  soumisTva: boolean
  actif: boolean
  vendable: boolean
}
interface Client { id: number; nom: string; actif: boolean }
interface Etablissement { id: number; nom: string; actif: boolean }
interface Entrepot { id: number; code: string; nom: string; actif: boolean }
interface StockNiveau { articleId: number; entrepotId: number; quantite: number }

interface LigneForm {
  articleId: number | null
  quantite: number | null
  // Prix saisi dans la devise de la vente (form.devise), jamais converti ici :
  // c'est le serveur qui ramène la vente en devise de base.
  prixUnitaire: number | null
  /** Minerais uniquement : chargement cédé, chacun ayant son propre prix. */
  camionId: number | null
}

interface CamionDispo { id: number; plaque: string; dateAchat: string; prixAchat: number }

const api = useApi()
const router = useRouter()

const loading = ref(false)
const envoi = ref(false)
const erreur = ref('')

const articles = ref<Article[]>([])
const catalogueComplet = ref<Article[]>([])
const stock = ref<StockNiveau[]>([])
const clients = ref<Client[]>([])
const banques = ref<Etablissement[]>([])
const operateurs = ref<Etablissement[]>([])
const entrepots = ref<Entrepot[]>([])
const tauxChange = ref(1)
const tauxTva = ref(0)

// Le client peut venir du répertoire ou être saisi librement.
const modeClient = ref<'REPERTOIRE' | 'LIBRE'>('REPERTOIRE')

const form = reactive({
  dateVente: new Date().toISOString().slice(0, 10),
  clientId: null as number | null,
  clientNom: '',
  modeReglement: 'CAISSE' as 'CREDIT' | 'CAISSE' | 'BANQUE' | 'MOBILE_MONEY',
  devise: 'CDF' as 'CDF' | 'USD',
  etablissementId: null as number | null,
  entrepotId: null as number | null,
  lignes: [{ articleId: null, quantite: 1, prixUnitaire: null, camionId: null }] as LigneForm[],
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [arts, cls, bqs, ops, ents, stk, taux, tva] = await Promise.all([
      api<Article[]>('/logistique/articles'),
      api<Client[]>('/clients').catch(() => []),
      api<Etablissement[]>('/etablissements?type=BANQUE').catch(() => []),
      api<Etablissement[]>('/etablissements?type=MOBILE_MONEY').catch(() => []),
      api<Entrepot[]>('/logistique/entrepots').catch(() => []),
      api<StockNiveau[]>('/logistique/stock').catch(() => []),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })) /* 0 et non 1 : un repli a 1 affichait les
        montants FC tels quels comme des USD (surevaluation d'un facteur
        egal au taux, ~2800x) sans que rien ne le signale. A 0, les
        convertisseurs (tous gardes par `taux > 0`) renvoient 0, valeur
        manifestement fausse plutot que plausible. */,
      api<{ taux: number }>('/admin/taux-tva').catch(() => ({ taux: 0 })),
    ])
    catalogueComplet.value = arts
    articles.value = arts.filter((a) => a.vendable)
    clients.value = cls.filter((c) => c.actif)
    banques.value = bqs.filter((b) => b.actif)
    operateurs.value = ops.filter((o) => o.actif)
    entrepots.value = ents.filter((e) => e.actif)
    stock.value = stk
    tauxChange.value = taux.taux || 0
    tauxTva.value = tva.taux || 0
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les données.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

const articlesOptions = computed(() =>
  articles.value.map((a) => ({
    title: `${a.code} — ${a.libelle}${a.type === 'SERVICE' ? ' (service)' : ''}`,
    value: a.id,
  }))
)
const clientsOptions = computed(() => clients.value.map((c) => ({ title: c.nom, value: c.id })))
const entrepotsOptions = computed(() => entrepots.value.map((e) => ({ title: `${e.code} — ${e.nom}`, value: e.id })))
const etablissementsOptions = computed(() => {
  const source = form.modeReglement === 'BANQUE' ? banques.value : operateurs.value
  return source.map((e) => ({ title: e.nom, value: e.id }))
})

const besoinEtablissement = computed(() =>
  form.modeReglement === 'BANQUE' || form.modeReglement === 'MOBILE_MONEY')
const articleDe = (id: number | null) => articles.value.find((a) => a.id === id) || null
const contientMarchandise = computed(() =>
  form.lignes.some((l) => articleDe(l.articleId)?.type === 'MARCHANDISE'))

function ajouterLigne() {
  form.lignes.push({ articleId: null, quantite: 1, prixUnitaire: null, camionId: null })
}
function supprimerLigne(i: number) {
  if (form.lignes.length <= 1) return
  form.lignes.splice(i, 1)
}

// Le prix catalogue de l'article est tenu en FC — un choix independant de
// la devise de base du grand livre (article.prixVente n'est jamais touche
// par ConversionDeviseService), gere ici cote client de bout en bout.
const prixCatalogue = (article: Article | null) => {
  if (article?.prixVente == null) return null
  if (form.devise === 'CDF') return article.prixVente
  return tauxChange.value > 0 ? arrondi(article.prixVente / tauxChange.value) : null
}

/** Reprend le prix catalogue quand on choisit un article. */
function onArticleSelect(i: number, articleId: number | null) {
  const article = articleDe(articleId)
  form.lignes[i].articleId = articleId
  form.lignes[i].camionId = null
  const prix = prixCatalogue(article)
  if (prix != null) form.lignes[i].prixUnitaire = prix
  // Minerais : un camion se vend entier, la quantité n'est pas saisissable.
  if (article?.minerais) {
    form.lignes[i].quantite = 1
    chargerCamions(articleId!)
  }
}

/**
 * Camions encore en stock d'un minerais, chargés à la demande et mis en cache
 * par article : la liste ne dépend pas de la ligne, et une vente peut porter
 * plusieurs camions du même minerais.
 */
const camionsParArticle = ref<Record<number, CamionDispo[]>>({})
async function chargerCamions(articleId: number) {
  if (camionsParArticle.value[articleId]) return
  try {
    camionsParArticle.value[articleId] =
      await api<CamionDispo[]>(`/logistique/minerais/camions/disponibles?articleId=${articleId}`)
  } catch {
    camionsParArticle.value[articleId] = []
  }
}

/** Camions proposables sur la ligne i : ceux en stock, moins ceux déjà pris par une autre ligne. */
function camionsDisponibles(i: number): CamionDispo[] {
  const l = form.lignes[i]
  if (!l.articleId) return []
  const tous = camionsParArticle.value[l.articleId] || []
  const prisAilleurs = new Set(
    form.lignes.filter((autre, j) => j !== i && autre.camionId).map(autre => autre.camionId))
  return tous.filter(c => !prisAilleurs.has(c.id))
}

const estMinerais = (articleId: number | null) => !!articleDe(articleId)?.minerais

// Changer de devise en cours de saisie reconvertit les prix déjà tapés, pour
// que le caissier retrouve le même montant réel dans l'autre unité.
watch(() => form.devise, (apres, avant) => {
  if (apres === avant || tauxChange.value <= 0) return
  for (const ligne of form.lignes) {
    if (ligne.prixUnitaire == null) continue
    ligne.prixUnitaire = apres === 'USD'
      ? arrondi(ligne.prixUnitaire / tauxChange.value)
      : arrondi(ligne.prixUnitaire * tauxChange.value)
  }
})

// Totaux calculés en direct, avec la même règle que le serveur : la TVA
// est calculée ligne par ligne, les totaux sont la somme des lignes.
const totaux = computed(() => {
  let ht = 0
  let tva = 0
  for (const l of form.lignes) {
    const article = articleDe(l.articleId)
    if (!article || !l.quantite || l.prixUnitaire == null) continue
    const ligneHt = arrondi(l.quantite * l.prixUnitaire)
    ht += ligneHt
    if (article.soumisTva && tauxTva.value > 0) {
      tva += arrondi((ligneHt * tauxTva.value) / 100)
    }
  }
  return { ht: arrondi(ht), tva: arrondi(tva), ttc: arrondi(ht + tva) }
})

const arrondi = (v: number) => Math.round(v * 100) / 100

// Cumule les quantités par article (une même marchandise peut apparaître
// sur plusieurs lignes) pour comparer au stock réellement disponible.
const quantiteDemandeeParArticle = computed(() => {
  const totaux = new Map<number, number>()
  for (const l of form.lignes) {
    if (!l.articleId || !l.quantite || l.quantite <= 0) continue
    if (articleDe(l.articleId)?.type !== 'MARCHANDISE') continue
    totaux.set(l.articleId, (totaux.get(l.articleId) || 0) + l.quantite)
  }
  return totaux
})
const stockDisponible = (articleId: number, entrepotId: number | null) =>
  stock.value.find((s) => s.articleId === articleId && s.entrepotId === entrepotId)?.quantite ?? 0

// Raisons précises et toujours visibles pour lesquelles la vente ne peut pas
// être enregistrée — jamais un simple bouton grisé sans explication.
const raisonsBlocage = computed(() => {
  const raisons: string[] = []

  if (!loading.value && articles.value.length === 0) {
    raisons.push("Aucun article vendable dans le catalogue : définissez un prix de vente et un compte de produit sur au moins un article.")
  }

  const lignesRemplies = form.lignes.filter((l) => l.articleId)
  if (lignesRemplies.length === 0) {
    raisons.push('Ajoutez au moins un article à la vente.')
  }
  for (const l of lignesRemplies) {
    const nom = articleDe(l.articleId)?.libelle ?? "l'article"
    // Un minerais se vend camion par camion : la quantité vaut toujours 1,
    // c'est le camion qui doit être désigné.
    if (estMinerais(l.articleId)) {
      if (!l.camionId) {
        raisons.push(`Choisissez le camion vendu pour « ${nom} ».`)
      }
    } else if (!l.quantite || l.quantite <= 0) {
      raisons.push(`Renseignez une quantité valide pour « ${nom} ».`)
    }
  }

  if (form.devise === 'USD' && tauxChange.value <= 0) {
    raisons.push("Aucun taux de change n'est défini : une vente en USD ne peut pas être convertie. "
      + 'Vendez en FC ou demandez à un administrateur le taux du jour.')
  }

  if (besoinEtablissement.value && !form.etablissementId) {
    raisons.push(form.modeReglement === 'BANQUE'
      ? 'Choisissez la banque de règlement.'
      : "Choisissez l'opérateur mobile money.")
  }

  if (contientMarchandise.value && !form.entrepotId) {
    raisons.push("Choisissez l'entrepôt de sortie des marchandises.")
  } else if (contientMarchandise.value) {
    for (const [articleId, demande] of quantiteDemandeeParArticle.value) {
      const disponible = stockDisponible(articleId, form.entrepotId)
      if (demande > disponible) {
        raisons.push(
          `Stock insuffisant pour « ${articleDe(articleId)?.libelle} » dans cet entrepôt : `
          + `${disponible} disponible(s), ${demande} demandé(s).`
        )
      }
    }
  }

  return raisons
})

const peutEnregistrer = computed(() => raisonsBlocage.value.length === 0)

async function enregistrer(validerEnsuite: boolean) {
  if (!peutEnregistrer.value) {
    erreur.value = raisonsBlocage.value[0] || 'Complétez au moins une ligne et les informations de règlement.'
    return
  }
  envoi.value = true
  erreur.value = ''
  try {
    const body = {
      dateVente: form.dateVente,
      clientId: modeClient.value === 'REPERTOIRE' ? form.clientId : null,
      clientNom: modeClient.value === 'LIBRE' ? form.clientNom : null,
      modeReglement: form.modeReglement,
      devise: form.devise,
      etablissementId: besoinEtablissement.value ? form.etablissementId : null,
      entrepotId: contientMarchandise.value ? form.entrepotId : null,
      // Les prix partent tels quels dans la devise de la vente : c'est le
      // serveur qui les convertit, au taux qu'il fige sur la vente.
      lignes: form.lignes
        .filter((l) => l.articleId && l.quantite)
        .map((l) => ({
          articleId: l.articleId,
          quantite: estMinerais(l.articleId) ? 1 : l.quantite,
          prixUnitaire: l.prixUnitaire ?? 0,
          camionId: l.camionId,
        })),
    }
    const vente = await api<{ id: number }>('/ventes', { method: 'POST', body })

    if (validerEnsuite) {
      await api(`/ventes/${vente.id}/valider`, { method: 'POST' })
    }
    await router.push(`/ventes/${vente.id}`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Erreur lors de l'enregistrement de la vente.")
  } finally {
    envoi.value = false
  }
}

// Les montants s'affichent dans la devise de la vente, jamais convertis.
const fmtMontant = (v: number) =>
  form.devise === 'USD'
    ? new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(v || 0)
    : `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(v || 0)} FC`

// Contre-valeur indicative dans l'autre devise, au taux du jour.
const contreValeur = computed(() => {
  if (tauxChange.value <= 0) return ''
  return form.devise === 'USD'
    ? `≈ ${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(totaux.value.ttc * tauxChange.value)} FC`
    : `≈ ${new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(totaux.value.ttc / tauxChange.value)}`
})
</script>

<template>
  <div class="vn-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Nouvelle vente</h1>
        <p class="page-sub">
          Marchandises et services · TVA
          <span class="vn-taux">{{ tauxTva }} %</span>
        </p>
      </div>
      <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/ventes">Retour</v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-card class="classroom-card pa-6 mb-4">
      <!-- ── Client ────────────────────────────────────────── -->
      <p class="vn-section">Client</p>
      <v-btn-toggle v-model="modeClient" mandatory color="primary" variant="outlined" rounded="lg" class="mb-3 w-100">
        <v-btn value="REPERTOIRE" class="flex-1-1">
          <v-icon icon="mdi-account-multiple-outline" class="mr-1" size="18" />Répertoire
        </v-btn>
        <v-btn value="LIBRE" class="flex-1-1">
          <v-icon icon="mdi-account-edit-outline" class="mr-1" size="18" />Nom libre
        </v-btn>
      </v-btn-toggle>

      <v-autocomplete
        v-if="modeClient === 'REPERTOIRE'"
        v-model="form.clientId"
        :items="clientsOptions"
        label="Client"
        prepend-inner-icon="mdi-account-outline"
        no-data-text="Aucun client — utilisez le nom libre ou créez une fiche"
        variant="outlined"
        density="comfortable"
        rounded="lg"
        clearable
        hint="Facultatif : laissez vide pour une vente au comptoir"
        persistent-hint
        class="mb-4"
      />
      <v-text-field
        v-else
        v-model="form.clientNom"
        label="Nom du client"
        placeholder="ex : Client comptoir"
        prepend-inner-icon="mdi-account-outline"
        variant="outlined"
        density="comfortable"
        rounded="lg"
        class="mb-4"
      />

      <!-- ── Règlement ─────────────────────────────────────── -->
      <p class="vn-section">Règlement</p>
      <v-row>
        <v-col cols="12" md="4">
          <v-select
            v-model="form.modeReglement"
            :items="[
              { title: 'Au comptant — Caisse', value: 'CAISSE' },
              { title: 'Au comptant — Banque', value: 'BANQUE' },
              { title: 'Au comptant — Mobile Money', value: 'MOBILE_MONEY' },
              { title: 'À crédit (créance client)', value: 'CREDIT' },
            ]"
            label="Mode de règlement *"
            prepend-inner-icon="mdi-cash-multiple"
            variant="outlined"
            density="comfortable"
            rounded="lg"
          />
        </v-col>
        <v-col cols="12" md="4">
          <v-select
            v-model="form.devise"
            :items="[
              { title: 'Franc congolais (FC)', value: 'CDF' },
              { title: 'Dollar américain (USD)', value: 'USD' },
            ]"
            label="Devise de la vente *"
            prepend-inner-icon="mdi-cash-sync"
            variant="outlined"
            density="comfortable"
            rounded="lg"
            hide-details
          />
          <!-- Texte simple plutôt qu'un hint Vuetify : la valeur du taux
               arrive après l'hydratation et la transition des messages
               laisserait l'ancien texte affiché. -->
          <p class="vn-hint">
            {{ form.devise === 'CDF'
              ? `Vente en FC · taux du jour conservé : ${tauxChange} FC/$`
              : `Convertie en FC au taux du jour : ${tauxChange} FC/$` }}
          </p>
        </v-col>
        <v-col v-if="besoinEtablissement" cols="12" md="4">
          <v-select
            v-model="form.etablissementId"
            :items="etablissementsOptions"
            :label="form.modeReglement === 'BANQUE' ? 'Banque *' : 'Opérateur *'"
            :prepend-inner-icon="form.modeReglement === 'BANQUE' ? 'mdi-bank' : 'mdi-cellphone'"
            no-data-text="Aucun établissement configuré"
            variant="outlined"
            density="comfortable"
            rounded="lg"
          />
        </v-col>
        <v-col cols="12" md="4">
          <v-text-field
            v-model="form.dateVente"
            label="Date de la vente"
            type="date"
            prepend-inner-icon="mdi-calendar"
            variant="outlined"
            density="comfortable"
            rounded="lg"
          />
        </v-col>
      </v-row>

      <!-- ── Entrepôt (marchandises) ───────────────────────── -->
      <template v-if="contientMarchandise">
        <p class="vn-section">Sortie de stock</p>
        <v-select
          v-model="form.entrepotId"
          :items="entrepotsOptions"
          label="Entrepôt de sortie *"
          prepend-inner-icon="mdi-warehouse"
          no-data-text="Aucun entrepôt configuré"
          variant="outlined"
          density="comfortable"
          rounded="lg"
          hint="Les marchandises vendues seront déduites de cet entrepôt"
          persistent-hint
          class="mb-2"
        />
      </template>
    </v-card>

    <!-- ── Lignes ──────────────────────────────────────────── -->
    <v-card class="classroom-card pa-6 mb-4">
      <div class="d-flex align-center mb-3">
        <p class="vn-section mb-0">Articles vendus</p>
        <v-spacer />
        <v-btn size="small" variant="tonal" color="primary" prepend-icon="mdi-plus" @click="ajouterLigne">
          Ajouter une ligne
        </v-btn>
      </div>

      <div v-if="articles.length === 0 && !loading" class="vn-empty">
        <v-icon icon="mdi-package-variant" size="28" color="#d1d5db" />
        <p>Aucun article vendable. Renseignez un prix de vente et un compte de produit sur vos articles.</p>
      </div>

      <div v-for="(ligne, i) in form.lignes" :key="i" class="vn-ligne">
        <v-autocomplete
          :model-value="ligne.articleId"
          :items="articlesOptions"
          label="Article"
          variant="outlined"
          density="comfortable"
          rounded="lg"
          hide-details="auto"
          class="vn-ligne__article"
          @update:model-value="(v: number | null) => onArticleSelect(i, v)"
        />
        <!-- Minerais : on ne saisit pas une quantité mais LE camion cédé,
             puisque chaque chargement se revend à son propre prix. -->
        <v-select
          v-if="estMinerais(ligne.articleId)"
          v-model="ligne.camionId"
          :items="camionsDisponibles(i).map(c => ({
            title: `${c.plaque} — ${new Date(c.dateAchat).toLocaleDateString('fr-FR')}`,
            value: c.id,
          }))"
          label="Camion"
          variant="outlined"
          density="comfortable"
          rounded="lg"
          hide-details="auto"
          no-data-text="Aucun camion en stock"
          class="vn-ligne__qte"
        />
        <v-text-field
          v-else
          v-model.number="ligne.quantite"
          label="Quantité"
          type="number"
          min="0.001"
          step="1"
          variant="outlined"
          density="comfortable"
          rounded="lg"
          hide-details="auto"
          class="vn-ligne__qte"
        />
        <v-text-field
          v-model.number="ligne.prixUnitaire"
          :label="form.devise === 'USD' ? 'P.U. HT (USD)' : 'P.U. HT (FC)'"
          type="number"
          min="0"
          :step="form.devise === 'USD' ? '0.01' : '1'"
          :prepend-inner-icon="form.devise === 'USD' ? 'mdi-currency-usd' : 'mdi-cash'"
          variant="outlined"
          density="comfortable"
          rounded="lg"
          hide-details="auto"
          class="vn-ligne__prix"
        />
        <div class="vn-ligne__total">
          <span class="vn-ligne__total-label">Total HT</span>
          <strong>{{ fmtMontant((ligne.quantite || 0) * (ligne.prixUnitaire || 0)) }}</strong>
          <v-chip
            v-if="ligne.articleId && !articleDe(ligne.articleId)?.soumisTva"
            size="x-small"
            color="grey"
            variant="tonal"
            class="mt-1"
          >
            Exonéré
          </v-chip>
        </div>
        <v-btn
          icon="mdi-delete-outline"
          variant="text"
          color="error"
          size="small"
          :disabled="form.lignes.length <= 1"
          @click="supprimerLigne(i)"
        />
      </div>

      <!-- ── Totaux ────────────────────────────────────────── -->
      <div class="vn-totaux">
        <div class="vn-total-row">
          <span>Total HT</span>
          <strong>{{ fmtMontant(totaux.ht) }}</strong>
        </div>
        <div class="vn-total-row">
          <span>TVA ({{ tauxTva }} %)</span>
          <strong>{{ fmtMontant(totaux.tva) }}</strong>
        </div>
        <div class="vn-total-row vn-total-row--ttc">
          <span>Total TTC</span>
          <strong>{{ fmtMontant(totaux.ttc) }}</strong>
        </div>
        <div v-if="contreValeur && totaux.ttc" class="vn-contre-valeur">{{ contreValeur }}</div>
      </div>
    </v-card>

    <v-alert
      v-if="raisonsBlocage.length"
      type="warning"
      variant="tonal"
      rounded="lg"
      icon="mdi-alert-circle-outline"
      class="mb-3"
    >
      <div class="vn-blocage-titre">Vente impossible pour l'instant</div>
      <ul class="vn-blocage-liste">
        <li v-for="(raison, i) in raisonsBlocage" :key="i">{{ raison }}</li>
      </ul>
    </v-alert>

    <div class="vn-actions">
      <v-btn variant="tonal" rounded="lg" :disabled="envoi" to="/ventes">Annuler</v-btn>
      <v-spacer />
      <v-btn
        variant="outlined"
        color="primary"
        rounded="lg"
        prepend-icon="mdi-content-save-outline"
        :loading="envoi"
        :disabled="!peutEnregistrer"
        @click="enregistrer(false)"
      >
        Enregistrer en brouillon
      </v-btn>
      <v-btn
        color="primary"
        variant="flat"
        rounded="lg"
        prepend-icon="mdi-check-circle-outline"
        :loading="envoi"
        :disabled="!peutEnregistrer"
        @click="enregistrer(true)"
      >
        Créer et valider
      </v-btn>
    </div>
  </div>
</template>

<style scoped>
.vn-page { max-width: 1100px; margin: 0 auto; padding-bottom: 48px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.vn-taux {
  font-size: 0.78rem;
  background: #ede9fe;
  color: #6d28d9;
  border: 1px solid #ddd6fe;
  border-radius: 6px;
  padding: 1px 7px;
  font-weight: 600;
}

.vn-section {
  font-size: 0.72rem;
  font-weight: 700;
  color: #9ca3af;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 0 0 12px;
}

.vn-ligne {
  display: grid;
  grid-template-columns: 3fr 1fr 1.2fr 1.3fr auto;
  gap: 12px;
  align-items: start;
  padding: 12px 0;
  border-top: 1px solid #f3f4f6;
}
@media (max-width: 860px) {
  .vn-ligne { grid-template-columns: 1fr 1fr; }
  .vn-ligne__article { grid-column: 1 / -1; }
}
.vn-ligne__total {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: flex-end;
  padding-top: 6px;
  font-variant-numeric: tabular-nums;
}
.vn-ligne__total-label { font-size: 0.7rem; color: #9ca3af; }

.vn-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px;
  color: #9ca3af;
  font-size: 0.85rem;
  text-align: center;
}
.vn-empty p { margin: 0; }

.vn-totaux {
  margin-top: 16px;
  padding: 16px 20px;
  background: #f9fafb;
  border: 1px solid #f0f0f0;
  border-radius: 14px;
  max-width: 380px;
  margin-left: auto;
}
.vn-total-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  font-size: 0.875rem;
  color: #6b7280;
  padding: 4px 0;
  font-variant-numeric: tabular-nums;
}
.vn-total-row strong { color: #111827; }
.vn-total-row--ttc {
  margin-top: 8px;
  padding-top: 12px;
  border-top: 1px solid #e5e7eb;
  font-size: 1rem;
}
.vn-total-row--ttc strong { font-size: 1.15rem; color: #16a34a; }
.vn-contre-valeur { text-align: right; font-size: 0.75rem; color: #9ca3af; padding-top: 4px; }
.vn-hint { font-size: 0.75rem; color: #6b7280; margin: 4px 0 0 16px; }

.vn-blocage-titre { font-weight: 700; margin-bottom: 4px; }
.vn-blocage-liste { margin: 0; padding-left: 18px; }
.vn-blocage-liste li { font-size: 0.85rem; }

.vn-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
</style>
