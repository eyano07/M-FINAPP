import { useAuthStore } from '~/stores/auth'
import { estApplicationMobile, pontApplicationMobile } from '~/utils/applicationMobile'

/**
 * Application Android : inscrit le téléphone aux notifications push du compte connecté (POST /appareils) et l'en
 * retire à la déconnexion. Le jeton Firebase est donné par l'application (window.FinappAndroid.jetonPush) ; il peut
 * arriver quelques secondes après l'ouverture, d'où les nouvelles tentatives.
 */
export default defineNuxtPlugin(() => {
  if (!estApplicationMobile()) return
  document.documentElement.classList.add('application-mobile')

  // Défilement fluide : pendant le mouvement, la page ne réagit plus au survol ni aux transitions (responsive.scss,
  // html.en-defilement). Écouteur passif : il ne retarde jamais le défilement lui-même.
  let finDefilement: ReturnType<typeof setTimeout> | undefined
  window.addEventListener('scroll', () => {
    const html = document.documentElement
    if (!html.classList.contains('en-defilement')) html.classList.add('en-defilement')
    clearTimeout(finDefilement)
    finDefilement = setTimeout(() => html.classList.remove('en-defilement'), 150)
  }, { passive: true })

  const auth = useAuthStore()
  const config = useRuntimeConfig()
  const base = config.public.apiBase as string
  let inscrit = ''   // « idUtilisateur:jeton » déjà inscrit dans cette session

  async function inscrire(essai = 0) {
    const pont = pontApplicationMobile()
    if (!pont || !auth.isAuthenticated || !auth.accessToken) return
    const jeton = pont.jetonPush()
    if (!jeton) {
      if (essai < 5) setTimeout(() => inscrire(essai + 1), 3000)
      return
    }
    const cle = `${auth.user?.id}:${jeton}`
    if (cle === inscrit) return
    try {
      await $fetch(`${base}/appareils`, {
        method: 'POST',
        body: { jeton },
        headers: { Authorization: `Bearer ${auth.accessToken}` },
      })
      inscrit = cle
    } catch {
      // Serveur injoignable ou ancien serveur sans /appareils : nouvel essai à la prochaine ouverture de session.
    }
  }

  // Widgets de l'écran d'accueil : un jeton propre au téléphone, qui ne lit que le résumé du compte (/widget/resume).
  // Demandé à la connexion (ou quand un autre compte se connecte sur ce téléphone), retiré à la déconnexion.
  async function jetonWidget() {
    const pont = pontApplicationMobile()
    if (!pont?.definirJetonWidget || !auth.isAuthenticated || !auth.accessToken || !auth.user?.id) return
    const compte = String(auth.user.id)
    const actuel = pont.jetonWidget?.() || ''
    if (actuel && pont.utilisateurWidget?.() === compte) return
    try {
      const r = await $fetch<{ jeton: string }>(`${base}/appareils/widget`, {
        method: 'POST',
        body: { ancien: actuel || null },
        headers: { Authorization: `Bearer ${auth.accessToken}` },
      })
      if (r?.jeton) pont.definirJetonWidget(r.jeton, compte)
    } catch {
      // Ancien serveur sans widgets, ou serveur injoignable : nouvel essai à la prochaine ouverture.
    }
  }

  // Déconnexion : le jeton d'accès est encore là quand l'action démarre ; le téléphone quitte ce compte.
  auth.$onAction(({ name }) => {
    if (name !== 'logout') return
    const pont = pontApplicationMobile()
    const jeton = pont?.jetonPush()
    const widget = pont?.jetonWidget?.() || ''
    const acces = auth.accessToken
    inscrit = ''
    pont?.definirJetonWidget?.('', '')
    if (!acces) return
    if (widget) {
      $fetch(`${base}/appareils/widget/retrait`, {
        method: 'POST',
        body: { jeton: widget },
        headers: { Authorization: `Bearer ${acces}` },
      }).catch(() => {})
    }
    if (!jeton) return
    $fetch(`${base}/appareils/retrait`, {
      method: 'POST',
      body: { jeton },
      headers: { Authorization: `Bearer ${acces}` },
    }).catch(() => {})
  })

  watch(() => auth.accessToken, (acces) => { if (acces) { inscrire(); jetonWidget() } }, { immediate: true })
})
