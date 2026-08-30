<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Stock de bouteilles vides, par boisson.
 *
 * Le nombre de casiers n'est pas stocké : il se déduit du compteur de vides et
 * de la contenance. Ce compteur ne se saisit jamais directement — il résulte
 * des ventes (automatique), des réceptions et de la casse.
 */
interface Emballage {
  id: number
  code: string
  libelle: string
  format?: string
  articleBoissonId: number
  articleBoissonCode: string
  articleBoissonLibelle: string
  contenanceCasier: number
  bouteillesVides: number
  casiers: number
  bouteillesRestantes: number
  actif: boolean
}
interface Boisson { id: number; code: string; libelle: string; type: string }

const api = useApi()
const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const emballages = ref<Emballage[]>([])
const boissons = ref<Boisson[]>([])
const dialog = ref(false)
const editId = ref<number | null>(null)

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT']))

const FORMATS = ['33CL', '50CL', '65CL', '1L', '1.5L']

const form = reactive({
  code: '',
  libelle: '',
  articleBoissonId: null as number | null,
  contenanceCasier: 24,
  format: '' as string | null,
  actif: true,
})

/** Boissons sans conditionnement : une boisson n'en a qu'un seul. */
const boissonsDisponibles = computed(() => {
  const prises = new Set(
    emballages.value.filter(e => e.id !== editId.value).map(e => e.articleBoissonId))
  return boissons.value.filter(b => !prises.has(b.id))
})

const totalVides = computed(() => emballages.value.reduce((s, e) => s + (e.bouteillesVides || 0), 0))
const totalCasiers = computed(() => emballages.value.reduce((s, e) => s + (e.casiers || 0), 0))

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [emb, carte] = await Promise.all([
      api<Emballage[]>('/restaurant/emballages'),
      api<Boisson[]>('/restaurant/carte').catch(() => []),
    ])
    emballages.value = emb
    boissons.value = carte.filter(a => a.type === 'BOISSON')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les emballages.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function ouvrirCreation() {
  editId.value = null
  Object.assign(form, {
    code: '', libelle: '', articleBoissonId: null, contenanceCasier: 24,
    format: '', actif: true,
  })
  erreur.value = ''
  dialog.value = true
}

function ouvrirEdition(e: Emballage) {
  editId.value = e.id
  Object.assign(form, {
    code: e.code,
    libelle: e.libelle,
    articleBoissonId: e.articleBoissonId,
    contenanceCasier: e.contenanceCasier,
    format: e.format || '',
    actif: e.actif,
  })
  erreur.value = ''
  dialog.value = true
}

