import { defineNuxtConfig } from 'nuxt/config'
import vuetify, { transformAssetUrls } from 'vite-plugin-vuetify'

// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  // SPA plutôt que SSR : l'app est entièrement derrière connexion, aucune
  // page n'a besoin d'être indexée. Surtout, le rendu serveur est ce qui
  // provoquait la déconnexion à chaque rechargement — la session vit dans
  // localStorage (stores/auth.ts), invisible côté serveur ; le garde
  // (middleware/auth.global.ts) s'exécutait donc sans session lors du rendu
  // serveur et redirigeait vers /login avant même que le client ait pu
  // restaurer le token.
  ssr: false,
  compatibilityDate: '2025-01-01',
  devtools: { enabled: false },

  build: {
    transpile: ['vuetify'],
  },

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
    '@mdi/font/css/materialdesignicons.min.css',
    'vuetify/styles',
    '~/assets/styles/classroom.scss',
  ],

  vite: {
    vue: {
      template: { transformAssetUrls },
    },
  },

  app: {
    head: {
      title: 'MBSC Finapp',
      meta: [
        { charset: 'utf-8' },
        { name: 'viewport', content: 'width=device-width, initial-scale=1' },
      ],
      link: [{ rel: 'icon', type: 'image/x-icon', href: '/favicon.ico' }],
    },
  },

  runtimeConfig: {
    public: {
      apiBase: process.env.NUXT_PUBLIC_API_BASE || '/api',
    },
  },
})
