<script setup lang="ts">
definePageMeta({ module: 'DRH_PRESENCES' })

interface Employe { id: number; matricule: string; nomComplet: string }
interface Presence { id: number; employeId: number; date: string; statut: string; motif: string | null }

const STATUTS: { value: string; code: string; label: string; bg: string; color: string; motif: boolean }[] = [
  { value: 'PRESENT', code: 'P', label: 'Présent', bg: '#dcfce7', color: '#15803d', motif: false },
  { value: 'ABSENT', code: 'A', label: 'Absent', bg: '#fee2e2', color: '#b91c1c', motif: true },
  { value: 'CONGE', code: 'C', label: 'Congé', bg: '#dbeafe', color: '#1d4ed8', motif: true },
  { value: 'MALADIE', code: 'M', label: 'Maladie', bg: '#fef3c7', color: '#b45309', motif: true },
  { value: 'MISSION', code: 'J', label: 'Mission', bg: '#ede9fe', color: '#6d28d9', motif: true },
  { value: 'FERIE', code: 'F', label: 'Férié / week-end', bg: '#f3f4f6', color: '#6b7280', motif: false },
]
const statutMeta = (v: string) => STATUTS.find(s => s.value === v) ?? STATUTS[0]

const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()
const canWrite = computed(() => auth.hasAnyRole(['RESP_DRH']))

const aujourdhui = new Date()
const mois = ref(aujourdhui.getMonth() + 1)
const annee = ref(aujourdhui.getFullYear())
const MOIS = ['Janvier', 'Février', 'Mars', 'Avril', 'Mai', 'Juin', 'Juillet', 'Août', 'Septembre', 'Octobre', 'Novembre', 'Décembre']

const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const employes = ref<Employe[]>([])
const presences = ref<Presence[]>([])
const dialog = ref(false)
const cellule = reactive({ employeId: 0, employeNom: '', date: '', statut: 'PRESENT', motif: '' })

const jours = computed(() => {
  const n = new Date(annee.value, mois.value, 0).getDate()
  return Array.from({ length: n }, (_, i) => i + 1)
})

function statutParDefaut(jour: number): string {
  const d = new Date(annee.value, mois.value - 1, jour)
  const dow = d.getDay()
  return (dow === 0 || dow === 6) ? 'FERIE' : 'PRESENT'
}

function dateIso(jour: number): string {
  return `${annee.value}-${String(mois.value).padStart(2, '0')}-${String(jour).padStart(2, '0')}`
}

function statutDe(employeId: number, jour: number): string {
  const p = presences.value.find(p => p.employeId === employeId && p.date === dateIso(jour))
  return p ? p.statut : statutParDefaut(jour)
}

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [e, p] = await Promise.all([
      employes.value.length ? Promise.resolve(employes.value) : api<Employe[]>('/drh/employes'),
      api<Presence[]>('/drh/presences', { params: { mois: mois.value, annee: annee.value } }),
    ])
    employes.value = e
    presences.value = p
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les présences.')
  } finally {
    loading.value = false
  }
}
onMounted(() => { charger(); parametresStore.charger() })
watch([mois, annee], charger)

const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  nextTick(() => window.print())
}

function ouvrirCellule(emp: Employe, jour: number) {
  if (!canWrite) return
  const p = presences.value.find(p => p.employeId === emp.id && p.date === dateIso(jour))
  Object.assign(cellule, {
    employeId: emp.id, employeNom: emp.nomComplet, date: dateIso(jour),
    statut: p ? p.statut : statutParDefaut(jour), motif: p?.motif ?? '',
  })
  erreur.value = ''
  dialog.value = true
}

