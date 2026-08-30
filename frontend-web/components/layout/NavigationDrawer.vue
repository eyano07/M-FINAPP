<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'
import { usePermissionsStore, type ModuleMetier, type NiveauPermission } from '~/stores/permissions'
import { useParametresStore } from '~/stores/parametres'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const auth = useAuthStore()
const permissions = usePermissionsStore()
const parametresStore = useParametresStore()

interface NavItem {
  title: string
  icon: string
  to: string
  /** Pages hors modules configurables (tableau de bord, administration...). */
  roles?: string[]
  /** Pages des modules metier desactivables/permissionnables (page Modules/Permissions). */
  module?: ModuleMetier
  /** Niveau minimal pour voir le lien (LECTURE par defaut : suffisant pour consulter). */
  niveau?: NiveauPermission
  /** États financiers consultés en premier reflexe (Bilan, Balance...) : mis en avant dans le menu. */
  essentiel?: boolean
}

// Le Directeur metier (DIRECTEUR) n'a pas acces au tableau de bord
// (statistiques/graphiques globaux) : il ne voit que ses notes de frais.
const NON_DIRECTEUR = ['ADMIN', 'DG', 'DA', 'DFIN', 'CAISSIER', 'LOGISTIQUE', 'COMPTABLE']
const items: NavItem[] = [
  { title: 'Tableau de bord', icon: 'mdi-view-dashboard-outline', to: '/dashboard', roles: NON_DIRECTEUR },
  { title: 'Notes de frais', icon: 'mdi-receipt-text-outline', to: '/notes-frais' },
  // Le comptable consulte la tresorerie (journal/grand livre/balance) mais
  // n'opere pas la caisse (encaissement/decaissement direct) : cet ecran-ci
  // reste reserve aux roles qui en avaient deja l'usage.
  // Ecran d'operation (encaissement/decaissement), pas de consultation : reserve
  // aux roles qui l'operent reellement (niveau ECRITURE requis, comme la page).
  { title: 'Caisse', icon: 'mdi-cash-register', to: '/caisse', module: 'CAISSE', niveau: 'ECRITURE', roles: ['CAISSIER', 'ADMIN'] },
  { title: 'Journal de caisse', icon: 'mdi-notebook-outline', to: '/caisse/journal', module: 'CAISSE' },
  { title: 'Banque', icon: 'mdi-bank', to: '/banque', module: 'BANQUE' },
  { title: 'Journal de banque', icon: 'mdi-notebook-outline', to: '/banque/journal', module: 'BANQUE' },
  { title: 'Mobile Money', icon: 'mdi-cellphone', to: '/mobile-money', module: 'MOBILE_MONEY' },
  { title: 'Journal de mobile money', icon: 'mdi-notebook-outline', to: '/mobile-money/journal', module: 'MOBILE_MONEY' },
  { title: 'Grand Livre', icon: 'mdi-book-open-variant', to: '/grand-livre', module: 'CAISSE' },
  { title: 'Balance', icon: 'mdi-scale-balance', to: '/balance', module: 'CAISSE', essentiel: true },
  // CAISSIER a COMPTABILITE en LECTURE (pour Balance/Compte de resultat
  // uniquement) mais ne voit pas les Budgets.
  { title: 'Budgets', icon: 'mdi-chart-box-outline', to: '/budgets', module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] },
]

