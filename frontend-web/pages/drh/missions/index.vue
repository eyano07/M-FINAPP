<script setup lang="ts">
definePageMeta({ module: 'DRH_MISSIONS' })

interface Employe { id: number; matricule: string; nomComplet: string }
interface AgentMission { id: number; employeId: number; employeNomComplet: string; fonctionMission: string | null; civilite: string | null }
interface Mission {
  id: number; numero: string; lieuMission: string; distanceVille: number | null; province: string | null
  butMission: string; dureeMission: string | null; dateDepart: string; dateRetour: string
  moyenTransport: string | null; fraisMission: string | null; collective: boolean; agents: AgentMission[]
}

const api = useApi()
const auth = useAuthStore()
const canWrite = computed(() => auth.hasAnyRole(['RESP_DRH']))

const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const missions = ref<Mission[]>([])
const employes = ref<Employe[]>([])
const provinces = ref<string[]>([])
const moyensTransport = ref<string[]>([])
const dialog = ref(false)
const editing = ref<Mission | null>(null)

const formVide = () => ({
  lieuMission: '', distanceVille: null as number | null, province: '', butMission: '', dureeMission: '',
  dateDepart: new Date().toISOString().slice(0, 10), dateRetour: new Date().toISOString().slice(0, 10),
  moyenTransport: '', fraisMission: '',
  agents: [] as { employeId: number | null; fonctionMission: string; civilite: string }[],
})
const form = reactive(formVide())

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [m, e, p, t] = await Promise.all([
      api<Mission[]>('/drh/missions'),
      employes.value.length ? Promise.resolve(employes.value) : api<Employe[]>('/drh/employes'),
      provinces.value.length ? Promise.resolve(provinces.value) : api<string[]>('/drh/missions/provinces'),
      moyensTransport.value.length ? Promise.resolve(moyensTransport.value) : api<string[]>('/drh/missions/moyens-transport'),
    ])
    missions.value = m
    employes.value = e
    provinces.value = p
    moyensTransport.value = t
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les ordres de mission.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function ouvrirAjout() {
  editing.value = null
  Object.assign(form, formVide())
  form.agents.push({ employeId: null, fonctionMission: '', civilite: '' })
  erreur.value = ''
  dialog.value = true
}
function ouvrirEdition(m: Mission) {
  editing.value = m
  Object.assign(form, {
    lieuMission: m.lieuMission, distanceVille: m.distanceVille, province: m.province ?? '',
    butMission: m.butMission, dureeMission: m.dureeMission ?? '',
    dateDepart: m.dateDepart, dateRetour: m.dateRetour,
    moyenTransport: m.moyenTransport ?? '', fraisMission: m.fraisMission ?? '',
    agents: m.agents.map(a => ({ employeId: a.employeId, fonctionMission: a.fonctionMission ?? '', civilite: a.civilite ?? '' })),
  })
  erreur.value = ''
  dialog.value = true
}

function ajouterAgent() { form.agents.push({ employeId: null, fonctionMission: '', civilite: '' }) }
function retirerAgent(i: number) { form.agents.splice(i, 1) }

