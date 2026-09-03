<script setup lang="ts">
definePageMeta({ module: 'DRH_MISSIONS' })

interface Employe { id: number; matricule: string; nomComplet: string }
interface AgentMission {
  id: number; employeId: number | null; employeNomComplet: string | null; nomLibre: string | null
  nomAffiche: string; nationalite: string | null; numeroPasseport: string | null
  fonctionMission: string | null; civilite: string | null
}
interface Mission {
  id: number; numero: string; lieuMission: string; distanceVille: number | null; province: string | null
  territoire: string | null; superviseurEmployeId: number | null; superviseurNomComplet: string | null
  superviseurCivilite: string | null
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

interface AgentForm {
  externe: boolean
  employeId: number | null
  nomLibre: string
  nationalite: string
  numeroPasseport: string
  fonctionMission: string
  civilite: string
  /** Chef de mission (= superviseur principal) — un seul agent a la fois, uniquement parmi les employés
   * enregistrés (voir marquerChef). Résolu vers superviseurEmployeId/Civilite à l'enregistrement. */
  estChef: boolean
}
const agentVide = (): AgentForm => ({
  externe: false, employeId: null, nomLibre: '', nationalite: '', numeroPasseport: '', fonctionMission: '',
  civilite: '', estChef: false,
})

const formVide = () => ({
  lieuMission: '', distanceVille: null as number | null, province: '', territoire: '', butMission: '', dureeMission: '',
  dateDepart: new Date().toISOString().slice(0, 10), dateRetour: new Date().toISOString().slice(0, 10),
  moyenTransport: '', fraisMission: '',
  agents: [] as AgentForm[],
})
const form = reactive(formVide())
// Le chef de mission n'a de sens (et n'est exige par le backend) que si plus d'un agent est designe — voir
// OrdreMissionService.valider. Base sur la longueur du tableau, pas sur son contenu : une ligne ajoutee
// mais pas encore remplie doit deja faire apparaitre la case a cocher, pour que le DRH la voie avant de valider.
const estCollective = computed(() => form.agents.length > 1)

/** Une seule case "Chef de mission" a la fois : cocher l'une decoche les autres (comportement radio),
 * decocher la case deja active la libere (aucun chef selectionne pour l'instant). */
function marquerChef(i: number) {
  const nouvelEtat = !form.agents[i].estChef
  form.agents.forEach((a, j) => { a.estChef = j === i && nouvelEtat })
}

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
  form.agents.push(agentVide())
  erreur.value = ''
  dialog.value = true
}
function ouvrirEdition(m: Mission) {
  editing.value = m
  Object.assign(form, {
    lieuMission: m.lieuMission, distanceVille: m.distanceVille, province: m.province ?? '', territoire: m.territoire ?? '',
    butMission: m.butMission, dureeMission: m.dureeMission ?? '',
    dateDepart: m.dateDepart, dateRetour: m.dateRetour,
    moyenTransport: m.moyenTransport ?? '', fraisMission: m.fraisMission ?? '',
    agents: m.agents.map(a => ({
      externe: !a.employeId, employeId: a.employeId, nomLibre: a.nomLibre ?? '',
      nationalite: a.nationalite ?? '', numeroPasseport: a.numeroPasseport ?? '',
      fonctionMission: a.fonctionMission ?? '', civilite: a.civilite ?? '',
      estChef: !!a.employeId && a.employeId === m.superviseurEmployeId,
    })),
  })
  erreur.value = ''
  dialog.value = true
}

function ajouterAgent() { form.agents.push(agentVide()) }
function retirerAgent(i: number) { form.agents.splice(i, 1) }

