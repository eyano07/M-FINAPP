<script lang="ts">
export interface LigneNoteFraisForm {
  montant: number | null
  compteImputation: string | null
  description: string
  achatMarchandise: boolean
  quantiteMarchandise: number | null
  articleId: number | null
  articleNom: string | null
  soumisTva: boolean
}
</script>

<script setup lang="ts">
const props = withDefaults(defineProps<{
  modelValue: LigneNoteFraisForm[]
  devise?: string
  sens?: string
}>(), {
  devise: 'CDF',
  sens: 'DECAISSEMENT',
})

const estEncaissement = computed(() => props.sens === 'ENCAISSEMENT')
const titreSection = computed(() => estEncaissement.value ? 'Recettes de la note' : 'Dépenses de la note')
const libelleAjout = computed(() => estEncaissement.value ? 'Ajouter une recette' : 'Ajouter une dépense')

const emit = defineEmits<{ (e: 'update:modelValue', v: LigneNoteFraisForm[]): void }>()
const api = useApi()

// ── Article (achat de marchandise) ──────────────────────────────────────
// Chargé paresseusement : la plupart des notes n'achètent pas de
// marchandise, inutile d'alourdir chaque ouverture du formulaire.
// L'entrepôt et le compte de TVA récupérable ne sont plus choisis par
// l'utilisateur : l'entrepôt actif et le compte 4452 sont retenus
// automatiquement côté serveur (voir NoteFraisService.creerLignes).
interface ArticleOption { id: number; code: string; libelle: string; type: 'MARCHANDISE' | 'SERVICE' }
const articlesDispo = ref<ArticleOption[]>([])
const chargementRef = ref(false)
let refsChargees = false

async function chargerReferentielStock() {
  if (refsChargees) return
  chargementRef.value = true
  try {
    const arts = await api<ArticleOption[]>('/logistique/articles')
    articlesDispo.value = arts.filter(a => a.type === 'MARCHANDISE')
    refsChargees = true
  } catch {
    // L'utilisateur reste bloqué sur le toggle si le référentiel est indisponible ;
    // ce n'est pas pire que ne pas pouvoir créer la ligne du tout.
  } finally {
    chargementRef.value = false
  }
}

// Taux de TVA courant, pour prévisualiser la ventilation HT/TVA à la saisie
// (l'engagement comptable réel est calculé côté serveur, au taux en vigueur
// à la date effective du paiement — cet aperçu est purement indicatif).
const tauxTva = ref<number | null>(null)
let tauxTvaCharge = false
async function chargerTauxTva() {
  if (tauxTvaCharge) return
  tauxTvaCharge = true
  try {
    const res = await api<{ taux: number }>('/admin/taux-tva')
    tauxTva.value = res.taux || null
  } catch {
    tauxTva.value = null
  }
}

const articlesOptions = computed(() =>
  articlesDispo.value.map(a => `${a.libelle}`))

const lignes = computed({
  get: () => props.modelValue,
  set: (v: LigneNoteFraisForm[]) => emit('update:modelValue', v),
})

function ajouterLigne() {
  lignes.value = [...lignes.value, {
    montant: null, compteImputation: null, description: '',
    achatMarchandise: false, quantiteMarchandise: null,
    articleId: null, articleNom: null,
    soumisTva: false,
  }]
}

function supprimerLigne(index: number) {
  if (lignes.value.length <= 1) return
  lignes.value = lignes.value.filter((_, i) => i !== index)
  delete iaAppliquee.value[index]
}

function majChamp<K extends keyof LigneNoteFraisForm>(index: number, champ: K, val: LigneNoteFraisForm[K]) {
  const copy = [...lignes.value]
  copy[index] = { ...copy[index], [champ]: val }
  lignes.value = copy
}

function toggleAchatMarchandise(index: number, val: boolean) {
  majChamp(index, 'achatMarchandise', val)
  if (val) {
    chargerReferentielStock()
  } else {
    majChamp(index, 'quantiteMarchandise', null)
    majChamp(index, 'articleId', null)
    majChamp(index, 'articleNom', null)
  }
}

function toggleSoumisTva(index: number, val: boolean) {
  majChamp(index, 'soumisTva', val)
  if (val) chargerTauxTva()
}

