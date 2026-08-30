<script setup lang="ts">
definePageMeta({ module: 'MOBILE_MONEY' })

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
    journal.value = await api<LivreJournal>(`/mobile-money/journal?${params}`)
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
        <h1 class="page-title">Journal de mobile money</h1>
        <p class="page-sub">Écritures comptabilisées par mobile money, en ordre chronologique</p>
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
      <v-row class="mb-4">
        <v-col cols="6" md="4">
          <v-card class="classroom-card pa-4 text-center">
            <div class="kpi-label">Pièces</div>
            <div class="kpi-value">{{ journal.pieces.length }}</div>
          </v-card>
        </v-col>
        <v-col cols="6" md="4">
          <v-card class="classroom-card pa-4 text-center">
            <div class="kpi-label">Total débit (USD)</div>
            <div class="kpi-value">{{ fmtUSD(journal.totalDebit) }}</div>
          </v-card>
        </v-col>
        <v-col cols="6" md="4">
          <v-card class="classroom-card pa-4 text-center">
            <div class="kpi-label">Total crédit (USD)</div>
            <div class="kpi-value">{{ fmtUSD(journal.totalCredit) }}</div>
          </v-card>
        </v-col>
      </v-row>

      <v-card v-if="journal.pieces.length === 0" class="classroom-card pa-8 text-center">
        <v-icon icon="mdi-cellphone" size="42" color="grey" class="mb-2" />
        <div class="text-medium-emphasis">Aucune écriture mobile money sur cette période.</div>
      </v-card>

      <v-expansion-panels v-else variant="accordion" class="journal-panels">
        <v-expansion-panel v-for="p in journal.pieces" :key="p.id" elevation="0" class="classroom-card mb-2">
          <v-expansion-panel-title>
            <div class="d-flex align-center flex-wrap ga-3" style="width: 100%">
              <span class="font-weight-bold">{{ p.reference }}</span>
              <span class="text-medium-emphasis">{{ fmtDate(p.datePiece) }}</span>
              <span class="text-truncate flex-1-1">{{ p.libelle }}</span>
              <v-chip v-if="p.statut === 'ANNULEE'" size="small" color="error" variant="tonal">Annulée</v-chip>
              <span class="font-weight-medium">{{ fmtUSD(p.totalDebit) }}</span>
            </div>
          </v-expansion-panel-title>
          <v-expansion-panel-text>
            <v-table density="compact">
              <thead>
                <tr>
                  <th>Compte</th>
                  <th>Libellé</th>
                  <th class="text-right">Débit</th>
                  <th class="text-right">Crédit</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(l, i) in p.lignes" :key="i">
                  <td>
                    <span class="font-weight-medium">{{ l.compteNumero }}</span>
                    <span class="text-medium-emphasis"> — {{ l.compteLibelle }}</span>
                  </td>
                  <td>{{ l.libelle }}</td>
                  <td class="text-right">{{ l.debit ? fmtUSD(l.debit) : '—' }}</td>
                  <td class="text-right">{{ l.credit ? fmtUSD(l.credit) : '—' }}</td>
                </tr>
              </tbody>
            </v-table>
          </v-expansion-panel-text>
        </v-expansion-panel>
      </v-expansion-panels>
    </template>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.kpi-label { font-size: 0.72rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.25rem; font-weight: 700; color: #111827; margin-top: 4px; }
.journal-panels :deep(.v-expansion-panel) { border-radius: 12px !important; }
</style>
