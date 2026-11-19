<script setup lang="ts">
import { useEtablissementStore } from '~/stores/etablissement'
import { useSessionStore } from '~/stores/session'

/** Choix de l'établissement de travail à la connexion (plusieurs hôtels, motels, résidences d'appartements). */
definePageMeta({ layout: false })
useHead({ title: 'Choisir l’établissement' })

const etablissements = useEtablissementStore()
const session = useSessionStore()
const route = useRoute()
const ICONES: Record<string, string> = { HOTEL: 'mdi-office-building', MOTEL: 'mdi-car-estate', APPARTEMENTS: 'mdi-home-city-outline' }
const TYPES: Record<string, string> = { HOTEL: 'Hôtel', MOTEL: 'Motel', APPARTEMENTS: 'Appartements' }
const voitTous = computed(() => session.a('DIR_HEBERGEMENT', 'ADMIN', 'DG', 'DFIN', 'DA', 'COMPTABLE'))

onMounted(async () => {
  await etablissements.charger()
  if (etablissements.choisi !== undefined && route.query.changer !== '1') await navigateTo((route.query.suite as string) || '/')
})

async function choisir(id: number | null) {
  etablissements.choisir(id)
  await navigateTo((route.query.suite as string) || '/')
}
</script>

<template>
  <div class="acces">
    <div class="acces__contenu">
      <div class="text-center mb-6">
        <div class="acces__logo logo-principal"><LogoMfinapp /></div>
        <h1 class="text-h5 font-weight-bold mt-4">Où travaillez-vous aujourd'hui ?</h1>
        <p class="text-body-2 text-medium-emphasis">{{ session.nomComplet }} — choisissez l'établissement ; vous pourrez en changer depuis la barre du haut.</p>
      </div>
      <v-progress-linear v-if="!etablissements.charge" indeterminate color="primary" />
      <div class="grille">
        <v-card v-for="e in etablissements.liste" :key="e.id" class="pa-5 carte" rounded="xl" :class="{ 'carte--active': etablissements.choisi === e.id }"
          @click="choisir(e.id)">
          <div class="carte__icone"><v-icon :icon="ICONES[e.type] ?? 'mdi-domain'" size="30" /></div>
          <div class="text-subtitle-1 font-weight-bold mt-3">{{ e.nom }}</div>
          <div class="text-caption text-medium-emphasis">{{ TYPES[e.type] ?? e.type }} · {{ e.unites }} unité(s)</div>
          <div v-if="e.adresse" class="text-caption text-medium-emphasis">{{ e.adresse }}</div>
        </v-card>
        <v-card v-if="voitTous && etablissements.liste.length > 1" class="pa-5 carte" rounded="xl" :class="{ 'carte--active': etablissements.choisi === null }"
          @click="choisir(null)">
          <div class="carte__icone"><v-icon icon="mdi-view-grid-outline" size="30" /></div>
          <div class="text-subtitle-1 font-weight-bold mt-3">Tous les établissements</div>
          <div class="text-caption text-medium-emphasis">Vue d'ensemble (direction)</div>
        </v-card>
      </div>
      <v-alert v-if="etablissements.charge && !etablissements.liste.length" type="info" variant="tonal" class="mt-4">
        Aucun établissement n'est encore créé.
        <v-btn v-if="session.a('DIR_HEBERGEMENT', 'ADMIN')" variant="text" color="primary" @click="choisir(null)">Créer le premier</v-btn>
      </v-alert>
    </div>
  </div>
</template>

<style scoped>
.acces { min-height: 100dvh; display: grid; place-items: center; padding: 24px 16px; background: var(--canvas); }
.acces__contenu { width: 100%; max-width: 860px; }
.acces__logo { width: 64px; height: 64px; margin: 0 auto; border-radius: 18px; display: grid; place-items: center; padding: 10px; }
.grille { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 14px; }
.carte { cursor: pointer; border: 2px solid transparent; transition: border-color .15s; }
.carte:hover, .carte--active { border-color: var(--color-primary); }
.carte__icone { width: 54px; height: 54px; border-radius: 16px; display: grid; place-items: center; color: var(--color-primary);
  background: color-mix(in srgb, var(--color-primary) 12%, transparent); }
</style>
