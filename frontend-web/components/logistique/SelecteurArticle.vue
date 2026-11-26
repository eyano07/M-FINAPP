<script setup lang="ts">
import { usePermissionsStore } from '~/stores/permissions'
interface Article {
  id: number
  code: string
  libelle: string
  uniteMesure?: string
  type?: string
  minerais?: boolean
}

/** Types gérés par le module Restaurant (TypeArticle.estGereParModuleDedie côté serveur). */
const TYPES_MODULE_RESTAURANT = ['PLAT', 'BOISSON', 'PROVISION']

const props = withDefaults(defineProps<{
  modelValue: number | null
  label?: string
  disabled?: boolean
  /** Écarte les plats, boissons et provisions (saisie d'un mouvement hors administrateur). */
  horsModuleRestaurant?: boolean
}>(), {
  label: 'Article',
  disabled: false,
  horsModuleRestaurant: false,
})

const emit = defineEmits<{ (e: 'update:modelValue', v: number | null): void }>()

const api = useApi()
const permissions = usePermissionsStore()
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
  articles.value
    .filter(a => !props.horsModuleRestaurant || !TYPES_MODULE_RESTAURANT.includes(a.type ?? ''))
    // Module coupé : ses articles ne sont plus proposés (Restaurant : plats, boissons, provisions ; Mine : minerais).
    .filter(a => permissions.peutVoir('RESTAURANT') || !TYPES_MODULE_RESTAURANT.includes(a.type ?? ''))
    .filter(a => permissions.peutVoir('MINE') || !a.minerais)
    .map(a => ({ title: `${a.code} — ${a.libelle}`, value: a.id }))
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
