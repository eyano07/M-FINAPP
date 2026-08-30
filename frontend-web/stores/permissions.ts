import { defineStore } from 'pinia'

export type NiveauPermission = 'AUCUN' | 'LECTURE' | 'ECRITURE'
export type ModuleMetier =
  | 'CAISSE' | 'BANQUE' | 'MOBILE_MONEY' | 'COMPTABILITE' | 'VENTES' | 'LOGISTIQUE' | 'TRANSPORT'
  | 'PATRIMOINE'
  // Restaurant : module plat (pas de sous-modules), comme PATRIMOINE.
  | 'RESTAURANT'
  // DRH est le conteneur (jamais assigne dans role_permissions, jamais utilise
  // pour une garde de page) ; seuls les 4 sous-modules gatent reellement des
  // pages — voir ModuleMetier.java pour la hierarchie complete.
  | 'DRH' | 'DRH_PERSONNEL' | 'DRH_PRESENCES' | 'DRH_PAIE' | 'DRH_MISSIONS'

const ORDRE: Record<NiveauPermission, number> = { AUCUN: 0, LECTURE: 1, ECRITURE: 2 }

/**
 * Permissions effectives de l'utilisateur courant sur les modules metier
 * desactivables (deja resolues cote serveur : role + module actif). Source
 * de verite pour la navigation et les gardes de page ; l'application reelle
 * des droits reste imposee par le backend (ModuleAccessFilter), ceci n'est
 * qu'un reflet cote client pour l'affichage.
 */
export const usePermissionsStore = defineStore('permissions', {
  state: () => ({
    permissions: {} as Partial<Record<ModuleMetier, NiveauPermission>>,
    charge: false,
  }),

  getters: {
    niveau: (s) => (module: ModuleMetier): NiveauPermission => s.permissions[module] ?? 'AUCUN',
    peutVoir: (s) => (module: ModuleMetier): boolean => (s.permissions[module] ?? 'AUCUN') !== 'AUCUN',
    peutModifier: (s) => (module: ModuleMetier): boolean => s.permissions[module] === 'ECRITURE',
  },

  actions: {
    async charger() {
      const api = useApi()
      try {
        this.permissions = await api<Record<ModuleMetier, NiveauPermission>>('/mes-permissions')
      } catch {
        this.permissions = {}
      } finally {
        this.charge = true
      }
    },

    /** Compare deux niveaux (utilise par la garde de page). */
    satisfait(actuel: NiveauPermission, requis: NiveauPermission): boolean {
      return ORDRE[actuel] >= ORDRE[requis]
    },

    reinitialiser() {
      this.permissions = {}
      this.charge = false
    },
  },
})