/** Choix ou saisie libre dans le combobox article : bascule entre id (article
 *  existant) et nom (nouvel article, créé automatiquement à la soumission). */
function onArticleChange(index: number, val: string | null) {
  const existant = articlesDispo.value.find(a => a.libelle === val)
  majChamp(index, 'articleId', existant ? existant.id : null)
  majChamp(index, 'articleNom', existant ? null : val)
}

function libelleArticleAffiche(ligne: LigneNoteFraisForm) {
  if (ligne.articleId) {
    return articlesDispo.value.find(a => a.id === ligne.articleId)?.libelle ?? null
  }
  return ligne.articleNom
}

/**
 * Montant HT total de la ligne. Pour un achat de marchandise, le champ
 * "Montant" est le prix unitaire : le total facturé est ce prix multiplié
 * par la quantité réceptionnée — pas le seul prix unitaire.
 */
function montantHtTotal(ligne: LigneNoteFraisForm) {
  const montant = Number(ligne.montant) || 0
  if (ligne.achatMarchandise && ligne.quantiteMarchandise) {
    return montant * Number(ligne.quantiteMarchandise)
  }
  return montant
}

/** La TVA s'ajoute par-dessus le HT total (elle n'en est jamais extraite). */
function ventilationTva(montantHt: number | null) {
  if (!montantHt || !tauxTva.value) return null
  const tva = montantHt * (tauxTva.value / 100)
  return { tva, ttc: montantHt + tva }
}

// ── Suggestion IA du compte OHADA à partir du motif ────────────────────
// Le compte suggéré est appliqué directement dans le champ "Compte" ;
// un petit badge indique qu'il provient de l'IA et peut être annulé.
interface SuggestionCompte { compteNumero: string; compteLibelle: string }
const iaAppliquee = ref<Record<number, SuggestionCompte | null>>({})
const chargementSuggestion = ref<Record<number, boolean>>({})

async function suggererCompte(index: number) {
  const ligne = lignes.value[index]
  if (!ligne || ligne.compteImputation || !ligne.description || ligne.description.trim().length < 4) {
    return
  }
  chargementSuggestion.value[index] = true
  try {
    const res = await api<SuggestionCompte>('/ia/suggestion-compte', {
      method: 'POST',
      body: { description: ligne.description },
    })
    if (res?.compteNumero && !lignes.value[index]?.compteImputation) {
      majChamp(index, 'compteImputation', res.compteNumero)
      iaAppliquee.value[index] = res
    }
  } catch {
    // Suggestion indisponible : l'utilisateur reste libre de choisir le compte manuellement.
  } finally {
    chargementSuggestion.value[index] = false
  }
}

function onCompteChange(index: number, val: string | null) {
  majChamp(index, 'compteImputation', val)
  const appliquee = iaAppliquee.value[index]
  if (appliquee && val !== appliquee.compteNumero) {
    delete iaAppliquee.value[index]
  }
}

function annulerSuggestion(index: number) {
  const s = iaAppliquee.value[index]
  if (!s) return
  if (lignes.value[index]?.compteImputation === s.compteNumero) {
    majChamp(index, 'compteImputation', null)
  }
  delete iaAppliquee.value[index]
}

// TTC : ce qui sera réellement décaissé, pas la seule somme des HT saisis
// (et, pour un achat de marchandise, HT total = prix unitaire x quantité).
const total = computed(() =>
  lignes.value.reduce((s, l) => {
    const ht = montantHtTotal(l)
    const v = ventilationTva(ht)
    return s + (l.soumisTva && v ? v.ttc : ht)
  }, 0)
)

const symboleDevise: Record<string, string> = { CDF: 'FC', USD: '$' }
const deviseLabel = computed(() => symboleDevise[props.devise] || props.devise)

function fmt(v: number) {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(v)
}
</script>

