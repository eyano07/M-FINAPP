<script setup lang="ts">
definePageMeta({ module: 'BANQUE' })

interface LignePiece {
  compteNumero: string
  compteLibelle: string
  debit: number
  credit: number
  libelle: string
}

interface Piece {
  id: number
  reference: string
  datePiece: string
  journal: string
  libelle: string
  statut: string
  totalDebit: number
  totalCredit: number
  lignes: LignePiece[]
}

interface LivreJournal {
  du: string
  au: string
  pieces: Piece[]
  totalDebit: number
  totalCredit: number
}

const api = useApi()
const loading = ref(false)
const erreur = ref('')
const journal = ref<LivreJournal | null>(null)

const debutAnnee = new Date().getFullYear() + '-01-01'
const aujourdhui = new Date().toISOString().slice(0, 10)
const filtres = reactive({ du: debutAnnee, au: aujourdhui })

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const params = new URLSearchParams({ du: filtres.du, au: filtres.au })
    journal.value = await api<LivreJournal>(`/banque/journal?${params}`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le journal.')
    journal.value = null
  } finally {
    loading.value = false
  }
}

onMounted(charger)

// Le grand livre est tenu en USD (devise de base) : les montants du journal
// sont deja exprimes en USD, aucune conversion a faire pour l'affichage.
const fmtUSD = (usd: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(usd || 0)
const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '')
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Journal de banque</h1>
        <p class="page-sub">Écritures comptabilisées par la banque, en ordre chronologique</p>
      </div>
      <div class="page-head-actions">
        <v-btn variant="outlined" color="primary" prepend-icon="mdi-refresh" rounded="lg" @click="charger">
          Actualiser
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-6 mb-4">
      <v-row align="end">
        <v-col cols="12" md="6">
          <v-text-field v-model="filtres.du" label="Du" type="date" variant="outlined" density="comfortable" />
        </v-col>
        <v-col cols="12" md="6">
          <v-text-field v-model="filtres.au" label="Au" type="date" variant="outlined" density="comfortable" />
        </v-col>
      </v-row>
    </v-card>

    <template v-if="journal">
      <div class="kpi-grid">
        <v-card class="classroom-card pa-4 text-center">
          <div class="kpi-label">Pièces</div>
          <div class="kpi-value">{{ journal.pieces.length }}</div>
        </v-card>
        <v-card class="classroom-card pa-4 text-center">
          <div class="kpi-label">Total débit (USD)</div>
          <div class="kpi-value">{{ fmtUSD(journal.totalDebit) }}</div>
        </v-card>
        <v-card class="classroom-card pa-4 text-center">
          <div class="kpi-label">Total crédit (USD)</div>
          <div class="kpi-value">{{ fmtUSD(journal.totalCredit) }}</div>
        </v-card>
      </div>

      <v-card v-if="journal.pieces.length === 0" class="classroom-card pa-8 text-center">
        <v-icon icon="mdi-bank" size="42" color="grey" class="mb-2" />
        <div class="text-medium-emphasis">Aucune écriture bancaire sur cette période.</div>
      </v-card>

      <CommunPiecesJournal v-else :pieces="journal.pieces" :fmt="fmtUSD" />
    </template>
  </div>
</template>

<style scoped>
</style>
