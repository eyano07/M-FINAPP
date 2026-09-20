<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT', niveau: 'ECRITURE' })

/**
 * Production : transforme des provisions en portions d'un plat.
 *
 * Les ingrédients sont pré-remplis depuis la fiche technique (× le nombre de
 * portions) mais restent ajustables — perte, substitution, portion plus
 * généreuse. C'est cette saisie, jamais la fiche, qui fait foi sur le stock.
 *
 * Le coût de revient n'est pas saisi : le serveur le déduit du CMP réel des
 * ingrédients sortis. C'est ce qui donne enfin au plat un CMP non nul, sans
 * quoi sa vente afficherait une marge de 100 %.
 */
interface LigneRecette {
  provisionId: number
  provisionCode: string
  provisionLibelle: string
  uniteMesure?: string
  quantite: number
  coutMoyenActuel: number
}
interface Recette {
  platId: number
  platCode: string
  platLibelle: string
  uniteMesure?: string
  lignes: LigneRecette[]
  coutEstimeParPortion: number
}
interface LigneSaisie {
  provisionId: number | null
  quantite: number | null
}
interface Provision { id: number; code: string; libelle: string; uniteMesure?: string }
interface Entrepot { id: number; code: string; nom: string; actif: boolean }
interface StockNiveau { articleId: number; entrepotId: number; quantite: number; coutMoyen: number }
interface LigneProduction {
  provisionCode: string
  provisionLibelle: string
  quantite: number
  coutUnitaire: number
  montant: number
}
interface Production {
  id: number
  reference: string
  dateProduction: string
  platCode: string
  platLibelle: string
  uniteMesure?: string
  entrepotNom: string
  quantite: number
  coutTotal: number
  coutUnitaire: number
  statut: 'VALIDEE' | 'ANNULEE'
  mouvementSortieRef?: string
  mouvementEntreeRef?: string
  createdByNom?: string
  lignes: LigneProduction[]
}

const api = useApi()
const auth = useAuthStore()
const parametres = useRestaurantParametresStore()

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT', 'ADMIN']))

const recettes = ref<Recette[]>([])
const provisions = ref<Provision[]>([])
const entrepots = ref<Entrepot[]>([])
const stock = ref<StockNiveau[]>([])
const productions = ref<Production[]>([])
const loading = ref(false)
const envoi = ref(false)
const erreur = ref('')
const succes = ref('')
const annulationId = ref<number | null>(null)
const detailOuvertId = ref<number | null>(null)

const form = reactive({
  platId: null as number | null,
  quantite: 1 as number | null,
  entrepotId: null as number | null,
  dateProduction: new Date().toISOString().slice(0, 10),
  lignes: [] as LigneSaisie[],
})

const platsOptions = computed(() =>
  recettes.value.map(r => ({ title: `${r.platCode} — ${r.platLibelle}`, value: r.platId })))
const provisionsOptions = computed(() =>
  provisions.value.map(p => ({
    title: `${p.code} — ${p.libelle}${p.uniteMesure ? ` (${p.uniteMesure})` : ''}`,
    value: p.id,
  })))
const entrepotsOptions = computed(() =>
  entrepots.value.map(e => ({ title: `${e.code} — ${e.nom}`, value: e.id })))

const recetteDuPlat = computed(() => recettes.value.find(r => r.platId === form.platId) || null)
const platSansFiche = computed(() => !!recetteDuPlat.value && !recetteDuPlat.value.lignes.length)

const provisionDe = (id: number | null) => provisions.value.find(p => p.id === id) || null
const stockDisponible = (provisionId: number | null, entrepotId: number | null) =>
  stock.value.find(s => s.articleId === provisionId && s.entrepotId === entrepotId)?.quantite ?? 0
const coutMoyenDe = (provisionId: number | null, entrepotId: number | null) =>
  stock.value.find(s => s.articleId === provisionId && s.entrepotId === entrepotId)?.coutMoyen ?? 0

