import { defineNuxtConfig } from 'nuxt/config'
import vuetify, { transformAssetUrls } from 'vite-plugin-vuetify'

// M-FINAPP Hôtellerie : site sans rendu serveur (tout est derrière connexion), même style que M-FINAPP.
export default defineNuxtConfig({
  ssr: false,
  compatibilityDate: '2025-01-01',
  devtools: { enabled: false },
  build: { transpile: ['vuetify'] },
  modules: [
    '@pinia/nuxt',
    (_options, nuxt) => {
      nuxt.hooks.hook('vite:extendConfig', (config) => {
        config.plugins = config.plugins || []
        config.plugins.push(vuetify({ autoImport: true }))
      })
    },
  ],
  css: [
    '@fontsource-variable/schibsted-grotesk',
    '@mdi/font/css/materialdesignicons.min.css',
    'vuetify/styles',
    '~/assets/styles/classroom.scss',
    '~/assets/styles/responsive.scss',
    '~/assets/styles/hotellerie.scss',
    '~/assets/styles/design-2027.scss',
  ],
  vite: { vue: { template: { transformAssetUrls } } },
  app: {
    head: {
      htmlAttrs: { lang: 'fr' },
      title: 'M-FINAPP Hôtellerie',
      titleTemplate: (t?: string) => (t && t !== 'M-FINAPP Hôtellerie' ? `${t} — Hôtellerie` : 'M-FINAPP Hôtellerie'),
      meta: [
        { charset: 'utf-8' },
        { name: 'viewport', content: 'width=device-width, initial-scale=1' },
      ],
      link: [{ rel: 'icon', type: 'image/svg+xml', href: '/logo-mfinapp.svg' }],
    },
  },
  runtimeConfig: {
    public: {
      apiBase: process.env.NUXT_PUBLIC_API_BASE || '/api',
      appVersion: '1.0',
    },
  },
})
