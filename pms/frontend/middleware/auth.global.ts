import { useSessionStore } from '~/stores/session'
import { appairage } from '~/utils/appairage'
import { useEtablissementStore } from '~/stores/etablissement'

const PUBLIQUES = ['/login', '/sso', '/premier-lancement', '/reserver']

/** Premier lancement (pas encore appairée) → écran de la clé ; sans session → connexion. */
export default defineNuxtRouteMiddleware(async (to) => {
  const session = useSessionStore()
  session.restaurer()
  if (appairage.appaire !== true) {
    try {
      const base = useRuntimeConfig().public.apiBase as string
      const etat = await $fetch<{ appaire: boolean }>(`${base}/appairage`)
      appairage.appaire = etat.appaire
    } catch {
      appairage.appaire = null
    }
  }
  if (appairage.appaire === false && to.path !== '/premier-lancement') return navigateTo('/premier-lancement')
  if (PUBLIQUES.includes(to.path)) return
  if (!session.connecte) return navigateTo({ path: '/login', query: to.fullPath !== '/' ? { suite: to.fullPath } : undefined })
  // Mot de passe provisoire : rien d'autre tant qu'il n'est pas changé.
  if (session.utilisateur?.doitChangerMotDePasse && to.path !== '/changer-mot-de-passe') return navigateTo('/changer-mot-de-passe')
  // Plusieurs établissements : on choisit celui où l'on travaille (gardé sur ce navigateur).
  if (to.path === '/changer-mot-de-passe' || to.path === '/choisir-etablissement') return
  const etablissements = useEtablissementStore()
  etablissements.restaurer()
  if (etablissements.choisi === undefined) {
    if (!etablissements.charge) await etablissements.charger()
    if (etablissements.choisi === undefined) return navigateTo({ path: '/choisir-etablissement', query: to.fullPath !== '/' ? { suite: to.fullPath } : undefined })
  }
})
