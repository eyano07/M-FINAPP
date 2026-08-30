<script setup lang="ts">
definePageMeta({ module: 'VENTES' })

interface Client {
  id: number
  code: string
  nom: string
  telephone?: string
  email?: string
  adresse?: string
  actif: boolean
}

const api = useApi()
const auth = useAuthStore()

const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const clients = ref<Client[]>([])

const dialog = ref(false)
const formRef = ref()
const editId = ref<number | null>(null)

const canWrite = computed(() => auth.hasAnyRole(['CAISSIER']))

const form = reactive({
  code: '',
  nom: '',
  telephone: '',
  email: '',
  adresse: '',
  actif: true,
})

const rules = {
  code: [(v: any) => !!v?.trim() || 'Code obligatoire'],
  nom: [(v: any) => !!v?.trim() || 'Nom obligatoire'],
}

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    clients.value = await api<Client[]>('/clients')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les clients.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function ouvrirCreation() {
  editId.value = null
  Object.assign(form, { code: '', nom: '', telephone: '', email: '', adresse: '', actif: true })
  erreur.value = ''
  dialog.value = true
}

function ouvrirEdition(c: Client) {
  editId.value = c.id
  Object.assign(form, {
    code: c.code,
    nom: c.nom,
    telephone: c.telephone || '',
    email: c.email || '',
    adresse: c.adresse || '',
    actif: c.actif,
  })
  erreur.value = ''
  dialog.value = true
}

async function enregistrer() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  saving.value = true
  erreur.value = ''
  try {
    const body = {
      code: form.code.trim(),
      nom: form.nom.trim(),
      telephone: form.telephone || null,
      email: form.email || null,
      adresse: form.adresse || null,
      actif: form.actif,
    }
    if (editId.value) {
      await api(`/clients/${editId.value}`, { method: 'PUT', body })
    } else {
      await api('/clients', { method: 'POST', body })
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Enregistrement impossible.')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Clients</h1>
        <p class="page-sub">Répertoire réutilisable · une vente peut aussi n'indiquer qu'un nom libre</p>
      </div>
      <div class="page-head-actions">
        <v-btn variant="outlined" color="primary" prepend-icon="mdi-refresh" rounded="lg" @click="charger">
          Actualiser
        </v-btn>
        <v-btn variant="outlined" color="primary" prepend-icon="mdi-cart-outline" rounded="lg" to="/ventes">
          Ventes
        </v-btn>
        <v-btn v-if="canWrite" color="primary" prepend-icon="mdi-plus" rounded="lg" @click="ouvrirCreation">
          Nouveau client
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-card class="classroom-card">
      <v-data-table
        :items="clients"
        :loading="loading"
        density="comfortable"
        items-per-page="15"
        :headers="[
          { title: 'Code', key: 'code' },
          { title: 'Nom', key: 'nom' },
          { title: 'Téléphone', key: 'telephone' },
          { title: 'E-mail', key: 'email' },
          { title: 'Adresse', key: 'adresse' },
          { title: 'Actif', key: 'actif', align: 'center' },
          { title: '', key: 'actions', sortable: false },
        ]"
      >
        <template #[`item.code`]="{ item }">
          <code class="text-caption text-primary">{{ item.code }}</code>
        </template>
        <template #[`item.telephone`]="{ item }">
          <span :class="item.telephone ? '' : 'text-medium-emphasis'">{{ item.telephone || '—' }}</span>
        </template>
        <template #[`item.email`]="{ item }">
          <span :class="item.email ? '' : 'text-medium-emphasis'">{{ item.email || '—' }}</span>
        </template>
        <template #[`item.adresse`]="{ item }">
          <span :class="item.adresse ? '' : 'text-medium-emphasis'">{{ item.adresse || '—' }}</span>
        </template>
        <template #[`item.actif`]="{ item }">
          <v-chip :color="item.actif ? 'success' : 'grey'" size="small" variant="tonal">
            {{ item.actif ? 'Actif' : 'Inactif' }}
          </v-chip>
        </template>
        <template #[`item.actions`]="{ item }">
          <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil" @click="ouvrirEdition(item)" />
        </template>
        <template #no-data>
          <div class="cl-empty">
            <v-icon icon="mdi-account-multiple-outline" size="42" color="#d1d5db" class="mb-2" />
            <p>Aucun client enregistré.</p>
          </div>
        </template>
      </v-data-table>
    </v-card>

    <!-- ── Dialogue ────────────────────────────────────────── -->
    <v-dialog v-model="dialog" max-width="520" persistent>
      <v-card class="dialog-card" rounded="xl">
        <div class="dialog-header">
          <v-icon icon="mdi-account-outline" size="28" color="white" class="mb-2" />
          <div class="text-h6 font-weight-bold text-white">
            {{ editId ? 'Modifier le client' : 'Nouveau client' }}
          </div>
        </div>

        <v-card-text class="pa-6 pt-5">
          <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" density="compact">{{ erreur }}</v-alert>

          <v-form ref="formRef">
            <v-text-field
              v-model="form.code"
              label="Code *"
              placeholder="ex : CLI-001"
              prepend-inner-icon="mdi-identifier"
              variant="outlined" density="comfortable" rounded="lg"
              :rules="rules.code" class="mb-3"
            />
            <v-text-field
              v-model="form.nom"
              label="Nom *"
              prepend-inner-icon="mdi-account-outline"
              variant="outlined" density="comfortable" rounded="lg"
              :rules="rules.nom" class="mb-3"
            />
            <v-text-field
              v-model="form.telephone"
              label="Téléphone"
              prepend-inner-icon="mdi-phone-outline"
              variant="outlined" density="comfortable" rounded="lg" class="mb-3"
            />
            <v-text-field
              v-model="form.email"
              label="E-mail"
              type="email"
              prepend-inner-icon="mdi-email-outline"
              variant="outlined" density="comfortable" rounded="lg" class="mb-3"
            />
            <v-text-field
              v-model="form.adresse"
              label="Adresse"
              prepend-inner-icon="mdi-map-marker-outline"
              variant="outlined" density="comfortable" rounded="lg" class="mb-2"
            />
            <v-switch v-model="form.actif" label="Actif" color="primary" density="compact" />
          </v-form>
        </v-card-text>

        <v-card-actions class="px-6 pb-5 pt-0 gap-2">
          <v-btn variant="tonal" rounded="lg" :disabled="saving" @click="dialog = false">Annuler</v-btn>
          <v-spacer />
          <v-btn color="primary" variant="flat" rounded="lg" :loading="saving" prepend-icon="mdi-check" @click="enregistrer">
            Enregistrer
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-head-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }

.cl-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 20px;
  color: #9ca3af;
  font-size: 0.85rem;
}
.cl-empty p { margin: 0; }

.dialog-card { overflow: hidden; }
.dialog-header {
  padding: 26px 26px 20px;
  text-align: center;
  background: linear-gradient(140deg, #34d399 0%, #16a34a 50%, #14532d 100%);
}
</style>
