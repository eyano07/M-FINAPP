<script setup lang="ts">
// Voir /logistique/articles : le module en LECTURE du caissier sert la vente,
// pas la consultation des ecrans logistiques.
definePageMeta({ module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'GEST_PATRIMOINE', 'ADMIN'] })

interface Entrepot {
  id: number
  code: string
  nom: string
  localisation?: string
  actif: boolean
}

const api = useApi()
const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const entrepots = ref<Entrepot[]>([])
const dialog = ref(false)
const editId = ref<number | null>(null)

const canWrite = computed(() => auth.hasAnyRole(['LOGISTIQUE']))

const form = reactive({ code: '', nom: '', localisation: '', actif: true })

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    entrepots.value = await api<Entrepot[]>('/logistique/entrepots')
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger les entrepôts.'
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function ouvrirCreation() {
  editId.value = null
  Object.assign(form, { code: '', nom: '', localisation: '', actif: true })
  dialog.value = true
}

function ouvrirEdition(e: Entrepot) {
  editId.value = e.id
  Object.assign(form, { code: e.code, nom: e.nom, localisation: e.localisation || '', actif: e.actif })
  dialog.value = true
}

async function enregistrer() {
  if (!form.code.trim() || !form.nom.trim()) {
    erreur.value = 'Code et nom sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    if (editId.value) {
      await api(`/logistique/entrepots/${editId.value}`, { method: 'PUT', body: { ...form } })
    } else {
      await api('/logistique/entrepots', { method: 'POST', body: { ...form } })
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Enregistrement impossible.'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Entrepôts</h1>
        <p class="page-sub">Lieux de stockage</p>
      </div>
      <v-btn v-if="canWrite" color="primary" prepend-icon="mdi-plus" @click="ouvrirCreation">
        Nouvel entrepôt
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Code', key: 'code' },
          { title: 'Nom', key: 'nom' },
          { title: 'Localisation', key: 'localisation' },
          { title: 'Actif', key: 'actif' },
          { title: '', key: 'actions', sortable: false },
        ]"
        :items="entrepots"
        :loading="loading"
        items-per-page="15"
      >
        <template #item.actif="{ item }">
          <v-chip :color="item.actif ? 'success' : 'grey'" size="small" variant="flat">
            {{ item.actif ? 'Oui' : 'Non' }}
          </v-chip>
        </template>
        <template #item.actions="{ item }">
          <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil" @click="ouvrirEdition(item)" />
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="480">
      <v-card class="pa-6">
        <h2 class="text-h6 mb-4">{{ editId ? 'Modifier' : 'Nouvel' }} entrepôt</h2>
        <v-text-field v-model="form.code" label="Code" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.nom" label="Nom" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.localisation" label="Localisation" variant="outlined" density="comfortable" class="mb-3" />
        <v-switch v-model="form.actif" label="Actif" color="primary" />
        <div class="d-flex justify-end ga-3 mt-2">
          <v-btn variant="text" @click="dialog = false">Annuler</v-btn>
          <v-btn color="primary" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>
