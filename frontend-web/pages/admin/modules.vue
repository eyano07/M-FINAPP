<script setup lang="ts">
definePageMeta({ roles: ['ADMIN'] })

interface ModuleConfig {
  module: string
  actif: boolean
  parentModule: string | null
}

/** Ce que changerait la coupure d'un module : les rôles qui disparaîtraient et les comptes qui seraient désactivés. */
interface ImpactModule {
  module: string
  actif: boolean
  roles: string[]
  comptesADesactiver: string[]
  comptesAReactiver: string[]
}

const api = useApi()
const rolesStore = useRolesStore()
const loading = ref(true)
const erreur = ref('')
const succes = ref('')
const toggling = ref<string | null>(null)
const modules = ref<ModuleConfig[]>([])
/** Coupure en attente de confirmation (le module et son aperçu d'impact), ou null. */
const aConfirmer = ref<{ module: ModuleConfig; impact: ImpactModule } | null>(null)
/**
 * Un clic sur l'interrupteur bascule d'abord la case native, avant que la page ait décidé quoi que ce soit : si la
 * coupure est annulée (ou échoue), le module reste actif mais la case est restée « éteinte ». Incrémenter ce
 * compteur, qui entre dans la clé des interrupteurs, les recrée dans l'état réel du module.
 */
const resynchro = ref(0)

const META: Record<string, { label: string; description: string; icon: string; color: string }> = {
  CAISSE: { label: 'Caisse', description: 'Opérations de caisse, paiement des notes de frais, journal, grand livre et balance.', icon: 'mdi-cash-register', color: 'var(--color-primary)' },
  BANQUE: { label: 'Banque', description: 'Opérations bancaires et journal de banque.', icon: 'mdi-bank', color: '#2563eb' },
  MOBILE_MONEY: { label: 'Mobile Money', description: 'Opérations mobile money et journal associé.', icon: 'mdi-cellphone', color: '#7c3aed' },
  COMPTABILITE: { label: 'Comptabilité', description: 'Pièces comptables, grand livre par compte, bilan, compte de résultat, clôture.', icon: 'mdi-calculator-variant-outline', color: '#0891b2' },
  RAPPROCHEMENT: { label: 'Rapprochement bancaire', description: 'Relevés des banques et du mobile money lus par l\'IA, pointés contre la comptabilité, état de rapprochement validé par le DFIN.', icon: 'mdi-bank-check', color: '#0891b2' },
  BUDGET: { label: 'Budget', description: 'Budgets prévisionnels et contrôle budgétaire des notes de frais. Désactivé : les dépenses ne sont plus rattachées au budget ni contrôlées.', icon: 'mdi-chart-box-outline', color: '#0891b2' },
  FACTURATION_NORMALISEE: { label: 'Facture normalisée (DGI)', description: 'Chaque vente validée est transmise au dispositif e-MCF de la DGI (UID, signature, code QR sur la facture). Désactivé : plus aucune transmission. Réglages du dispositif : Administration › Facture normalisée.', icon: 'mdi-receipt-text-check-outline', color: '#ea580c' },
  VENTES: { label: 'Ventes', description: 'Ventes et clients.', icon: 'mdi-cart-outline', color: '#ea580c' },
  LOGISTIQUE: { label: 'Logistique', description: 'Articles, entrepôts, mouvements et stock.', icon: 'mdi-package-variant-closed', color: '#b45309' },
  TRANSPORT: { label: 'Transport', description: 'Véhicules, trajets et dépenses de transport.', icon: 'mdi-truck-outline', color: '#dc2626' },
  PATRIMOINE: { label: 'Patrimoine', description: 'Biens immobilises, amortissements et consommables', icon: 'mdi-office-building-cog-outline', color: '#9333ea' },
  RESTAURANT: { label: 'Restaurant', description: 'Carte (plats, boissons) et parc d\'emballages consignés (bouteilles, bacs).', icon: 'mdi-silverware-fork-knife', color: '#e11d48' },
  DRH: { label: 'DRH', description: 'Ressources humaines et paie — module conteneur, regroupe les 4 sous-modules ci-dessous.', icon: 'mdi-account-tie-outline', color: '#0d9488' },
  DRH_PERSONNEL: { label: 'Personnel', description: 'Fiches employés, matricules.', icon: 'mdi-account-multiple-outline', color: '#0d9488' },
  DRH_PRESENCES: { label: 'Présences', description: 'Pointage, fiche de présence manuelle, sorties de travailleurs.', icon: 'mdi-calendar-check-outline', color: '#0d9488' },
  DRH_PAIE: { label: 'Paie', description: 'Bulletins de paie et paramètres de paie.', icon: 'mdi-cash-multiple', color: '#0d9488' },
  DRH_MISSIONS: { label: 'Missions & Terrain', description: 'Ordres de mission, sites opérationnels, rotations de superviseurs.', icon: 'mdi-map-marker-path', color: '#0d9488' },
}
const meta = (m: string) => META[m] ?? { label: m, description: '', icon: 'mdi-view-grid-outline', color: '#6b7280' }