async function enregistrer() {
  if (!form.code.trim() || !form.libelle.trim() || !form.articleBoissonId) {
    erreur.value = 'Code, libellé et boisson sont obligatoires.'
    return
  }
  if (!form.contenanceCasier || form.contenanceCasier <= 0) {
    erreur.value = 'Indiquez combien de bouteilles contient un casier.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    const body = {
      code: form.code,
      libelle: form.libelle,
      articleBoissonId: form.articleBoissonId,
      contenanceCasier: form.contenanceCasier,
      format: form.format || null,
      actif: form.actif,
    }
    if (editId.value) {
      await api(`/restaurant/emballages/${editId.value}`, { method: 'PUT', body })
    } else {
      await api('/restaurant/emballages', { method: 'POST', body })
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

const fmtNb = (n: number) => new Intl.NumberFormat('fr-FR').format(n || 0)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Bouteilles vides</h1>
        <p class="page-sub">Stock de consigne par boisson — casiers et bouteilles</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-truck-delivery-outline" to="/restaurant/receptions">
          Réception
        </v-btn>
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-swap-horizontal-bold" to="/restaurant/emballages/mouvements">
          Mouvements
        </v-btn>
        <v-btn v-if="canWrite" color="success" variant="flat" rounded="lg" prepend-icon="mdi-plus" @click="ouvrirCreation">
          Nouveau conditionnement
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-alert type="info" variant="tonal" density="compact" rounded="lg" class="mb-4">
      Le stock se remplit tout seul : chaque bouteille vendue y revient. Une réception de casiers pleins le diminue
      d'autant (échange de consigne).
    </v-alert>

    <div class="rst-stats mb-4">
      <div class="rst-stat rst-stat--vides">
        <v-icon icon="mdi-bottle-wine-outline" size="18" />
        <span class="rst-stat__val">{{ fmtNb(totalVides) }}</span>
        <span class="rst-stat__lbl">Bouteilles vides</span>
      </div>
      <div class="rst-stat rst-stat--casiers">
        <v-icon icon="mdi-package-variant-closed" size="18" />
        <span class="rst-stat__val">{{ fmtNb(totalCasiers) }}</span>
        <span class="rst-stat__lbl">Casiers complets</span>
      </div>
    </div>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Boisson', key: 'articleBoissonLibelle' },
          { title: 'Format', key: 'format' },
          { title: 'Casier', key: 'contenanceCasier', align: 'end' },
          { title: 'Stock de vides', key: 'stock' },
          { title: 'Total bouteilles', key: 'bouteillesVides', align: 'end' },
          { title: 'Actions', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="emballages"
        :loading="loading"
        items-per-page="25"
      >
        <template #item.articleBoissonLibelle="{ item }">
          <span class="font-weight-medium">{{ item.articleBoissonLibelle }}</span>
          <span class="text-medium-emphasis text-caption d-block">{{ item.code }}</span>
        </template>
        <template #item.format="{ item }">{{ item.format || '—' }}</template>
        <template #item.contenanceCasier="{ item }">{{ item.contenanceCasier }} bout.</template>
        <template #item.stock="{ item }">
          <span class="rst-casiers">
            <strong>{{ item.casiers }}</strong> casier{{ item.casiers > 1 ? 's' : '' }}
            <span class="text-medium-emphasis"> + </span>
            <strong>{{ item.bouteillesRestantes }}</strong> bouteille{{ item.bouteillesRestantes > 1 ? 's' : '' }}
          </span>
        </template>
        <template #item.bouteillesVides="{ item }">{{ fmtNb(item.bouteillesVides) }}</template>
        <template #item.actions="{ item }">
          <v-btn size="small" variant="text" icon="mdi-history" title="Voir les mouvements"
            :to="`/restaurant/emballages/mouvements?emballage=${item.id}`" />
          <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil-outline" title="Modifier" @click="ouvrirEdition(item)" />
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">
            Aucun conditionnement défini. Créez-en un pour chaque boisson consignée
            (par exemple : Coca 33 cl, casier de 24).
          </div>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="560" scrollable>
      <v-card class="pa-6">
        <h2 class="text-h6 mb-4">{{ editId ? 'Modifier le' : 'Nouveau' }} conditionnement</h2>

        <v-alert v-if="erreur" type="error" variant="tonal" density="compact" rounded="lg" class="mb-4">{{ erreur }}</v-alert>

        <v-select
          v-model="form.articleBoissonId"
          :items="boissonsDisponibles.map(b => ({ title: `${b.code} — ${b.libelle}`, value: b.id }))"
          label="Boisson *"
          hint="Une boisson ne peut avoir qu'un seul conditionnement"
          persistent-hint
          variant="outlined"
          density="comfortable"
          class="mb-3"
        />
        <v-text-field v-model="form.code" label="Code *" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.libelle" label="Libellé *" variant="outlined" density="comfortable" class="mb-3" />
        <v-combobox v-model="form.format" :items="FORMATS" label="Format (33CL, 65CL...)" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field
          v-model.number="form.contenanceCasier"
          type="number"
          label="Bouteilles par casier *"
          hint="24 pour un casier de Coca 33 cl"
          persistent-hint
          variant="outlined"
          density="comfortable"
          class="mb-3"
        />
        <v-switch v-model="form.actif" label="Actif" color="success" density="compact" hide-details class="mb-4" />

        <div class="d-flex justify-end ga-2">
          <v-btn variant="text" :disabled="saving" @click="dialog = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.rst-stats { display: flex; gap: 12px; flex-wrap: wrap; }
.rst-stat {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 18px;
  border-radius: 12px;
  font-size: 0.875rem;
}
.rst-stat--vides   { background: linear-gradient(135deg,#eff6ff,#dbeafe); color: #1d4ed8; }
.rst-stat--casiers { background: linear-gradient(135deg,#f0fdf4,#dcfce7); color: #15803d; }
.rst-stat__val { font-size: 1.1rem; font-weight: 800; letter-spacing: -0.5px; }
.rst-stat__lbl { font-weight: 500; opacity: 0.75; }
.rst-casiers { white-space: nowrap; }
</style>
