import { useEtablissementStore } from '~/stores/etablissement'
import { defineStore } from 'pinia'

/** Utilisateur M-FINAPP connecté à l'hôtellerie (les comptes et les rôles sont ceux de M-FINAPP). */
export interface Utilisateur {
  id: number
  email: string
  nom: string | null
  prenom: string | null
  roles: string[]
  niveau?: string
  /** Mot de passe provisoire (compte créé par l'administrateur) : à changer avant toute autre action. */
  doitChangerMotDePasse?: boolean
}

interface ReponseConnexion {
  accessToken: string
  refreshToken: string
  user: { id: number, email: string, nom: string | null, prenom: string | null, roles: string[], doitChangerMotDePasse?: boolean,
    doitActiverDoubleAuth?: boolean }
}

const CLES = { acces: 'pms_acces', rafraichissement: 'pms_rafraichissement', utilisateur: 'pms_utilisateur' }

function lire(cle: string): string | null {
  try { return localStorage.getItem(cle) } catch { return null }
}
function ecrire(cle: string, valeur: string | null) {
  try {
    if (valeur === null) localStorage.removeItem(cle)
    else localStorage.setItem(cle, valeur)
  } catch { /* stockage indisponible : la session vit le temps de l'onglet */ }
}

export const useSessionStore = defineStore('session', {
  state: () => ({
    acces: null as string | null,
    rafraichissement: null as string | null,
    utilisateur: null as Utilisateur | null,
  }),

  getters: {
    connecte: (s) => !!s.acces && !!s.utilisateur,
    nomComplet: (s) => s.utilisateur ? `${s.utilisateur.prenom ?? ''} ${s.utilisateur.nom ?? ''}`.trim() || s.utilisateur.email : '',
    initiales: (s) => {
      const u = s.utilisateur
      if (!u) return '?'
      return ((u.prenom?.[0] ?? '') + (u.nom?.[0] ?? u.email[0])).toUpperCase()
    },
    a: (s) => (...roles: string[]) => !!s.utilisateur && roles.some(r => s.utilisateur!.roles.includes(r)),
  },

  actions: {
    restaurer() {
      if (this.acces) return
      this.acces = lire(CLES.acces)
      this.rafraichissement = lire(CLES.rafraichissement)
      try { this.utilisateur = JSON.parse(lire(CLES.utilisateur) || 'null') } catch { this.utilisateur = null }
    },

    ouvrir(r: ReponseConnexion) {
      this.acces = r.accessToken
      this.rafraichissement = r.refreshToken
      this.utilisateur = { id: r.user.id, email: r.user.email, nom: r.user.nom, prenom: r.user.prenom, roles: [...r.user.roles],
        doitChangerMotDePasse: !!r.user.doitChangerMotDePasse }
      ecrire(CLES.acces, this.acces)
      ecrire(CLES.rafraichissement, this.rafraichissement)
      ecrire(CLES.utilisateur, JSON.stringify(this.utilisateur))
    },

    /** Nouveau jeton d'accès par M-FINAPP ; faux si la session est perdue. */
    async rafraichir(): Promise<boolean> {
      if (!this.rafraichissement) return false
      try {
        const base = useRuntimeConfig().public.apiBase as string
        const r = await $fetch<ReponseConnexion>(`${base}/auth/refresh`, { method: 'POST', body: { refreshToken: this.rafraichissement } })
        this.ouvrir(r)
        return true
      } catch {
        return false
      }
    },

    fermer() {
      useEtablissementStore().oublier()
      const base = useRuntimeConfig().public.apiBase as string
      if (this.acces) {
        $fetch(`${base}/auth/logout`, { method: 'POST', headers: { Authorization: `Bearer ${this.acces}` } }).catch(() => {})
      }
      this.acces = null
      this.rafraichissement = null
      this.utilisateur = null
      Object.values(CLES).forEach(c => ecrire(c, null))
    },
  },
})