const comptabiliteItems: NavItem[] = [
  // Plan comptable reste hors systeme de modules : reference partagee
  // consultee par la caisse/la logistique bien au-dela du seul module
  // Comptabilite (voir definePageMeta de la page elle-meme, inchange).
  { title: 'Plan comptable', icon: 'mdi-format-list-numbered', to: '/admin/plan-comptable', roles: ['DFIN', 'DA', 'DG', 'LOGISTIQUE', 'COMPTABLE', 'ADMIN'] },
  // CAISSIER a COMPTABILITE en LECTURE (pour Balance/Compte de resultat
  // uniquement) mais ne voit pas les Pieces comptables.
  { title: 'Pièces comptables', icon: 'mdi-file-document-edit-outline', to: '/comptabilite/pieces', module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] },
  // Creation de piece : reservee au DFIN cote serveur (ADMIN ne modifie que
  // les pages dediees a l'administration).
  { title: "Solde d'ouverture", icon: 'mdi-flag-outline', to: '/comptabilite/solde-ouverture', module: 'COMPTABILITE', niveau: 'ECRITURE', roles: ['DFIN'] },
  // CAISSIER a COMPTABILITE en LECTURE (pour Balance/Compte de resultat
  // uniquement) mais ne voit pas le GL par compte ni le Livre-journal.
  { title: 'GL par compte', icon: 'mdi-book-account-outline', to: '/comptabilite/grand-livre', module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] },
  // Balance de verification et Compte de resultat : accessibles au CAISSIER.
  { title: 'Balance de vérification', icon: 'mdi-table-check', to: '/comptabilite/balance-verification', module: 'COMPTABILITE', essentiel: true },
  { title: 'Livre-journal', icon: 'mdi-notebook-outline', to: '/comptabilite/livre-journal', module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] },
  { title: 'Compte de résultat', icon: 'mdi-finance', to: '/comptabilite/compte-resultat', module: 'COMPTABILITE', essentiel: true },
  // DA exclu explicitement de la consultation du Bilan.
  { title: 'Bilan', icon: 'mdi-scale-balance', to: '/comptabilite/bilan', module: 'COMPTABILITE', essentiel: true, roles: ['ADMIN', 'DFIN', 'DG', 'COMPTABLE'] },
  // COMPTABLE, DA et DG exclus explicitement.
  { title: 'États financiers', icon: 'mdi-file-document-multiple-outline', to: '/comptabilite/etats-financiers', module: 'COMPTABILITE', essentiel: true, roles: ['ADMIN', 'DFIN'] },
  // Consultation ouverte a DA/DG ; constitution et reprise reservees au DFIN.
  { title: 'Provisions', icon: 'mdi-shield-alert-outline', to: '/comptabilite/provisions', module: 'COMPTABILITE', roles: ['DFIN', 'DA', 'DG', 'COMPTABLE', 'ADMIN'] },
  // DA exclu explicitement de la consultation de la Gestion TVA.
  { title: 'Gestion TVA', icon: 'mdi-percent-box-outline', to: '/comptabilite/tva', module: 'COMPTABILITE', roles: ['DFIN', 'DG', 'COMPTABLE', 'ADMIN'] },
  // PeriodeComptableService.definirDateCloture est reserve a ADMIN.
  { title: 'Clôture de période', icon: 'mdi-lock-outline', to: '/comptabilite/cloture', module: 'COMPTABILITE', niveau: 'ECRITURE', roles: ['ADMIN'] },
]

const venteItems: NavItem[] = [
  { title: 'Ventes', icon: 'mdi-cart-outline', to: '/ventes', module: 'VENTES' },
  { title: 'Clients', icon: 'mdi-account-multiple-outline', to: '/ventes/clients', module: 'VENTES' },
]

const logistiqueItems: NavItem[] = [
  { title: 'Articles', icon: 'mdi-package-variant-closed', to: '/logistique/articles', module: 'LOGISTIQUE' },
  { title: 'Entrepôts', icon: 'mdi-warehouse', to: '/logistique/entrepots', module: 'LOGISTIQUE' },
  // Mouvements et grand livre stock : StockService en reserve la lecture aux
  // roles logistiques et financiers. Le caissier a bien le module en LECTURE
  // (catalogue + disponibilite necessaires a une vente) mais pas ces deux
  // ecrans, qui lui renverraient 403.
  { title: 'Mouvements', icon: 'mdi-swap-horizontal-bold', to: '/logistique/mouvements', module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'ADMIN'] },
  { title: 'État du stock', icon: 'mdi-clipboard-list-outline', to: '/logistique/stock', module: 'LOGISTIQUE' },
  { title: 'Grand livre stock', icon: 'mdi-book-open-page-variant-outline', to: '/logistique/stock/grand-livre', module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'ADMIN'] },
]
const patrimoineItems: NavItem[] = [
  { title: 'Immobilisations', icon: 'mdi-office-building-cog-outline', to: '/patrimoine/immobilisations', module: 'PATRIMOINE' },
  { title: 'Amortissements', icon: 'mdi-chart-timeline-variant-shimmer', to: '/patrimoine/amortissements', module: 'PATRIMOINE' },
  { title: 'Consommables', icon: 'mdi-package-variant', to: '/patrimoine/consommables', module: 'PATRIMOINE' },
]
const transportItems: NavItem[] = [
  { title: 'Véhicules', icon: 'mdi-truck-outline', to: '/transport/vehicules', module: 'TRANSPORT' },
  { title: 'Trajets', icon: 'mdi-map-marker-path', to: '/transport/trajets', module: 'TRANSPORT' },
  { title: 'Dépenses', icon: 'mdi-fuel', to: '/transport/depenses', module: 'TRANSPORT' },
]