async function enregistrerCellule() {
  saving.value = true
  erreur.value = ''
  try {
    await api('/drh/presences', {
      method: 'PUT',
      body: { employeId: cellule.employeId, date: cellule.date, statut: cellule.statut, motif: cellule.motif || null },
    })
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

async function toutPresent() {
  saving.value = true
  erreur.value = ''
  try {
    await api('/drh/presences/tout-present', { method: 'POST', params: { mois: mois.value, annee: annee.value } })
    succes.value = 'Présences réinitialisées pour la période.'
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la réinitialisation.')
  } finally {
    saving.value = false
  }
}

const pourcentagePresence = (employeId: number) => {
  const comptes = jours.value.map(j => statutDe(employeId, j))
  const comptables = comptes.filter(s => s !== 'FERIE').length
  const prestes = comptes.filter(s => s === 'PRESENT' || s === 'MISSION').length
  return comptables > 0 ? Math.round((prestes / comptables) * 100) : 0
}
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Présences</h1>
        <p class="page-sub">Pointage journalier — cliquez sur une cellule pour saisir le statut du jour</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn variant="text" prepend-icon="mdi-clipboard-text-outline" to="/drh/presences/fiche-manuelle">Fiche manuelle</v-btn>
        <v-btn color="error" variant="tonal" rounded="lg" prepend-icon="mdi-printer-outline"
          :disabled="!employes.length" @click="imprimer">
          Imprimer
        </v-btn>
        <v-btn v-if="canWrite" color="primary" variant="tonal" rounded="lg" prepend-icon="mdi-check-all"
          :loading="saving" @click="toutPresent">
          Marquer tout présent
        </v-btn>
      </div>
    </div>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4 no-print" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" class="mb-4 no-print" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <div class="d-flex ga-3 mb-4 no-print">
      <v-select v-model="mois" :items="MOIS.map((m, i) => ({ title: m, value: i + 1 }))" label="Mois" variant="outlined"
        density="comfortable" rounded="lg" hide-details style="max-width: 200px" />
      <v-text-field v-model.number="annee" type="number" label="Année" variant="outlined" density="comfortable"
        rounded="lg" hide-details style="max-width: 140px" />
    </div>

    <div class="etat-print-header">
      <div class="etat-print-header__brand">
        <div class="etat-print-header__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-finance" size="16" color="white" />
        </div>
        <div>
          <span class="etat-print-header__company">{{ parametresStore.parametres.nom }}</span>
          <span class="etat-print-header__service">Direction des Ressources Humaines</span>
          <span class="etat-print-header__doc">Fiche de présence — {{ MOIS[mois - 1] }} {{ annee }}</span>
        </div>
      </div>
      <div class="etat-print-header__meta">
        <span>Imprimé le : {{ dateImpression }}</span>
      </div>
    </div>

    <div class="legende mb-3">
      <span v-for="s in STATUTS" :key="s.value" class="legende__item">
        <span class="legende__badge" :style="{ background: s.bg, color: s.color }">{{ s.code }}</span> {{ s.label }}
      </span>
    </div>

    <v-card class="classroom-card">
      <v-skeleton-loader v-if="loading" type="table" />
      <div v-else class="presence-scroll">
        <table class="presence-grid">
          <thead>
            <tr>
              <th class="presence-grid__emp-head">Employé</th>
              <th v-for="j in jours" :key="j" class="presence-grid__day">{{ j }}</th>
              <th class="presence-grid__pct">%</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="emp in employes" :key="emp.id">
              <td class="presence-grid__emp">{{ emp.nomComplet }}</td>
              <td v-for="j in jours" :key="j" class="presence-grid__cell" @click="ouvrirCellule(emp, j)">
                <span class="presence-grid__badge" :style="{ background: statutMeta(statutDe(emp.id, j)).bg, color: statutMeta(statutDe(emp.id, j)).color }">
                  {{ statutMeta(statutDe(emp.id, j)).code }}
                </span>
              </td>
              <td class="presence-grid__pct-val">{{ pourcentagePresence(emp.id) }}%</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!employes.length" class="pa-6 text-center text-medium-emphasis">Aucun employé actif.</div>
      </div>
    </v-card>

    <v-dialog v-model="dialog" max-width="420">
      <v-card>
        <v-card-title>Présence du {{ new Date(cellule.date).toLocaleDateString('fr-FR') }}</v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>
          <p class="mb-3 text-body-2"><strong>{{ cellule.employeNom }}</strong></p>
          <v-select v-model="cellule.statut" :items="STATUTS.map(s => ({ title: s.label, value: s.value }))"
            label="Statut" variant="outlined" density="comfortable" />
          <v-text-field v-if="statutMeta(cellule.statut).motif" v-model="cellule.motif" label="Motif" variant="outlined" density="comfortable" />
        </v-card-text>
        <v-divider />
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="saving" @click="dialog = false">Annuler</v-btn>
          <v-btn color="success" variant="flat" :loading="saving" @click="enregistrerCellule">Enregistrer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.legende { display: flex; flex-wrap: wrap; gap: 14px; }
.legende__item { display: inline-flex; align-items: center; gap: 6px; font-size: 0.78rem; color: #6b7280; }
.legende__badge { display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; border-radius: 5px; font-size: 0.68rem; font-weight: 700; }

.presence-scroll { overflow-x: auto; }
.presence-grid { border-collapse: collapse; font-size: 0.78rem; width: 100%; }
.presence-grid th, .presence-grid td { padding: 4px 6px; text-align: center; border-bottom: 1px solid #f3f4f6; }
.presence-grid__emp-head, .presence-grid__emp {
  text-align: left; position: sticky; left: 0; background: #fff; white-space: nowrap; padding-right: 14px;
  font-weight: 600; color: #111827;
}
.presence-grid__day { font-size: 0.68rem; color: #9ca3af; font-weight: 700; min-width: 26px; }
.presence-grid__pct { font-size: 0.68rem; color: #9ca3af; font-weight: 700; }
.presence-grid__pct-val { font-weight: 700; color: #111827; }
.presence-grid__cell { cursor: pointer; }
.presence-grid__cell:hover { background: #f9fafb; }
.presence-grid__badge {
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; border-radius: 5px; font-size: 0.7rem; font-weight: 700;
}
</style>
