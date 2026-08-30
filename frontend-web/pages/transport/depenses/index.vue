<script setup lang="ts">
definePageMeta({ module: 'TRANSPORT' })

interface Depense {
  id: number
  reference: string
  vehiculeId: number
  vehiculeImmatriculation: string
  trajetReference?: string
  type: string
  montant: number
  dateDepense: string
  compteChargeNumero?: string
  pieceReference?: string
}

interface Vehicule { id: number; immatriculation: string }

const api = useApi()
const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const depenses = ref<Depense[]>([])
const vehicules = ref<Vehicule[]>([])
const dialog = ref(false)

const canWrite = computed(() => auth.hasAnyRole(['LOGISTIQUE']))

const types = ['CARBURANT', 'ENTRETIEN', 'PEAGE', 'ASSURANCE', 'AUTRE']

const form = reactive({
  vehiculeId: null as number | null,
  type: 'CARBURANT',
  montant: 0,
  dateDepense: new Date().toISOString().slice(0, 10),
  compteChargeNumero: '' as string | null,
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    depenses.value = await api<Depense[]>('/transport/depenses')
    vehicules.value = await api<Vehicule[]>('/transport/vehicules')
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger les dépenses.'
  } finally {
    loading.value = false
  }
}
onMounted(charger)

const vehiculeItems = computed(() => vehicules.value.map(v => ({ title: v.immatriculation, value: v.id })))

function ouvrirCreation() {
  Object.assign(form, { vehiculeId: null, type: 'CARBURANT', montant: 0, dateDepense: new Date().toISOString().slice(0, 10), compteChargeNumero: '' })
  dialog.value = true
}

async function enregistrer() {
  if (!form.vehiculeId || !form.montant || form.montant <= 0) {
    erreur.value = 'Véhicule et montant positif sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    await api('/transport/depenses', {
      method: 'POST',
      body: {
        vehiculeId: form.vehiculeId,
        type: form.type,
        montant: form.montant,
        dateDepense: form.dateDepense,
        compteChargeNumero: form.compteChargeNumero || null,
      },
    })
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Enregistrement impossible.'
  } finally {
    saving.value = false
  }
}

async function comptabiliser(id: number) {
  erreur.value = ''
  try {
    await api(`/transport/depenses/${id}/comptabiliser`, { method: 'POST' })
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Comptabilisation impossible.'
  }
}

function fmt(v: number) {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(v)
}
function fmtDate(d: string) {
  return new Date(d).toLocaleDateString('fr-FR')
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Dépenses véhicules</h1>
        <p class="page-sub">Carburant, entretien, péage, assurance — comptabilisables en OHADA</p>
      </div>
      <v-btn v-if="canWrite" color="primary" prepend-icon="mdi-plus" @click="ouvrirCreation">
        Nouvelle dépense
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Référence', key: 'reference' },
          { title: 'Véhicule', key: 'vehiculeImmatriculation' },
          { title: 'Type', key: 'type' },
          { title: 'Date', key: 'dateDepense' },
          { title: 'Montant', key: 'montant', align: 'end' },
          { title: 'Compte', key: 'compteChargeNumero' },
          { title: 'Pièce', key: 'pieceReference' },
          { title: 'Actions', key: 'actions', sortable: false },
        ]"
        :items="depenses"
        :loading="loading"
        items-per-page="15"
      >
        <template #item.dateDepense="{ item }">{{ fmtDate(item.dateDepense) }}</template>
        <template #item.montant="{ item }">{{ fmt(item.montant) }}</template>
        <template #item.pieceReference="{ item }">{{ item.pieceReference || '—' }}</template>
        <template #item.actions="{ item }">
          <v-btn
            v-if="canWrite && !item.pieceReference"
            size="small"
            color="success"
            variant="tonal"
            @click="comptabiliser(item.id)"
          >
            Comptabiliser
          </v-btn>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="520">
      <v-card class="pa-6">
        <h2 class="text-h6 mb-4">Nouvelle dépense</h2>
        <v-select v-model="form.vehiculeId" :items="vehiculeItems" label="Véhicule" variant="outlined" density="comfortable" class="mb-3" />
        <v-select v-model="form.type" :items="types" label="Type" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model.number="form.montant" label="Montant (USD)" type="number" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.dateDepense" label="Date" type="date" variant="outlined" density="comfortable" class="mb-3" />
        <ComptabiliteSelecteurCompte v-model="form.compteChargeNumero" label="Compte de charge (défaut 611)" class="mb-3" />
        <div class="d-flex justify-end ga-3 mt-2">
          <v-btn variant="text" @click="dialog = false">Annuler</v-btn>
          <v-btn color="primary" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>