// Toutes les pages du restaurant appellent /restaurant/* et jamais
// /logistique/* : le responsable restaurant n'a pas le module LOGISTIQUE, le
// filtre serveur lui renverrait un 403 sur sa propre carte.
const restaurantItems: NavItem[] = [
  { title: 'Tableau de bord', icon: 'mdi-view-dashboard-outline', to: '/restaurant', module: 'RESTAURANT' },
  { title: 'Carte', icon: 'mdi-silverware-fork-knife', to: '/restaurant/carte', module: 'RESTAURANT' },
  { title: 'Stock cuisine & bar', icon: 'mdi-clipboard-list-outline', to: '/restaurant/stock', module: 'RESTAURANT' },
  { title: 'Provisions', icon: 'mdi-sack', to: '/restaurant/provisions', module: 'RESTAURANT' },
  { title: 'Tableau de bord Provisions', icon: 'mdi-chart-box-outline', to: '/restaurant/provisions/tableau-bord', module: 'RESTAURANT' },
  { title: 'Réceptions', icon: 'mdi-truck-delivery-outline', to: '/restaurant/receptions', module: 'RESTAURANT', niveau: 'ECRITURE' },
  { title: 'Bouteilles vides', icon: 'mdi-bottle-wine-outline', to: '/restaurant/emballages', module: 'RESTAURANT' },
  { title: 'Mouvements', icon: 'mdi-swap-horizontal-bold', to: '/restaurant/emballages/mouvements', module: 'RESTAURANT' },
  { title: 'Paramètres', icon: 'mdi-cog-outline', to: '/restaurant/parametres', module: 'RESTAURANT' },
]

const drhItems: NavItem[] = [
  { title: 'Employés', icon: 'mdi-account-multiple-outline', to: '/drh/employes', module: 'DRH_PERSONNEL' },
  { title: 'Présences', icon: 'mdi-calendar-check-outline', to: '/drh/presences', module: 'DRH_PRESENCES' },
  { title: 'Fiche manuelle', icon: 'mdi-clipboard-text-outline', to: '/drh/presences/fiche-manuelle', module: 'DRH_PRESENCES' },
  { title: 'Sorties de travailleurs', icon: 'mdi-exit-run', to: '/drh/sorties', module: 'DRH_PRESENCES' },
  { title: 'Bulletins de paie', icon: 'mdi-cash-multiple', to: '/drh/bulletins', module: 'DRH_PAIE' },
  { title: 'Paramètres de paie', icon: 'mdi-cog-outline', to: '/drh/parametres-paie', module: 'DRH_PAIE', niveau: 'ECRITURE' },
  { title: 'Ordres de mission', icon: 'mdi-map-marker-path', to: '/drh/missions', module: 'DRH_MISSIONS' },
  { title: 'Sites opérationnels', icon: 'mdi-office-building-marker-outline', to: '/drh/sites', module: 'DRH_MISSIONS' },
  { title: 'Rotations', icon: 'mdi-account-sync-outline', to: '/drh/rotations', module: 'DRH_MISSIONS' },
]

