<script lang="ts">
export interface LigneMouvementForm {
  articleId: number | null
  entrepotSourceId: number | null
  entrepotCibleId: number | null
  quantite: number | null
  coutUnitaire: number | null
}
</script>

<script setup lang="ts">
const props = defineProps<{
  modelValue: LigneMouvementForm[]
  type: 'ENTREE' | 'SORTIE' | 'TRANSFERT'
}>()

const emit = defineEmits<{ (e: 'update:modelValue', v: LigneMouvementForm[]): void }>()

const lignes = computed({
  get: () => props.modelValue,
  set: (v: LigneMouvementForm[]) => emit('update:modelValue', v),
})

const montreSource = computed(() => props.type === 'SORTIE' || props.type === 'TRANSFERT')
const montreCible = computed(() => props.type === 'ENTREE' || props.type === 'TRANSFERT')
const montreCout = computed(() => props.type === 'ENTREE')

function ajouterLigne() {
  lignes.value = [...lignes.value, {
    articleId: null, entrepotSourceId: null, entrepotCibleId: null, quantite: null, coutUnitaire: null,
  }]
}

function supprimerLigne(index: number) {
  if (lignes.value.length <= 1) return
  lignes.value = lignes.value.filter((_, i) => i !== index)
}

const total = computed(() =>
  lignes.value.reduce((s, l) => s + (Number(l.quantite) || 0) * (Number(l.coutUnitaire) || 0), 0)
)

function fmt(v: number) {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(v)
}
</script>

<template>
  <div class="lignes-mvt">
    <div class="lignes-mvt__head">
      <span class="lignes-mvt__title">Lignes du mouvement</span>
      <v-btn size="small" variant="tonal" color="primary" prepend-icon="mdi-plus" @click="ajouterLigne">
        Ajouter une ligne
      </v-btn>
    </div>

    <div v-for="(ligne, index) in lignes" :key="index" class="lignes-mvt__row">
      <LogistiqueSelecteurArticle v-model="ligne.articleId" :label="`Article ${index + 1}`" />
      <LogistiqueSelecteurEntrepot v-if="montreSource" v-model="ligne.entrepotSourceId" label="Source" />
      <LogistiqueSelecteurEntrepot v-if="montreCible" v-model="ligne.entrepotCibleId" label="Cible" />
      <v-text-field
        v-model.number="ligne.quantite"
        label="Quantité"
        type="number"
        min="0"
        step="0.001"
        variant="outlined"
        density="comfortable"
        hide-details="auto"
      />
      <v-text-field
        v-if="montreCout"
        v-model.number="ligne.coutUnitaire"
        label="Coût unitaire"
        type="number"
        min="0"
        step="0.01"
        variant="outlined"
        density="comfortable"
        hide-details="auto"
      />
      <v-btn
        icon="mdi-delete-outline"
        variant="text"
        color="error"
        :disabled="lignes.length <= 1"
        @click="supprimerLigne(index)"
      />
    </div>

    <div v-if="montreCout" class="lignes-mvt__total">
      <span class="label">Valeur totale de l'entrée</span>
      <strong>{{ fmt(total) }} USD</strong>
    </div>
  </div>
</template>

<style scoped>
.lignes-mvt {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.lignes-mvt__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.lignes-mvt__title {
  font-weight: 600;
  color: #111827;
}
.lignes-mvt__row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)) auto;
  gap: 10px;
  align-items: start;
  padding: 12px;
  border: 1px solid #f0f0f0;
  border-radius: 12px;
  background: #fafafa;
}
.lignes-mvt__total {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 16px;
  border-radius: 12px;
  background: #f0fdf4;
  border: 1px solid #dcfce7;
}
.lignes-mvt__total .label {
  font-size: 0.72rem;
  color: #6b7280;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
</style>
