<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Fiches techniques : la composition d'un plat en provisions, pour UNE portion.
 *
 * La fiche sert de modèle à l'écran de production, qui reste libre de s'en
 * écarter (perte, substitution). Le coût affiché ici est donc une estimation au
 * CMP courant — le coût de revient comptable, lui, naît à la production.
 */
interface LigneRecette {
  id: number | null
  provisionId: number | null
  provisionCode?: string
  provisionLibelle?: string
  uniteMesure?: string
  quantite: number | null
  coutMoyenActuel?: number
  coutLigne?: number
}
interface Recette {
  platId: number
  platCode: string
  platLibelle: string
  uniteMesure?: string
  prixVente?: number | null
  lignes: LigneRecette[]
  coutEstimeParPortion: number
}
interface Provision { id: number; code: string; libelle: string; uniteMesure?: string }

const api = useApi()
const auth = useAuthStore()
const parametres = useRestaurantParametresStore()

// Composer un plat en ingrédients est le travail quotidien du chef
// cuisinier (responsable restaurant) — l'administrateur, lui, se limite à
// créer la fiche article sur la carte (RestaurantService.ECRITURE).
const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT', 'ADMIN']))

const recettes = ref<Recette[]>([])
const provisions = ref<Provision[]>([])
const tauxChange = ref(0)
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')

const platSelectionneId = ref<number | null>(null)
const lignesEnCours = ref<LigneRecette[]>([])
const modifie = ref(false)

const recetteSelectionnee = computed(() =>
  recettes.value.find(r => r.platId === platSelectionneId.value) || null)

/**
 * Recherche dans la liste des plats : code ou libellé du plat, mais aussi code ou libellé de l'un de
 * ses ingrédients (« oeuf » retrouve tous les plats qui en contiennent), sans accents ni casse.
 * La fiche affichée à droite ne change pas tant qu'on n'en choisit pas une autre.
 */
const recherche = ref('')
const recettesFiltrees = computed(() =>
  recettes.value.filter(r => correspondRecherche(
    [r.platCode, r.platLibelle, ...r.lignes.flatMap(l => [l.provisionCode, l.provisionLibelle])],
    recherche.value)))

const provisionsOptions = computed(() =>
  provisions.value.map(p => ({
    title: `${p.code} — ${p.libelle}${p.uniteMesure ? ` (${p.uniteMesure})` : ''}`,
    value: p.id,
  })))

/** CMP courant par provision, pour chiffrer une ligne dès sa saisie. */
const coutParProvision = computed(() => {
  const m: Record<number, number> = {}
  for (const r of recettes.value) {
    for (const l of r.lignes) {
      if (l.provisionId != null && l.coutMoyenActuel != null) m[l.provisionId] = l.coutMoyenActuel
    }
  }
  return m
})

const coutEstime = computed(() =>
  lignesEnCours.value.reduce((s, l) => s + coutLigne(l), 0))

function coutLigne(l: LigneRecette): number {
  if (l.provisionId == null || !l.quantite) return 0
  return (coutParProvision.value[l.provisionId] ?? 0) * l.quantite
}

/** Marge théorique : prix de vente (FC) vs coût estimé (USD, devise du grand livre). */
const margeEstimee = computed(() => {
  const r = recetteSelectionnee.value
  if (!r?.prixVente || tauxChange.value <= 0 || coutEstime.value <= 0) return null
  const prixUSD = r.prixVente / tauxChange.value
  return ((prixUSD - coutEstime.value) / prixUSD) * 100
})

// ── Devise d'affichage de cette page : bouton poussoir local, par défaut
// FC — indépendant de la préférence globale du module (parametresStore),
// pour ne pas la modifier au passage sur un simple écran de consultation.
const deviseAffichage = ref<'CDF' | 'USD'>('CDF')