const adminItems: NavItem[] = [
  { title: 'Utilisateurs', icon: 'mdi-account-group-outline', to: '/admin/users', roles: ['ADMIN'] },
  { title: 'Banques & Mobile Money', icon: 'mdi-bank-outline', to: '/admin/etablissements', roles: ['ADMIN'] },
  { title: 'Taux de change', icon: 'mdi-currency-usd', to: '/admin/taux-change', roles: ['ADMIN'] },
  { title: 'Taux de TVA', icon: 'mdi-percent-outline', to: '/admin/taux-tva', roles: ['ADMIN'] },
  { title: 'Paramètres', icon: 'mdi-cog-outline', to: '/admin/parametres', roles: ['ADMIN'] },
  { title: 'Modules', icon: 'mdi-view-grid-outline', to: '/admin/modules', roles: ['ADMIN'] },
  { title: 'Permissions', icon: 'mdi-shield-key-outline', to: '/admin/permissions', roles: ['ADMIN'] },
  { title: 'Importer un journal', icon: 'mdi-database-import-outline', to: '/admin/import-journal', roles: ['ADMIN'] },
]

/**
 * Les deux gardes sont CUMULATIVES : le module dit si le role a acces au
 * domaine, `roles` restreint en plus les ecrans dont l'action est reservee a
 * certains roles cote serveur (creation de piece -> DFIN, cloture -> ADMIN,
 * mouvements de stock -> LOGISTIQUE...). Auparavant `roles` etait ignore des
 * qu'un module etait declare, ce qui affichait des entrees de menu dont
 * chaque page renvoyait un 403.
 */
function visible(item: NavItem) {
  if (item.roles && !auth.hasAnyRole(item.roles)) {
    return false
  }
  if (item.module) {
    return permissions.satisfait(permissions.niveau(item.module), item.niveau ?? 'LECTURE')
  }
  return true
}

const proxyModel = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const showAdmin = computed(() => auth.hasRole('ADMIN'))
const showVentes = computed(() => venteItems.some(item => visible(item)))
const showComptabilite = computed(() => comptabiliteItems.some(item => visible(item)))
const showLogistique = computed(() => logistiqueItems.some(item => visible(item)))
const showTransport = computed(() => transportItems.some(item => visible(item)))
const showPatrimoine = computed(() => patrimoineItems.some(item => visible(item)))
const showDrh = computed(() => drhItems.some(item => visible(item)))
const showRestaurant = computed(() => restaurantItems.some(item => visible(item)))
</script>

