<script setup lang="ts">
import { useSessionStore } from '~/stores/session'

definePageMeta({ layout: false })
useHead({ title: 'Connexion' })

const session = useSessionStore()
const route = useRoute()
const erreur = ref('')

onMounted(async () => {
  const code = route.query.code as string | undefined
  // Le code ne reste pas dans l'adresse (historique, partage d'écran).
  window.history.replaceState({}, '', '/sso')
  if (!code) {
    erreur.value = 'Lien de connexion incomplet.'
    return
  }
  try {
    const base = useRuntimeConfig().public.apiBase as string
    const r = await $fetch<any>(`${base}/auth/sso`, { method: 'POST', body: { code } })
    session.ouvrir(r)
    await navigateTo(r.user?.doitChangerMotDePasse ? '/changer-mot-de-passe' : '/')
  } catch (e) {
    erreur.value = messageErreur(e, 'Connexion impossible.')
  }
})
</script>

<template>
  <div class="acces">
    <v-card class="acces__carte pa-8" rounded="xl">
      <div class="acces__logo logo-principal"><LogoMfinapp /></div>
      <template v-if="!erreur">
        <v-progress-circular indeterminate color="primary" class="mt-6" />
        <p class="text-body-2 mt-4">Connexion avec votre compte M-FINAPP…</p>
      </template>
      <template v-else>
        <v-alert type="error" variant="tonal" class="mt-6 text-left">{{ erreur }}</v-alert>
        <v-btn class="mt-4" color="primary" variant="tonal" to="/login">Se connecter avec ses identifiants</v-btn>
      </template>
    </v-card>
  </div>
</template>

<style scoped>
.acces { min-height: 100dvh; display: grid; place-items: center; padding: 16px; background: var(--canvas); }
.acces__carte { width: 100%; max-width: 420px; text-align: center; }
.acces__logo { width: 64px; height: 64px; margin: 0 auto; border-radius: 18px; display: grid; place-items: center; padding: 10px; }
</style>