/** Coût prévisionnel au CMP courant — le coût réel est figé côté serveur. */
const coutPrevisionnel = computed(() =>
  form.lignes.reduce((s, l) =>
    s + (l.quantite || 0) * coutMoyenDe(l.provisionId, form.entrepotId), 0))
const coutPrevisionnelParPortion = computed(() =>
  form.quantite && form.quantite > 0 ? coutPrevisionnel.value / form.quantite : 0)

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [recs, provs, ents, stk, prods] = await Promise.all([
      api<Recette[]>('/restaurant/recettes'),
      api<Provision[]>('/restaurant/provisions'),
      api<Entrepot[]>('/restaurant/entrepots').catch(() => []),
      api<StockNiveau[]>('/restaurant/provisions/stock').catch(() => []),
      api<Production[]>('/restaurant/productions').catch(() => []),
      parametres.charger(),
    ])
    recettes.value = recs
    provisions.value = provs
    entrepots.value = ents.filter(e => e.actif)
    stock.value = stk
    productions.value = prods
    // Pré-sélection quand un seul entrepôt existe : évite une saisie
    // obligatoire pour un choix qui n'en est pas un (voir receptions.vue).
    if (entrepots.value.length === 1 && !form.entrepotId) form.entrepotId = entrepots.value[0].id
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les données de production.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

/** Le choix d'un plat (ou de la quantité) re-remplit les lignes depuis sa fiche. */
function appliquerFiche() {
  const r = recetteDuPlat.value
  const portions = form.quantite || 0
  form.lignes = r
    ? r.lignes.map(l => ({ provisionId: l.provisionId, quantite: arrondi(l.quantite * portions) }))
    : []
}
watch(() => form.platId, appliquerFiche)
watch(() => form.quantite, appliquerFiche)

const arrondi = (n: number) => Math.round(n * 1000) / 1000

function ajouterLigne() {
  form.lignes.push({ provisionId: null, quantite: null })
}
function supprimerLigne(i: number) {
  form.lignes.splice(i, 1)
}

const raisonsBlocage = computed(() => {
  const raisons: string[] = []
  if (!recettes.value.length) raisons.push("Aucun plat sur la carte : créez-en un d'abord.")
  if (!form.platId) raisons.push('Choisissez le plat à produire.')
  if (!form.quantite || form.quantite <= 0) raisons.push('Indiquez un nombre de portions strictement positif.')
  if (!form.entrepotId) raisons.push("Choisissez l'entrepôt.")
  if (!form.lignes.length) {
    raisons.push('Ajoutez au moins un ingrédient consommé : sans cela, la production n’aurait aucun coût.')
  }
  if (form.lignes.some(l => !l.provisionId)) raisons.push('Une ligne est sans provision.')
  if (form.lignes.some(l => !l.quantite || l.quantite <= 0)) {
    raisons.push('Chaque ingrédient doit porter une quantité strictement positive.')
  }
  const vues = new Set<number>()
  for (const l of form.lignes) {
    if (l.provisionId != null && vues.has(l.provisionId)) {
      raisons.push('La même provision figure deux fois : regroupez les quantités.')
      break
    }
    if (l.provisionId != null) vues.add(l.provisionId)
  }
  for (const l of form.lignes) {
    if (!l.provisionId || !l.quantite) continue
    const dispo = stockDisponible(l.provisionId, form.entrepotId)
    if (l.quantite > dispo) {
      raisons.push(
        `Stock insuffisant pour « ${provisionDe(l.provisionId)?.libelle} » : `
        + `${fmtQte(dispo)} disponible(s), ${fmtQte(l.quantite)} demandé(s).`)
    }
  }
  return raisons
})
const peutProduire = computed(() => raisonsBlocage.value.length === 0)

