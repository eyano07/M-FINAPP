<script setup lang="ts">
interface Article {
  id: number
  code: string
  libelle: string
  uniteMesure?: string
}

const props = withDefaults(defineProps<{
  modelValue: number | null
  label?: string
  disabled?: boolean
}>(), {
  label: 'Article',
  disabled: false,
})

const emit = defineEmits<{ (e: 'update:modelValue', v: number | null): void }>()

const api = useApi()
const articles = ref<Article[]>([])
const loading = ref(false)

async function charger() {
  if (articles.value.length) return
  loading.value = true
  try {
    articles.value = await api<Article[]>('/logistique/articles')
  } catch {
    articles.value = []
  } finally {
    loading.value = false
  }
}

onMounted(charger)

const items = computed(() =>
  articles.value.map(a => ({ title: `${a.code} — ${a.libelle}`, value: a.id }))
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
