import { defineStore } from 'pinia'

export interface Parametres {
  nom: string
  nomComplet?: string | null
  slogan?: string | null
  logoUrl?: string | null
}

const DEFAUT: Parametres = { nom: 'MBSC Finapp', nomComplet: null, slogan: null, logoUrl: null }

/**
 * Identite visuelle de l'entreprise. Chargee via $fetch brut (pas useApi()) :
 * l'endpoint est public et doit rester lisible depuis /login, avant toute
 * authentification — donc avant qu'un jeton existe.
 */
export const useParametresStore = defineStore('parametres', {
  state: () => ({
    parametres: DEFAUT as Parametres,
    charge: false,
  }),

  actions: {
    async charger() {
      const config = useRuntimeConfig()
      const apiBase = config.public.apiBase as string
      try {
        const data = await $fetch<Parametres>('/parametres', { baseURL: apiBase })
        // Le backend renvoie un chemin relatif a la racine de l'API (meme
        // convention que les autres telechargements de fichiers de l'appli) :
        // sans ce prefixe, un <img :src="logoUrl"> le resoudrait relatif a
        // l'origine du site (le frontend Nuxt), pas au backend derriere /api.
        this.parametres = {
          ...data,
          logoUrl: data.logoUrl ? `${apiBase}${data.logoUrl}` : null,
        }
      } catch {
        // Best-effort : conserve les valeurs par defaut si l'appel echoue
        // (ex. backend indisponible au tout premier chargement).
      } finally {
        this.charge = true
      }
    },
  },
})