async function produire() {
  if (!peutProduire.value) {
    erreur.value = raisonsBlocage.value[0]
    return
  }
  envoi.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const res = await api<Production>('/restaurant/productions', {
      method: 'POST',
      body: {
        platId: form.platId,
        quantite: form.quantite,
        entrepotId: form.entrepotId,
        dateProduction: form.dateProduction,
        lignes: form.lignes.map(l => ({ provisionId: l.provisionId, quantite: l.quantite })),
      },
    })
    succes.value = `Production ${res.reference} enregistrée — coût de revient `
      + `${parametres.fmtMontant(res.coutUnitaire)} par portion.`
    appliquerFiche()
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de la production.")
  } finally {
    envoi.value = false
  }
}

async function annuler(p: Production) {
  if (!confirm(
    `Annuler la production ${p.reference} ? Les ingrédients reviendront en stock et les `
    + `${fmtQte(p.quantite)} portion(s) en sortiront.`)) return
  annulationId.value = p.id
  erreur.value = ''
  succes.value = ''
  try {
    await api(`/restaurant/productions/${p.id}/annuler`, { method: 'POST' })
    succes.value = `Production ${p.reference} annulée.`
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'annulation.")
  } finally {
    annulationId.value = null
  }
}

function toggleDetail(id: number) {
  detailOuvertId.value = detailOuvertId.value === id ? null : id
}

