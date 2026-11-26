import { useSessionStore } from '~/stores/session'

let rafraichissementEnCours: Promise<boolean> | null = null

/** Client de l'API de l'hôtellerie : jeton M-FINAPP, un renouvellement silencieux sur 401, sinon retour à la connexion. */
export function useApi() {
  const config = useRuntimeConfig()
  const session = useSessionStore()
  return $fetch.create({
    baseURL: config.public.apiBase as string,
    retry: 1,
    retryStatusCodes: [401],
    onRequest({ options }) {
      if (session.acces) {
        options.headers = new Headers(options.headers as HeadersInit)
        options.headers.set('Authorization', `Bearer ${session.acces}`)
      }
    },
    async onResponseError({ response }) {
      // Mot de passe provisoire : M-FINAPP n'ouvre que son changement.
      if (response.status === 403 && (response._data as { code?: string } | undefined)?.code === 'MOT_DE_PASSE_A_CHANGER') {
        if (session.utilisateur) session.utilisateur = { ...session.utilisateur, doitChangerMotDePasse: true }
        await navigateTo('/changer-mot-de-passe')
        return
      }
      if (response.status !== 401) return
      if (!rafraichissementEnCours) {
        rafraichissementEnCours = session.rafraichir().finally(() => { rafraichissementEnCours = null })
      }
      if (!(await rafraichissementEnCours)) {
        session.fermer()
        await navigateTo('/login')
      }
    },
  })
}

/** Message lisible d'une erreur d'API (celui du serveur de l'hôtellerie ou de M-FINAPP). */
export function messageErreur(e: unknown, defaut = 'Une erreur est survenue.'): string {
  const err = e as { name?: string, response?: { status?: number }, data?: { message?: string } }
  if (err?.data?.message) return err.data.message
  const statut = err?.response?.status
  if (statut === 502 || statut === 503 || statut === 504) return "Serveur momentanément indisponible : réessayez dans un instant."
  if (!statut && err?.name === 'FetchError') return 'Impossible de joindre le serveur (connexion interrompue ?).'
  return defaut
}

/** Clé d'idempotence : la même opération rejouée (réseau coupé) ne produit jamais deux écritures. */
export function nouvelleCle(prefixe: string): string {
  const alea = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `${prefixe}-${alea}`
}