<template>
  <v-navigation-drawer
    v-model="proxyModel"
    class="modern-drawer"
    width="256"
    :temporary="$vuetify.display.mobile"
  >
    <!-- Brand in drawer (mobile only, since AppBar is hidden on mobile) -->
    <div class="modern-drawer__brand">
      <div class="modern-drawer__logo" :class="{ 'modern-drawer__logo--image': parametresStore.parametres.logoUrl }">
        <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
        <v-icon v-else icon="mdi-finance" size="16" color="white" />
      </div>
      <span class="modern-drawer__brand-name">{{ parametresStore.parametres.nom }}</span>
    </div>

    <div class="modern-drawer__section-label">Navigation</div>

    <nav class="modern-drawer__nav">
      <template v-for="item in items" :key="item.to">
        <nuxt-link
          v-if="visible(item)"
          :to="item.to"
          :class="['modern-drawer__item', { 'modern-drawer__item--essentiel': item.essentiel }]"
          active-class="modern-drawer__item--active"
        >
          <span class="modern-drawer__item-icon">
            <v-icon :icon="item.icon" size="18" />
          </span>
          <span class="modern-drawer__item-label">{{ item.title }}</span>
        </nuxt-link>
      </template>
    </nav>

    <template v-if="showVentes">
      <div class="modern-drawer__divider" />
      <div class="modern-drawer__section-label">Ventes</div>
      <nav class="modern-drawer__nav">
        <template v-for="item in venteItems" :key="item.to">
          <nuxt-link
            v-if="visible(item)"
            :to="item.to"
            :class="['modern-drawer__item', { 'modern-drawer__item--essentiel': item.essentiel }]"
            active-class="modern-drawer__item--active"
          >
            <span class="modern-drawer__item-icon">
              <v-icon :icon="item.icon" size="18" />
            </span>
            <span class="modern-drawer__item-label">{{ item.title }}</span>
          </nuxt-link>
        </template>
      </nav>
    </template>

    <template v-if="showComptabilite">
      <div class="modern-drawer__divider" />
      <div class="modern-drawer__section-label">Comptabilité</div>
      <nav class="modern-drawer__nav">
        <template v-for="item in comptabiliteItems" :key="item.to">
          <nuxt-link
            v-if="visible(item)"
            :to="item.to"
            :class="['modern-drawer__item', { 'modern-drawer__item--essentiel': item.essentiel }]"
            active-class="modern-drawer__item--active"
          >
            <span class="modern-drawer__item-icon">
              <v-icon :icon="item.icon" size="18" />
            </span>
            <span class="modern-drawer__item-label">{{ item.title }}</span>
          </nuxt-link>
        </template>
      </nav>
    </template>

    <template v-if="showLogistique">
      <div class="modern-drawer__divider" />
      <div class="modern-drawer__section-label">Logistique</div>
      <nav class="modern-drawer__nav">
        <template v-for="item in logistiqueItems" :key="item.to">
          <nuxt-link
            v-if="visible(item)"
            :to="item.to"
            :class="['modern-drawer__item', { 'modern-drawer__item--essentiel': item.essentiel }]"
            active-class="modern-drawer__item--active"
          >
            <span class="modern-drawer__item-icon">
              <v-icon :icon="item.icon" size="18" />
            </span>
            <span class="modern-drawer__item-label">{{ item.title }}</span>
          </nuxt-link>
        </template>
      </nav>
    </template>

    <template v-if="showPatrimoine">
      <div class="modern-drawer__divider" />
      <div class="modern-drawer__section-label">Patrimoine</div>
      <nav class="modern-drawer__nav">
        <template v-for="item in patrimoineItems" :key="item.to">
          <nuxt-link
            v-if="visible(item)"
            :to="item.to"
            :class="['modern-drawer__item', { 'modern-drawer__item--essentiel': item.essentiel }]"
            active-class="modern-drawer__item--active"
          >
            <span class="modern-drawer__item-icon">
              <v-icon :icon="item.icon" size="18" />
            </span>
            <span class="modern-drawer__item-label">{{ item.title }}</span>
          </nuxt-link>
        </template>
      </nav>
    </template>

    <template v-if="showRestaurant">
      <div class="modern-drawer__divider" />
      <div class="modern-drawer__section-label">Restaurant</div>
      <nav class="modern-drawer__nav">
        <template v-for="item in restaurantItems" :key="item.to">
          <nuxt-link
            v-if="visible(item)"
            :to="item.to"
            :class="['modern-drawer__item', { 'modern-drawer__item--essentiel': item.essentiel }]"
            active-class="modern-drawer__item--active"
          >
            <span class="modern-drawer__item-icon">
              <v-icon :icon="item.icon" size="18" />
            </span>
            <span class="modern-drawer__item-label">{{ item.title }}</span>
          </nuxt-link>
        </template>
      </nav>
    </template>

    <template v-if="showDrh">
      <div class="modern-drawer__divider" />
      <div class="modern-drawer__section-label">DRH</div>
      <nav class="modern-drawer__nav">
        <template v-for="item in drhItems" :key="item.to">
          <nuxt-link
            v-if="visible(item)"
            :to="item.to"
            :class="['modern-drawer__item', { 'modern-drawer__item--essentiel': item.essentiel }]"
            active-class="modern-drawer__item--active"
          >
            <span class="modern-drawer__item-icon">
              <v-icon :icon="item.icon" size="18" />
            </span>
            <span class="modern-drawer__item-label">{{ item.title }}</span>
          </nuxt-link>
        </template>
      </nav>
    </template>

    <template v-if="showTransport">
      <div class="modern-drawer__divider" />
      <div class="modern-drawer__section-label">Transport</div>
      <nav class="modern-drawer__nav">
        <template v-for="item in transportItems" :key="item.to">
          <nuxt-link
            v-if="visible(item)"
            :to="item.to"
            :class="['modern-drawer__item', { 'modern-drawer__item--essentiel': item.essentiel }]"
            active-class="modern-drawer__item--active"
          >
            <span class="modern-drawer__item-icon">
              <v-icon :icon="item.icon" size="18" />
            </span>
            <span class="modern-drawer__item-label">{{ item.title }}</span>
          </nuxt-link>
        </template>
      </nav>
    </template>

    <template v-if="showAdmin">
      <div class="modern-drawer__divider" />
      <div class="modern-drawer__section-label">Administration</div>
      <nav class="modern-drawer__nav">
        <nuxt-link
          v-for="item in adminItems"
          :key="item.to"
          :to="item.to"
          :class="['modern-drawer__item', { 'modern-drawer__item--essentiel': item.essentiel }]"
          active-class="modern-drawer__item--active"
        >
          <span class="modern-drawer__item-icon">
            <v-icon :icon="item.icon" size="18" />
          </span>
          <span class="modern-drawer__item-label">{{ item.title }}</span>
        </nuxt-link>
      </nav>
    </template>

    <template #append>
      <div class="modern-drawer__footer">
        MBSC &copy; {{ new Date().getFullYear() }}
      </div>
    </template>
  </v-navigation-drawer>