function fmtQte(n?: number | null) {
  return n == null ? '—' : new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 3 }).format(n)
}
const fmtDate = (d: string) => new Date(d).toLocaleDateString('fr-FR', { dateStyle: 'medium' })
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Production</h1>
        <p class="page-sub">Transformation des matières premières en portions — coût de revient calculé automatiquement</p>
      </div>
      <v-btn
        variant="tonal" color="primary" rounded="lg"
        prepend-icon="mdi-clipboard-text-outline" to="/restaurant/recettes"
      >
        Fiches techniques
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" class="mb-4" closable @click:close="succes = ''">
      {{ succes }}
    </v-alert>

    <v-card v-if="loading" class="pa-8 d-flex justify-center classroom-card">
      <v-progress-circular indeterminate color="primary" />
    </v-card>

    <template v-else>
      <!-- ── Saisie d'une production ──────────────────────── -->
      <v-card v-if="canWrite" class="classroom-card pa-5 mb-4">
        <p class="prod-section">Nouvelle production</p>

        <v-row dense>
          <v-col cols="12" md="5">
            <v-autocomplete
              v-model="form.platId"
              :items="platsOptions"
              label="Plat à produire *"
              prepend-inner-icon="mdi-silverware-fork-knife"
              variant="outlined" density="comfortable" rounded="lg"
              no-data-text="Aucun plat sur la carte"
            />
          </v-col>
          <v-col cols="12" sm="6" md="2">
            <v-text-field
              v-model.number="form.quantite"
              type="number" min="0" step="1"
              label="Portions *"
              variant="outlined" density="comfortable" rounded="lg"
            />
          </v-col>
          <v-col cols="12" sm="6" md="3">
            <v-select
              v-model="form.entrepotId"
              :items="entrepotsOptions"
              label="Entrepôt *"
              prepend-inner-icon="mdi-warehouse"
              variant="outlined" density="comfortable" rounded="lg"
            />
          </v-col>
          <v-col cols="12" md="2">
            <v-text-field
              v-model="form.dateProduction"
              type="date" label="Date"
              variant="outlined" density="comfortable" rounded="lg"
            />
          </v-col>
        </v-row>

        <v-alert
          v-if="platSansFiche"
          type="info" variant="tonal" density="compact" rounded="lg" class="mb-3"
        >
          Ce plat n'a pas encore de fiche technique : saisissez manuellement les ingrédients consommés,
          ou définissez sa fiche pour que la prochaine production se pré-remplisse toute seule.
        </v-alert>

        <div class="d-flex justify-space-between align-center mb-2 mt-2">
          <p class="prod-section ma-0">Ingrédients consommés</p>
          <v-btn size="small" variant="tonal" rounded="lg" prepend-icon="mdi-plus" @click="ajouterLigne">
            Ingrédient
          </v-btn>
        </div>
        <p class="text-caption text-medium-emphasis mb-3">
          Pré-remplis depuis la fiche technique. Ajustez-les si la réalité diffère — c'est cette
          saisie qui sort du stock.
        </p>

        <div v-for="(l, i) in form.lignes" :key="i" class="prod-ligne">
          <v-autocomplete
            v-model="l.provisionId"
            :items="provisionsOptions"
            label="Provision"
            variant="outlined" density="compact" hide-details
            class="prod-ligne__provision"
          />
          <v-text-field
            v-model.number="l.quantite"
            type="number" min="0" step="0.001" label="Quantité"
            variant="outlined" density="compact" hide-details
            class="prod-ligne__qte"
          />
          <span class="prod-ligne__dispo">
            dispo {{ fmtQte(stockDisponible(l.provisionId, form.entrepotId)) }}
          </span>
          <span class="prod-ligne__cout">
            {{ parametres.fmtMontant((l.quantite || 0) * coutMoyenDe(l.provisionId, form.entrepotId)) }}
          </span>
          <v-btn icon="mdi-close" size="x-small" variant="text" title="Retirer" @click="supprimerLigne(i)" />
        </div>
        <p v-if="!form.lignes.length" class="text-medium-emphasis text-body-2 py-4 text-center ma-0">
          Choisissez un plat pour charger sa fiche, ou ajoutez les ingrédients à la main.
        </p>

        <v-divider class="my-4" />

        <div class="prod-total">
          <span>Coût prévisionnel</span>
          <strong>{{ parametres.fmtMontant(coutPrevisionnel) }}</strong>
        </div>
        <div v-if="coutPrevisionnelParPortion > 0" class="prod-total prod-total--secondaire">
          <span>soit par portion</span>
          <span>{{ parametres.fmtMontant(coutPrevisionnelParPortion) }}</span>
        </div>
        <p class="text-caption text-medium-emphasis mt-1 mb-3">
          Estimation au coût moyen actuel. Le coût définitif est figé à l'enregistrement, d'après le
          coût réel des ingrédients sortis.
        </p>

        <v-alert
          v-if="raisonsBlocage.length"
          type="warning" variant="tonal" density="compact" rounded="lg" class="mb-3"
        >
          <p class="font-weight-medium mb-1 text-body-2">Production impossible pour l'instant</p>
          <p v-for="(r, i) in raisonsBlocage" :key="i" class="ma-0 text-caption">{{ r }}</p>
        </v-alert>

        <v-btn
          color="primary" variant="flat" rounded="lg" block size="large"
          prepend-icon="mdi-chef-hat"
          :loading="envoi" :disabled="!peutProduire"
          @click="produire"
        >
          Produire
        </v-btn>
      </v-card>

      <!-- ── Historique ───────────────────────────────────── -->
      <v-card class="classroom-card">
        <v-data-table
          :headers="[
            { title: 'Référence', key: 'reference' },
            { title: 'Date', key: 'dateProduction' },
            { title: 'Plat', key: 'platLibelle' },
            { title: 'Portions', key: 'quantite', align: 'end' },
            { title: 'Coût total', key: 'coutTotal', align: 'end' },
            { title: 'Coût / portion', key: 'coutUnitaire', align: 'end' },
            { title: 'Statut', key: 'statut' },
            { title: '', key: 'actions', sortable: false, align: 'end' },
          ]"
          :items="productions"
          items-per-page="15"
        >
          <template #item.reference="{ item }">
            <button type="button" class="prod-ref" @click="toggleDetail(item.id)">
              <v-icon :icon="detailOuvertId === item.id ? 'mdi-chevron-down' : 'mdi-chevron-right'" size="16" />
              {{ item.reference }}
            </button>
          </template>
          <template #item.dateProduction="{ item }">{{ fmtDate(item.dateProduction) }}</template>
          <template #item.platLibelle="{ item }">
            <span class="font-weight-medium">{{ item.platLibelle }}</span>
            <span class="text-medium-emphasis text-caption d-block">{{ item.platCode }}</span>
          </template>
          <template #item.quantite="{ item }">{{ fmtQte(item.quantite) }}</template>
          <template #item.coutTotal="{ item }">{{ parametres.fmtMontant(item.coutTotal) }}</template>
          <template #item.coutUnitaire="{ item }">{{ parametres.fmtMontant(item.coutUnitaire) }}</template>
          <template #item.statut="{ item }">
            <v-chip :color="item.statut === 'VALIDEE' ? 'success' : 'error'" size="small" variant="tonal">
              {{ item.statut === 'VALIDEE' ? 'Produite' : 'Annulée' }}
            </v-chip>
          </template>
          <template #item.actions="{ item }">
            <v-btn
              v-if="canWrite && item.statut === 'VALIDEE'"
              size="small" variant="text" color="error" icon="mdi-undo-variant"
              title="Annuler la production"
              :loading="annulationId === item.id"
              @click="annuler(item)"
            />
          </template>
          <template #expanded-row />
          <template #no-data>
            <div class="pa-6 text-center text-medium-emphasis">
              Aucune production sur la période. Produisez un plat pour lui donner un coût de revient.
            </div>
          </template>
        </v-data-table>

        <!-- Détail hors tableau : Vuetify n'expose pas simplement une ligne
             dépliée sans `show-expand`, et le détail reste court. -->
        <div v-if="detailOuvertId" class="prod-detail">
          <template v-for="p in productions" :key="p.id">
            <template v-if="p.id === detailOuvertId">
              <p class="prod-detail__titre">
                Ingrédients consommés — {{ p.reference }}
                <span v-if="p.createdByNom" class="text-medium-emphasis"> · {{ p.createdByNom }}</span>
              </p>
              <div v-for="(l, li) in p.lignes" :key="li" class="prod-detail__ligne">
                <span>{{ l.provisionLibelle }} × {{ fmtQte(l.quantite) }}</span>
                <span>{{ parametres.fmtMontant(l.montant) }}</span>
              </div>
              <p class="prod-detail__pieces">
                Mouvements de stock : {{ p.mouvementSortieRef || '—' }} (sortie) ·
                {{ p.mouvementEntreeRef || '—' }} (entrée)
              </p>
            </template>
          </template>
        </div>
      </v-card>
    </template>
  </div>
