<script lang="ts">
export interface LigneEcritureForm {
  compteNumero: string | null
  debit: number | null
  credit: number | null
  libelle: string
}
</script>

<script setup lang="ts">
const props = defineProps<{
  modelValue: LigneEcritureForm[]
}>()

const emit = defineEmits<{ (e: 'update:modelValue', v: LigneEcritureForm[]): void }>()

const lignes = computed({
  get: () => props.modelValue,
  set: (v: LigneEcritureForm[]) => emit('update:modelValue', v),
})

function ajouterLigne() {
  lignes.value = [...lignes.value, { compteNumero: null, debit: null, credit: null, libelle: '' }]
}

function supprimerLigne(index: number) {
  if (lignes.value.length <= 2) return
  lignes.value = lignes.value.filter((_, i) => i !== index)
}

function onDebitChange(index: number, val: number | null) {
  const copy = [...lignes.value]
  copy[index] = { ...copy[index], debit: val, credit: val && val > 0 ? null : copy[index].credit }
  lignes.value = copy
}

function onCreditChange(index: number, val: number | null) {
  const copy = [...lignes.value]
  copy[index] = { ...copy[index], credit: val, debit: val && val > 0 ? null : copy[index].debit }
  lignes.value = copy
}

const totalDebit = computed(() =>
  lignes.value.reduce((s, l) => s + (Number(l.debit) || 0), 0)
)
const totalCredit = computed(() =>
  lignes.value.reduce((s, l) => s + (Number(l.credit) || 0), 0)
)
const equilibree = computed(() =>
  Math.abs(totalDebit.value - totalCredit.value) < 0.01 && totalDebit.value > 0
)
const ecart = computed(() => totalDebit.value - totalCredit.value)

function fmt(v: number) {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(v)
}
</script>

<template>
  <div class="lignes-ecriture">
    <div class="lignes-ecriture__head">
      <span class="lignes-ecriture__title">Lignes d'écriture</span>
      <v-btn size="small" variant="tonal" color="primary" prepend-icon="mdi-plus" @click="ajouterLigne">
        Ajouter une ligne
      </v-btn>
    </div>

    <div v-for="(ligne, index) in lignes" :key="index" class="lignes-ecriture__row">
      <div class="lignes-ecriture__compte">
        <ComptabiliteSelecteurCompte v-model="ligne.compteNumero" :label="`Compte ligne ${index + 1}`" />
      </div>
      <v-text-field
        :model-value="ligne.debit"
        label="Débit"
        type="number"
        min="0"
        step="0.01"
        variant="outlined"
        density="comfortable"
        hide-details="auto"
        @update:model-value="onDebitChange(index, $event ? Number($event) : null)"
      />
      <v-text-field
        :model-value="ligne.credit"
        label="Crédit"
        type="number"
        min="0"
        step="0.01"
        variant="outlined"
        density="comfortable"
        hide-details="auto"
        @update:model-value="onCreditChange(index, $event ? Number($event) : null)"
      />
      <v-text-field
        v-model="ligne.libelle"
        label="Libellé ligne"
        variant="outlined"
        density="comfortable"
        hide-details="auto"
      />
      <v-btn
        icon="mdi-delete-outline"
        variant="text"
        color="error"
        :disabled="lignes.length <= 2"
        @click="supprimerLigne(index)"
      />
    </div>

    <div class="lignes-ecriture__totaux">
      <div>
        <span class="label">Total débit</span>
        <strong>{{ fmt(totalDebit) }} USD</strong>
      </div>
      <div>
        <span class="label">Total crédit</span>
        <strong>{{ fmt(totalCredit) }} USD</strong>
      </div>
      <div>
        <span class="label">Écart</span>
        <strong :class="equilibree ? 'ok' : 'ko'">{{ fmt(ecart) }} USD</strong>
      </div>
      <v-chip :color="equilibree ? 'success' : 'warning'" size="small" variant="flat">
        {{ equilibree ? 'Pièce équilibrée' : 'Pièce déséquilibrée' }}
      </v-chip>
    </div>
  </div>
</template>

<style scoped>
.lignes-ecriture {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.lignes-ecriture__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.lignes-ecriture__title {
  font-weight: 600;
  color: #111827;
}
.lignes-ecriture__row {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 2fr auto;
  gap: 10px;
  align-items: start;
  padding: 12px;
  border: 1px solid #f0f0f0;
  border-radius: 12px;
  background: #fafafa;
}
.lignes-ecriture__totaux {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 20px;
  padding: 14px 16px;
  border-radius: 12px;
  background: #f0fdf4;
  border: 1px solid #dcfce7;
}
.lignes-ecriture__totaux .label {
  display: block;
  font-size: 0.72rem;
  color: #6b7280;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.lignes-ecriture__totaux strong.ok { color: #16a34a; }
.lignes-ecriture__totaux strong.ko { color: #dc2626; }

@media (max-width: 960px) {
  .lignes-ecriture__row {
    grid-template-columns: 1fr;
  }
}
</style>