</template>

<style scoped>
.modern-drawer {
  background: #ffffff !important;
  border-right: 1px solid #f0f0f0 !important;
  padding: 12px 10px;
}

.modern-drawer__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 6px 16px;
}
.modern-drawer__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: #16a34a;
  flex-shrink: 0;
  overflow: hidden;
}
.modern-drawer__logo--image { background: #fff; border: 1px solid #f0f0f0; }
.modern-drawer__logo img { width: 100%; height: 100%; object-fit: contain; padding: 3px; }
.modern-drawer__brand-name {
  font-size: 0.9375rem;
  font-weight: 400;
  color: #374151;
}
.modern-drawer__brand-name strong {
  font-weight: 700;
  color: #111827;
}

.modern-drawer__section-label {
  font-size: 0.68rem;
  font-weight: 700;
  letter-spacing: 0.8px;
  text-transform: uppercase;
  color: #d1d5db;
  padding: 6px 10px 6px;
}

.modern-drawer__divider {
  height: 1px;
  background: #f3f4f6;
  margin: 10px 6px;
}

.modern-drawer__nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.modern-drawer__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: 10px;
  font-size: 0.875rem;
  font-weight: 500;
  color: #6b7280;
  text-decoration: none;
  transition: background 0.15s, color 0.15s;
}
.modern-drawer__item:hover {
  background: #f9fafb;
  color: #111827;
}
.modern-drawer__item--active {
  background: #dcfce7 !important;
  color: #16a34a !important;
  font-weight: 600;
}
.modern-drawer__item--active .modern-drawer__item-icon {
  color: #16a34a;
}

/* États financiers de premier reflexe (Bilan, Balance...) : reperables d'un
   coup d'oeil dans une liste par ailleurs uniforme, sans rivaliser avec le
   vert de la page active. */
.modern-drawer__item--essentiel {
  color: #92400e;
  font-weight: 600;
}
.modern-drawer__item--essentiel .modern-drawer__item-icon {
  background: #fef3c7;
  color: #b45309;
}
.modern-drawer__item--essentiel:hover {
  background: #fffbeb;
  color: #92400e;
}
.modern-drawer__item--essentiel:hover .modern-drawer__item-icon {
  background: #fde68a;
}
.modern-drawer__item--active.modern-drawer__item--essentiel {
  background: #dcfce7 !important;
  color: #16a34a !important;
}
.modern-drawer__item--active.modern-drawer__item--essentiel .modern-drawer__item-icon {
  background: transparent;
  color: #16a34a;
}

.modern-drawer__item-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 7px;
  flex-shrink: 0;
  transition: background 0.15s;
}
.modern-drawer__item:hover .modern-drawer__item-icon {
  background: #f0fdf4;
}

.modern-drawer__item-label {
  flex: 1;
}

.modern-drawer__footer {
  padding: 16px 12px;
  font-size: 0.72rem;
  color: #d1d5db;
  border-top: 1px solid #f3f4f6;
  margin: 0 -10px;
}
</style>
