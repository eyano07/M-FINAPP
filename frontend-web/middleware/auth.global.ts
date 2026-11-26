import { useAuthStore } from '~/stores/auth'
import { usePermissionsStore, type ModuleMetier, type NiveauPermission } from '~/stores/permissions'
import { accueilApplication, estApplicationMobile, routePermiseDansApplication } from '~/utils/applicationMobile'

/**
 * Garde globale : restaure la session et protege les routes.
 * Les pages publiques sont definies dans PUBLIC_ROUTES.
 */
const PUBLIC_ROUTES = ['/login']
/** Page où reste un utilisateur au mot de passe provisoire (voir AuthUser.doitChangerMotDePasse). */
const PAGE_MOT_DE_PASSE = '/changer-mot-de-passe'
/** Page d'activation de la double authentification quand elle est obligatoire (voir AuthUser.doitActiverDoubleAuth). */
const PAGE_DOUBLE_AUTH = '/double-authentification'

/**
 * Anciens écrans « Grand Livre » et « Balance » retirés le 15/11/2026 : ils doublaient « GL par compte » et « Balance
 * de vérification » de la rubrique Comptabilité. Une adresse ancienne (carte du tableau de bord, favori) y mène.
 */
const ANCIENNES_ROUTES: Record<string, string> = {
  '/grand-livre': '/comptabilite/grand-livre',
  '/balance': '/comptabilite/balance-verification',
}

export default defineNuxtRouteMiddleware(async (to) => {
  const remplacement = ANCIENNES_ROUTES[to.path.replace(/\/$/, '')]
  if (remplacement) {
    return navigateTo({ path: remplacement, query: to.query })
  }

  const auth = useAuthStore()

  // Restaure depuis le localStorage cote client
  if (import.meta.client && !auth.isAuthenticated) {
    auth.restore()
  }

  const isPublic = PUBLIC_ROUTES.includes(to.path)

  if (!auth.isAuthenticated && !isPublic) {
    return navigateTo('/login')
  }

  // Mot de passe provisoire (choisi par l'administrateur) : l'utilisateur reste sur la page de changement tant qu'il
  // ne l'a pas remplacé ; rien d'autre n'est chargé (le serveur refuserait de toute façon : 403 MOT_DE_PASSE_A_CHANGER).
  if (auth.isAuthenticated && auth.user?.doitChangerMotDePasse) {
    return to.path === PAGE_MOT_DE_PASSE ? undefined : navigateTo(PAGE_MOT_DE_PASSE)
  }
  if (to.path === PAGE_MOT_DE_PASSE) {
    return navigateTo(auth.isAuthenticated ? auth.homeRoute : '/login')
  }

  // Double authentification obligatoire pour ce rôle (audit SYSCOHADA, C-23) : rien d'autre avant son activation.
  if (auth.isAuthenticated && auth.user?.doitActiverDoubleAuth) {
    return to.path === PAGE_DOUBLE_AUTH ? undefined : navigateTo(PAGE_DOUBLE_AUTH)
  }

  if (auth.isAuthenticated && to.path === '/login') {
    return navigateTo(auth.homeRoute)
  }

  // Application Android : tableau de bord, notes de frais et comptabilité ; pour le caissier, en plus la trésorerie, la
  // vente, les clients et les fournisseurs (le serveur refuse le reste des saisies). L'accueil choisi est une page que le
  // rôle peut ouvrir : pas de boucle avec les gardes ci-dessous.
  if (auth.isAuthenticated && estApplicationMobile() && !routePermiseDansApplication(to.path, auth.user?.roles ?? [])) {
    const accueil = accueilApplication(auth.user?.roles ?? [])
    if (to.path !== accueil) return navigateTo(accueil)
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
    const rolesStore = useRolesStore()
    if (!rolesStore.charge) {
      await rolesStore.charger()
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
