import { defineStore } from 'pinia'

/**
 * Libelles d'affichage des roles, personnalisables par l'ADMIN (ex. DA -> DAF).
 * Cosmetique uniquement : les droits et gardes continuent d'utiliser le code
 * technique du role (DA, DFIN...), jamais le libelle.
 */
export const useRolesStore = defineStore('roles', {
  state: () => ({
    libelles: {} as Record<string, string>,
    charge: false,
  }),

  getters: {
    /** Libelle affiche d'un role ; retombe sur le code technique si inconnu. */
    libelle: (s) => (role: string): string => s.libelles[role] || role,
  },

  actions: {
    async charger() {
      const api = useApi()
      try {
        this.libelles = await api<Record<string, string>>('/roles/libelles')
      } catch {
        // Best-effort : sans libelles, l'interface affiche les codes techniques.
      } finally {
        this.charge = true
      }
    },

    async definir(role: string, libelle: string) {
      const api = useApi()
      this.libelles = await api<Record<string, string>>(`/admin/roles/${role}/libelle`, {
        method: 'PUT',
        body: { libelle },
      })
    },
  },
})
