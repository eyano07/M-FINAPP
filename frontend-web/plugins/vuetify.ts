import { createVuetify } from 'vuetify'
import { aliases, mdi } from 'vuetify/iconsets/mdi'
import { fr } from 'vuetify/locale'
import { classerCellule } from '~/utils/classerCellule'

/**
 * Theme MBSC - clone visuel Google Classroom.
 * Palette Material : vert Classroom, fond gris tres clair, surfaces blanches.
 */
const classroomLight = {
  dark: false,
  colors: {
    background: '#F8F9FA',
    surface: '#FFFFFF',
    primary: '#15803D',
    secondary: '#2563EB',
    accent: '#F59E0B',
    error: '#DC2626',
    info: '#2563EB',
    success: '#15803D',
    warning: '#D97706',
    'on-surface': '#111827',
    'on-background': '#111827',
    'surface-variant': '#6B7280',
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
      themes: { classroomLight },
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
})
