<script setup lang="ts">
// Logo M-FINAPP monochrome (18/11/2026) : le « M » et la flèche de croissance, d'une seule couleur (currentColor) — blanc
// sur le menu et les fonds sombres, couleur de marque sur les fonds clairs : le conteneur choisit la couleur.
// La version en couleurs (public/logo-mfinapp.svg) reste celle de l'onglet du navigateur et de l'icône du téléphone.
</script>

<template>
  <svg class="logo-mfinapp" viewBox="16 18 92 86" role="img" aria-label="M-FINAPP" xmlns="http://www.w3.org/2000/svg">
    <g fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <path d="M28 90 V36 L60 66" stroke-width="13" />
      <path d="M92 58 V90" stroke-width="13" />
      <path d="M60 66 L94 32" stroke-width="13" />
      <path d="M76 30 H96 V50" stroke-width="10" />
    </g>
  </svg>
</template>

<style scoped>
.logo-mfinapp { display: block; width: 100%; height: 100%; }
</style>
