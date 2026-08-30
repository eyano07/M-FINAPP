<script setup lang="ts">
definePageMeta({ module: 'TRANSPORT' })

interface Trajet {
  id: number
  reference: string
  vehiculeId: number
  vehiculeImmatriculation: string
  origine?: string
  destination?: string
  distanceKm: number
  dateDepart?: string
  statut: string
}

interface Vehicule { id: number; immatriculation: string }

const api = useApi()
const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const trajets = ref<Trajet[]>([])
const vehicules = ref<Vehicule[]>([])
const dialog = ref(false)
const editId = ref<number | null>(null)

const canWrite = computed(() => auth.hasAnyRole(['LOGISTIQUE']))

const statutColor: Record<string, string> = {
  PLANIFIE: 'grey',
  EN_COURS: 'blue',
  TERMINE: 'success',
  ANNULE: 'error',
}
const statuts = ['PLANIFIE', 'EN_COURS', 'TERMINE', 'ANNULE']

const form = reactive({
  vehiculeId: null as number | null,
  origine: '',
  destination: '',
  distanceKm: 0,
  dateDepart: '',
  dateArrivee: '',
  statut: 'PLANIFIE',
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    trajets.value = await api<Trajet[]>('/transport/trajets')
    vehicules.value = await api<Vehicule[]>('/transport/vehicules')
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger les trajets.'
  } finally {
    loading.value = false
  }
}
onMounted(charger)

const vehiculeItems = computed(() => vehicules.value.map(v => ({ title: v.immatriculation, value: v.id })))

function ouvrirCreation() {
  editId.value = null
  Object.assign(form, { vehiculeId: null, origine: '', destination: '', distanceKm: 0, dateDepart: '', dateArrivee: '', statut: 'PLANIFIE' })
  dialog.value = true
}

function ouvrirEdition(t: Trajet) {
  editId.value = t.id
  Object.assign(form, {
    vehiculeId: t.vehiculeId, origine: t.origine || '', destination: t.destination || '',
    distanceKm: t.distanceKm, dateDepart: '', dateArrivee: '', statut: t.statut,
  })
  dialog.value = true
}

async function enregistrer() {
  if (!form.vehiculeId) {
    erreur.value = 'Le véhicule est obligatoire.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    const body = {
      vehiculeId: form.vehiculeId,
      origine: form.origine,
      destination: form.destination,
      distanceKm: form.distanceKm,
      dateDepart: form.dateDepart ? new Date(form.dateDepart).toISOString() : null,
      dateArrivee: form.dateArrivee ? new Date(form.dateArrivee).toISOString() : null,
      statut: form.statut,
    }
    if (editId.value) {
      await api(`/transport/trajets/${editId.value}`, { method: 'PUT', body })
    } else {
      await api('/transport/trajets', { method: 'POST', body })
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Enregistrement impossible.'
  } finally {
    saving.value = false
  }
}

function fmt(v: number) {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(v)
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Trajets</h1>
        <p class="page-sub">Déplacements de la flotte</p>
      </div>
      <v-btn v-if="canWrite" color="primary" prepend-icon="mdi-plus" @click="ouvrirCreation">
        Nouveau trajet
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Référence', key: 'reference' },
          { title: 'Véhicule', key: 'vehiculeImmatriculation' },
          { title: 'Origine', key: 'origine' },
          { title: 'Destination', key: 'destination' },
          { title: 'Distance (km)', key: 'distanceKm', align: 'end' },
          { title: 'Statut', key: 'statut' },
          { title: '', key: 'actions', sortable: false },
        ]"
        :items="trajets"
        :loading="loading"
        items-per-page="15"
      >
        <template #item.distanceKm="{ item }">{{ fmt(item.distanceKm) }}</template>
        <template #item.statut="{ item }">
          <v-chip :color="statutColor[item.statut] || 'grey'" size="small" variant="flat">{{ item.statut }}</v-chip>
        </template>
        <template #item.actions="{ item }">
          <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil" @click="ouvrirEdition(item)" />
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="560">
      <v-card class="pa-6">
        <h2 class="text-h6 mb-4">{{ editId ? 'Modifier' : 'Nouveau' }} trajet</h2>
        <v-select v-model="form.vehiculeId" :items="vehiculeItems" label="Véhicule" variant="outlined" density="comfortable" class="mb-3" />
        <v-row>
          <v-col cols="6"><v-text-field v-model="form.origine" label="Origine" variant="outlined" density="comfortable" /></v-col>
          <v-col cols="6"><v-text-field v-model="form.destination" label="Destination" variant="outlined" density="comfortable" /></v-col>
        </v-row>
        <v-row>
          <v-col cols="6"><v-text-field v-model="form.dateDepart" label="Départ" type="datetime-local" variant="outlined" density="comfortable" /></v-col>
          <v-col cols="6"><v-text-field v-model="form.dateArrivee" label="Arrivée" type="datetime-local" variant="outlined" density="comfortable" /></v-col>
        </v-row>
        <v-row>
          <v-col cols="6"><v-text-field v-model.number="form.distanceKm" label="Distance (km)" type="number" variant="outlined" density="comfortable" /></v-col>
          <v-col cols="6"><v-select v-model="form.statut" :items="statuts" label="Statut" variant="outlined" density="comfortable" /></v-col>
        </v-row>
        <div class="d-flex justify-end ga-3 mt-2">
          <v-btn variant="text" @click="dialog = false">Annuler</v-btn>
          <v-btn color="primary" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>
