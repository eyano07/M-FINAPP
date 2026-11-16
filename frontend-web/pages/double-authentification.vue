<script setup lang="ts">
/**
 * Double authentification obligatoire pour les rôles sensibles (audit SYSCOHADA du 10/11/2026, C-23) : un compte qui
 * ne l'a pas encore activée reste sur cette page (middleware/auth.global.ts) ; le serveur refuse tout le reste
 * (403 DOUBLE_AUTH_A_ACTIVER). Une fois activée, on reprend la navigation normale.
 */
definePageMeta({ layout: 'blank' })

import { useAuthStore } from '~/stores/auth'

const auth = useAuthStore()
const { logout } = useAuth()

async function apresActivation() {
  if (auth.user) auth.user = { ...auth.user, doitActiverDoubleAuth: false }
  await navigateTo(auth.homeRoute)
}
</script>

<template>
  <div class="page-double-auth">
    <v-card class="classroom-card pa-6" max-width="640">
      <div class="text-h6 font-weight-bold mb-2">Activez la double authentification</div>
      <p class="text-body-2 mb-4">
        Votre rôle donne accès à l'argent ou aux comptes de l'entreprise : votre compte doit être protégé par un code
        à usage unique en plus du mot de passe.
      </p>
      <CommunDoubleAuthentification @activee="apresActivation" />
      <v-btn variant="text" class="mt-4" @click="logout">Se déconnecter</v-btn>
    </v-card>
  </div>
</template>

<style scoped>
.page-double-auth {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}
</style>
