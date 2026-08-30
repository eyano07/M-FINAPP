<script setup lang="ts">
defineProps<{
  icon?: string
  label?: string
}>()
const emit = defineEmits<{ (e: 'click'): void }>()
</script>

<template>
  <v-btn
    class="classroom-fab"
    color="primary"
    size="large"
    :icon="!label"
    :rounded="label ? 'pill' : undefined"
    elevation="4"
    @click="emit('click')"
  >
    <v-icon :icon="icon ?? 'mdi-plus'" :class="label ? 'mr-2' : ''" />
    <span v-if="label">{{ label }}</span>
  </v-btn>
</template>
