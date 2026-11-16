/**
 * Activation du mode local (Administration › Serveur local et synchronisation) : les comptes de ce serveur sont remplacés
 * par ceux du serveur en ligne, la session de l'administrateur tombe donc en cours de route et useApi renvoie à la page
 * de connexion. Ce repère, gardé dans l'onglet pendant 30 minutes, permet à la page de connexion de l'expliquer puis de
 * ramener l'administrateur sur la page de synchronisation, où il voit le résultat.
 */
const CLE_STOCKAGE = 'mbsc.activation-mode-local'
const DUREE_MS = 30 * 60_000

export function noterActivationModeLocal() {
  try {
    sessionStorage.setItem(CLE_STOCKAGE, String(Date.now()))
  } catch {
    // Stockage indisponible : la page de connexion n'affichera pas l'explication, l'activation n'en dépend pas.
  }
}

export function oublierActivationModeLocal() {
  try {
    sessionStorage.removeItem(CLE_STOCKAGE)
  } catch {
    // Stockage indisponible : rien à oublier.
  }
}

/** Vrai si une activation a été lancée depuis cet onglet il y a moins de 30 minutes. */
export function activationModeLocalRecente(): boolean {
  try {
    const debut = Number(sessionStorage.getItem(CLE_STOCKAGE))
    return debut > 0 && Date.now() - debut < DUREE_MS
  } catch {
    return false
  }
}