async function enregistrer() {
  const agentsRenseignes = form.agents.filter(a => a.employeId || a.nomLibre.trim())
  if (!form.lieuMission || !form.butMission || !agentsRenseignes.length) {
    erreur.value = 'Lieu, but de mission et au moins un agent (employé ou personne externe) sont obligatoires.'
    return
  }
  const chef = agentsRenseignes.find(a => a.estChef && !a.externe)
  if (agentsRenseignes.length > 1 && !chef) {
    erreur.value = 'Cochez qui est le chef de mission (superviseur principal) : obligatoire pour un ordre de mission collectif.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    const body = {
      ...form,
      superviseurEmployeId: agentsRenseignes.length > 1 ? chef!.employeId : null,
      superviseurCivilite: agentsRenseignes.length > 1 ? (chef!.civilite || 'Monsieur') : null,
      agents: agentsRenseignes.map(a => ({
        employeId: a.externe ? null : a.employeId,
        nomLibre: a.externe ? a.nomLibre.trim() : null,
        nationalite: a.nationalite || null,
        numeroPasseport: a.numeroPasseport || null,
        fonctionMission: a.fonctionMission,
        civilite: a.civilite,
      })),
    }
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
        <template #item.agents="{ item }">{{ item.agents.map(a => a.nomAffiche).join(', ') }}</template>
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
            <v-col cols="12" md="4"><v-text-field v-model="form.lieuMission" label="Site de la mission *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model="form.territoire" label="Territoire" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-select v-model="form.province" :items="provinces" label="Province" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model.number="form.distanceVille" type="number" label="Distance (km)" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model="form.dateDepart" type="date" label="Date de départ *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model="form.dateRetour" type="date" label="Date de retour *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-select v-model="form.moyenTransport" :items="moyensTransport" label="Moyen de transport" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.dureeMission" label="Durée (texte libre)" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12">
              <v-textarea
                v-model="form.butMission" label="But de la mission *" variant="outlined" density="comfortable" rows="3"
                hint="Un point par ligne : chacune devient une puce distincte sur le document imprimé" persistent-hint
              />
            </v-col>
            <v-col cols="12">
              <v-text-field
                v-model="form.fraisMission" label="Frais de mission" variant="outlined" density="comfortable"
                placeholder="ex : À la charge de la société"
                hint="La provenance des frais, pas un montant" persistent-hint
              />
            </v-col>
          </v-row>

          <v-divider class="my-3" />
          <div class="d-flex align-center justify-space-between mb-2">
            <span class="text-subtitle-2">Agents en mission</span>
            <v-btn size="small" variant="tonal" prepend-icon="mdi-plus" @click="ajouterAgent">Ajouter un agent</v-btn>
          </div>
          <p v-if="estCollective" class="text-caption text-medium-emphasis mb-2">
            Cochez « Chef de mission » sur l'employé qui supervise : « Sous la supervision de… » sur le document imprimé.
          </p>
          <div v-for="(a, i) in form.agents" :key="i" class="agent-row">
            <div class="d-flex align-center justify-space-between mb-2 flex-wrap ga-2">
              <v-btn-toggle v-model="a.externe" density="compact" mandatory variant="outlined" divided color="primary">
                <v-btn :value="false" size="small">Employé enregistré</v-btn>
                <v-btn :value="true" size="small">Personne externe</v-btn>
              </v-btn-toggle>
              <div class="d-flex align-center ga-1">
                <v-checkbox-btn
                  v-if="estCollective && !a.externe"
                  :model-value="a.estChef" density="compact" color="primary"
                  @update:model-value="marquerChef(i)"
                />
                <span v-if="estCollective && !a.externe" class="text-caption">Chef de mission</span>
                <v-btn v-if="form.agents.length > 1" size="small" variant="text" color="error" icon="mdi-close" title="Retirer" @click="retirerAgent(i)" />
              </div>
            </div>
            <v-row dense>
              <v-col cols="12" md="5">
                <v-select v-if="!a.externe" v-model="a.employeId"
                  :items="employes.map(e => ({ title: `${e.matricule} — ${e.nomComplet}`, value: e.id }))"
                  label="Employé" variant="outlined" density="compact" />
                <v-text-field v-else v-model="a.nomLibre" label="Nom complet *" variant="outlined" density="compact" />
              </v-col>
              <v-col cols="6" md="3"><v-text-field v-model="a.nationalite" label="Nationalité" variant="outlined" density="compact" /></v-col>
              <v-col cols="6" md="4"><v-text-field v-model="a.numeroPasseport" label="N° Passeport" variant="outlined" density="compact" placeholder="si étranger" /></v-col>
              <v-col cols="7" md="8"><v-text-field v-model="a.fonctionMission" label="Fonction en mission" variant="outlined" density="compact" /></v-col>
              <v-col cols="5" md="4"><v-text-field v-model="a.civilite" label="Civilité" variant="outlined" density="compact" placeholder="Monsieur" /></v-col>
            </v-row>
          </div>
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

<style scoped>
.agent-row { padding: 12px; border: 1px solid #f0f0f0; border-radius: 12px; margin-bottom: 10px; }
</style>
