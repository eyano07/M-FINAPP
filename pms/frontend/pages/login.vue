<script setup lang="ts">
import { useSessionStore } from '~/stores/session'

definePageMeta({ layout: false })
useHead({ title: 'Connexion' })

const session = useSessionStore()
const route = useRoute()
const form = reactive({ email: '', motDePasse: '', code: '' })
const avecCode = ref(false)
const chargement = ref(false)
const erreur = ref('')

async function connecter() {
  erreur.value = ''
  chargement.value = true
  try {
    const base = useRuntimeConfig().public.apiBase as string
    const r = await $fetch<any>(`${base}/auth/login`, {
      method: 'POST',
      body: { email: form.email, motDePasse: form.motDePasse, codeDoubleAuth: avecCode.value ? form.code : undefined },
    })
    session.ouvrir(r)
    if (r.user?.doitChangerMotDePasse) return navigateTo('/changer-mot-de-passe')
    await navigateTo((route.query.suite as string) || '/')
  } catch (e: any) {
    const code = e?.data?.code
    if (code === 'DOUBLE_AUTH_REQUISE' || /double authentification/i.test(e?.data?.message ?? '')) avecCode.value = true
    erreur.value = messageErreur(e, 'Connexion impossible.')
  } finally {
    chargement.value = false
  }
}
</script>

<template>
  <CadreAcces>
    <v-card class="acces__carte pa-8" rounded="xl">
      <div class="acces__logo logo-principal"><LogoMfinapp /></div>
      <h1 class="acces__titre">Bon retour</h1>
      <p class="text-body-2 text-medium-emphasis mb-6">Connectez-vous à l’hôtellerie avec votre compte M-FINAPP.</p>
      <v-alert v-if="route.query.motDePasse === 'change' && !erreur" type="success" variant="tonal" density="compact" class="mb-4 text-left">
        Mot de passe changé : connectez-vous avec le nouveau.
      </v-alert>
      <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-4 text-left">{{ erreur }}</v-alert>
      <v-form @submit.prevent="connecter">
        <v-text-field v-model="form.email" label="Nom d'utilisateur ou e-mail" prepend-inner-icon="mdi-account-outline"
          autocomplete="username" autofocus />
        <v-text-field v-model="form.motDePasse" label="Mot de passe" type="password" prepend-inner-icon="mdi-lock-outline"
          autocomplete="current-password" />
        <v-text-field v-if="avecCode" v-model="form.code" label="Code de double authentification" inputmode="numeric"
          prepend-inner-icon="mdi-shield-key-outline" maxlength="6" />
        <v-btn type="submit" color="primary" variant="flat" size="x-large" block class="mt-2" :loading="chargement" :disabled="!form.email || !form.motDePasse">
          Se connecter
        </v-btn>
      </v-form>
      <p class="text-caption text-medium-emphasis mt-6">
        Depuis M-FINAPP, le menu « Hôtellerie › Ouvrir l'hôtellerie » vous connecte directement.
      </p>
    </v-card>
  </CadreAcces>
</template>

<style scoped>
.acces { min-height: 100dvh; display: grid; place-items: center; padding: 16px; background: var(--canvas); }
.acces__carte { width: 100%; max-width: 440px; text-align: center; }
.acces__titre { font-size: 1.9rem; font-weight: 780; letter-spacing: -0.04em; margin: 20px 0 6px; }
.acces__logo { width: 64px; height: 64px; margin: 0 auto; border-radius: 18px; display: grid; place-items: center; padding: 10px; }
</style>
