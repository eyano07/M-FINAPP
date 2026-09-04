<script setup lang="ts">
definePageMeta({ roles: ['ADMIN'] })

import { useDisplay } from 'vuetify'

const { width } = useDisplay()
const esMobile = computed(() => width.value < 600)

interface UserRow {
  id: number
  nom: string
  prenom: string
  email: string
  telephone: string | null
  fonction: string | null
  affectation: string | null
  roles: string[]
  actif: boolean
  nomComplet?: string
}

const ALL_ROLES = ['ADMIN', 'DG', 'DA', 'DFIN', 'DIRECTEUR', 'CAISSIER', 'COMPTABLE', 'LOGISTIQUE', 'GEST_PATRIMOINE', 'RESP_DRH', 'RESP_RESTAURANT']

const api = useApi()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const users = ref<UserRow[]>([])

// ── Dialog ─────────────────────────────────────────────────────────────────
const dialog = ref(false)
const editMode = ref(false)
const editId = ref<number | null>(null)

const form = reactive({
  nom: '',
  prenom: '',
  email: '',
  motDePasse: '',
  telephone: '',
  fonction: '',
  affectation: '',
  roles: [] as string[],
})

function ouvrirAjouter() {
  editMode.value = false
  editId.value = null
  form.nom = ''
  form.prenom = ''
  form.email = ''
  form.motDePasse = ''
  form.telephone = ''
  form.fonction = ''
  form.affectation = ''
  form.roles = []
  erreur.value = ''
  dialog.value = true
}

function ouvrirModifier(u: UserRow) {
  editMode.value = true
  editId.value = u.id
  form.nom = u.nom
  form.prenom = u.prenom
  form.email = u.email
  form.motDePasse = ''
  form.telephone = u.telephone ?? ''
  form.fonction = u.fonction ?? ''
  form.affectation = u.affectation ?? ''
  form.roles = [...u.roles]
  erreur.value = ''
  dialog.value = true
}

// ── Chargement ─────────────────────────────────────────────────────────────
async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const data = await api<UserRow[]>('/admin/users')
    users.value = data.map((u) => ({ ...u, nomComplet: `${u.prenom ?? ''} ${u.nom ?? ''}`.trim() }))
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger les utilisateurs.'
  } finally {
    loading.value = false
  }
}

onMounted(charger)

// ── Enregistrement ─────────────────────────────────────────────────────────
async function enregistrer() {
  if (!form.nom || !form.prenom || !form.email || form.roles.length === 0) {
    erreur.value = 'Renseignez tous les champs obligatoires et au moins un rôle.'
    return
  }
  if (!editMode.value && !form.motDePasse) {
    erreur.value = 'Le mot de passe est obligatoire pour un nouvel utilisateur.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    if (editMode.value) {
      await api(`/admin/users/${editId.value}`, {
        method: 'PUT',
        body: {
          nom: form.nom, prenom: form.prenom, motDePasse: form.motDePasse || null,
          telephone: form.telephone.trim() || null,
          fonction: form.fonction.trim() || null, affectation: form.affectation.trim() || null,
          roles: form.roles,
        },
      })
      succes.value = 'Utilisateur modifié avec succès.'
    } else {
      await api('/admin/users', {
        method: 'POST',
        body: {
          nom: form.nom, prenom: form.prenom, email: form.email, motDePasse: form.motDePasse,
          telephone: form.telephone.trim() || null,
          fonction: form.fonction.trim() || null, affectation: form.affectation.trim() || null,
          roles: form.roles,
        },
      })
      succes.value = 'Utilisateur créé avec succès.'
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || "Échec de l'enregistrement."
  } finally {
    saving.value = false
  }
}

// ── Toggle actif ───────────────────────────────────────────────────────────
const togglingId = ref<number | null>(null)
async function toggleActif(u: UserRow) {
  togglingId.value = u.id
  try {
    const updated = await api<UserRow>(`/admin/users/${u.id}/actif`, { method: 'PATCH' })
    const idx = users.value.findIndex((x) => x.id === u.id)
    if (idx !== -1) users.value[idx].actif = updated.actif
    succes.value = updated.actif ? `${u.nomComplet} activé.` : `${u.nomComplet} désactivé.`
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Échec du changement de statut.'
  } finally {
    togglingId.value = null
  }
}

