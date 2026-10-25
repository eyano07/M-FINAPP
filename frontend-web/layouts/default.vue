<script setup lang="ts">
import { useDisplay } from 'vuetify'
import { useInstanceStore } from '~/stores/instance'

// Ferme par defaut sur mobile (l'overlay couvrirait sinon toute la page a
// l'ouverture) : pas de risque d'hydratation puisque l'app est en SPA pur
// (ssr:false), $vuetify.display.mobile reflete deja le vrai innerWidth des
// le premier rendu.
const { mobile } = useDisplay()
const drawer = ref(!mobile.value)

// Mode du serveur (copie en ligne, serveur local...) : bandeau et pastille de la barre, rafraîchis chaque minute.
const instance = useInstanceStore()
let minuterie: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  void instance.charger()
  minuterie = setInterval(() => { void instance.charger() }, 60_000)
})
onBeforeUnmount(() => { if (minuterie) clearInterval(minuterie) })
</script>

<template>
  <v-app>
    <LayoutAppBar :drawer-open="drawer" @toggle-drawer="drawer = !drawer" />
    <LayoutNavigationDrawer v-model="drawer" />

    <v-main style="background: #f8f9fa;">
      <!-- Marges de page : 12 px (téléphone), 16 px (tablette), 24 px (PC). -->
      <v-container fluid class="pa-3 pa-sm-4 pa-md-6">
        <LayoutBandeauInstance />
        <slot />
      </v-container>
    </v-main>
  </v-app>
</template>
