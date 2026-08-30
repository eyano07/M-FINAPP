import { defineStore } from 'pinia'
import { useAuthStore } from '~/stores/auth'

export interface NotificationItem {
  id: number
  type: string
  titre: string
  message: string
  lien?: string | null
  lue: boolean
  dateCreation: string
}

interface NotificationsState {
  notifications: NotificationItem[]
  nonLues: number
  /** AbortController de la connexion SSE en cours (null = pas connecte). */
  controleur: AbortController | null
  /** Passe a false uniquement sur deconnexion volontaire (logout) : coupe les reconnexions automatiques. */
  actif: boolean
  delaiReconnexion: number
}

const DELAI_RECONNEXION_MIN = 2000
const DELAI_RECONNEXION_MAX = 30000

export const useNotificationsStore = defineStore('notifications', {
  state: (): NotificationsState => ({
    notifications: [],
    nonLues: 0,
    controleur: null,
    actif: false,
    delaiReconnexion: DELAI_RECONNEXION_MIN,
  }),

  actions: {
    async charger() {
      const api = useApi()
      try {
        const page = await api<{ notifications: NotificationItem[]; nonLues: number }>('/notifications')
        this.notifications = page.notifications
        this.nonLues = page.nonLues
      } catch {
        // Best-effort : la cloche reste utilisable (juste vide) si l'appel initial echoue.
      }
    },

    async marquerLue(id: number) {
      const notif = this.notifications.find((n) => n.id === id)
      if (!notif || notif.lue) return
      notif.lue = true
      this.nonLues = Math.max(0, this.nonLues - 1)
      const api = useApi()
      try {
        await api(`/notifications/${id}/lue`, { method: 'POST' })
      } catch {
        // Reconciliation best-effort : un prochain charger() corrigera l'etat si l'appel a echoue.
      }
    },

    async marquerToutesLues() {
      this.notifications.forEach((n) => { n.lue = true })
      this.nonLues = 0
      const api = useApi()
      try {
        await api('/notifications/lues', { method: 'POST' })
      } catch {
        // Idem : best-effort, prochain charger() reconcilie.
      }
    },

    /** Ajoute une notification recue en direct (flux SSE) en tete de liste. */
    recevoir(notif: NotificationItem) {
      this.notifications.unshift(notif)
      if (this.notifications.length > 30) this.notifications.length = 30
      if (!notif.lue) this.nonLues++
    },

    /**
     * Ouvre le flux temps reel. EventSource natif ne peut pas joindre l'en-tete
     * Authorization : on lit donc le flux via fetch() + ReadableStream, avec
     * reconnexion automatique (le serveur ferme la connexion toutes les 30 min).
     */
    async connecter() {
      if (this.actif) return
      this.actif = true
      this.ecouter()
    },

    async ecouter() {
      if (!this.actif) return
      const auth = useAuthStore()
      if (!auth.accessToken) {
        this.actif = false
        return
      }

      const config = useRuntimeConfig()
      const controleur = new AbortController()
      this.controleur = controleur

      try {
        const reponse = await fetch(`${config.public.apiBase}/notifications/stream`, {
          headers: { Authorization: `Bearer ${auth.accessToken}` },
          signal: controleur.signal,
        })

        if (reponse.status === 401) {
          const renouvele = await auth.rafraichir()
          if (!renouvele) { this.actif = false; return }
          return this.reconnecterApresDelai(200)
        }
        if (!reponse.ok || !reponse.body) {
          return this.reconnecterApresDelai()
        }

        this.delaiReconnexion = DELAI_RECONNEXION_MIN
        await this.lireFlux(reponse.body)

        // Fin normale du flux (timeout serveur cote emetteur) : reconnexion immediate.
        if (this.actif) this.reconnecterApresDelai(200)
      } catch (e: any) {
        if (e?.name === 'AbortError') return // deconnexion volontaire
        this.reconnecterApresDelai()
      }
    },

    async lireFlux(body: ReadableStream<Uint8Array>) {
      const reader = body.getReader()
      const decoder = new TextDecoder()
      let tampon = ''

      while (this.actif) {
        const { value, done } = await reader.read()
        if (done) break
        tampon += decoder.decode(value, { stream: true })

        let indexSeparateur: number
        while ((indexSeparateur = tampon.indexOf('\n\n')) !== -1) {
          const bloc = tampon.slice(0, indexSeparateur)
          tampon = tampon.slice(indexSeparateur + 2)
          this.traiterBlocEvenement(bloc)
        }
      }
    },

    traiterBlocEvenement(bloc: string) {
      let evenement = 'message'
      const lignesData: string[] = []
      for (const ligne of bloc.split('\n')) {
        if (ligne.startsWith('event:')) evenement = ligne.slice(6).trim()
        else if (ligne.startsWith('data:')) lignesData.push(ligne.slice(5).trim())
      }
      if (evenement !== 'notification' || lignesData.length === 0) return
      try {
        const notif = JSON.parse(lignesData.join('\n')) as NotificationItem
        this.recevoir(notif)
      } catch {
        // Trame malformee : ignoree plutot que de casser le flux.
      }
    },

    reconnecterApresDelai(delaiForce?: number) {
      if (!this.actif) return
      const delai = delaiForce ?? this.delaiReconnexion
      this.delaiReconnexion = Math.min(this.delaiReconnexion * 2, DELAI_RECONNEXION_MAX)
      setTimeout(() => { if (this.actif) this.ecouter() }, delai)
    },

    deconnecter() {
      this.actif = false
      this.controleur?.abort()
      this.controleur = null
    },
  },
})
