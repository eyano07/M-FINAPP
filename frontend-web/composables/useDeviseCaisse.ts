/**
 * Devise d'affichage du journal et du rapport de caisse (bouton USD / FC).
 *
 * C'est le SERVEUR qui convertit (CaisseService.journal, RapportCaisseService) : il renvoie les montants déjà dans la
 * devise demandée, arrondis ligne par ligne et additionnés exactement. Ce composable garde seulement le choix de
 * l'utilisateur — partagé entre les pages, mémorisé dans ce navigateur — et formate les montants.
 */
export type DeviseCaisse = 'USD' | 'CDF'

const CLE_STOCKAGE = 'mbsc.caisse.devise'

export function useDeviseCaisse() {
  const devise = useState<DeviseCaisse>('caisse-devise', () => 'USD')
  const lue = useState<boolean>('caisse-devise-lue', () => false)

  if (import.meta.client && !lue.value) {
    lue.value = true
    try {
      const memorisee = localStorage.getItem(CLE_STOCKAGE)
      if (memorisee === 'USD' || memorisee === 'CDF') devise.value = memorisee
    } catch {
      // Stockage indisponible (navigation privée, données du site bloquées) : on reste en dollars.
    }
  }

  function choisir(nouvelle: DeviseCaisse) {
    devise.value = nouvelle
    try {
      localStorage.setItem(CLE_STOCKAGE, nouvelle)
    } catch {
      // Sans stockage le choix vaut pour cette visite seulement.
    }
  }

  return { devise, choisir }
}

/** Montant déjà exprimé dans `devise` : dollars à 2 décimales, francs sans décimale (comme le reste du module Caisse). */
export function fmtMontantCaisse(montant: number | null | undefined, devise: DeviseCaisse): string {
  const v = montant || 0
  return devise === 'USD'
    ? new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(v)
    : new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(v) + ' FC'
}

/** « USD » ou « FC » : l'unité à écrire dans un en-tête de colonne. */
export const uniteCaisse = (devise: DeviseCaisse): string => (devise === 'USD' ? 'USD' : 'FC')
