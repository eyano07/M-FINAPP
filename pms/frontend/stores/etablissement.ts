import { defineStore } from 'pinia'

/** Établissement de travail choisi à la connexion (hôtel, motel ou résidence d'appartements) ; null : tous. */
export interface EtablissementCourt { id: number, nom: string, type: string, adresse: string | null, unites: number, actif: boolean }

const CLE = 'pms_etablissement'

export const useEtablissementStore = defineStore('etablissement', {
  state: () => ({
    liste: [] as EtablissementCourt[],
    /** undefined : pas encore choisi ; null : tous les établissements ; sinon l'identifiant. */
    choisi: undefined as number | null | undefined,
    charge: false,
  }),

  getters: {
    courant: (s): EtablissementCourt | undefined => s.liste.find(e => e.id === s.choisi),
    /** Paramètre de requête : l'établissement choisi, ou rien pour tous. */
    filtre: (s): number | undefined => (s.choisi == null ? undefined : s.choisi),
    nom(): string { return this.courant?.nom ?? 'Tous les établissements' },
  },

  actions: {
    restaurer() {
      if (this.choisi !== undefined) return
      try {
        const v = localStorage.getItem(CLE)
        if (v === 'tous') this.choisi = null
        else if (v && /^\d+$/.test(v)) this.choisi = Number(v)
      } catch { /* stockage indisponible : choix à refaire */ }
    },

    async charger() {
      try {
        this.liste = (await useApi()<EtablissementCourt[]>('/etablissements')).filter(e => e.actif)
      } catch {
        this.liste = []
      }
      this.charge = true
      // Choix devenu impossible (établissement désactivé) ou un seul établissement : réglé d'office.
      if (typeof this.choisi === 'number' && !this.liste.some(e => e.id === this.choisi)) this.choisi = undefined
      if (this.liste.length === 1) this.choisir(this.liste[0].id)
      if (this.liste.length === 0) this.choisi = null
    },

    choisir(id: number | null) {
      this.choisi = id
      try { localStorage.setItem(CLE, id == null ? 'tous' : String(id)) } catch { /* rien à garder */ }
    },

    oublier() {
      this.choisi = undefined
      this.liste = []
      this.charge = false
      try { localStorage.removeItem(CLE) } catch { /* rien */ }
    },
  },
})
