import { defineStore } from 'pinia'
import { usePermissionsStore, type ModuleMetier } from '~/stores/permissions'

/**
 * Le backend renvoie photoUrl en chemin relatif a la racine de l'API (meme
 * convention que ParametresEntreprise.logoUrl) : sans ce prefixe, un
 * <img :src="photoUrl"> le resoudrait relatif a l'origine du frontend Nuxt,
 * pas au backend derriere /api.
 */
function prefixerPhotoUrl(user: AuthUser): AuthUser {
  if (!user.photoUrl || !user.photoUrl.startsWith('/')) return user
  if (!import.meta.client) return user
  const apiBase = useRuntimeConfig().public.apiBase as string
  return { ...user, photoUrl: `${apiBase}${user.photoUrl}` }
}

/**
 * Les rôles qui voient tout le module Restaurant : ceux que le serveur admet en lecture
 * (RestaurantService.LECTURE), le caissier excepté. Le caissier a le module en ÉCRITURE (le plan des
 * salles, les ventes) mais ne voit du restaurant que ce qu'il lui faut au comptoir : la carte, le plan
 * des salles et les stocks (voir NavigationDrawer). Les pages réservées portent la même liste dans leur
 * definePageMeta({ roles }) : une macro de compilation, qui ne peut pas la lire ici.
 */
export const ROLES_RESTAURANT_COMPLET = ['RESP_RESTAURANT', 'DFIN', 'DG', 'DA', 'COMPTABLE', 'ADMIN']

/**
 * La cuisine (plats, fiches techniques, production, provisions) et le bar (boissons, bouteilles vides, achats de
 * boissons) : le responsable restaurant, la direction et la comptabilité voient les deux ; le responsable cuisine
 * et le responsable bar chacun le sien. Ces listes servent au menu et aux boutons — le serveur applique de toute
 * façon la même séparation (PerimetreRestaurant, gardes de RestaurantService) et filtre les listes mixtes (carte,
 * stock, analyses) sur le type des articles. Les `definePageMeta({ roles })` en recopient les valeurs (macro de
 * compilation, qui ne peut pas lire cette constante).
 */
export const ROLES_RESTAURANT_CUISINE = ['RESP_RESTAURANT', 'RESP_CUISINE', 'DFIN', 'DG', 'DA', 'COMPTABLE', 'ADMIN']
export const ROLES_RESTAURANT_BAR = ['RESP_RESTAURANT', 'RESP_BAR', 'DFIN', 'DG', 'DA', 'COMPTABLE', 'ADMIN']

export interface AuthUser {
  id: number
  email: string
  nom: string
  prenom: string
  telephone?: string | null
  photoUrl?: string | null
  roles: string[]
}

