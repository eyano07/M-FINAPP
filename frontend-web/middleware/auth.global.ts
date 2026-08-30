import { useAuthStore } from '~/stores/auth'
import { usePermissionsStore, type ModuleMetier, type NiveauPermission } from '~/stores/permissions'

/**
 * Garde globale : restaure la session et protege les routes.
 * Les pages publiques sont definies dans PUBLIC_ROUTES.
 */
const PUBLIC_ROUTES = ['/login']

export default defineNuxtRouteMiddleware(async (to) => {
  const auth = useAuthStore()

  // Restaure depuis le localStorage cote client
  if (import.meta.client && !auth.isAuthenticated) {
    auth.restore()
  }

  const isPublic = PUBLIC_ROUTES.includes(to.path)

  if (!auth.isAuthenticated && !isPublic) {
    return navigateTo('/login')
  }

  if (auth.isAuthenticated && to.path === '/login') {
    return navigateTo(auth.homeRoute)
  }

  // Controle d'acces par role via meta.roles (pages hors modules configurables :
  // tableau de bord, administration...).
  const requiredRoles = (to.meta.roles as string[] | undefined) ?? []
  if (requiredRoles.length && !auth.hasAnyRole(requiredRoles)) {
    return navigateTo(auth.homeRoute)
  }

  // Chargee une seule fois par session (mise en cache dans le store), des
  // l'authentification et quelle que soit la page visee : la barre laterale
  // (tous les liens de modules) en depend des le premier affichage, pas
  // seulement les pages elles-memes.
  if (auth.isAuthenticated) {
    const permissions = usePermissionsStore()
    if (!permissions.charge) {
      await permissions.charger()
    }
  }

  // Controle d'acces par module configurable (voir meta.module) : la
  // correspondance role -> niveau est administrable (page Permissions).
  const requiredModule = to.meta.module as ModuleMetier | undefined
  if (requiredModule && auth.isAuthenticated) {
    const permissions = usePermissionsStore()
    const niveauRequis = (to.meta.niveau as NiveauPermission | undefined) ?? 'LECTURE'
    if (!permissions.satisfait(permissions.niveau(requiredModule), niveauRequis)) {
      return navigateTo(auth.homeRoute)
    }
  }
})