const roleColor: Record<string, string> = {
  ADMIN: '#7c3aed', DG: '#0ea5e9', DA: '#f59e0b',
  DFIN: '#16a34a', DIRECTEUR: '#2563eb', CAISSIER: '#ea580c', COMPTABLE: '#be185d', LOGISTIQUE: '#0d9488',
  GEST_PATRIMOINE: '#9333ea', RESP_DRH: '#c026d3', RESP_RESTAURANT: '#e11d48',
}
</script>

<template>
  <div>
    <!-- ── En-tête ───────────────────────────────────────────────────── -->
    <div class="page-head">
      <div>
        <h1 class="page-title">Utilisateurs</h1>
        <p class="page-sub">Gestion des comptes et des rôles</p>
      </div>
      <div class="usr-head-actions">
        <button class="usr-refresh-btn" :disabled="loading" @click="charger">
          <v-icon icon="mdi-refresh" size="16" />
        </button>
        <button class="usr-new-btn" @click="ouvrirAjouter">
          <v-icon icon="mdi-plus" size="18" class="mr-1" />
          Ajouter
        </button>
      </div>
    </div>

    <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" density="compact" class="mb-4" closable @click:close="succes = ''">
      {{ succes }}
    </v-alert>
    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" rounded="lg" density="compact" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <!-- ── Tableau ───────────────────────────────────────────────────── -->
    <div class="usr-card">
      <div v-if="loading" class="usr-loading">
        <v-progress-circular indeterminate color="primary" size="28" />
      </div>

      <div v-else-if="users.length === 0" class="usr-empty">
        <v-icon icon="mdi-account-group-outline" size="40" color="#d1d5db" />
        <p>Aucun utilisateur trouvé.</p>
      </div>

      <div v-else-if="!esMobile" class="usr-table-scroll">
      <table class="usr-table">
        <thead>
          <tr>
            <th>Utilisateur</th>
            <th>E-mail</th>
            <th>Rôles</th>
            <th>Statut</th>
            <th class="usr-th-actions">Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in users" :key="u.id" :class="{ 'usr-row--inactive': !u.actif }">
            <td>
              <div class="usr-name-cell">
                <div class="usr-avatar" :style="{ background: u.actif ? '#dcfce7' : '#f3f4f6', color: u.actif ? '#16a34a' : '#9ca3af' }">
                  {{ (u.prenom?.[0] ?? '') + (u.nom?.[0] ?? '') }}
                </div>
                <span>{{ u.nomComplet }}</span>
              </div>
            </td>
            <td class="usr-email">{{ u.email }}</td>
            <td>
              <div class="usr-roles">
                <span v-for="r in u.roles" :key="r" class="usr-role-chip" :style="{ background: roleColor[r] + '1a', color: roleColor[r] }">
                  {{ r }}
                </span>
              </div>
            </td>
            <td>
              <span class="usr-status-chip" :class="u.actif ? 'usr-status-chip--on' : 'usr-status-chip--off'">
                <span class="usr-status-dot" />
                {{ u.actif ? 'Actif' : 'Inactif' }}
              </span>
            </td>
            <td>
              <div class="usr-actions">
                <button class="usr-action-btn usr-action-btn--edit" title="Modifier" @click="ouvrirModifier(u)">
                  <v-icon icon="mdi-pencil-outline" size="16" />
                </button>
                <button
                  class="usr-action-btn"
                  :class="u.actif ? 'usr-action-btn--deact' : 'usr-action-btn--act'"
                  :title="u.actif ? 'Désactiver' : 'Activer'"
                  :disabled="togglingId === u.id"
                  @click="toggleActif(u)"
                >
                  <v-progress-circular v-if="togglingId === u.id" indeterminate size="14" width="2" color="currentColor" />
                  <v-icon v-else :icon="u.actif ? 'mdi-account-off-outline' : 'mdi-account-check-outline'" size="16" />
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      </div>

      <!-- ── Cartes (mobile) ──────────────────────────────────── -->
      <div v-else class="usr-cards">
        <div v-for="u in users" :key="u.id" class="usr-card-item" :class="{ 'usr-card-item--inactive': !u.actif }">
          <div class="usr-card-item__top">
            <div class="usr-avatar" :style="{ background: u.actif ? '#dcfce7' : '#f3f4f6', color: u.actif ? '#16a34a' : '#9ca3af' }">
              {{ (u.prenom?.[0] ?? '') + (u.nom?.[0] ?? '') }}
            </div>
            <div class="usr-card-item__id">
              <span class="usr-card-item__name">{{ u.nomComplet }}</span>
              <span class="usr-card-item__email">{{ u.email }}</span>
            </div>
            <span class="usr-status-chip" :class="u.actif ? 'usr-status-chip--on' : 'usr-status-chip--off'">
              <span class="usr-status-dot" />
              {{ u.actif ? 'Actif' : 'Inactif' }}
            </span>
          </div>

          <div class="usr-roles usr-card-item__roles">
            <span v-for="r in u.roles" :key="r" class="usr-role-chip" :style="{ background: roleColor[r] + '1a', color: roleColor[r] }">
              {{ r }}
            </span>
          </div>

          <div class="usr-card-item__footer">
            <button class="usr-card-item__btn usr-card-item__btn--edit" @click="ouvrirModifier(u)">
              <v-icon icon="mdi-pencil-outline" size="15" class="mr-1" />
              Modifier
            </button>
            <button
              class="usr-card-item__btn"
              :class="u.actif ? 'usr-card-item__btn--deact' : 'usr-card-item__btn--act'"
              :disabled="togglingId === u.id"
              @click="toggleActif(u)"
            >
              <v-progress-circular v-if="togglingId === u.id" indeterminate size="14" width="2" color="currentColor" class="mr-1" />
              <v-icon v-else :icon="u.actif ? 'mdi-account-off-outline' : 'mdi-account-check-outline'" size="15" class="mr-1" />
              {{ u.actif ? 'Désactiver' : 'Activer' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- ── Dialog ────────────────────────────────────────────────────── -->
    <v-dialog v-model="dialog" max-width="560">
      <div class="usr-dialog">
        <!-- En-tête dégradé -->
        <div class="usr-dialog__head">
          <div class="usr-dialog__blob usr-dialog__blob--a" />
          <div class="usr-dialog__blob usr-dialog__blob--b" />
          <div class="usr-dialog__icon">
            <v-icon :icon="editMode ? 'mdi-account-edit-outline' : 'mdi-account-plus-outline'" size="22" color="white" />
          </div>
          <div class="usr-dialog__head-text">
            <p class="usr-dialog__head-title">{{ editMode ? 'Modifier l\'utilisateur' : 'Nouvel utilisateur' }}</p>
            <p class="usr-dialog__head-sub">{{ editMode ? 'Modifiez les informations du compte' : 'Créer un nouveau compte MBSC' }}</p>
          </div>
          <button class="usr-dialog__close" @click="dialog = false">
            <v-icon icon="mdi-close" size="18" color="rgba(255,255,255,0.75)" />
          </button>
        </div>

        <!-- Corps -->
        <div class="usr-dialog__body">
          <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" density="compact" class="mb-3" closable @click:close="erreur = ''">
            {{ erreur }}
          </v-alert>

          <div class="usr-form-row">
            <div class="usr-field">
              <label class="usr-label">Prénom *</label>
              <v-text-field v-model="form.prenom" placeholder="Prénom" hide-details="auto" />
            </div>
            <div class="usr-field">
              <label class="usr-label">Nom *</label>
              <v-text-field v-model="form.nom" placeholder="Nom de famille" hide-details="auto" />
            </div>
          </div>

          <div class="usr-field">
            <label class="usr-label">E-mail *</label>
            <v-text-field
              v-model="form.email"
              placeholder="utilisateur@mbsc.cd"
              prepend-inner-icon="mdi-email-outline"
              hide-details="auto"
              :disabled="editMode"
            />
          </div>

          <div class="usr-field">
            <label class="usr-label">Téléphone</label>
            <v-text-field
              v-model="form.telephone"
              placeholder="Ex: +243 999 123 456"
              prepend-inner-icon="mdi-phone-outline"
              hide-details="auto"
            />
          </div>

          <div class="usr-form-row">
            <div class="usr-field">
              <label class="usr-label">Fonction</label>
              <v-text-field v-model="form.fonction" placeholder="Ex: Comptable" hide-details="auto" />
            </div>
            <div class="usr-field">
              <label class="usr-label">Affectation</label>
              <v-text-field v-model="form.affectation" placeholder="Ex: Siège Lubumbashi" hide-details="auto" />
            </div>
          </div>
          <p class="usr-hint">Utilisées sur le papier à en-tête individuel de cet utilisateur.</p>

          <div class="usr-field">
            <label class="usr-label">{{ editMode ? 'Nouveau mot de passe (laisser vide = inchangé)' : 'Mot de passe *' }}</label>
            <v-text-field
              v-model="form.motDePasse"
              :placeholder="editMode ? 'Laisser vide pour ne pas changer' : 'Min. 8 caractères'"
              prepend-inner-icon="mdi-lock-outline"
              type="password"
              hide-details="auto"
            />
          </div>

          <div class="usr-field">
            <label class="usr-label">Rôles *</label>
            <div class="usr-roles-grid">
              <button
                v-for="r in ALL_ROLES"
                :key="r"
                class="usr-role-toggle"
                :class="{ 'usr-role-toggle--on': form.roles.includes(r) }"
                :style="form.roles.includes(r) ? { background: roleColor[r] + '1a', color: roleColor[r], borderColor: roleColor[r] + '55' } : {}"
                @click="form.roles.includes(r) ? form.roles.splice(form.roles.indexOf(r), 1) : form.roles.push(r)"
                type="button"
              >
                {{ r }}
              </button>
            </div>
          </div>
        </div>

        <!-- Pied -->
        <div class="usr-dialog__footer">
          <button class="usr-cancel-btn" :disabled="saving" @click="dialog = false">Annuler</button>
          <button class="usr-submit-btn" :disabled="saving" @click="enregistrer">
            <v-progress-circular v-if="saving" indeterminate size="16" width="2" color="white" class="mr-2" />
            <v-icon v-else :icon="editMode ? 'mdi-content-save-outline' : 'mdi-account-plus-outline'" size="17" class="mr-1" />
            {{ editMode ? 'Enregistrer' : 'Créer le compte' }}
          </button>
        </div>
      </div>
    </v-dialog>
  </div>
</template>

<style scoped>
/* ── Header actions ──────────────────────────────────────────────────────── */
.usr-head-actions { display: flex; align-items: center; gap: 10px; }

.usr-refresh-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 40px; height: 40px; border-radius: 11px;
  background: #f3f4f6; border: 1px solid #e5e7eb; color: #6b7280;
  cursor: pointer; transition: background 0.15s;
}
.usr-refresh-btn:hover:not(:disabled) { background: #e5e7eb; }
.usr-refresh-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.usr-new-btn {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 0 20px; height: 40px; border-radius: 11px;
  background: #16a34a; color: #fff; font-size: 0.875rem; font-weight: 600;
  border: none; cursor: pointer;
  transition: background 0.18s, box-shadow 0.18s;
  box-shadow: 0 2px 8px rgba(22,163,74,0.25);
}
.usr-new-btn:hover { background: #15803d; }

/* ── Table card ──────────────────────────────────────────────────────────── */
.usr-card {
  background: #fff; border: 1px solid #f0f0f0; border-radius: 18px;
  overflow: hidden;
}
.usr-loading, .usr-empty {
  display: flex; flex-direction: column; align-items: center;
  gap: 10px; padding: 48px 16px; color: #9ca3af;
  font-size: 0.85rem;
}
.usr-empty p { margin: 0; }

/* ── Table ───────────────────────────────────────────────────────────────── */
.usr-table-scroll { overflow-x: auto; }
.usr-table { width: 100%; min-width: 640px; border-collapse: collapse; font-size: 0.875rem; }
.usr-table thead tr {
  border-bottom: 1px solid #f3f4f6;
}
.usr-table th {
  padding: 12px 16px;
  font-size: 0.72rem; font-weight: 700; letter-spacing: 0.4px;
  text-transform: uppercase; color: #9ca3af; text-align: left;
}
.usr-th-actions { text-align: right; }

.usr-table tbody tr {
  border-bottom: 1px solid #f9fafb;
  transition: background 0.12s;
}
.usr-table tbody tr:last-child { border-bottom: none; }
.usr-table tbody tr:hover { background: #f9fafb; }
.usr-row--inactive { opacity: 0.65; }

.usr-table td { padding: 12px 16px; vertical-align: middle; }

.usr-name-cell { display: flex; align-items: center; gap: 10px; }
.usr-avatar {
  width: 34px; height: 34px; border-radius: 10px;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 0.75rem; font-weight: 700; flex-shrink: 0;
}
.usr-email { color: #6b7280; font-size: 0.8125rem; }

/* roles */
.usr-roles { display: flex; flex-wrap: wrap; gap: 4px; }
.usr-role-chip {
  font-size: 0.65rem; font-weight: 700; padding: 2px 8px;
  border-radius: 100px; letter-spacing: 0.3px;
}

/* statut */
.usr-status-chip {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 0.75rem; font-weight: 600; padding: 3px 10px;
  border-radius: 100px;
}
.usr-status-dot {
  width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0;
}
.usr-status-chip--on  { background: #dcfce7; color: #16a34a; }
.usr-status-chip--on  .usr-status-dot  { background: #16a34a; }
.usr-status-chip--off { background: #f3f4f6; color: #9ca3af; }
.usr-status-chip--off .usr-status-dot { background: #9ca3af; }

/* actions */
.usr-actions { display: flex; align-items: center; justify-content: flex-end; gap: 6px; }
.usr-action-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 32px; height: 32px; border-radius: 9px; border: 1px solid;
  cursor: pointer; transition: background 0.15s;
}
.usr-action-btn--edit  { background: #eff6ff; color: #2563eb; border-color: #bfdbfe; }
.usr-action-btn--edit:hover  { background: #dbeafe; }
.usr-action-btn--deact { background: #fff7ed; color: #ea580c; border-color: #fed7aa; }
.usr-action-btn--deact:hover { background: #ffedd5; }
.usr-action-btn--act   { background: #f0fdf4; color: #16a34a; border-color: #bbf7d0; }
.usr-action-btn--act:hover   { background: #dcfce7; }
.usr-action-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* ── Cartes (mobile) ─────────────────────────────────────────────────────── */
.usr-cards { display: flex; flex-direction: column; gap: 10px; padding: 12px; }

.usr-card-item {
  background: #fff; border: 1px solid #eef0f2; border-left: 3px solid #16a34a; border-radius: 16px;
  padding: 14px 16px; box-shadow: 0 2px 10px rgba(15, 23, 42, 0.06), 0 1px 2px rgba(15, 23, 42, 0.04);
  transition: box-shadow 0.15s;
}
.usr-card-item--inactive { opacity: 0.65; }

.usr-card-item__top { display: flex; align-items: flex-start; gap: 10px; }
.usr-card-item__id {
  flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px;
  padding-top: 2px;
}
.usr-card-item__name {
  font-size: 0.9rem; font-weight: 700; color: #111827;
  overflow-wrap: anywhere;
}
.usr-card-item__email {
  font-size: 0.78rem; color: #9ca3af; overflow-wrap: anywhere;
}

.usr-card-item__roles {
  margin: 12px 0 0; padding-top: 12px; border-top: 1px solid #f6f6f6;
}

.usr-card-item__footer {
  display: flex; align-items: center; gap: 8px;
  margin-top: 12px; padding-top: 12px; border-top: 1px solid #f6f6f6;
}
.usr-card-item__btn {
  flex: 1; display: inline-flex; align-items: center; justify-content: center;
  height: 36px; border-radius: 10px; border: 1px solid;
  font-size: 0.78rem; font-weight: 700; cursor: pointer; transition: background 0.15s;
}
.usr-card-item__btn--edit  { background: #eff6ff; color: #2563eb; border-color: #bfdbfe; }
.usr-card-item__btn--edit:active  { background: #dbeafe; }
.usr-card-item__btn--deact { background: #fff7ed; color: #ea580c; border-color: #fed7aa; }
.usr-card-item__btn--deact:active { background: #ffedd5; }
.usr-card-item__btn--act   { background: #f0fdf4; color: #16a34a; border-color: #bbf7d0; }
.usr-card-item__btn--act:active   { background: #dcfce7; }
.usr-card-item__btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* ── Dialog ──────────────────────────────────────────────────────────────── */
.usr-dialog { background: #fff; border-radius: 20px; overflow: hidden; }

.usr-dialog__head {
  position: relative; overflow: hidden;
  display: flex; align-items: center; gap: 14px; padding: 22px;
  background: linear-gradient(140deg, #2563eb 0%, #1d4ed8 50%, #1e3a8a 100%);
}
.usr-dialog__blob {
  position: absolute; border-radius: 50%;
  background: rgba(255,255,255,0.10); pointer-events: none;
}
.usr-dialog__blob--a { width: 150px; height: 150px; top: -40px; right: -30px; }
.usr-dialog__blob--b { width: 70px;  height: 70px;  bottom: -20px; left: 50px; }

.usr-dialog__icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 46px; height: 46px; border-radius: 13px;
  background: rgba(255,255,255,0.18); backdrop-filter: blur(6px);
  border: 1px solid rgba(255,255,255,0.22); flex-shrink: 0; position: relative; z-index: 1;
}
.usr-dialog__head-text { flex: 1; position: relative; z-index: 1; }
.usr-dialog__head-title { font-size: 1rem; font-weight: 700; color: #fff; margin: 0 0 3px; }
.usr-dialog__head-sub   { font-size: 0.78rem; color: rgba(255,255,255,0.72); margin: 0; }

.usr-dialog__close {
  display: inline-flex; align-items: center; justify-content: center;
  width: 32px; height: 32px; border-radius: 50%;
  background: rgba(255,255,255,0.12); border: none; cursor: pointer;
  transition: background 0.15s; position: relative; z-index: 1;
}
.usr-dialog__close:hover { background: rgba(255,255,255,0.22); }

.usr-dialog__body {
  padding: 22px; display: flex; flex-direction: column; gap: 14px;
  max-height: 65vh; overflow-y: auto;
}
.usr-form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.usr-field { display: flex; flex-direction: column; gap: 6px; }
.usr-label { font-size: 0.8125rem; font-weight: 600; color: #374151; }
.usr-hint { font-size: 0.72rem; color: #9ca3af; margin: -8px 0 0; }

/* rôles toggles */
.usr-roles-grid { display: flex; flex-wrap: wrap; gap: 8px; }
.usr-role-toggle {
  padding: 5px 14px; border-radius: 100px; font-size: 0.78rem; font-weight: 600;
  border: 1px solid #e5e7eb; background: #f9fafb; color: #6b7280;
  cursor: pointer; transition: all 0.15s;
}
.usr-role-toggle:hover { border-color: #d1d5db; background: #f3f4f6; }
.usr-role-toggle--on { font-weight: 700; }

.usr-dialog__footer {
  display: flex; align-items: center; justify-content: flex-end; gap: 10px;
  padding: 16px 22px; border-top: 1px solid #f3f4f6;
}
.usr-cancel-btn {
  padding: 0 18px; height: 40px; border-radius: 10px;
  background: #f3f4f6; color: #374151; font-size: 0.875rem; font-weight: 600;
  border: none; cursor: pointer; transition: background 0.15s;
}
.usr-cancel-btn:hover:not(:disabled) { background: #e5e7eb; }
.usr-cancel-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.usr-submit-btn {
  display: inline-flex; align-items: center;
  padding: 0 22px; height: 40px; border-radius: 10px;
  background: #2563eb; color: #fff; font-size: 0.875rem; font-weight: 600;
  border: none; cursor: pointer;
  transition: background 0.18s, box-shadow 0.18s;
  box-shadow: 0 2px 8px rgba(37,99,235,0.25);
}
.usr-submit-btn:hover:not(:disabled) { background: #1d4ed8; }
.usr-submit-btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
