<script setup lang="ts">
/**
 * Photos d'un ticket, d'un état des lieux ou d'un relevé : vignettes chargées avec le jeton de l'utilisateur (jamais
 * d'adresse publique), agrandissement au clic, prise de vue directe par l'appareil photo du téléphone.
 */
const props = defineProps<{ photos: { id: number, legende: string | null }[], envoi: string, peutAjouter?: boolean }>()
const emit = defineEmits<{ (e: 'change'): void }>()

const api = useApi()
const urls = reactive<Record<number, string>>({})
const grande = ref<number | null>(null)
const envoiEnCours = ref(false)
const erreur = ref('')
const champ = ref<HTMLInputElement | null>(null)

async function charger() {
  for (const p of props.photos) {
    if (urls[p.id]) continue
    try {
      const blob = await api<Blob>(`/photos/${p.id}`, { responseType: 'blob' })
      urls[p.id] = URL.createObjectURL(blob)
    } catch { /* vignette indisponible : on n'affiche pas d'image cassée */ }
  }
}
watch(() => props.photos.map(p => p.id).join(','), charger, { immediate: true })
onBeforeUnmount(() => Object.values(urls).forEach(u => URL.revokeObjectURL(u)))

async function envoyer(e: Event) {
  const fichiers = (e.target as HTMLInputElement).files
  if (!fichiers?.length) return
  erreur.value = ''
  envoiEnCours.value = true
  try {
    for (const f of Array.from(fichiers)) {
      const donnees = new FormData()
      donnees.append('fichier', f)
      await api(props.envoi, { method: 'POST', body: donnees })
    }
    emit('change')
  } catch (err) {
    erreur.value = messageErreur(err, "La photo n'a pas été enregistrée.")
  } finally {
    envoiEnCours.value = false
    if (champ.value) champ.value.value = ''
  }
}
</script>

<template>
  <div class="galerie">
    <div class="galerie__grille">
      <button v-for="p in photos" :key="p.id" type="button" class="galerie__vignette" :title="p.legende ?? 'Photo'" @click="grande = p.id">
        <img v-if="urls[p.id]" :src="urls[p.id]" :alt="p.legende ?? 'Photo'">
        <v-progress-circular v-else indeterminate size="18" width="2" />
      </button>
      <button v-if="peutAjouter" type="button" class="galerie__ajout" :disabled="envoiEnCours" @click="champ?.click()">
        <v-progress-circular v-if="envoiEnCours" indeterminate size="20" width="2" />
        <template v-else><v-icon icon="mdi-camera-plus-outline" size="22" /><span>Photo</span></template>
      </button>
    </div>
    <input ref="champ" type="file" accept="image/jpeg,image/png,image/webp" capture="environment" multiple hidden @change="envoyer">
    <p v-if="erreur" class="text-caption text-error mt-2 mb-0">{{ erreur }}</p>
    <v-dialog :model-value="grande !== null" max-width="960" @update:model-value="grande = null">
      <v-card v-if="grande !== null" class="pa-2">
        <img :src="urls[grande]" alt="Photo" class="galerie__grande">
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.galerie__grille { display: grid; grid-template-columns: repeat(auto-fill, minmax(84px, 1fr)); gap: 8px; }
.galerie__vignette, .galerie__ajout {
  aspect-ratio: 1; border-radius: 14px; overflow: hidden; display: grid; place-items: center; cursor: pointer; padding: 0;
  border: 1px solid var(--d-line); background: var(--d-surface-2);
}
.galerie__vignette img { width: 100%; height: 100%; object-fit: cover; transition: transform 0.25s; }
.galerie__vignette:hover img { transform: scale(1.05); }
.galerie__ajout { gap: 2px; font: inherit; font-size: 0.72rem; font-weight: 650; color: var(--color-primary); border-style: dashed;
  border-color: color-mix(in srgb, var(--color-primary) 40%, transparent); background: color-mix(in srgb, var(--color-primary) 5%, var(--d-surface)); }
.galerie__ajout { display: flex; flex-direction: column; align-items: center; justify-content: center; }
.galerie__grande { display: block; width: 100%; max-height: 80vh; object-fit: contain; border-radius: 18px; }
</style>
