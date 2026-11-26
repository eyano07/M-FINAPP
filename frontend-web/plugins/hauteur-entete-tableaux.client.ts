/**
 * Ascenseur des tableaux sous la ligne d'en-tête (21/11/2026) : la hauteur de l'en-tête fixe de chaque tableau qui
 * défile est mesurée et posée sur son conteneur (--entete-h) ; la feuille de style décale d'autant le haut du rail de
 * l'ascenseur (classroom.scss). Mesure tenue à jour quand l'en-tête change de taille (titre sur deux lignes, fenêtre).
 */
export default defineNuxtPlugin(() => {
  const suivis = new WeakSet<Element>()
  const mesure = new ResizeObserver((entrees) => {
    for (const e of entrees) {
      const conteneur = (e.target as HTMLElement).closest('.v-table__wrapper, div.tableau-fixe') as HTMLElement | null
      if (conteneur) conteneur.style.setProperty('--entete-h', `${Math.round((e.target as HTMLElement).offsetHeight)}px`)
    }
  })
  const rechercher = () => {
    document.querySelectorAll('.v-table__wrapper > table > thead, div.tableau-fixe table > thead').forEach((thead) => {
      if (suivis.has(thead)) return
      suivis.add(thead)
      mesure.observe(thead)
    })
  }
  let attente = 0
  new MutationObserver(() => {
    if (attente) return
    attente = window.setTimeout(() => { attente = 0; rechercher() }, 150)
  }).observe(document.body, { childList: true, subtree: true })
  rechercher()
})
