<script setup lang="ts">
interface Compte {
  id: number
  numero: string
  libelle: string
  type: string
  classe: number
  imputable: boolean
  actif: boolean
}

const props = withDefaults(defineProps<{
  modelValue: string | null
  label?: string
  disabled?: boolean
  /**
   * false pour un selecteur de filtre/consultation (ex. Grand Livre) ou
   * l'on doit pouvoir retrouver un compte desactive qui porte deja des
   * ecritures. true (defaut) pour un selecteur de saisie, ou seuls les
   * comptes imputables et actifs doivent etre proposes.
   */
  seulementActifs?: boolean
}>(), {
  label: 'Compte OHADA',
  disabled: false,
  seulementActifs: true,
})

const emit = defineEmits<{ (e: 'update:modelValue', v: string | null): void }>()

const api = useApi()
const comptes = ref<Compte[]>([])
const loading = ref(false)

async function chargerComptes() {
  if (comptes.value.length) return
  loading.value = true
  try {
    comptes.value = await api<Compte[]>('/comptes')
  } catch {
    comptes.value = []
  } finally {
    loading.value = false
  }
}

onMounted(chargerComptes)

// Seuls les comptes imputables et actifs sont proposes a la saisie : un
// compte desactive par le referentiel (ex. 6323, remplace par 6384) reste
// techniquement imputable mais ne doit plus etre choisi. La valeur deja
// selectionnee reste affichee meme si le compte est desormais filtre, pour
// ne pas faire disparaitre une saisie existante a l'ouverture du formulaire.
const items = computed(() =>
  comptes.value
    .filter(c => !props.seulementActifs || (c.imputable && c.actif) || c.numero === props.modelValue)
    .map(c => ({
      title: `${c.numero} — ${c.libelle}`,
      value: c.numero,
    }))
)

const proxy = computed({
  get: () => props.modelValue,
  set: (v: string | null) => emit('update:modelValue', v),
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
    @focus="chargerComptes"
  />
</template>