</template>

<style scoped>
.prod-section {
  font-size: 0.78rem;
  font-weight: 700;
  letter-spacing: 0.4px;
  text-transform: uppercase;
  color: #9ca3af;
  margin: 0 0 12px;
}

.prod-ligne {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 0;
  flex-wrap: wrap;
}
.prod-ligne__provision { flex: 1 1 220px; min-width: 0; }
.prod-ligne__qte { flex: 0 0 120px; }
.prod-ligne__dispo { flex: 0 0 auto; font-size: 0.75rem; color: #9ca3af; white-space: nowrap; }
.prod-ligne__cout {
  flex: 0 0 100px;
  text-align: right;
  font-size: 0.8125rem;
  color: #6b7280;
  white-space: nowrap;
}

.prod-total {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 12px;
  font-size: 0.9375rem;
  padding: 2px 0;
}
.prod-total strong { font-size: 1.15rem; color: var(--color-primary); }
.prod-total--secondaire { font-size: 0.8125rem; color: #6b7280; }

.prod-ref {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: none;
  border: none;
  padding: 0;
  font: inherit;
  font-weight: 600;
  color: inherit;
  cursor: pointer;
}

.prod-detail { border-top: 1px solid #f0f0f0; padding: 14px 20px 18px; background: #fafafa; }
.prod-detail__titre { font-size: 0.8125rem; font-weight: 700; margin: 0 0 8px; }
.prod-detail__ligne {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 0.8125rem;
  color: #4b5563;
  padding: 2px 0 2px 10px;
}
.prod-detail__pieces { font-size: 0.75rem; color: #9ca3af; margin: 10px 0 0; }
</style>
