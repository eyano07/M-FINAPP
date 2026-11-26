import { createVuetify } from 'vuetify'
import { aliases, mdi } from 'vuetify/iconsets/mdi'
import { fr } from 'vuetify/locale'
import { classerCellule } from '~/utils/classerCellule'

/**
 * Theme MBSC « Malachite » : vert de marque, papier vert tres pale, surfaces blanches a filet fin, encre vert-noir.
 * Les teintes derivees de la couleur de marque sont dans assets/styles/classroom.scss (color-mix).
 */
const classroomLight = {
  dark: false,
  colors: {
    background: '#F4F5F2',
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
  // Filets presque invisibles (design 2027) : cartes, champs, séparateurs.
  variables: { 'border-color': '#0E1512', 'border-opacity': 0.08 },
}

/**
 * « Malachite nuit » : forêt très sombre (jamais un noir pur), surfaces légèrement relevées, vert de marque éclairci pour
 * rester lisible. Le vert de marque réel est réappliqué par stores/parametres.ts (version éclaircie pour la nuit).
 */
const malachiteNuit = {
  dark: true,
  colors: {
    background: '#080C0A',
    surface: '#101614',
    'surface-bright': '#18211D',
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
  variables: { 'border-color': '#FFFFFF', 'border-opacity': 0.08 },
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
        rounded: 'xl',
        elevation: 0,
        border: true,
      },
      VBtn: {
        rounded: 'lg',
        style: 'text-transform: none; letter-spacing: 0;',
      },
      VTextField: {
        variant: 'outlined',
        density: 'comfortable',
        color: 'primary',
        rounded: 'lg',
      },
      VTextarea: {
        variant: 'outlined',
        density: 'comfortable',
        color: 'primary',
        rounded: 'lg',
      },
      VSelect: {
        variant: 'outlined',
        density: 'comfortable',
        color: 'primary',
        rounded: 'lg',
      },
      VAutocomplete: {
        variant: 'outlined',
        density: 'comfortable',
        color: 'primary',
        rounded: 'lg',
      },
      VAlert: {
        rounded: 'lg',
      },
      VChip: {
        rounded: 'pill',
      },
      VDialog: {
        scrim: true,
      },
      VAppBar: {
        flat: true,
      },
      // Sous 960px (telephones et tablettes en portrait), chaque ligne se replie en carte a tuiles au lieu d'un tableau large :
      // comportement natif de Vuetify (mode « mobile » des v-data-table, 49 tableaux dans 45 pages), applique une fois ici plutot
      // que repete sur chaque page. `cellProps` range chaque cellule dans un role (identifiant, puce de statut, libelle, chiffre
      // cle, action...) d'apres sa colonne : voir utils/classerCellule.ts et assets/styles/responsive.scss. Au-dela de 960px, ce
      // sont de vrais tableaux a en-tete fixe (meme fichier de styles).
      VDataTable: {
        mobileBreakpoint: 'md',
        cellProps: classerCellule,
      },
    },
  })

  nuxtApp.vueApp.use(vuetify)
  // Accès au thème hors composant (mode nuit appliqué au démarrage : composables/useModeAffichage.ts).
  nuxtApp.provide('vuetifyTheme', vuetify.theme)
})