/** Formate un montant tenu en USD (coût moyen des provisions, coût de revient). */
// La devise est celle choisie sur cet écran ; l'arrondi, celui du store
// (parametres.fmtDans : francs sans décimale, dollars à 2 décimales).
function fmtUSD(montantUSD: number): string {
  if (deviseAffichage.value === 'CDF') {
    if (tauxChange.value <= 0) return '—'
    return parametres.fmtDans(montantUSD * tauxChange.value, 'CDF')
  }
  return parametres.fmtDans(montantUSD, 'USD')
}
/** Formate un montant tenu en FC (Article.prixVente). */
function fmtFC(montantFC?: number | null): string {
  if (montantFC == null) return '—'
  if (deviseAffichage.value === 'CDF') {
    return parametres.fmtDans(montantFC, 'CDF')
  }
  if (tauxChange.value <= 0) return '—'
  return parametres.fmtDans(montantFC / tauxChange.value, 'USD')
}

// ── Prix de vente, modifiable directement depuis la fiche ────────────────
const editionPrix = ref(false)
const prixEdite = ref<number | null>(null)
const enregistrementPrix = ref(false)

function activerEditionPrix() {
  prixEdite.value = recetteSelectionnee.value?.prixVente ?? null
  editionPrix.value = true
}
function annulerEditionPrix() {
  editionPrix.value = false
}
async function enregistrerPrix() {
  if (!platSelectionneId.value) return
  erreur.value = ''
  enregistrementPrix.value = true
  try {
    const maj = await api<Recette>(`/restaurant/recettes/${platSelectionneId.value}/prix-vente`, {
      method: 'PUT',
      body: { prixVente: prixEdite.value },
    })
    const i = recettes.value.findIndex(r => r.platId === maj.platId)
    if (i !== -1) recettes.value[i] = maj
    editionPrix.value = false
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la modification du prix de vente.')
  } finally {
    enregistrementPrix.value = false
  }
}

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [recs, provs, taux] = await Promise.all([
      api<Recette[]>('/restaurant/recettes'),
      api<Provision[]>('/restaurant/provisions'),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
      parametres.charger(),
    ])
    recettes.value = recs
    provisions.value = provs
    tauxChange.value = taux.taux || 0
    if (platSelectionneId.value === null || !recs.some(r => r.platId === platSelectionneId.value)) {
      platSelectionneId.value = recs.length ? recs[0].platId : null
    }
    rechargerLignes()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les fiches techniques.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function rechargerLignes() {
  lignesEnCours.value = recetteSelectionnee.value
    ? recetteSelectionnee.value.lignes.map(l => ({ ...l }))
    : []
  modifie.value = false
}

function choisirPlat(platId: number) {
  if (platId === platSelectionneId.value) return
  if (modifie.value && !confirm('Les modifications non enregistrées de cette fiche seront perdues. Continuer ?')) {
    return
  }
  platSelectionneId.value = platId
  succes.value = ''
  rechargerLignes()
}

function ajouterLigne() {
  lignesEnCours.value.push({ id: null, provisionId: null, quantite: null })
  modifie.value = true
}
function supprimerLigne(i: number) {
  lignesEnCours.value.splice(i, 1)
  modifie.value = true
}

/**
 * Raisons de ne pas pouvoir enregistrer, explicitées plutôt qu'un bouton grisé
 * muet (même principe que ventes/nouvelle.vue).
 */
const raisonsBlocage = computed(() => {
  const raisons: string[] = []
  if (!provisions.value.length) {
    raisons.push("Aucune provision n'est définie : créez-en d'abord dans « Provisions ».")
  }
  const vues = new Set<number>()
  for (const l of lignesEnCours.value) {
    if (l.provisionId == null) { raisons.push('Une ligne est sans provision.'); break }
  }
  for (const l of lignesEnCours.value) {
    if (l.provisionId != null && vues.has(l.provisionId)) {
      raisons.push('La même provision figure deux fois : regroupez les quantités sur une seule ligne.')
      break
    }
    if (l.provisionId != null) vues.add(l.provisionId)
  }
  if (lignesEnCours.value.some(l => !l.quantite || l.quantite <= 0)) {
    raisons.push('Chaque ligne doit porter une quantité strictement positive.')
  }
  return raisons
})
const peutEnregistrer = computed(() => raisonsBlocage.value.length === 0)

async function enregistrer() {
  if (!platSelectionneId.value) return
  if (!peutEnregistrer.value) {
    erreur.value = raisonsBlocage.value[0]
    return
  }
  saving.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const body = {
      lignes: lignesEnCours.value.map(l => ({
        provisionId: l.provisionId,
        quantite: l.quantite,
      })),
    }
    await api(`/restaurant/recettes/${platSelectionneId.value}`, { method: 'PUT', body })
    succes.value = 'Fiche technique enregistrée.'
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement de la fiche.")
  } finally {
    saving.value = false
  }
}

