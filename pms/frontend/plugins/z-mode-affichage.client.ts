/**
 * Applique le mode d'affichage (clair / nuit / système) dès le démarrage. Le préfixe « z- » fait passer ce plugin
 * après celui de Vuetify (les plugins sont chargés par ordre alphabétique), dont il a besoin.
 */
export default defineNuxtPlugin((nuxtApp) => {
  nuxtApp.runWithContext(() => useModeAffichage().initialiser())
})
