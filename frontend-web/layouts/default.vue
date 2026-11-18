<script setup lang="ts">
import { useDisplay } from 'vuetify'
import { useInstanceStore } from '~/stores/instance'
import { useAuthStore } from '~/stores/auth'
import { estApplicationMobile } from '~/utils/applicationMobile'

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

// Application Android, caissier et comptable : barre de navigation flottante en bas ; la page laisse la place dessous.
const auth = useAuthStore()
const avecBarreBas = computed(() => estApplicationMobile() && auth.hasAnyRole(['CAISSIER', 'COMPTABLE']))
</script>

<template>
  <v-app>
    <LayoutAppBar :drawer-open="drawer" @toggle-drawer="drawer = !drawer" />
    <LayoutNavigationDrawer v-model="drawer" />

    <v-main style="background: var(--canvas);" :class="{ 'avec-barre-bas': avecBarreBas }">
      <!-- Marges de page : 12 px (téléphone), 16 px (tablette), 24 px (PC). -->
      <v-container fluid class="pa-3 pa-sm-4 pa-md-6">
        <LayoutBandeauInstance />
        <slot />
      </v-container>
    </v-main>
    <LayoutBarreApplication />
  </v-app>
</template>

<style scoped>
/* Hauteur de la barre du bas (68 px) + son écart au bord (12 px) + une respiration. */
.avec-barre-bas :deep(.v-container) {
  padding-bottom: calc(96px + env(safe-area-inset-bottom, 0px)) !important;
}
/* Boutons flottants des pages : au-dessus de la barre du bas. */
.avec-barre-bas :deep(.classroom-fab) {
  bottom: calc(96px + env(safe-area-inset-bottom, 0px));
  right: 16px;
}
</style>
