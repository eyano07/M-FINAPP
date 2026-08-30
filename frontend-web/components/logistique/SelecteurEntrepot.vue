<script setup lang="ts">
interface Entrepot {
  id: number
  code: string
  nom: string
}

const props = withDefaults(defineProps<{
  modelValue: number | null
  label?: string
  disabled?: boolean
}>(), {
  label: 'Entrepôt',
  disabled: false,
})

const emit = defineEmits<{ (e: 'update:modelValue', v: number | null): void }>()

const api = useApi()
const entrepots = ref<Entrepot[]>([])
const loading = ref(false)

async function charger() {
  if (entrepots.value.length) return
  loading.value = true
  try {
    entrepots.value = await api<Entrepot[]>('/logistique/entrepots')
  } catch {
    entrepots.value = []
  } finally {
    loading.value = false
  }
}

onMounted(charger)

const items = computed(() =>
  entrepots.value.map(e => ({ title: `${e.code} — ${e.nom}`, value: e.id }))
)

const proxy = computed({
  get: () => props.modelValue,
  set: (v: number | null) => emit('update:modelValue', v),
})
</script>

<template>
  <v-autocomplete
    v-model="proxy"
    :items="items"
    :label="label"
    :loading="loading"
    :disabled="disabled"
    variant="outlined"
    density="comfortable"
    hide-details="auto"
    clearable
    @focus="charger"
  />
</template>