<template>
  <div class="lignes-note">
    <div class="lignes-note__head">
      <span class="lignes-note__title">{{ titreSection }}</span>
      <v-btn size="small" variant="tonal" color="primary" prepend-icon="mdi-plus" @click="ajouterLigne">
        {{ libelleAjout }}
      </v-btn>
    </div>

    <div v-for="(ligne, index) in lignes" :key="index" class="lignes-note__row">
      <v-text-field
        :model-value="ligne.montant"
        :label="ligne.achatMarchandise ? 'Prix unitaire (HT)' : (ligne.soumisTva ? 'Montant (HT)' : 'Montant')"
        type="number"
        min="0"
        step="0.01"
        variant="outlined"
        density="comfortable"
        hide-details="auto"
        class="lignes-note__montant"
        @update:model-value="majChamp(index, 'montant', $event ? Number($event) : null)"
      />
      <div class="lignes-note__compte">
        <!-- Achat de marchandise : le compte de stock de l'article prime,
             le champ libre n'aurait aucun effet et ne fait que confondre. -->
        <div v-if="ligne.achatMarchandise" class="lignes-note__compte-auto">
          <v-icon icon="mdi-information-outline" size="14" class="mr-1" />
          Compte imputé automatiquement : le compte de stock de l'article
        </div>
        <template v-else>
          <ComptabiliteSelecteurCompte
            :model-value="ligne.compteImputation"
            label="Compte (optionnel)"
            @update:model-value="onCompteChange(index, $event)"
          />
          <div v-if="chargementSuggestion[index]" class="lignes-note__ia lignes-note__ia--loading">
            <v-progress-circular indeterminate size="14" width="2" color="primary" class="mr-1" />
            L'IA recherche le compte adapté...
          </div>
          <div v-else-if="iaAppliquee[index] && ligne.compteImputation === iaAppliquee[index]?.compteNumero" class="lignes-note__ia">
            <v-icon icon="mdi-creation" size="14" color="#7c3aed" class="mr-1" />
            <span>Compte suggéré par l'IA : {{ iaAppliquee[index]?.compteLibelle }}</span>
            <button type="button" class="lignes-note__ia-dismiss" title="Annuler la suggestion" @click="annulerSuggestion(index)">
              <v-icon icon="mdi-close" size="12" />
            </button>
          </div>
        </template>
      </div>
      <v-btn
        icon="mdi-delete-outline"
        variant="text"
        color="error"
        class="lignes-note__delete"
        :disabled="lignes.length <= 1"
        @click="supprimerLigne(index)"
      />
      <v-textarea
        :model-value="ligne.description"
        label="Motif / description"
        variant="outlined"
        density="comfortable"
        rows="2"
        auto-grow
        hide-details="auto"
        class="lignes-note__motif"
        @update:model-value="majChamp(index, 'description', $event)"
        @blur="suggererCompte(index)"
      />

      <div class="lignes-note__options">
        <div class="lignes-note__toggle-row">
          <v-switch
            :model-value="ligne.achatMarchandise"
            label="Achat de marchandise"
            color="primary"
            density="compact"
            hide-details
            @update:model-value="toggleAchatMarchandise(index, !!$event)"
          />
          <v-text-field
            v-if="ligne.achatMarchandise"
            :model-value="ligne.quantiteMarchandise"
            label="Quantité"
            type="number"
            min="0"
            step="0.001"
            variant="outlined"
            density="compact"
            hide-details="auto"
            class="lignes-note__quantite"
            @update:model-value="majChamp(index, 'quantiteMarchandise', $event ? Number($event) : null)"
          />
        </div>
        <div v-if="ligne.achatMarchandise" class="lignes-note__toggle-row">
          <v-combobox
            :model-value="libelleArticleAffiche(ligne)"
            :items="articlesOptions"
            :loading="chargementRef"
            label="Article *"
            hint="Choisissez un article existant ou tapez un nouveau nom : il sera créé automatiquement"
            persistent-hint
            variant="outlined"
            density="compact"
            hide-details="auto"
            no-data-text="Tapez le nom d'un nouvel article"
            class="lignes-note__article"
            @update:model-value="onArticleChange(index, $event)"
          />
        </div>
        <div v-if="ligne.achatMarchandise" class="lignes-note__compte-auto">
          <v-icon icon="mdi-information-outline" size="14" class="mr-1" />
          L'entrepôt de réception est retenu automatiquement
        </div>
        <div v-if="ligne.achatMarchandise && ligne.montant && ligne.quantiteMarchandise" class="lignes-note__compte-auto">
          <v-icon icon="mdi-calculator-variant-outline" size="14" class="mr-1" />
          {{ fmt(ligne.montant) }} {{ deviseLabel }} × {{ ligne.quantiteMarchandise }} = HT {{ fmt(montantHtTotal(ligne)) }} {{ deviseLabel }}
        </div>
        <div class="lignes-note__toggle-row">
          <v-switch
            :model-value="ligne.soumisTva"
            label="Soumis à la TVA"
            color="primary"
            density="compact"
            hide-details
            @update:model-value="toggleSoumisTva(index, !!$event)"
          />
          <div v-if="ligne.soumisTva" class="lignes-note__tva-apercu">
            <template v-if="ventilationTva(montantHtTotal(ligne))">
              HT {{ fmt(montantHtTotal(ligne)) }} {{ deviseLabel }}
              + TVA ({{ tauxTva }}%) {{ fmt(ventilationTva(montantHtTotal(ligne))!.tva) }} {{ deviseLabel }}
              = TTC {{ fmt(ventilationTva(montantHtTotal(ligne))!.ttc) }} {{ deviseLabel }}
              — imputée automatiquement au compte 4452
            </template>
            <template v-else>
              TVA imputée automatiquement au compte 4452
            </template>
          </div>
        </div>
      </div>
    </div>

    <div class="lignes-note__total">
      <span class="label">Montant total</span>
      <strong>{{ fmt(total) }} {{ deviseLabel }}</strong>
    </div>
  </div>
