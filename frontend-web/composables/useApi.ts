import { useAuthStore } from '~/stores/auth'

/** Promesse partagée : un seul rafraîchissement simultané pour toute l'app. */
let refreshEnCours: Promise<boolean> | null = null

/**
 * Wrapper $fetch authentifié :
 * - injecte le Bearer token ;
 * - sur 401, tente UNE FOIS de rafraîchir la session (refresh token) puis
 *   rejoue la requête (retry natif ofetch), sinon déconnecte ;
 * - sur 403, laisse l'appelant afficher l'erreur (pas de déconnexion :
 *   l'utilisateur est authentifié mais n'a pas le droit).
 */
export function useApi() {
  const config = useRuntimeConfig()
  const auth = useAuthStore()

  const api = $fetch.create({
    baseURL: config.public.apiBase as string,
    // Rejoue une fois la requête après un 401 : onRequest réinjectera le
    // jeton (rafraîchi entre-temps par onResponseError).
    retry: 1,
    retryStatusCodes: [401],
    onRequest({ options }) {
      if (auth.accessToken) {
        options.headers = new Headers(options.headers as HeadersInit)
        options.headers.set('Authorization', `Bearer ${auth.accessToken}`)
      }
    },
    async onResponseError({ response }) {
      if (response.status !== 401 || !import.meta.client) {
        return
      }
      // Session expirée : tentative de renouvellement silencieux avant le retry.
      if (!refreshEnCours) {
        refreshEnCours = auth.rafraichir().finally(() => {
          refreshEnCours = null
        })
      }
      const renouvele = await refreshEnCours
      if (!renouvele) {
        auth.logout()
        await navigateTo('/login')
      }
    },
  })

  return api
}

/** Message d'erreur lisible pour l'utilisateur à partir d'une erreur API. */
export function messageErreurApi(e: unknown, fallback = 'Une erreur est survenue'): string {
  const err = e as { name?: string, response?: { status?: number }, data?: { message?: string, fieldErrors?: Record<string, string> } }
  const statut = err?.response?.status
  if (statut === 403) {
    return "Accès refusé : vous n'avez pas les droits nécessaires pour cette action."
  }
  // Serveur injoignable derrière le proxy (redémarrage lors d'une mise à
  // jour, arrêt) : la demande ne l'a pas atteint, rien n'a été enregistré.
  // Sans ce cas, l'utilisateur ne voyait que le message générique de l'écran
  // (« Erreur lors de l'enregistrement… ») et cherchait une faute de saisie.
  if (statut === 502 || statut === 503) {
    return "Le serveur est momentanément indisponible (redémarrage en cours ?) : rien n'a été enregistré. Réessayez dans une minute."
  }
  // Ici en revanche le serveur a pu traiter la demande après que le proxy a
  // cessé d'attendre, ou la connexion a pu tomber en chemin.
  if (statut === 504) {
    return "Le serveur a mis trop de temps à répondre. Vérifiez si l'opération a bien été enregistrée avant de réessayer."
  }
  if (!statut && err?.name === 'FetchError') {
    return "Impossible de joindre le serveur (connexion interrompue ?). Vérifiez si l'opération a bien été enregistrée avant de réessayer."
  }
  // Erreur de validation Bean Validation (@Valid) : le message générique
  // "Validation echouee" ne dit rien d'exploitable, le détail utile est dans
  // fieldErrors (voir GlobalExceptionHandler.handleValidation) — sans ça,
  // l'utilisateur voit une erreur sans savoir quel champ corriger.
  const champs = err?.data?.fieldErrors
  if (champs && Object.keys(champs).length > 0) {
    return Object.values(champs).join(' · ')
  }
  return err?.data?.message ?? fallback
}

/**
 * Télécharge un fichier binaire exposé par l'API (export Excel, etc.) via le
 * client authentifié, et déclenche l'enregistrement côté navigateur.
 *
 * Nécessaire car $fetch renvoie du JSON par défaut : sans `responseType:
 * 'blob'`, un classeur Excel serait décodé en texte et corrompu.
 */
export async function telechargerFichier(
  api: ReturnType<typeof useApi>,
  url: string,
  nomParDefaut: string
) {
  const reponse = await api.raw<Blob>(url, { responseType: 'blob' })
  const entete = reponse.headers.get('content-disposition') || ''
  const correspondance = entete.match(/filename="?([^"]+)"?/)
  const nomFichier = correspondance ? correspondance[1] : nomParDefaut

  const lienObjet = URL.createObjectURL(reponse._data as Blob)
  const lien = document.createElement('a')
  lien.href = lienObjet
  lien.download = nomFichier
  document.body.appendChild(lien)
  lien.click()
  lien.remove()
  URL.revokeObjectURL(lienObjet)
}
