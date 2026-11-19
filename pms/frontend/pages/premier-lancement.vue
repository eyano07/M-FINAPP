<script setup lang="ts">
import { useSessionStore } from '~/stores/session'
import { marquerAppaire } from '~/utils/appairage'

definePageMeta({ layout: false })
useHead({ title: 'Appairage' })

interface Etat { appaire: boolean, cleFin?: string, joignable?: boolean, moduleActif?: boolean, entreprise?: string, message?: string }

const session = useSessionStore()
const etat = ref<Etat | null>(null)
const cle = ref('')
const chargement = ref(false)
const erreur = ref('')
const base = useRuntimeConfig().public.apiBase as string

async function charger() {
  try {
    etat.value = await $fetch<Etat>(`${base}/appairage`)
  } catch (e) {
    erreur.value = messageErreur(e, "Le serveur de l'hôtellerie ne répond pas.")
  }
}
onMounted(charger)

async function enregistrer() {
  erreur.value = ''
  chargement.value = true
  try {
    etat.value = await $fetch<Etat>(`${base}/appairage`, {
      method: 'POST',
      body: { cle: cle.value },
      headers: session.acces ? { Authorization: `Bearer ${session.acces}` } : undefined,
    })
    marquerAppaire()
    cle.value = ''
  } catch (e) {
    erreur.value = messageErreur(e, "La clé n'a pas été acceptée.")
    await charger()
  } finally {
    chargement.value = false
  }
}
</script>

<template>
  <div class="acces">
    <v-card class="acces__carte pa-8" rounded="xl">
      <div class="acces__logo logo-principal"><LogoMfinapp /></div>
      <h1 class="text-h5 font-weight-bold mt-4">Relier l'hôtellerie à M-FINAPP</h1>
      <template v-if="etat?.appaire">
        <v-alert :type="etat.joignable && etat.moduleActif ? 'success' : 'warning'" variant="tonal" class="mt-6 text-left">
          <div>Hôtellerie appairée (clé …{{ etat.cleFin }}).</div>
          <div v-if="etat.joignable">M-FINAPP répond<template v-if="etat.entreprise"> — {{ etat.entreprise }}</template>.
            <template v-if="!etat.moduleActif"> Le module Hôtellerie y est encore désactivé.</template></div>
          <div v-else>M-FINAPP ne répond pas : {{ etat.message }}</div>
        </v-alert>
        <v-btn class="mt-4" color="primary" to="/">Continuer</v-btn>
        <v-divider class="my-6" />
        <p class="text-body-2 text-medium-emphasis text-left">Changer de clé (administrateur ou direction de l'hébergement, connecté) :</p>
      </template>
      <template v-else>
        <ol class="etapes text-left mt-6 mb-4">
          <li>Dans M-FINAPP, activez le module « Hôtellerie » (Administration › Modules).</li>
          <li>Dans Administration › Hôtellerie, saisissez l'adresse et le port de ce serveur, puis générez la clé d'appairage.</li>
          <li>Collez la clé ci-dessous.</li>
        </ol>
      </template>
      <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-4 text-left">{{ erreur }}</v-alert>
      <v-textarea v-model="cle" label="Clé d'appairage (MBSC1.…)" rows="3" auto-grow variant="outlined" spellcheck="false" />
      <v-btn color="primary" size="large" block :loading="chargement" :disabled="!cle.trim()" @click="enregistrer">
        Enregistrer la clé
      </v-btn>
    </v-card>
  </div>
</template>

<style scoped>
.acces { min-height: 100dvh; display: grid; place-items: center; padding: 16px; background: var(--canvas); }
.acces__carte { width: 100%; max-width: 520px; text-align: center; }
.acces__logo { width: 64px; height: 64px; margin: 0 auto; border-radius: 18px; display: grid; place-items: center; padding: 10px; }
.etapes { padding-left: 20px; line-height: 1.7; font-size: 0.92rem; }
</style>
