<script setup lang="ts">
// Aucune ecriture ne se fait depuis cette page (la TVA se genere depuis les
// ventes et les notes de frais), donc aucun role d'ecriture a restreindre.
// DA exclu explicitement de la consultation.
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DG', 'COMPTABLE'] })

interface LigneTva {
  date: string
  pieceReference?: string
  journal?: string
  compteNumero: string
  compteLibelle: string
  libelle: string
  montant: number
  nature: 'COLLECTEE' | 'RECUPERABLE'
}

interface Situation {
  du: string
  au: string
  tvaCollectee: number
  tvaRecuperable: number
  soldeNet: number
  lignes: LigneTva[]
}

const api = useApi()
const auth = useAuthStore()
const loading = ref(false)
const erreur = ref('')
const succes = ref('')
const situation = ref<Situation | null>(null)

// L'arrete pose une ecriture : reserve au DFIN et a l'ADMIN, comme la cloture
// annuelle. Le COMPTABLE et le DG gardent la consultation.
const peutDeclarer = computed(() => auth.hasAnyRole(['DFIN', 'ADMIN']))
const declarant = ref(false)
const dialogDeclaration = ref(false)

async function declarer() {
  declarant.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const params = new URLSearchParams({ du: filtres.du, au: filtres.au })
    const res = await api<{ pieceReference: string | null; message: string }>(
      `/comptabilite/tva/declarer?${params}`, { method: 'POST' })
    succes.value = res.pieceReference
      ? `${res.message} — pièce ${res.pieceReference}.`
      : res.message
    dialogDeclaration.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'arrêté de TVA.")
  } finally {
    declarant.value = false
  }
}

const debutAnnee = new Date().getFullYear() + '-01-01'
const aujourdhui = new Date().toISOString().slice(0, 10)
const filtres = reactive({ du: debutAnnee, au: aujourdhui })

const filtreNature = ref<'TOUS' | 'COLLECTEE' | 'RECUPERABLE'>('TOUS')

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const params = new URLSearchParams({ du: filtres.du, au: filtres.au })
    situation.value = await api<Situation>(`/comptabilite/tva?${params}`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger la situation TVA.')
    situation.value = null
  } finally {
    loading.value = false
  }
}
onMounted(charger)

const lignesFiltrees = computed(() => {
  if (!situation.value) return []
  if (filtreNature.value === 'TOUS') return situation.value.lignes
  return situation.value.lignes.filter(l => l.nature === filtreNature.value)
})

const sensSolde = computed(() => {
  const s = situation.value?.soldeNet ?? 0
  if (s > 0.005) return { label: 'TVA due à l\'État', color: 'error' }
  if (s < -0.005) return { label: 'Crédit de TVA à reporter', color: 'success' }
  return { label: 'Solde nul', color: 'grey' }
})