// Modules autonomes (sans parent) affiches tels quels ; DRH affiche ses 4
// sous-modules imbriques en dessous, desactives visuellement si DRH est off
// (la cascade reelle est deja appliquee cote backend, ceci n'est qu'un reflet).
const modulesAutonomes = computed(() => modules.value.filter((m) => !m.parentModule))
const enfantsDe = (parent: string) => modules.value.filter((m) => m.parentModule === parent)

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    modules.value = await api<ModuleConfig[]>('/admin/modules')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les modules.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

/** Combien de comptes en clair : « 1 compte », « 3 comptes ». */
const comptes = (n: number) => `${n} compte${n > 1 ? 's' : ''}`

async function basculer(m: ModuleConfig) {
  const nouvelEtat = !m.actif
  erreur.value = ''
  succes.value = ''
  // Couper un module qui porte des rôles ou des comptes : on montre d'abord ce qui va disparaître et on demande
  // confirmation. Les comptes concernés sont désactivés et masqués, puis remis en service avec le module.
  if (!nouvelEtat) {
    toggling.value = m.module
    try {
      const impact = await api<ImpactModule>(`/admin/modules/${m.module}/impact`, { query: { actif: false } })
      if (impact.roles.length > 0 || impact.comptesADesactiver.length > 0) {
        aConfirmer.value = { module: m, impact }
        return
      }
    } catch (e: any) {
      erreur.value = messageErreurApi(e, "Impossible de mesurer l'effet de la désactivation.")
      resynchro.value++
      return
    } finally {
      toggling.value = null
    }
  }
  await appliquer(m, nouvelEtat)
}

/** Coupure refusée (bouton Annuler, clic à côté, Échap) : le module reste actif, l'interrupteur aussi. */
function annulerCoupure() {
  aConfirmer.value = null
  resynchro.value++
}

async function confirmerCoupure() {
  const attente = aConfirmer.value
  aConfirmer.value = null
  if (attente) await appliquer(attente.module, false)
}

async function appliquer(m: ModuleConfig, nouvelEtat: boolean) {
  toggling.value = m.module
  erreur.value = ''
  try {
    const reponse = await api<{ comptesDesactives?: number | null; comptesReactives?: number | null }>(
      `/admin/modules/${m.module}`, { method: 'PUT', body: { actif: nouvelEtat } })
    m.actif = nouvelEtat
    const coupes = reponse?.comptesDesactives ?? 0
    const remis = reponse?.comptesReactives ?? 0
    const nom = meta(m.module).label
    succes.value = nouvelEtat
      ? `Module « ${nom} » activé.` + (remis ? ` ${comptes(remis)} réactivé${remis > 1 ? 's' : ''} et de nouveau visible${remis > 1 ? 's' : ''}.` : '')
      : `Module « ${nom} » désactivé.` + (coupes ? ` ${comptes(coupes)} désactivé${coupes > 1 ? 's' : ''} et masqué${coupes > 1 ? 's' : ''}.` : '')
    // Les permissions effectives ne sont chargees qu'une fois par session :
    // sans ce rechargement, le module desactive restait visible dans le menu
    // jusqu'a la reconnexion, alors que toutes ses pages renvoyaient deja 403.
    await usePermissionsStore().charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la mise à jour.')
    resynchro.value++
  } finally {
    toggling.value = null
  }
}
</script>

