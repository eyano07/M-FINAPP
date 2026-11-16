<script setup lang="ts">
// Bouton à bascule USD / FC du journal et du rapport de caisse. Il ne convertit rien : il annonce le choix, et la page
// redemande ses données au serveur dans la devise choisie (voir composables/useDeviseCaisse.ts).
defineProps<{
  modelValue: 'USD' | 'CDF'
  disabled?: boolean
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: 'USD' | 'CDF'): void }>()
</script>

<template>
  <v-btn-toggle
    :model-value="modelValue"
    mandatory
    divided
    density="comfortable"
    color="primary"
    variant="outlined"
    rounded="lg"
    :disabled="disabled"
    aria-label="Devise d'affichage"
    class="bascule-devise"
    @update:model-value="(v: 'USD' | 'CDF' | undefined) => v && emit('update:modelValue', v)"
  >
    <v-btn value="USD" prepend-icon="mdi-currency-usd">USD</v-btn>
    <v-btn value="CDF" prepend-icon="mdi-cash">FC</v-btn>
  </v-btn-toggle>
</template>
