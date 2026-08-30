<script setup lang="ts">
definePageMeta({ module: 'DRH_PRESENCES' })

interface Employe { id: number; matricule: string; nomComplet: string }
interface Sortie {
  id: number; employeId: number; employeNomComplet: string
  dateSortie: string; heureSortie: string; heureRetour: string | null
  motif: string; notes: string | null; dureeMinutes: number | null
}

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
const sorties = ref<Sortie[]>([])
const employes = ref<Employe[]>([])
const dialog = ref(false)
const editing = ref<Sortie | null>(null)

const formVide = () => ({
  employeId: null as number | null,
  dateSortie: new Date().toISOString().slice(0, 10),
  heureSortie: new Date().toTimeString().slice(0, 5),
  heureRetour: '',
  motif: '', notes: '',
})
const form = reactive(formVide())

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [s, e] = await Promise.all([
      api<Sortie[]>('/drh/sorties', { params: { mois: mois.value, annee: annee.value } }),
      employes.value.length ? Promise.resolve(employes.value) : api<Employe[]>('/drh/employes'),
    ])
    sorties.value = s
    employes.value = e
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les sorties.')
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

function ouvrirAjout() {
  editing.value = null
  Object.assign(form, formVide())
  erreur.value = ''
  dialog.value = true
}
function ouvrirEdition(s: Sortie) {
  editing.value = s
  Object.assign(form, {
    employeId: s.employeId, dateSortie: s.dateSortie, heureSortie: s.heureSortie,
    heureRetour: s.heureRetour ?? '', motif: s.motif, notes: s.notes ?? '',
  })
  erreur.value = ''
  dialog.value = true
}

async function enregistrer() {
  if (!form.employeId || !form.motif) {
    erreur.value = 'Employé et motif sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    const body = { ...form, heureRetour: form.heureRetour || null }
    if (editing.value) {
      await api(`/drh/sorties/${editing.value.id}`, { method: 'PUT', body })
    } else {
      await api('/drh/sorties', { method: 'POST', body })
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

async function supprimer(s: Sortie) {
  saving.value = true
  erreur.value = ''
  try {
    await api(`/drh/sorties/${s.id}`, { method: 'DELETE' })
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la suppression.')
  } finally {
    saving.value = false
  }
}

const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
const fmtDuree = (m: number | null) => (m === null || m === undefined ? '—' : `${Math.floor(m / 60)}h${String(m % 60).padStart(2, '0')}`)
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Sorties de travailleurs</h1>
        <p class="page-sub">Sorties pendant les heures de service — motif et durée</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn color="error" variant="tonal" rounded="lg" prepend-icon="mdi-printer-outline" :disabled="!sorties.length" @click="imprimer">
          Imprimer
        </v-btn>
        <v-btn v-if="canWrite" color="success" variant="flat" rounded="lg" prepend-icon="mdi-plus" @click="ouvrirAjout">
          Nouvelle sortie
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
          <span class="etat-print-header__doc">Rapport de sorties — {{ MOIS[mois - 1] }} {{ annee }}</span>
        </div>
      </div>
      <div class="etat-print-header__meta">
        <span>Imprimé le : {{ dateImpression }}</span>
      </div>
    </div>

    <v-card class="classroom-card no-print">
      <v-data-table
        :headers="[
          { title: 'Employé', key: 'employeNomComplet' },
          { title: 'Date', key: 'dateSortie' },
          { title: 'Sortie', key: 'heureSortie' },
          { title: 'Retour', key: 'heureRetour' },
          { title: 'Durée', key: 'dureeMinutes' },
          { title: 'Motif', key: 'motif' },
          { title: 'Actions', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="sorties"
        :loading="loading"
        items-per-page="25"
      >
        <template #item.dateSortie="{ item }">{{ fmtDate(item.dateSortie) }}</template>
        <template #item.heureRetour="{ item }">{{ item.heureRetour ?? '—' }}</template>
        <template #item.dureeMinutes="{ item }">{{ fmtDuree(item.dureeMinutes) }}</template>
        <template #item.actions="{ item }">
          <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil-outline" title="Modifier" @click="ouvrirEdition(item)" />
          <v-btn v-if="canWrite" size="small" variant="text" color="error" icon="mdi-delete-outline" title="Supprimer" @click="supprimer(item)" />
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">Aucune sortie pour cette période.</div>
        </template>
      </v-data-table>
    </v-card>

    <!-- Version imprimable : table simple, sans pagination ni virtualisation
         (v-data-table ci-dessus n'imprime pas correctement au-dela d'une page). -->
    <table class="etat-table print-only">
      <thead>
        <tr><th>Employé</th><th>Date</th><th>Sortie</th><th>Retour</th><th>Durée</th><th>Motif</th></tr>
      </thead>
      <tbody>
        <tr v-for="s in sorties" :key="s.id">
          <td>{{ s.employeNomComplet }}</td><td>{{ fmtDate(s.dateSortie) }}</td>
          <td>{{ s.heureSortie }}</td><td>{{ s.heureRetour ?? '—' }}</td>
          <td>{{ fmtDuree(s.dureeMinutes) }}</td><td>{{ s.motif }}</td>
        </tr>
      </tbody>
    </table>

    <v-dialog v-model="dialog" max-width="560">
      <v-card>
        <v-card-title>{{ editing ? 'Modifier la sortie' : 'Nouvelle sortie' }}</v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>
          <v-row>
            <v-col cols="12">
              <v-select v-model="form.employeId" :items="employes.map(e => ({ title: `${e.matricule} — ${e.nomComplet}`, value: e.id }))"
                label="Employé *" variant="outlined" density="comfortable" />
            </v-col>
            <v-col cols="12" md="4"><v-text-field v-model="form.dateSortie" type="date" label="Date *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model="form.heureSortie" type="time" label="Heure de sortie *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model="form.heureRetour" type="time" label="Heure de retour" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12"><v-text-field v-model="form.motif" label="Motif *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12"><v-textarea v-model="form.notes" label="Notes" variant="outlined" density="comfortable" rows="2" /></v-col>
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

<style scoped>
.print-only { display: none; }
.etat-table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
.etat-table th { text-align: left; padding: 7px 8px; font-size: 0.68rem; text-transform: uppercase; color: #6b7280; border-bottom: 1px solid #e5e7eb; }
.etat-table td { padding: 6px 8px; border-bottom: 1px solid #f3f4f6; color: #374151; }

@media print {
  .print-only { display: table; }
}
</style>
