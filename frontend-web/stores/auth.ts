import { defineStore } from 'pinia'

export interface AuthUser {
  id: number
  email: string
  nom: string
  prenom: string
  roles: string[]
}

interface AuthState {
  accessToken: string | null
  refreshToken: string | null
  user: AuthUser | null
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    accessToken: null,
    refreshToken: null,
    user: null,
  }),

  getters: {
    isAuthenticated: (s) => !!s.accessToken && !!s.user,
    roles: (s) => s.user?.roles ?? [],
    /**
     * Route d'atterrissage par defaut selon le role. Le Directeur metier
     * (DIRECTEUR) n'a pas acces au tableau de bord (statistiques/graphiques
     * globaux) : il est redirige vers ses notes de frais.
     */
    homeRoute: (s) => {
      const roles = s.user?.roles ?? []
      // Doit refleter EXACTEMENT la garde de pages/dashboard/index.vue :
      // renvoyer un role vers une page qu'il n'a pas le droit d'ouvrir
      // provoque une boucle de redirection (le middleware le renvoie vers
      // homeRoute, qui le renvoie vers la meme page interdite).
      const ROLES_DASHBOARD = ['ADMIN', 'DG', 'DA', 'DFIN', 'CAISSIER', 'COMPTABLE', 'LOGISTIQUE']
      if (roles.some((r) => ROLES_DASHBOARD.includes(r))) {
        return '/dashboard'
      }
      // Le gestionnaire du patrimoine atterrit directement sur son module.
      if (roles.includes('GEST_PATRIMOINE')) {
        return '/patrimoine/immobilisations'
      }
      // Le responsable DRH atterrit directement sur son module (meme logique
      // que GEST_PATRIMOINE) : la paie/RH n'a pas de place dans le tableau de
      // bord financier general.
      if (roles.includes('RESP_DRH')) {
        return '/drh/bulletins'
      }
      // Responsable restaurant : meme logique. La carte est la page d'entree
      // naturelle du module, et il a le droit de l'ouvrir — condition
      // necessaire pour ne pas creer de boucle de redirection.
      if (roles.includes('RESP_RESTAURANT')) {
        return '/restaurant'
      }
      // Directeur metier (et tout role sans tableau de bord) : ses notes.
      return '/notes-frais'
    },
    fullName: (s) =>
      s.user ? `${s.user.prenom ?? ''} ${s.user.nom ?? ''}`.trim() : '',
    initials: (s) => {
      if (!s.user) return '?'
      const p = (s.user.prenom?.[0] ?? '').toUpperCase()
      const n = (s.user.nom?.[0] ?? '').toUpperCase()
      return (p + n) || s.user.email[0]?.toUpperCase() || '?'
    },
  },

  actions: {
    setSession(payload: {
      accessToken: string
      refreshToken: string
      user: AuthUser
    }) {
      this.accessToken = payload.accessToken
      this.refreshToken = payload.refreshToken
      this.user = payload.user
      if (import.meta.client) {
        localStorage.setItem('mbsc_access', payload.accessToken)
        localStorage.setItem('mbsc_refresh', payload.refreshToken)
        localStorage.setItem('mbsc_user', JSON.stringify(payload.user))
      }
    },

    restore() {
      if (!import.meta.client) return
      const access = localStorage.getItem('mbsc_access')
      const refresh = localStorage.getItem('mbsc_refresh')
      const user = localStorage.getItem('mbsc_user')
      if (access && refresh && user) {
        // localStorage peut etre corrompu/altere : ne jamais faire confiance
        // aveuglement au JSON stocke.
        try {
          const parsed = JSON.parse(user)
          if (!parsed || typeof parsed !== 'object' || !Array.isArray(parsed.roles)) {
            this.logout()
            return
          }
          this.accessToken = access
          this.refreshToken = refresh
          this.user = parsed as AuthUser
          // Re-synchronise le profil (roles inclus) depuis le serveur : les
          // roles stockes localement ne sont qu'un cache d'affichage.
          void this.verifierSession()
        } catch {
          this.logout()
        }
      }
    },

    /** Recharge le profil depuis /auth/me ; deconnecte si le jeton est invalide. */
    async verifierSession() {
      if (!this.accessToken) return
      const config = useRuntimeConfig()
      try {
        const me = await $fetch<AuthUser>('/auth/me', {
          baseURL: config.public.apiBase as string,
          headers: { Authorization: `Bearer ${this.accessToken}` },
        })
        this.user = { ...this.user, ...me }
        if (import.meta.client) {
          localStorage.setItem('mbsc_user', JSON.stringify(this.user))
        }
      } catch (e: any) {
        if (e?.response?.status === 401) {
          const ok = await this.rafraichir()
          if (!ok) {
            this.logout()
            await navigateTo('/login')
          }
        }
      }
    },

    /**
     * Tente d'obtenir une nouvelle paire de jetons avec le refresh token.
     * @returns true si la session a ete renouvelee.
     */
    async rafraichir(): Promise<boolean> {
      if (!this.refreshToken) return false
      const config = useRuntimeConfig()
      try {
        const res = await $fetch<{
          accessToken: string
          refreshToken: string
          user: AuthUser
        }>('/auth/refresh', {
          method: 'POST',
          baseURL: config.public.apiBase as string,
          body: { refreshToken: this.refreshToken },
        })
        this.setSession({
          accessToken: res.accessToken,
          refreshToken: res.refreshToken,
          user: res.user ?? this.user!,
        })
        return true
      } catch {
        return false
      }
    },

    logout() {
      this.accessToken = null
      this.refreshToken = null
      this.user = null
      if (import.meta.client) {
        localStorage.removeItem('mbsc_access')
        localStorage.removeItem('mbsc_refresh')
        localStorage.removeItem('mbsc_user')
      }
    },

    hasRole(role: string): boolean {
      return this.roles.includes(role)
    },

    hasAnyRole(roles: string[]): boolean {
      return roles.some((r) => this.roles.includes(r))
    },
  },
})
