import { defineStore } from 'pinia'

/**
 * Préférences du module Restaurant : dans quelle devise montrer les montants
 * (carte, stock, tableaux de bord), avec combien de chiffres après la virgule
 * pour les montants et les quantités.
 *
 * N'affecte que la présentation — le stockage sous-jacent ne change pas :
 * le grand livre reste en USD au centime, Article.prixVente reste en FC. Voir
 * fmtMontant/fmtMontantDepuisFC/fmtDans ci-dessous pour la conversion et
 * l'arrondi appliqués à la lecture selon les préférences choisies.
 */
type Devise = 'USD' | 'CDF'
interface RestaurantParametresState {
  devise: Devise
  /** Chiffres après la virgule pour les montants ; null = automatique (FC sans décimale, USD à 2). */
  decimalesMontants: number | null
  /** Chiffres après la virgule, au plus, pour les quantités de stock. */
  decimalesQuantites: number
  tauxChange: number
  charge: boolean
}

export const useRestaurantParametresStore = defineStore('restaurantParametres', {
  state: (): RestaurantParametresState => ({
    devise: 'CDF',
    decimalesMontants: null,
    decimalesQuantites: 2,
    tauxChange: 0,
    charge: false,
  }),

  actions: {
    /**
     * Charge les préférences et le taux du jour. Ne recharge pas si c'est déjà
     * fait : chaque écran du module appelle cette action au montage, et les
     * préférences ne changent qu'ici, depuis l'écran Paramètres — qui force le
     * rechargement après enregistrement.
     */
    async charger(forcer = false) {
      if (this.charge && !forcer) return
      const api = useApi()
      try {
        const [params, taux] = await Promise.all([
          api<{
            deviseAffichage: Devise
            decimalesMontants: number | null
            decimalesQuantites: number
          }>('/restaurant/parametres'),
          api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
        ])
        this.devise = params.deviseAffichage
        this.decimalesMontants = params.decimalesMontants ?? null
        this.decimalesQuantites = params.decimalesQuantites ?? 2
        this.tauxChange = taux.taux || 0
      } catch {
        // FC par défaut, mais sans les paramètres ni le taux on ne peut rien
        // convertir : repli sur USD sans conversion — mieux vaut un affichage
        // correct dans la devise de base qu'un écran de tirets.
        this.devise = 'USD'
      } finally {
        this.charge = true
      }
    },

    /** Chiffres après la virgule pour un montant dans cette devise : le réglage, ou à défaut 0 en FC et 2 en dollars. */
    decimales(devise: Devise): number {
      return this.decimalesMontants ?? (devise === 'CDF' ? 0 : 2)
    },

    /** Formate un montant déjà exprimé dans `devise`, à l'arrondi choisi — sans conversion. */
    fmtDans(montant: number | null | undefined, devise: Devise): string {
      if (montant == null) return '—'
      const d = this.decimales(devise)
      return devise === 'USD'
        ? new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD', minimumFractionDigits: d, maximumFractionDigits: d }).format(montant)
        : new Intl.NumberFormat('fr-FR', { minimumFractionDigits: d, maximumFractionDigits: d }).format(montant) + ' FC'
    },

    /** Formate un montant déjà tenu en USD (devise de base du grand livre — CMUP, valeur de stock, CA...). */
    fmtMontant(montantUSD?: number | null): string {
      if (montantUSD == null) return '—'
      if (this.devise === 'CDF') {
        if (this.tauxChange <= 0) return '—'
        return this.fmtDans(montantUSD * this.tauxChange, 'CDF')
      }
      return this.fmtDans(montantUSD, 'USD')
    },

    /** Formate un montant déjà tenu en FC (Article.prixVente, seul champ dans cette devise). */
    fmtMontantDepuisFC(montantFC?: number | null): string {
      if (montantFC == null) return '—'
      if (this.devise === 'CDF') {
        return this.fmtDans(montantFC, 'CDF')
      }
      if (this.tauxChange <= 0) return '—'
      return this.fmtDans(montantFC / this.tauxChange, 'USD')
    },

    /** Formate une quantité de stock, au plus au nombre de décimales choisi (sans zéros inutiles : 20, 0,67). */
    fmtQuantite(quantite?: number | null): string {
      return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: this.decimalesQuantites }).format(quantite || 0)
    },
  },
})
