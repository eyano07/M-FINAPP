import { createVuetify } from 'vuetify'
import { aliases, mdi } from 'vuetify/iconsets/mdi'
import { fr } from 'vuetify/locale'

/**
 * Theme MBSC « Malachite » : vert de marque, papier vert tres pale, surfaces blanches a filet fin, encre vert-noir.
 * Les teintes derivees de la couleur de marque sont dans assets/styles/classroom.scss (color-mix).
 */
const classroomLight = {
  dark: false,
  colors: {
    background: '#EEF3F0',
    surface: '#FFFFFF',
    primary: '#15803D',
    secondary: '#2563EB',
    accent: '#F59E0B',
    error: '#DC2626',
    info: '#2563EB',
    success: '#15803D',
    warning: '#D97706',
    'on-surface': '#0D1A15',
    'on-background': '#0D1A15',
    'surface-variant': '#55645C',
  },
}

/**
 * « Malachite nuit » : forêt très sombre (jamais un noir pur), surfaces légèrement relevées, vert de marque éclairci pour
 * rester lisible. Le vert de marque réel est réappliqué par stores/parametres.ts (version éclaircie pour la nuit).
 */
const malachiteNuit = {
  dark: true,
  colors: {
    background: '#07110D',
    surface: '#0D1A14',
    'surface-bright': '#14251D',
    'surface-variant': '#97AA9F',
    primary: '#3FBF7F',
    secondary: '#8DB7FF',
    accent: '#F3D28A',
    error: '#F87171',
    info: '#8DB7FF',
    success: '#3FBF7F',
    warning: '#F5B04C',
    'on-surface': '#E7F0EA',
    'on-background': '#E7F0EA',
  },
}

export default defineNuxtPlugin((nuxtApp) => {
  const vuetify = createVuetify({
    ssr: true,
    // Textes propres aux composants (pagination des tableaux, « Aucune
    // donnée », sélecteurs de date...) : anglais par défaut sinon.
    locale: {
      locale: 'fr',
      fallback: 'fr',
      messages: { fr },
    },
    theme: {
      defaultTheme: 'classroomLight',
      themes: { classroomLight, malachiteNuit },
    },
    icons: {
      defaultSet: 'mdi',
      aliases,
      sets: { mdi },
    },
    defaults: {
      VCard: {
        rounded: 'lg',
        elevation: 0,
        border: true,
      },
      VBtn: {
        rounded: 'lg',
        style: 'text-transform: none; letter-spacing: 0.25px;',
      },
      VTextField: {
        variant: 'outlined',
        density: 'comfortable',
        color: 'primary',
      },
      VSelect: {
        variant: 'outlined',
        density: 'comfortable',
      },
      VAppBar: {
        flat: true,
      },
      // Sous 600px (telephones), chaque ligne se replie en carte
      // libelle/valeur au lieu d'un tableau large illisible — comportement
      // natif de Vuetify, jamais active jusqu'ici sur aucun des tableaux
      // de l'appli (17 v-data-table). Applique une fois ici plutot que
      // repete sur chaque page.
      VDataTable: {
        mobileBreakpoint: 'sm',
      },
    },
  })

  nuxtApp.vueApp.use(vuetify)
})
