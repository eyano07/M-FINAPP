import { defineStore } from 'pinia'

/**
 * Mode du serveur (V132) : serveur en ligne habituel, serveur local principal (réseau de l'entreprise), copie en ligne
 * d'un serveur local (consultation, création et validation des notes de frais, activation des comptes par l'ADMIN), ou
 * ancien serveur local (lecture seule). Rafraîchi régulièrement par la mise en page (bandeau, pastille de la barre).
 */
export type ModeInstance = 'EN_LIGNE' | 'LOCAL' | 'MIROIR' | 'LOCAL_ARRETE'

interface EtatInstance {
  mode: ModeInstance
  lectureSeule: boolean
  derniereSynchro: string | null
  enAttente: number
  horsConnexion: boolean
  charge: boolean
}

export const useInstanceStore = defineStore('instance', {
  state: (): EtatInstance => ({
    mode: 'EN_LIGNE',
    lectureSeule: false,
    derniereSynchro: null,
    enAttente: 0,
    horsConnexion: false,
    charge: false,
  }),

  getters: {
    /** Copie en ligne d'un serveur local : les saisies se font au serveur local, sauf les actions relayées. */
    estCopie: (s) => s.mode === 'MIROIR',
    estLocal: (s) => s.mode === 'LOCAL',
    estArrete: (s) => s.mode === 'LOCAL_ARRETE',
  },

  actions: {
    async charger() {
      try {
        const api = useApi()
        const e = await api<Omit<EtatInstance, 'charge'>>('/instance/etat')
        Object.assign(this, e, { charge: true })
      } catch {
        // Serveur plus ancien (sans /instance/etat) ou momentanément injoignable : fonctionnement habituel.
      }
    },

    reinitialiser() {
      this.$reset()
    },
  },
})
