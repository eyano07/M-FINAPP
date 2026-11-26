<script setup lang="ts">
import { useDisplay } from 'vuetify'
import { useInstanceStore } from '~/stores/instance'
import { useAuthStore } from '~/stores/auth'
import { useExerciceStore } from '~/stores/exercice'
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
// Exercice consulté (sélecteur de la barre du haut) : la page est reconstruite à chaque changement, avec ses bornes.
const exercice = useExerciceStore()
exercice.hydrater()
onMounted(async () => {
  await exercice.charger()
  // Seuls le DFIN et le comptable choisissent l'exercice : un autre compte revient toujours à l'exercice en cours
  // (choix resté d'une session précédente ou d'un autre compte sur ce navigateur).
  if (exercice.enConsultation && !useAuthStore().hasAnyRole(['DFIN', 'COMPTABLE'])) exercice.choisir(exercice.anneeCourante)
  void instance.charger()
  minuterie = setInterval(() => { void instance.charger() }, 60_000)
})
onBeforeUnmount(() => { if (minuterie) clearInterval(minuterie) })

// Application Android, caissier et comptable : barre de navigation flottante en bas ; la page laisse la place dessous.
const auth = useAuthStore()
const avecBarreBas = computed(() => estApplicationMobile() && auth.hasAnyRole(['CAISSIER', 'COMPTABLE']))
</script>

<template>
  <v-app class="app-verre">
    <!-- Fond coloré flou, fixe derrière toutes les pages : le verre dépoli des cartes et du menu en prend les reflets. -->
    <div class="fond-aurore" aria-hidden="true"><span /><span /><span /><span /><span /></div>
    <LayoutAppBar :drawer-open="drawer" @toggle-drawer="drawer = !drawer" />
    <LayoutNavigationDrawer v-model="drawer" />

    <v-main style="background: transparent;" :class="{ 'avec-barre-bas': avecBarreBas }">
      <!-- Marges de page : 12 px (téléphone), 16 px (tablette), 24 px (PC). -->
      <v-container fluid class="pa-3 pa-sm-4 pa-md-6">
        <LayoutBandeauInstance />
        <LayoutBandeauExercice />
        <div :key="exercice.anneeChoisie" class="page-exercice">
          <slot />
        </div>
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
