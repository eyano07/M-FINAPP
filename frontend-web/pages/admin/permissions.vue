<script setup lang="ts">
definePageMeta({ roles: ['ADMIN'] })

interface Cellule {
  role: string
  module: string
  niveau: 'AUCUN' | 'LECTURE' | 'ECRITURE'
}

const api = useApi()
const loading = ref(true)
const erreur = ref('')
const enregistrement = ref<string | null>(null)
const grille = ref<Cellule[]>([])

const ROLES: { role: string; label: string }[] = [
  { role: 'DIRECTEUR', label: 'Directeur métier' },
  { role: 'CAISSIER', label: 'Caissier' },
  { role: 'COMPTABLE', label: 'Comptable' },
  { role: 'LOGISTIQUE', label: 'Logistique' },
  { role: 'GEST_PATRIMOINE', label: 'Gestionnaire patrimoine' },
  { role: 'RESP_DRH', label: 'Responsable DRH' },
  { role: 'RESP_RESTAURANT', label: 'Responsable restaurant' },
  { role: 'DFIN', label: 'DFIN' },
  { role: 'DA', label: 'DA' },
  { role: 'DG', label: 'DG' },
]
// Modules autonomes (une colonne chacun) + les 4 sous-modules DRH regroupes
// sous un en-tete commun (voir groupesColonnes) : le module conteneur DRH
// lui-meme n'apparait jamais ici, seuls ses sous-modules sont assignables
// dans role_permissions.
const MODULES: { module: string; label: string }[] = [
  { module: 'CAISSE', label: 'Caisse' },
  { module: 'BANQUE', label: 'Banque' },
  { module: 'MOBILE_MONEY', label: 'Mobile Money' },
  { module: 'COMPTABILITE', label: 'Comptabilité' },
  { module: 'VENTES', label: 'Ventes' },
  { module: 'LOGISTIQUE', label: 'Logistique' },
  { module: 'TRANSPORT', label: 'Transport' },
  { module: 'PATRIMOINE', label: 'Patrimoine' },
  // Insere AVANT le bloc DRH : ses 4 colonnes doivent rester contigues pour
  // que leur en-tete groupe (colspan 4) reste aligne.
  { module: 'RESTAURANT', label: 'Restaurant' },
  { module: 'DRH_PERSONNEL', label: 'Personnel' },
  { module: 'DRH_PRESENCES', label: 'Présences' },
  { module: 'DRH_PAIE', label: 'Paie' },
  { module: 'DRH_MISSIONS', label: 'Missions' },
]
/**
 * En-tete de groupe (2e ligne d'entete) : 'debut' porte le colspan et le
 * libelle du groupe, 'skip' les colonnes suivantes du meme groupe (deja
 * couvertes par le colspan), 'aucun' une colonne sans groupe (cellule vide,
 * juste pour aligner les bordures avec la ligne des libelles individuels).
 */
type EnteteGroupe = { type: 'debut'; label: string; colspan: number } | { type: 'skip' } | { type: 'aucun' }
const entetesGroupes = computed<EnteteGroupe[]>(() => MODULES.map((m) => {
  if (m.module === 'DRH_PERSONNEL') return { type: 'debut', label: 'DRH', colspan: 4 }
  if (m.module === 'DRH_PRESENCES' || m.module === 'DRH_PAIE' || m.module === 'DRH_MISSIONS') return { type: 'skip' }
  return { type: 'aucun' }
}))
const NIVEAUX = [
  { value: 'AUCUN', label: 'Aucun accès' },
  { value: 'LECTURE', label: 'Lecture seule' },
  { value: 'ECRITURE', label: 'Lecture et modification' },
]

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    grille.value = await api<Cellule[]>('/admin/permissions')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger la grille de permissions.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function cellule(role: string, module: string): Cellule | undefined {
  return grille.value.find((c) => c.role === role && c.module === module)
}

