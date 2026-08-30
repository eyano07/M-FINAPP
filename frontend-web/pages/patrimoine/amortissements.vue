<script setup lang="ts">
definePageMeta({ module: 'PATRIMOINE' })

interface Immo { id: number; reference: string; libelle: string; statut: string; valeurNetteComptable: number }
interface Ligne {
  id: number
  periode: string
  dotation: number
  cumul: number
  valeurNette: number
  comptabilise: boolean
  pieceReference: string | null
}

const api = useApi()
const auth = useAuthStore()
const route = useRoute()
const canWrite = computed(() => auth.hasAnyRole(['GEST_PATRIMOINE', 'DFIN']))

const biens = ref<Immo[]>([])
const bienId = ref<number | null>(null)
const plan = ref<Ligne[]>([])
const tauxChange = ref(0)
const loading = ref(false)
const traitement = ref(false)
const erreur = ref('')
const succes = ref('')
const rapport = ref<{ lignesComptabilisees: number; totalDotations: number; piecesCreees: string[]; ignorees: string[] } | null>(null)
const jusqua = ref(new Date().toISOString().slice(0, 10))

async function chargerBiens() {
  try {
    const [data, taux] = await Promise.all([
      api<Immo[]>('/patrimoine/immobilisations'),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
    ])
    biens.value = data
    tauxChange.value = taux.taux || 0
    const q = Number(route.query.bien)
    bienId.value = q && biens.value.some(b => b.id === q) ? q : (biens.value[0]?.id ?? null)
    if (bienId.value) await chargerPlan()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les biens.')
  }
}

async function chargerPlan() {
  if (!bienId.value) { plan.value = []; return }
  loading.value = true
  erreur.value = ''
  try {
    plan.value = await api<Ligne[]>(`/patrimoine/immobilisations/${bienId.value}/amortissements`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le plan d’amortissement.')
    plan.value = []
  } finally {
    loading.value = false
  }
}

onMounted(chargerBiens)
watch(bienId, chargerPlan)

async function comptabiliser() {
  traitement.value = true
  erreur.value = ''
  succes.value = ''
  rapport.value = null
  try {
    const r = await api<typeof rapport.value>('/patrimoine/amortissements/comptabiliser', {
      method: 'POST', body: { jusqua: jusqua.value },
    })
    rapport.value = r
    succes.value = r && r.lignesComptabilisees > 0
      ? `${r.lignesComptabilisees} dotation(s) comptabilisée(s) pour ${fmt(r.totalDotations)}.`
      : 'Aucune dotation échue à comptabiliser à cette date.'
    await chargerPlan()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la comptabilisation.')
  } finally {
    traitement.value = false
  }
}

const toUSD = (fc: number) => (tauxChange.value > 0 ? (fc || 0) / tauxChange.value : 0)
const fmt = (fc: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 })
    .format(toUSD(fc))
const fmtPeriode = (d: string) =>
  d ? new Date(d).toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' }) : ''

const bienChoisi = computed(() => biens.value.find(b => b.id === bienId.value))
const restant = computed(() => plan.value.filter(l => !l.comptabilise).length)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Amortissements</h1>
        <p class="page-sub">Plan linéaire par bien et comptabilisation des dotations échues</p>
      </div>
    </div>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-card v-if="canWrite" class="classroom-card pa-5 mb-4">
      <div class="text-subtitle-2 mb-3">Comptabiliser les dotations échues</div>
      <v-row align="center">
        <v-col cols="12" md="4">
          <v-text-field v-model="jusqua" type="date" label="Jusqu'au" variant="outlined" density="comfortable" hide-details />
        </v-col>
        <v-col cols="12" md="4">
          <v-btn color="success" variant="flat" block :loading="traitement"
            prepend-icon="mdi-check-decagram" @click="comptabiliser">
            Comptabiliser
          </v-btn>
        </v-col>
        <v-col cols="12" md="4">
          <p class="text-caption text-medium-emphasis mb-0">
            Une dotation déjà passée n'est jamais repassée : chaque période est écrite une seule fois.
          </p>
        </v-col>
      </v-row>

      <v-alert v-if="rapport && rapport.ignorees.length" type="warning" variant="tonal" density="compact" class="mt-3">
        <div class="text-caption font-weight-bold mb-1">Biens écartés :</div>
        <ul class="pl-4 mb-0">
          <li v-for="(i, k) in rapport.ignorees" :key="k" class="text-caption">{{ i }}</li>
        </ul>
      </v-alert>
      <div v-if="rapport && rapport.piecesCreees.length" class="text-caption text-medium-emphasis mt-2">
        Pièces créées : {{ rapport.piecesCreees.join(', ') }}
      </div>
    </v-card>

    <v-card class="classroom-card pa-5 mb-4">
      <v-row align="center">
        <v-col cols="12" md="8">
          <v-select
            v-model="bienId"
            :items="biens.map(b => ({ value: b.id, title: `${b.reference} — ${b.libelle}` }))"
            label="Bien" variant="outlined" density="comfortable" hide-details />
        </v-col>
        <v-col cols="12" md="4">
          <div class="text-caption text-medium-emphasis">
            VNC actuelle : <strong>{{ fmt(bienChoisi?.valeurNetteComptable ?? 0) }}</strong><br>
            Périodes restantes : <strong>{{ restant }}</strong>
          </div>
        </v-col>
      </v-row>
    </v-card>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Période', key: 'periode' },
          { title: 'Dotation', key: 'dotation', align: 'end' },
          { title: 'Cumul', key: 'cumul', align: 'end' },
          { title: 'Valeur nette', key: 'valeurNette', align: 'end' },
          { title: 'État', key: 'comptabilise' },
          { title: 'Pièce', key: 'pieceReference' },
        ]"
        :items="plan"
        :loading="loading"
        items-per-page="12"
      >
        <template #item.periode="{ item }">{{ fmtPeriode(item.periode) }}</template>
        <template #item.dotation="{ item }">{{ fmt(item.dotation) }}</template>
        <template #item.cumul="{ item }">{{ fmt(item.cumul) }}</template>
        <template #item.valeurNette="{ item }">{{ fmt(item.valeurNette) }}</template>
        <template #item.comptabilise="{ item }">
          <span class="chip-soft" :style="item.comptabilise
            ? { background: '#dcfce7', color: '#15803d' } : { background: '#f3f4f6', color: '#6b7280' }">
            {{ item.comptabilise ? 'Comptabilisée' : 'À venir' }}
          </span>
        </template>
        <template #item.pieceReference="{ item }">{{ item.pieceReference ?? '—' }}</template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">
            Aucun plan à afficher — sélectionnez un bien, ou créez-en un depuis « Immobilisations ».
          </div>
        </template>
      </v-data-table>
    </v-card>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
</style>
