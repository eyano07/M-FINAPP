<script setup lang="ts">
import { useDisplay } from 'vuetify'

// Ferme par defaut sur mobile (l'overlay couvrirait sinon toute la page a
// l'ouverture) : pas de risque d'hydratation puisque l'app est en SPA pur
// (ssr:false), $vuetify.display.mobile reflete deja le vrai innerWidth des
// le premier rendu.
const { mobile } = useDisplay()
const drawer = ref(!mobile.value)
</script>

<template>
  <v-app>
    <LayoutAppBar :drawer-open="drawer" @toggle-drawer="drawer = !drawer" />
    <LayoutNavigationDrawer v-model="drawer" />

    <v-main style="background: #f8f9fa;">
      <v-container fluid class="pa-6">
        <slot />
      </v-container>
    </v-main>
  </v-app>
</template>