interface AuthState {
  accessToken: string | null
  refreshToken: string | null
  user: AuthUser | null
  /**
   * URL locale (blob:) vers la photo de profil, ou null si aucune photo /
   * pas encore chargee. /profil/photo exige un Bearer token qu'une balise
   * <img :src="...brute> ne peut pas fournir (contrairement au logo
   * d'entreprise, volontairement public pour la page de connexion) : la
   * photo est donc recuperee via le client authentifie puis exposee comme
   * blob URL. Jamais persiste (localStorage) : une blob URL ne survit pas
   * au rechargement de la page qui l'a creee.
   */
  photoObjectUrl: string | null
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    accessToken: null,
    refreshToken: null,
    user: null,
    photoObjectUrl: null,
  }),

  getters: {
    isAuthenticated: (s) => !!s.accessToken && !!s.user,
    /** Voit tout le module Restaurant (faux pour un compte qui n'est que caissier) — voir ROLES_RESTAURANT_COMPLET. */
    restaurantComplet: (s) => (s.user?.roles ?? []).some((r) => ROLES_RESTAURANT_COMPLET.includes(r)),
    /** Voit la cuisine du restaurant (plats, fiches techniques, provisions) — voir ROLES_RESTAURANT_CUISINE. */
    restaurantCuisine: (s) => (s.user?.roles ?? []).some((r) => ROLES_RESTAURANT_CUISINE.includes(r)),
    /** Voit le bar du restaurant (boissons, bouteilles vides) — voir ROLES_RESTAURANT_BAR. */
    restaurantBar: (s) => (s.user?.roles ?? []).some((r) => ROLES_RESTAURANT_BAR.includes(r)),
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
      const ROLES_DASHBOARD = ['ADMIN', 'DG', 'DA', 'DFIN', 'CAISSIER', 'COMPTABLE']
      if (roles.some((r) => ROLES_DASHBOARD.includes(r))) {
        return '/dashboard'
      }
      // Meme risque de boucle quand le module de la page d'accueil est coupe (ou ferme a ce compte) alors que le
      // compte garde un autre role : une fois les permissions chargees, un atterrissage inaccessible est ignore
      // au profit du suivant. Avant leur chargement on ne sait rien : on garde l'ordre habituel.
      const permissions = usePermissionsStore()
      const accessible = (module: ModuleMetier) => !permissions.charge || permissions.peutVoir(module)
      // La logistique atterrit directement sur son propre tableau de bord
      // (meme logique que GEST_PATRIMOINE ci-dessous) : le tableau de bord
      // general (tresorerie/notes de frais) n'a rien de pertinent pour elle.
      if (roles.includes('LOGISTIQUE') && accessible('LOGISTIQUE')) {
        return '/logistique'
      }
      // Le gestionnaire du patrimoine atterrit directement sur son module.
      if (roles.includes('GEST_PATRIMOINE') && accessible('PATRIMOINE')) {
        return '/patrimoine/immobilisations'
      }
      // Le responsable DRH atterrit directement sur son module (meme logique
      // que GEST_PATRIMOINE) : la paie/RH n'a pas de place dans le tableau de
      // bord financier general.
      if (roles.includes('RESP_DRH') && accessible('DRH_PAIE')) {
        return '/drh/bulletins'
      }
      // Responsable restaurant et responsable bar : le tableau de bord du bar. La page d'entree naturelle du module,
      // qu'ils ont le droit d'ouvrir — condition necessaire pour ne pas creer de boucle de redirection.
      if ((roles.includes('RESP_RESTAURANT') || roles.includes('RESP_BAR')) && accessible('RESTAURANT')) {
        return '/restaurant'
      }
      // Responsable cuisine : /restaurant est un tableau de bord de boissons, interdit a ce role ; son tableau de
      // bord est celui des provisions.
      if (roles.includes('RESP_CUISINE') && accessible('RESTAURANT')) {
        return '/restaurant/provisions/tableau-bord'
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
      this.user = prefixerPhotoUrl(payload.user)
      if (import.meta.client) {
        localStorage.setItem('mbsc_access', payload.accessToken)
        localStorage.setItem('mbsc_refresh', payload.refreshToken)
        localStorage.setItem('mbsc_user', JSON.stringify(this.user))
      }
      void this.chargerPhoto()
    },

    /**
     * Recupere la photo de profil via le client authentifie et l'expose en
     * blob URL (voir le commentaire de AuthState.photoObjectUrl). A
     * rappeler apres tout changement de photo (mettreAJourPhoto) en plus
     * des points d'entree de session (setSession/verifierSession).
     */
    async chargerPhoto() {
      if (!import.meta.client) return
      if (this.photoObjectUrl) {
        URL.revokeObjectURL(this.photoObjectUrl)
        this.photoObjectUrl = null
      }
      if (!this.user?.photoUrl || !this.accessToken) return
      try {
        const api = useApi()
        const blob = await api<Blob>('/profil/photo', { responseType: 'blob' })
        this.photoObjectUrl = URL.createObjectURL(blob)
      } catch {
        // Best-effort : l'avatar retombe sur les initiales si la photo ne charge pas.
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
        this.user = prefixerPhotoUrl({ ...this.user, ...me })
        if (import.meta.client) {
          localStorage.setItem('mbsc_user', JSON.stringify(this.user))
        }
        void this.chargerPhoto()
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
      // Le store des permissions ne recharge qu'une fois par session (son
      // flag "charge" reste a true tant que la page n'est pas rechargee) :
      // sans cette reinitialisation, le prochain compte connecte dans la
      // meme session navigateur heriterait silencieusement des droits/pages
      // du precedent (navigateTo() est une navigation cote client, elle ne
      // reinitialise pas les stores Pinia comme le ferait un rechargement).
      usePermissionsStore().reinitialiser()
      // Idem pour les préférences d'affichage (enregistrées sur le compte, rechargées à la prochaine ouverture de session).
      usePreferencesStore().reinitialiser()
    },

    hasRole(role: string): boolean {
      return this.roles.includes(role)
    },

    hasAnyRole(roles: string[]): boolean {
      return roles.some((r) => this.roles.includes(r))
    },
  },
})