<template>
  <div class="mod-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Modules</h1>
        <p class="page-sub">Active ou désactive les modules métier pour toute l'application</p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" density="compact" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" density="compact" class="mb-4" closable @click:close="succes = ''">
      {{ succes }}
    </v-alert>

    <v-alert type="info" variant="tonal" rounded="lg" density="compact" class="mb-5">
      Désactiver un module le rend inaccessible à tous les utilisateurs, y compris les administrateurs — cette page reste toujours disponible pour le réactiver.
      Les rôles propres au module disparaissent de la gestion des utilisateurs, et les comptes qui n'ont plus que ces rôles
      sont désactivés et masqués, puis remis en service automatiquement quand le module est réactivé.
    </v-alert>

    <v-skeleton-loader v-if="loading" type="list-item-avatar-two-line, list-item-avatar-two-line, list-item-avatar-two-line" />

    <div v-else class="mod-list">
      <template v-for="m in modulesAutonomes" :key="m.module">
        <div class="mod-row" :class="{ 'mod-row--inactif': !m.actif }">
          <span class="mod-row__icon" :style="{ background: meta(m.module).color + '1a', color: meta(m.module).color }">
            <v-icon :icon="meta(m.module).icon" size="20" />
          </span>
          <div class="mod-row__body">
            <span class="mod-row__label">{{ meta(m.module).label }}</span>
            <span class="mod-row__desc">{{ meta(m.module).description }}</span>
          </div>
          <span class="mod-row__statut" :class="m.actif ? 'mod-row__statut--on' : 'mod-row__statut--off'">
            {{ m.actif ? 'Actif' : 'Désactivé' }}
          </span>
          <v-switch
            :key="`${m.module}-${resynchro}`"
            :model-value="m.actif"
            color="primary"
            hide-details
            :loading="toggling === m.module"
            :disabled="toggling === m.module"
            @update:model-value="basculer(m)"
          />
        </div>

        <!-- Sous-modules : imbriques visuellement, desactives des que le parent l'est. -->
        <div
          v-for="enfant in enfantsDe(m.module)"
          :key="enfant.module"
          class="mod-row mod-row--enfant"
          :class="{ 'mod-row--inactif': !enfant.actif || !m.actif }"
        >
          <span class="mod-row__enfant-trait" />
          <span class="mod-row__icon mod-row__icon--sm" :style="{ background: meta(enfant.module).color + '1a', color: meta(enfant.module).color }">
            <v-icon :icon="meta(enfant.module).icon" size="16" />
          </span>
          <div class="mod-row__body">
            <span class="mod-row__label">{{ meta(enfant.module).label }}</span>
            <span class="mod-row__desc">{{ meta(enfant.module).description }}</span>
          </div>
          <span class="mod-row__statut" :class="enfant.actif ? 'mod-row__statut--on' : 'mod-row__statut--off'">
            {{ !m.actif ? 'Coupé (parent off)' : (enfant.actif ? 'Actif' : 'Désactivé') }}
          </span>
          <v-switch
            :key="`${enfant.module}-${resynchro}`"
            :model-value="enfant.actif"
            color="primary"
            hide-details
            :loading="toggling === enfant.module"
            :disabled="toggling === enfant.module || !m.actif"
            @update:model-value="basculer(enfant)"
          />
        </div>
      </template>
    </div>

    <!-- Confirmation avant de couper un module qui porte des rôles ou des comptes. -->
    <v-dialog :model-value="!!aConfirmer" max-width="520" @update:model-value="(v: boolean) => { if (!v) annulerCoupure() }">
      <v-card v-if="aConfirmer" rounded="xl" class="pa-2">
        <v-card-title class="text-h6">Désactiver « {{ meta(aConfirmer.module.module).label }} » ?</v-card-title>
        <v-card-text>
          <p v-if="aConfirmer.impact.roles.length" class="mb-3">
            Ces rôles disparaissent de la gestion des utilisateurs :
            <strong>{{ aConfirmer.impact.roles.map((r) => rolesStore.libelle(r)).join(', ') }}</strong>.
          </p>
          <template v-if="aConfirmer.impact.comptesADesactiver.length">
            <p class="mb-2">
              <strong>{{ comptes(aConfirmer.impact.comptesADesactiver.length) }}</strong>
              {{ aConfirmer.impact.comptesADesactiver.length > 1 ? 'seront désactivés et masqués' : 'sera désactivé et masqué' }}
              (leurs sessions ouvertes sont fermées) :
            </p>
            <ul class="mod-impact">
              <li v-for="nom in aConfirmer.impact.comptesADesactiver.slice(0, 8)" :key="nom">{{ nom }}</li>
              <li v-if="aConfirmer.impact.comptesADesactiver.length > 8">… et {{ aConfirmer.impact.comptesADesactiver.length - 8 }} autre(s)</li>
            </ul>
          </template>
          <p v-else class="mb-2">Aucun compte n'est désactivé : ils ont tous un autre rôle.</p>
          <p class="text-caption text-medium-emphasis mt-3 mb-0">
            Ils sont réactivés automatiquement quand le module est réactivé. Un compte désactivé à la main n'est jamais réactivé automatiquement.
          </p>
        </v-card-text>
        <v-card-actions class="px-4 pb-3">
          <v-spacer />
          <v-btn variant="text" @click="annulerCoupure">Annuler</v-btn>
          <v-btn color="error" variant="flat" rounded="lg" @click="confirmerCoupure">Désactiver le module</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.mod-page { max-width: 800px; margin: 0 auto; padding-bottom: 48px; }

.mod-list { display: flex; flex-direction: column; gap: 10px; }
.mod-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 16px;
  transition: opacity 0.15s;
}
.mod-row--inactif { opacity: 0.6; }
.mod-row--enfant { margin-left: 28px; padding: 12px 18px; }
.mod-row__enfant-trait {
  width: 14px; height: 1px; background: #e5e7eb; flex-shrink: 0; margin-right: -4px;
}
.mod-row__icon--sm { width: 34px; height: 34px; }

.mod-row__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px; height: 42px;
  border-radius: 12px;
  flex-shrink: 0;
}
.mod-row__body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.mod-row__label { font-size: 0.9rem; font-weight: 700; color: #111827; }
.mod-row__desc { font-size: 0.78rem; color: #9ca3af; }

.mod-row__statut {
  font-size: 0.7rem;
  font-weight: 700;
  padding: 3px 10px;
  border-radius: 100px;
  flex-shrink: 0;
  white-space: nowrap;
}
.mod-impact { margin: 0 0 4px; padding-left: 20px; font-size: 0.85rem; color: #374151; }
.mod-row__statut--on { background: var(--color-primary-light); color: var(--color-primary); }
.mod-row__statut--off { background: #f3f4f6; color: #9ca3af; }
</style>