const fmtQte = (n?: number | null) =>
  n == null ? '—' : new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 3 }).format(n)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Fiches techniques</h1>
        <p class="page-sub">Composition des plats en matières premières — base du coût de revient</p>
      </div>
      <v-btn
        variant="tonal" color="primary" rounded="lg"
        prepend-icon="mdi-chef-hat" to="/restaurant/production"
      >
        Production
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

    <v-card v-else-if="!recettes.length" class="pa-8 text-center text-medium-emphasis classroom-card">
      Aucun plat sur la carte. Créez-en un dans « Carte » pour lui définir une fiche technique.
    </v-card>

    <div v-else class="fiche-layout">
      <!-- ── Liste des plats ──────────────────────────────── -->
      <v-card class="classroom-card fiche-plats">
        <div class="fiche-plats__recherche">
          <v-text-field
            v-model="recherche"
            prepend-inner-icon="mdi-magnify"
            placeholder="Plat ou ingrédient…"
            aria-label="Rechercher une fiche technique par plat ou par ingrédient"
            clearable hide-details
            variant="outlined" density="compact" rounded="lg"
          />
        </div>
        <v-list density="compact" nav class="fiche-plats__liste">
          <v-list-item
            v-for="r in recettesFiltrees" :key="r.platId"
            :active="r.platId === platSelectionneId"
            color="primary"
            @click="choisirPlat(r.platId)"
          >
            <v-list-item-title class="text-body-2 font-weight-medium">{{ r.platLibelle }}</v-list-item-title>
            <v-list-item-subtitle class="text-caption">
              {{ r.platCode }} ·
              <span v-if="r.lignes.length">{{ r.lignes.length }} ingrédient{{ r.lignes.length > 1 ? 's' : '' }}</span>
              <span v-else class="fiche-sans">sans fiche</span>
            </v-list-item-subtitle>
          </v-list-item>
          <p v-if="!recettesFiltrees.length" class="text-caption text-medium-emphasis text-center pa-4 mb-0">
            Aucune fiche ne correspond à votre recherche.
          </p>
        </v-list>
      </v-card>

      <!-- ── Fiche du plat sélectionné ────────────────────── -->
      <v-card v-if="recetteSelectionnee" class="classroom-card pa-5 fiche-detail">
        <div class="d-flex justify-space-between align-start flex-wrap ga-2 mb-4">
          <div>
            <p class="text-h6 font-weight-bold mb-0">{{ recetteSelectionnee.platLibelle }}</p>
            <p class="text-caption text-medium-emphasis mb-0">
              Quantités pour {{ recetteSelectionnee.uniteMesure ? `une ${recetteSelectionnee.uniteMesure}` : 'une portion' }}
            </p>
          </div>
          <div v-if="canWrite" class="d-flex ga-2">
            <v-btn size="small" variant="tonal" rounded="lg" prepend-icon="mdi-plus" @click="ajouterLigne">
              Ingrédient
            </v-btn>
            <v-btn
              size="small" color="primary" variant="flat" rounded="lg"
              prepend-icon="mdi-content-save-outline"
              :loading="saving" :disabled="!modifie"
              @click="enregistrer"
            >
              Enregistrer
            </v-btn>
          </div>
        </div>

        <v-alert
          v-if="modifie && raisonsBlocage.length"
          type="warning" variant="tonal" density="compact" rounded="lg" class="mb-4"
        >
          <p v-for="(r, i) in raisonsBlocage" :key="i" class="ma-0 text-caption">{{ r }}</p>
        </v-alert>

        <p v-if="!lignesEnCours.length" class="text-medium-emphasis text-body-2 py-6 text-center">
          Aucun ingrédient.
          {{ canWrite ? 'Cliquez sur « Ingrédient » pour composer la fiche.' : '' }}
        </p>

        <div v-for="(l, i) in lignesEnCours" :key="i" class="fiche-ligne">
          <v-autocomplete
            v-model="l.provisionId"
            :items="provisionsOptions"
            label="Provision"
            variant="outlined" density="compact" hide-details
            :readonly="!canWrite"
            class="fiche-ligne__provision"
            @update:model-value="modifie = true"
          />
          <v-text-field
            v-model.number="l.quantite"
            type="number" label="Quantité" min="0" step="0.001"
            variant="outlined" density="compact" hide-details
            :readonly="!canWrite"
            class="fiche-ligne__qte"
            @update:model-value="modifie = true"
          />
          <span class="fiche-ligne__cout">{{ fmtUSD(coutLigne(l)) }}</span>
          <v-btn
            v-if="canWrite"
            icon="mdi-close" size="x-small" variant="text" title="Retirer"
            @click="supprimerLigne(i)"
          />
        </div>

        <v-divider class="my-4" />

        <div class="d-flex justify-end mb-2">
          <v-btn-toggle v-model="deviseAffichage" mandatory density="compact" variant="outlined" rounded="lg">
            <v-btn value="CDF" size="x-small">FC</v-btn>
            <v-btn value="USD" size="x-small">$US</v-btn>
          </v-btn-toggle>
        </div>

        <div class="fiche-total">
          <span>Coût de revient estimé par portion</span>
          <strong>{{ fmtUSD(coutEstime) }}</strong>
        </div>
        <div class="fiche-total fiche-total--secondaire">
          <span>Prix de vente</span>
          <span v-if="!editionPrix" class="d-flex align-center ga-1">
            {{ fmtFC(recetteSelectionnee.prixVente) }}
            <v-btn
              v-if="canWrite"
              icon="mdi-pencil-outline" size="x-small" variant="text" title="Modifier le prix de vente"
              @click="activerEditionPrix"
            />
          </span>
          <span v-else class="d-flex align-center ga-1">
            <v-text-field
              v-model.number="prixEdite"
              type="number" min="0" density="compact" variant="outlined" hide-details
              style="max-width: 130px" suffix="FC"
              @keyup.enter="enregistrerPrix"
              @keyup.esc="annulerEditionPrix"
            />
            <v-btn icon="mdi-check" size="x-small" variant="tonal" color="primary"
              :loading="enregistrementPrix" title="Enregistrer" @click="enregistrerPrix" />
            <v-btn icon="mdi-close" size="x-small" variant="text"
              :disabled="enregistrementPrix" title="Annuler" @click="annulerEditionPrix" />
          </span>
        </div>
        <div v-if="margeEstimee != null" class="fiche-total fiche-total--secondaire">
          <span>Marge théorique</span>
          <span :class="margeEstimee < 0 ? 'text-error' : 'text-success'">
            {{ margeEstimee.toFixed(1) }} %
          </span>
        </div>
        <p class="text-caption text-medium-emphasis mt-3 mb-0">
          Estimation au coût moyen actuel des provisions. Le coût de revient réel est figé à chaque
          production, d'après les ingrédients effectivement consommés.
        </p>
      </v-card>
    </div>
  </div>
</template>

<style scoped>
.fiche-layout { display: flex; gap: 16px; align-items: flex-start; flex-wrap: wrap; }
.fiche-plats { flex: 0 0 280px; max-height: 620px; display: flex; flex-direction: column; overflow: hidden; }
.fiche-plats__recherche { flex: 0 0 auto; padding: 10px 10px 4px; }
.fiche-plats__liste { flex: 1 1 auto; min-height: 0; overflow-y: auto; }
.fiche-detail { flex: 1 1 420px; min-width: 0; }
.fiche-sans { color: #b45309; font-weight: 600; }

.fiche-ligne {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
}
.fiche-ligne__provision { flex: 1 1 240px; min-width: 0; }
.fiche-ligne__qte { flex: 0 0 120px; }
.fiche-ligne__cout {
  flex: 0 0 100px;
  text-align: right;
  font-size: 0.8125rem;
  color: #6b7280;
  white-space: nowrap;
}

.fiche-total {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 12px;
  font-size: 0.9375rem;
  padding: 3px 0;
}
.fiche-total strong { font-size: 1.15rem; color: var(--color-primary); }
.fiche-total--secondaire { font-size: 0.8125rem; color: #6b7280; }
</style>