async function enregistrer() {
  if (!form.lieuMission || !form.butMission || !form.agents.some(a => a.employeId)) {
    erreur.value = 'Lieu, but de mission et au moins un agent sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    const body = { ...form, agents: form.agents.filter(a => a.employeId) }
    if (editing.value) {
      await api(`/drh/missions/${editing.value.id}`, { method: 'PUT', body })
    } else {
      await api('/drh/missions', { method: 'POST', body })
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

async function supprimer(m: Mission) {
  saving.value = true
  erreur.value = ''
  try {
    await api(`/drh/missions/${m.id}`, { method: 'DELETE' })
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la suppression.')
  } finally {
    saving.value = false
  }
}

const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Ordres de mission</h1>
        <p class="page-sub">Missions individuelles ou collectives sur le terrain</p>
      </div>
      <v-btn v-if="canWrite" color="success" variant="flat" rounded="lg" prepend-icon="mdi-plus" @click="ouvrirAjout">
        Nouvel ordre de mission
      </v-btn>
    </div>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'N°', key: 'numero' },
          { title: 'Lieu', key: 'lieuMission' },
          { title: 'Départ', key: 'dateDepart' },
          { title: 'Retour', key: 'dateRetour' },
          { title: 'Agents', key: 'agents' },
          { title: 'Type', key: 'collective' },
          { title: 'Actions', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="missions"
        :loading="loading"
        items-per-page="25"
      >
        <template #item.dateDepart="{ item }">{{ fmtDate(item.dateDepart) }}</template>
        <template #item.dateRetour="{ item }">{{ fmtDate(item.dateRetour) }}</template>
        <template #item.agents="{ item }">{{ item.agents.map(a => a.employeNomComplet).join(', ') }}</template>
        <template #item.collective="{ item }">
          <span class="chip-soft" :style="item.collective ? { background: '#ede9fe', color: '#6d28d9' } : { background: '#f3f4f6', color: '#6b7280' }">
            {{ item.collective ? 'Collective' : 'Individuelle' }}
          </span>
        </template>
        <template #item.actions="{ item }">
          <v-btn size="small" variant="text" icon="mdi-eye-outline" title="Détail" :to="`/drh/missions/${item.id}`" />
          <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil-outline" title="Modifier" @click="ouvrirEdition(item)" />
          <v-btn v-if="canWrite" size="small" variant="text" color="error" icon="mdi-delete-outline" title="Supprimer" @click="supprimer(item)" />
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">Aucun ordre de mission enregistré.</div>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="800" scrollable>
      <v-card>
        <v-card-title>{{ editing ? 'Modifier l’ordre de mission' : 'Nouvel ordre de mission' }}</v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>
          <v-row>
            <v-col cols="12" md="6"><v-text-field v-model="form.lieuMission" label="Lieu de mission *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-select v-model="form.province" :items="provinces" label="Province" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model.number="form.distanceVille" type="number" label="Distance (km)" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model="form.dateDepart" type="date" label="Date de départ *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model="form.dateRetour" type="date" label="Date de retour *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-select v-model="form.moyenTransport" :items="moyensTransport" label="Moyen de transport" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.dureeMission" label="Durée (texte libre)" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12"><v-textarea v-model="form.butMission" label="But de la mission *" variant="outlined" density="comfortable" rows="2" /></v-col>
            <v-col cols="12"><v-text-field v-model="form.fraisMission" label="Frais de mission" variant="outlined" density="comfortable" /></v-col>
          </v-row>

          <v-divider class="my-3" />
          <div class="d-flex align-center justify-space-between mb-2">
            <span class="text-subtitle-2">Agents en mission</span>
            <v-btn size="small" variant="tonal" prepend-icon="mdi-plus" @click="ajouterAgent">Ajouter un agent</v-btn>
          </div>
          <v-row v-for="(a, i) in form.agents" :key="i" dense align="center">
            <v-col cols="12" md="5">
              <v-select v-model="a.employeId" :items="employes.map(e => ({ title: `${e.matricule} — ${e.nomComplet}`, value: e.id }))"
                label="Employé" variant="outlined" density="compact" />
            </v-col>
            <v-col cols="12" md="4"><v-text-field v-model="a.fonctionMission" label="Fonction en mission" variant="outlined" density="compact" /></v-col>
            <v-col cols="12" md="2"><v-text-field v-model="a.civilite" label="Civilité" variant="outlined" density="compact" /></v-col>
            <v-col cols="12" md="1">
              <v-btn v-if="form.agents.length > 1" size="small" variant="text" color="error" icon="mdi-close" @click="retirerAgent(i)" />
            </v-col>
          </v-row>
        </v-card-text>
        <v-divider />
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="saving" @click="dialog = false">Annuler</v-btn>
          <v-btn color="success" variant="flat" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>
