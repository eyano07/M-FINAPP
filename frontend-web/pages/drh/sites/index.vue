<script setup lang="ts">
definePageMeta({ module: 'DRH_MISSIONS' })

interface Site { id: number; nom: string; localisation: string | null; actif: boolean }

const api = useApi()
const auth = useAuthStore()
const canWrite = computed(() => auth.hasAnyRole(['RESP_DRH']))

const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const sites = ref<Site[]>([])
const dialog = ref(false)
const editing = ref<Site | null>(null)
const form = reactive({ nom: '', localisation: '', actif: true })

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    sites.value = await api<Site[]>('/drh/sites')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les sites.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function ouvrirAjout() {
  editing.value = null
  Object.assign(form, { nom: '', localisation: '', actif: true })
  erreur.value = ''
  dialog.value = true
}
function ouvrirEdition(s: Site) {
  editing.value = s
  Object.assign(form, { nom: s.nom, localisation: s.localisation ?? '', actif: s.actif })
  erreur.value = ''
  dialog.value = true
}

async function enregistrer() {
  if (!form.nom) {
    erreur.value = 'Le nom du site est obligatoire.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    if (editing.value) {
      await api(`/drh/sites/${editing.value.id}`, { method: 'PUT', body: form })
    } else {
      await api('/drh/sites', { method: 'POST', body: form })
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Sites opérationnels</h1>
        <p class="page-sub">Sites sur lesquels les superviseurs effectuent des rotations</p>
      </div>
      <v-btn v-if="canWrite" color="success" variant="flat" rounded="lg" prepend-icon="mdi-plus" @click="ouvrirAjout">
        Nouveau site
      </v-btn>
    </div>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Nom', key: 'nom' },
          { title: 'Localisation', key: 'localisation' },
          { title: 'Statut', key: 'actif' },
          { title: 'Actions', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="sites"
        :loading="loading"
        items-per-page="25"
      >
        <template #item.localisation="{ item }">{{ item.localisation ?? '—' }}</template>
        <template #item.actif="{ item }">
          <span class="chip-soft" :style="item.actif ? { background: '#dcfce7', color: '#15803d' } : { background: '#f3f4f6', color: '#6b7280' }">
            {{ item.actif ? 'Actif' : 'Inactif' }}
          </span>
        </template>
        <template #item.actions="{ item }">
          <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil-outline" title="Modifier" @click="ouvrirEdition(item)" />
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">Aucun site enregistré.</div>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="480">
      <v-card>
        <v-card-title>{{ editing ? 'Modifier le site' : 'Nouveau site' }}</v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>
          <v-text-field v-model="form.nom" label="Nom *" variant="outlined" density="comfortable" class="mb-2" />
          <v-text-field v-model="form.localisation" label="Localisation" variant="outlined" density="comfortable" class="mb-2" />
          <v-checkbox v-model="form.actif" hide-details label="Site actif" />
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