</template>

<style scoped>
.lignes-note {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.lignes-note__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.lignes-note__title {
  font-weight: 600;
  color: #111827;
  font-size: 0.9rem;
}
.lignes-note__row {
  display: grid;
  grid-template-columns: 0.9fr 2.4fr auto;
  grid-template-areas:
    "montant compte  delete"
    "motif   motif   motif"
    "options options options";
  gap: 10px;
  align-items: start;
  padding: 12px;
  border: 1px solid #f0f0f0;
  border-radius: 12px;
  background: #fafafa;
}
.lignes-note__montant { grid-area: montant; }
.lignes-note__compte { grid-area: compte; display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.lignes-note__compte :deep(.v-field__input),
.lignes-note__compte :deep(.v-autocomplete__selection-text) {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.lignes-note__delete { grid-area: delete; align-self: start; }
.lignes-note__motif { grid-area: motif; }
.lignes-note__options {
  grid-area: options;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 4px;
  border-top: 1px dashed #e5e7eb;
}
.lignes-note__toggle-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.lignes-note__quantite { max-width: 160px; }
.lignes-note__article { flex: 1 1 260px; min-width: 220px; }
.lignes-note__compte-auto {
  display: flex;
  align-items: center;
  font-size: 0.78rem;
  color: #6b7280;
  background: #f3f4f6;
  border: 1px dashed #d1d5db;
  border-radius: 8px;
  padding: 8px 10px;
}
.lignes-note__tva-apercu {
  font-size: 0.78rem;
  color: #166534;
  background: #f0fdf4;
  border: 1px dashed #bbf7d0;
  border-radius: 8px;
  padding: 6px 10px;
  flex: 1 1 260px;
}
.lignes-note__ia {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 0.74rem;
  color: #6d28d9;
  background: #f5f3ff;
  border: 1px solid #ede9fe;
  border-radius: 8px;
  padding: 5px 8px;
}
.lignes-note__ia--loading { color: #6b7280; background: #f9fafb; border-color: #f0f0f0; }
.lignes-note__ia-dismiss {
  display: inline-flex;
  align-items: center;
  margin-left: auto;
  background: none;
  border: none;
  color: #9ca3af;
  cursor: pointer;
  padding: 0;
}
.lignes-note__total {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-radius: 12px;
  background: #f0fdf4;
  border: 1px solid #dcfce7;
}
.lignes-note__total .label {
  font-size: 0.78rem;
  font-weight: 600;
  color: #166534;
  text-transform: uppercase;
  letter-spacing: 0.4px;
}
.lignes-note__total strong {
  font-size: 1.05rem;
  color: #15803d;
}

@media (max-width: 720px) {
  .lignes-note__row {
    grid-template-columns: 1fr auto;
    grid-template-areas:
      "montant delete"
      "compte  compte"
      "motif   motif"
      "options options";
  }
}
</style>
