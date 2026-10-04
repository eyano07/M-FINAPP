/**
 * Chaque fois qu'un message d'erreur apparaît (v-alert de type « error »),
 * la page défile jusqu'à lui s'il est hors de vue.
 *
 * Les écrans affichent leurs erreurs en haut de page, alors que le bouton qui
 * les déclenche (Enregistrer...) est souvent tout en bas : l'utilisateur
 * cliquait, rien ne semblait se passer, et le message restait invisible. Ce
 * greffon règle le cas une fois pour toute l'application, sans toucher aux
 * écrans : il observe l'apparition de ces alertes (ou le changement de leur
 * texte) et les amène au centre de l'écran — dans une boîte de dialogue, il
 * fait défiler la boîte elle-même.
 *
 * Seules les alertes d'erreur sont concernées : Vuetify leur donne la classe
 * text-error (variantes tonal, outlined, text) ou bg-error (flat, elevated).
 */
export default defineNuxtPlugin(() => {
  const SELECTEUR = '.v-alert.text-error, .v-alert.bg-error'
  let enAttente: number | null = null

  function montrer(alerte: Element) {
    if (enAttente != null) cancelAnimationFrame(enAttente)
    // Attend la fin du rendu en cours : la position n'est juste qu'une fois
    // l'alerte réellement mise en page.
    enAttente = requestAnimationFrame(() => {
      enAttente = null
      const r = alerte.getBoundingClientRect()
      const horsDeVue = r.top < 0 || r.bottom > window.innerHeight
      if (horsDeVue) alerte.scrollIntoView({ behavior: 'smooth', block: 'center' })
    })
  }

  const observateur = new MutationObserver((mutations) => {
    for (const m of mutations) {
      if (m.type === 'characterData') {
        // Même alerte restée affichée, avec un nouveau message.
        const alerte = m.target.parentElement?.closest(SELECTEUR)
        if (alerte) return montrer(alerte)
        continue
      }
      for (const noeud of m.addedNodes) {
        if (!(noeud instanceof Element)) continue
        const alerte = noeud.matches(SELECTEUR) ? noeud : noeud.querySelector(SELECTEUR)
        if (alerte) return montrer(alerte)
      }
    }
  })
  observateur.observe(document.body, { childList: true, subtree: true, characterData: true })
})
