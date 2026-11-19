/** État de l'appairage avec M-FINAPP, lu une fois par la garde des pages (null : inconnu). */
export const appairage = { appaire: null as boolean | null }

export function marquerAppaire() {
  appairage.appaire = true
}
