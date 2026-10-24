/**
 * Vrai à partir de 960 px (PC), faux en dessous (téléphone, tablette).
 *
 * Évalué dès le premier rendu — l'application est une SPA, `window` existe — puis suivi à chaque changement de taille. À préférer à
 * `useDisplay().width` de Vuetify, qui vaut 0 au tout premier rendu, quand un écran doit choisir entre deux présentations.
 */
export function useGrandEcran(largeurMin = 960) {
  const requete = typeof window !== 'undefined' ? window.matchMedia(`(min-width: ${largeurMin}px)`) : null
  const grand = ref(requete?.matches ?? true)
  const majGrand = (e: MediaQueryListEvent) => { grand.value = e.matches }
  onMounted(() => requete?.addEventListener('change', majGrand))
  onBeforeUnmount(() => requete?.removeEventListener('change', majGrand))
  return grand
}
