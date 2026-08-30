import { defineStore } from 'pinia'

/**
 * Préférence d'affichage du module Restaurant : dans quelle devise montrer
 * les montants (carte, stock, tableaux de bord).
 *
 * N'affecte que la présentation — le stockage sous-jacent ne change pas :
 * le grand livre reste en USD, Article.prixVente reste en FC. Voir
 * fmtMontant/fmtMontantDepuisFC ci-dessous pour la conversion appliquée à
 * la lecture selon la préférence choisie.
 */
interface RestaurantParametresState {
  devise: 'USD' | 'CDF'
  tauxChange: number
  charge: boolean
}

export const useRestaurantParametresStore = defineStore('restaurantParametres', {
  state: (): RestaurantParametresState => ({
    devise: 'USD',
    tauxChange: 0,
    charge: false,
  }),

  actions: {
    /**
     * Charge la préférence et le taux du jour. Ne recharge pas si c'est déjà
     * fait : chaque écran du module appelle cette action au montage, et la
     * préférence ne change qu'ici, depuis l'écran Paramètres — qui force le
     * rechargement après enregistrement.
     */
    async charger(forcer = false) {
      if (this.charge && !forcer) return
      const api = useApi()
      try {
        const [params, taux] = await Promise.all([
          api<{ deviseAffichage: 'USD' | 'CDF' }>('/restaurant/parametres'),
          api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
        ])
        this.devise = params.deviseAffichage
        this.tauxChange = taux.taux || 0
      } catch {
        // Repli sur USD sans conversion : mieux vaut un affichage correct
        // dans la devise de base qu'un écran en erreur.
      } finally {
        this.charge = true
      }
    },

    /** Formate un montant déjà tenu en USD (devise de base du grand livre — CMUP, valeur de stock, CA...). */
    fmtMontant(montantUSD?: number | null): string {
      if (montantUSD == null) return '—'
      if (this.devise === 'CDF') {
        if (this.tauxChange <= 0) return '—'
        return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(montantUSD * this.tauxChange) + ' FC'
      }
      return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD' }).format(montantUSD)
    },

    /** Formate un montant déjà tenu en FC (Article.prixVente, seul champ dans cette devise). */
    fmtMontantDepuisFC(montantFC?: number | null): string {
      if (montantFC == null) return '—'
      if (this.devise === 'CDF') {
        return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(montantFC) + ' FC'
      }
      if (this.tauxChange <= 0) return '—'
      return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD' }).format(montantFC / this.tauxChange)
    },
  },
})
