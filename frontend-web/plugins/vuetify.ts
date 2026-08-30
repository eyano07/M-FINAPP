import { createVuetify } from 'vuetify'
import { aliases, mdi } from 'vuetify/iconsets/mdi'

/**
 * Theme MBSC - clone visuel Google Classroom.
 * Palette Material : vert Classroom, fond gris tres clair, surfaces blanches.
 */
const classroomLight = {
  dark: false,
  colors: {
    background: '#F8F9FA',
    surface: '#FFFFFF',
    primary: '#16A34A',
    secondary: '#2563EB',
    accent: '#F59E0B',
    error: '#DC2626',
    info: '#2563EB',
    success: '#16A34A',
    warning: '#D97706',
    'on-surface': '#111827',
    'on-background': '#111827',
    'surface-variant': '#6B7280',
  },
}

export default defineNuxtPlugin((nuxtApp) => {
  const vuetify = createVuetify({
    ssr: true,
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
