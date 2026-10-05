import { defineStore } from 'pinia'

/**
 * Préférence d'affichage du module Restaurant : dans quelle devise montrer
 * les montants (carte, stock, tableaux de bord).
 *
 * N'affecte que la présentation — le stockage sous-jacent ne change pas :
 * le grand livre reste en USD au centime, Article.prixVente reste en FC. Voir
 * fmtMontant/fmtMontantDepuisFC/fmtDans ci-dessous pour la conversion
 * appliquée à la lecture selon la préférence choisie.
 *
 * L'arrondi est fixe, comme avant V92 : francs sans décimale, dollars à 2
 * décimales, quantités à 2 décimales au plus. Il ne se règle plus.
 */
type Devise = 'USD' | 'CDF'
interface RestaurantParametresState {
  devise: Devise
  tauxChange: number
  charge: boolean
}

export const useRestaurantParametresStore = defineStore('restaurantParametres', {
  state: (): RestaurantParametresState => ({
    devise: 'CDF',
    tauxChange: 0,
    charge: false,
  }),

  actions: {
    /**
     * Charge la préférence et le taux du jour. Ne recharge pas si c'est déjà
     * fait : chaque écran du module appelle cette action au montage, et la
     * préférence ne change qu'ici, depuis l'écran Paramètres — qui force le
     * rechargement après enregistrement.
     *
     * Les deux lectures sont indépendantes : l'échec de l'une ne prive plus
     * l'écran de l'autre. Et seul un chargement complet reste en cache :
     * après un échec (serveur en redémarrage, coupure réseau), l'écran
     * suivant réessaie au lieu de rester sans taux jusqu'à la reconnexion.
     */
    async charger(forcer = false) {
      if (this.charge && !forcer) return
      const api = useApi()
      const [params, taux] = await Promise.allSettled([
        api<{ deviseAffichage: Devise }>('/restaurant/parametres'),
        api<{ taux: number }>('/admin/taux-change'),
      ])
      if (params.status === 'fulfilled') this.devise = params.value.deviseAffichage
      if (taux.status === 'fulfilled') this.tauxChange = taux.value.taux || 0
      if (this.devise === 'CDF' && this.tauxChange <= 0) {
        // Sans taux, rien ne se convertit en FC : repli sur la devise de base
        // du grand livre — mieux vaut des montants justes en dollars qu'un
        // écran de tirets. Le prochain chargement réussi rétablit les FC.
        this.devise = 'USD'
      }
      this.charge = params.status === 'fulfilled' && this.tauxChange > 0
    },

    /** Formate un montant déjà exprimé dans `devise`, sans conversion : francs sans décimale, dollars à 2 décimales. */
    fmtDans(montant: number | null | undefined, devise: Devise): string {
      if (montant == null) return '—'
      return devise === 'USD'
        ? new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD' }).format(montant)
        : new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(montant) + ' FC'
    },

    /** Formate un montant déjà tenu en USD (devise de base du grand livre — CMUP, valeur de stock, CA...). */
    fmtMontant(montantUSD?: number | null): string {
      if (montantUSD == null) return '—'
      if (this.devise === 'CDF' && this.tauxChange > 0) {
        return this.fmtDans(montantUSD * this.tauxChange, 'CDF')
      }
      return this.fmtDans(montantUSD, 'USD')
    },

    /** Formate un montant déjà tenu en FC (Article.prixVente, seul champ dans cette devise). */
    fmtMontantDepuisFC(montantFC?: number | null): string {
      if (montantFC == null) return '—'
      // Sans taux, le prix reste dans sa devise d'origine plutôt qu'un tiret.
      if (this.devise === 'CDF' || this.tauxChange <= 0) {
        return this.fmtDans(montantFC, 'CDF')
      }
      return this.fmtDans(montantFC / this.tauxChange, 'USD')
    },

    /** Formate une quantité de stock : 2 décimales au plus, sans zéros inutiles (20, 0,67). */
    fmtQuantite(quantite?: number | null): string {
      return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(quantite || 0)
    },
  },
})