function fmt(v: number) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(v || 0)
}
function fmtDate(d: string) {
  return d ? new Date(d).toLocaleDateString('fr-FR') : ''
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Gestion de la TVA</h1>
        <p class="page-sub">
          TVA collectée sur les ventes (443x) face à la TVA récupérable sur les achats (445x) — traçabilité complète
        </p>
      </div>
      <v-btn
        v-if="peutDeclarer" color="primary" variant="flat" rounded="lg"
        prepend-icon="mdi-file-check-outline" :loading="declarant"
        :disabled="!situation || (!situation.tvaCollectee && !situation.tvaRecuperable)"
        @click="dialogDeclaration = true"
      >
        Arrêter la TVA
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">
      {{ succes }}
    </v-alert>

    <!-- L'arrete solde 443x/445x vers 4441 ou 4449 : c'est une ecriture, donc
         une confirmation explicite plutot qu'un simple clic. -->
    <v-dialog v-model="dialogDeclaration" max-width="540">
      <v-card>
        <v-card-title>Arrêter la TVA de la période</v-card-title>
        <v-divider />
        <v-card-text>
          <p class="text-body-2 mb-3">
            Une pièce <strong>brouillon</strong> va être générée : elle solde la TVA collectée
            contre la TVA récupérable et porte le net sur le compte de l'État.
          </p>
          <div v-if="situation" class="decl-recap">
            <div><span>TVA collectée</span><strong>{{ fmt(situation.tvaCollectee) }}</strong></div>
            <div><span>TVA récupérable</span><strong>{{ fmt(situation.tvaRecuperable) }}</strong></div>
            <div class="decl-recap__net">
              <span>{{ situation.soldeNet >= 0 ? 'TVA due à l\'État (4441)' : 'Crédit à reporter (4449)' }}</span>
              <strong>{{ fmt(Math.abs(situation.soldeNet)) }}</strong>
            </div>
          </div>
          <p class="text-caption text-medium-emphasis mt-3 mb-0">
            Période du {{ filtres.du }} au {{ filtres.au }}. Rien n'impacte le Grand Livre
            tant que la pièce n'est pas comptabilisée depuis Pièces comptables.
          </p>
        </v-card-text>
        <v-divider />
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="declarant" @click="dialogDeclaration = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="declarant" @click="declarer">Générer la pièce</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-card class="classroom-card pa-6 mb-4">
      <v-row align="end">
        <v-col cols="12" md="4">
          <v-text-field v-model="filtres.du" label="Du" type="date" variant="outlined" density="comfortable" />
        </v-col>
        <v-col cols="12" md="4">
          <v-text-field v-model="filtres.au" label="Au" type="date" variant="outlined" density="comfortable" />
        </v-col>
        <v-col cols="12" md="4">
          <v-btn color="primary" block :loading="loading" @click="charger">Actualiser</v-btn>
        </v-col>
      </v-row>
    </v-card>

    <template v-if="situation">
      <v-row class="mb-4">
        <v-col cols="12" md="4">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">TVA collectée (ventes)</div>
            <div class="kpi-value text-success">{{ fmt(situation.tvaCollectee) }}</div>
          </v-card>
        </v-col>
        <v-col cols="12" md="4">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">TVA récupérable (achats)</div>
            <div class="kpi-value text-error">{{ fmt(situation.tvaRecuperable) }}</div>
          </v-card>
        </v-col>
        <v-col cols="12" md="4">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">Solde net</div>
            <div class="kpi-value" :class="`text-${sensSolde.color}`">{{ fmt(situation.soldeNet) }}</div>
            <v-chip :color="sensSolde.color" size="small" variant="tonal" class="mt-1">{{ sensSolde.label }}</v-chip>
          </v-card>
        </v-col>
      </v-row>

      <v-card class="classroom-card">
        <v-card-title class="d-flex align-center justify-space-between flex-wrap ga-3 pa-4 pb-2">
          <span class="text-subtitle-1 font-weight-semibold">Détail des écritures</span>
          <v-btn-toggle v-model="filtreNature" color="primary" density="comfortable" mandatory variant="outlined">
            <v-btn value="TOUS" size="small">Tout</v-btn>
            <v-btn value="COLLECTEE" size="small">Collectée</v-btn>
            <v-btn value="RECUPERABLE" size="small">Récupérable</v-btn>
          </v-btn-toggle>
        </v-card-title>
        <v-data-table
          :headers="[
            { title: 'Date', key: 'date' },
            { title: 'Pièce', key: 'pieceReference' },
            { title: 'Journal', key: 'journal' },
            { title: 'Compte', key: 'compteNumero' },
            { title: 'Libellé', key: 'libelle' },
            { title: 'Nature', key: 'nature' },
            { title: 'Montant', key: 'montant', align: 'end' },
          ]"
          :items="lignesFiltrees"
          :loading="loading"
          items-per-page="25"
          no-data-text="Aucun mouvement de TVA sur cette période."
        >
          <template #item.date="{ item }">{{ fmtDate(item.date) }}</template>
          <template #item.compteNumero="{ item }">
            <span class="font-weight-medium">{{ item.compteNumero }}</span>
            <span class="text-medium-emphasis"> — {{ item.compteLibelle }}</span>
          </template>
          <template #item.nature="{ item }">
            <v-chip :color="item.nature === 'COLLECTEE' ? 'success' : 'error'" size="small" variant="tonal">
              {{ item.nature === 'COLLECTEE' ? 'Collectée' : 'Récupérable' }}
            </v-chip>
          </template>
          <template #item.montant="{ item }">{{ fmt(item.montant) }}</template>
        </v-data-table>
      </v-card>
    </template>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }

/* Recapitulatif de l'arrete, avant generation de la piece. */
.decl-recap { display: flex; flex-direction: column; gap: 8px; background: #f9fafb; border-radius: 10px; padding: 14px 16px; }
.decl-recap > div { display: flex; justify-content: space-between; gap: 20px; font-size: 0.9rem; color: #374151; }
.decl-recap > div strong { font-variant-numeric: tabular-nums; color: #111827; }
.decl-recap__net { border-top: 1px solid #e5e7eb; padding-top: 8px; font-weight: 600; }
.kpi-label { font-size: 0.72rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.4rem; font-weight: 700; margin-top: 4px; color: #111827; }
</style>
