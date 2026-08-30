<script setup lang="ts">
definePageMeta({ roles: ['ADMIN'] })

interface ModuleConfig {
  module: string
  actif: boolean
  parentModule: string | null
}

const api = useApi()
const loading = ref(true)
const erreur = ref('')
const toggling = ref<string | null>(null)
const modules = ref<ModuleConfig[]>([])

const META: Record<string, { label: string; description: string; icon: string; color: string }> = {
  CAISSE: { label: 'Caisse', description: 'Opérations de caisse, paiement des notes de frais, journal, grand livre et balance.', icon: 'mdi-cash-register', color: '#16a34a' },
  BANQUE: { label: 'Banque', description: 'Opérations bancaires et journal de banque.', icon: 'mdi-bank', color: '#2563eb' },
  MOBILE_MONEY: { label: 'Mobile Money', description: 'Opérations mobile money et journal associé.', icon: 'mdi-cellphone', color: '#7c3aed' },
  COMPTABILITE: { label: 'Comptabilité', description: 'Pièces comptables, grand livre par compte, bilan, compte de résultat, budgets, clôture.', icon: 'mdi-calculator-variant-outline', color: '#0891b2' },
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

async function basculer(m: ModuleConfig) {
  const nouvelEtat = !m.actif
  toggling.value = m.module
  erreur.value = ''
  try {
    await api(`/admin/modules/${m.module}`, { method: 'PUT', body: { actif: nouvelEtat } })
    m.actif = nouvelEtat
    // Les permissions effectives ne sont chargees qu'une fois par session :
    // sans ce rechargement, le module desactive restait visible dans le menu
    // jusqu'a la reconnexion, alors que toutes ses pages renvoyaient deja 403.
    await usePermissionsStore().charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la mise à jour.')
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

    <v-alert type="info" variant="tonal" rounded="lg" density="compact" class="mb-5">
      Désactiver un module le rend inaccessible à tous les utilisateurs, y compris les administrateurs — cette page reste toujours disponible pour le réactiver.
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
.mod-row__statut--on { background: #dcfce7; color: #16a34a; }
.mod-row__statut--off { background: #f3f4f6; color: #9ca3af; }
</style>
