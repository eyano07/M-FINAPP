<script setup lang="ts">
definePageMeta({ module: 'TRANSPORT' })

interface Vehicule {
  id: number
  immatriculation: string
  marque?: string
  modele?: string
  type?: string
  dateAcquisition?: string
  actif: boolean
}

interface Couts {
  totalDepenses: number
  totalDistanceKm: number
  coutParKm: number
}

const api = useApi()
const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const vehicules = ref<Vehicule[]>([])
const dialog = ref(false)
const editId = ref<number | null>(null)
const coutsDialog = ref(false)
const couts = ref<Couts | null>(null)

const canWrite = computed(() => auth.hasAnyRole(['LOGISTIQUE']))

const form = reactive({ immatriculation: '', marque: '', modele: '', type: '', dateAcquisition: '', actif: true })

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    vehicules.value = await api<Vehicule[]>('/transport/vehicules')
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger les véhicules.'
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function ouvrirCreation() {
  editId.value = null
  Object.assign(form, { immatriculation: '', marque: '', modele: '', type: '', dateAcquisition: '', actif: true })
  dialog.value = true
}

function ouvrirEdition(v: Vehicule) {
  editId.value = v.id
  Object.assign(form, {
    immatriculation: v.immatriculation, marque: v.marque || '', modele: v.modele || '',
    type: v.type || '', dateAcquisition: v.dateAcquisition || '', actif: v.actif,
  })
  dialog.value = true
}

async function enregistrer() {
  if (!form.immatriculation.trim()) {
    erreur.value = 'L\'immatriculation est obligatoire.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    const body = { ...form, dateAcquisition: form.dateAcquisition || null }
    if (editId.value) {
      await api(`/transport/vehicules/${editId.value}`, { method: 'PUT', body })
    } else {
      await api('/transport/vehicules', { method: 'POST', body })
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Enregistrement impossible.'
  } finally {
    saving.value = false
  }
}

async function voirCouts(v: Vehicule) {
  couts.value = null
  coutsDialog.value = true
  try {
    couts.value = await api<Couts>(`/transport/vehicules/${v.id}/couts`)
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger les coûts.'
    coutsDialog.value = false
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
        <h1 class="page-title">Véhicules</h1>
        <p class="page-sub">Parc de la flotte</p>
      </div>
      <v-btn v-if="canWrite" color="primary" prepend-icon="mdi-plus" @click="ouvrirCreation">
        Nouveau véhicule
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Immatriculation', key: 'immatriculation' },
          { title: 'Marque', key: 'marque' },
          { title: 'Modèle', key: 'modele' },
          { title: 'Type', key: 'type' },
          { title: 'Actif', key: 'actif' },
          { title: 'Actions', key: 'actions', sortable: false },
        ]"
        :items="vehicules"
        :loading="loading"
        items-per-page="15"
      >
        <template #item.actif="{ item }">
          <v-chip :color="item.actif ? 'success' : 'grey'" size="small" variant="flat">
            {{ item.actif ? 'Oui' : 'Non' }}
          </v-chip>
        </template>
        <template #item.actions="{ item }">
          <div class="d-flex ga-1">
            <v-btn size="small" variant="text" icon="mdi-chart-line" @click="voirCouts(item)" />
            <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil" @click="ouvrirEdition(item)" />
          </div>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="520">
      <v-card class="pa-6">
        <h2 class="text-h6 mb-4">{{ editId ? 'Modifier' : 'Nouveau' }} véhicule</h2>
        <v-text-field v-model="form.immatriculation" label="Immatriculation" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.marque" label="Marque" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.modele" label="Modèle" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.type" label="Type" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.dateAcquisition" label="Date d'acquisition" type="date" variant="outlined" density="comfortable" class="mb-3" />
        <v-switch v-model="form.actif" label="Actif" color="primary" />
        <div class="d-flex justify-end ga-3 mt-2">
          <v-btn variant="text" @click="dialog = false">Annuler</v-btn>
          <v-btn color="primary" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
        </div>
      </v-card>
    </v-dialog>

    <v-dialog v-model="coutsDialog" max-width="420">
      <v-card class="pa-6">
        <h2 class="text-h6 mb-4">Coûts du véhicule</h2>
        <div v-if="couts">
          <div class="d-flex justify-space-between mb-2"><span>Total dépenses</span><strong>{{ fmt(couts.totalDepenses) }} USD</strong></div>
          <div class="d-flex justify-space-between mb-2"><span>Distance totale</span><strong>{{ fmt(couts.totalDistanceKm) }} km</strong></div>
          <div class="d-flex justify-space-between"><span>Coût par km</span><strong>{{ fmt(couts.coutParKm) }} USD/km</strong></div>
        </div>
        <v-progress-circular v-else indeterminate color="primary" class="mx-auto d-block" />
        <div class="d-flex justify-end mt-4">
          <v-btn variant="text" @click="coutsDialog = false">Fermer</v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>