async function modifier(role: string, module: string, niveau: string) {
  const clef = `${role}|${module}`
  enregistrement.value = clef
  erreur.value = ''
  try {
    await api(`/admin/permissions/${role}/${module}`, { method: 'PUT', body: { niveau } })
    const c = cellule(role, module)
    if (c) c.niveau = niveau as Cellule['niveau']
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la mise à jour.')
    await charger()
  } finally {
    enregistrement.value = null
  }
}
</script>

<template>
  <div class="perm-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Permissions</h1>
        <p class="page-sub">Droit d'accès de chaque rôle aux modules métier (lecture seule ou modification)</p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" density="compact" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-alert type="info" variant="tonal" rounded="lg" density="compact" class="mb-5">
      Le rôle <strong>ADMIN</strong> dispose toujours d'un accès complet à tous les modules et n'apparaît pas dans cette grille (pour éviter qu'un administrateur ne se verrouille lui-même hors de l'application).
    </v-alert>

    <v-skeleton-loader v-if="loading" type="table" />

    <div v-else class="perm-scroll">
      <table class="perm-grid">
        <thead>
          <tr>
            <th class="perm-grid__role-head" rowspan="2">Rôle</th>
            <template v-for="(g, i) in entetesGroupes" :key="MODULES[i].module + '-groupe'">
              <th v-if="g.type === 'debut'" :colspan="g.colspan" class="perm-grid__groupe">{{ g.label }}</th>
              <th v-else-if="g.type === 'aucun'" class="perm-grid__groupe perm-grid__groupe--vide"></th>
            </template>
          </tr>
          <tr>
            <th v-for="m in MODULES" :key="m.module">{{ m.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in ROLES" :key="r.role">
            <td class="perm-grid__role">{{ r.label }}</td>
            <td v-for="m in MODULES" :key="m.module">
              <select
                class="perm-select"
                :class="`perm-select--${(cellule(r.role, m.module)?.niveau ?? 'AUCUN').toLowerCase()}`"
                :value="cellule(r.role, m.module)?.niveau ?? 'AUCUN'"
                :disabled="enregistrement === `${r.role}|${m.module}`"
                @change="modifier(r.role, m.module, ($event.target as HTMLSelectElement).value)"
              >
                <option v-for="n in NIVEAUX" :key="n.value" :value="n.value">{{ n.label }}</option>
              </select>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.perm-page { max-width: 1100px; margin: 0 auto; padding-bottom: 48px; }

.perm-scroll {
  overflow-x: auto;
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 16px;
}
.perm-grid { width: 100%; min-width: 860px; border-collapse: collapse; font-size: 0.85rem; }
.perm-grid thead tr { border-bottom: 1px solid #f3f4f6; }
.perm-grid th {
  text-align: left;
  padding: 14px 12px;
  font-size: 0.7rem;
  font-weight: 700;
  letter-spacing: 0.4px;
  text-transform: uppercase;
  color: #9ca3af;
  white-space: nowrap;
}
.perm-grid__role-head { position: sticky; left: 0; background: #fff; }
.perm-grid__groupe {
  text-align: center;
  border-bottom: 1px solid #f3f4f6;
  color: #0d9488;
}
.perm-grid__groupe--vide { border-bottom: none; }
.perm-grid tbody tr { border-bottom: 1px solid #f9fafb; }
.perm-grid tbody tr:last-child { border-bottom: none; }
.perm-grid td { padding: 8px 12px; vertical-align: middle; }
.perm-grid__role {
  font-weight: 700;
  color: #111827;
  white-space: nowrap;
  position: sticky;
  left: 0;
  background: #fff;
}

.perm-select {
  width: 100%;
  min-width: 150px;
  font-size: 0.78rem;
  font-weight: 600;
  padding: 7px 10px;
  border-radius: 8px;
  border: 1.5px solid #e5e7eb;
  background: #fff;
  color: #374151;
  cursor: pointer;
}
.perm-select--aucun { color: #9ca3af; border-color: #f0f0f0; }
.perm-select--lecture { color: #0891b2; border-color: #cffafe; background: #ecfeff; }
.perm-select--ecriture { color: #16a34a; border-color: #dcfce7; background: #f0fdf4; }
</style>
