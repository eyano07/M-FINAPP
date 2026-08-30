<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'

// Redirige la racine vers la page d'accueil de l'utilisateur (la garde
// globale gere l'auth ; le role determine la destination).
const auth = useAuthStore()
await navigateTo(auth.homeRoute, { replace: true })
</script>

<template>
  <div />
</template>
