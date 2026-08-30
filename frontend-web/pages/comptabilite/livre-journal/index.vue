<script setup lang="ts">
// CAISSIER a COMPTABILITE en LECTURE (pour Balance/Compte de resultat
// uniquement) mais ne voit pas le Livre-journal.
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] })

interface LignePiece {
  compteNumero: string
  compteLibelle: string
  debit: number
  credit: number
  libelle: string
  tauxApplique?: number | null
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
    journal.value = await api<LivreJournal>(`/comptabilite/livre-journal?${params}`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le livre-journal.')
    journal.value = null
  } finally {
    loading.value = false
  }
}

onMounted(charger)

// Le grand livre est tenu en USD (devise de base) : debit/credit sont deja
// exprimes en USD par le serveur. `tauxApplique` reste une donnee d'audit
// (taux applique si la ligne provient d'une devise etrangere).
const pieceUSD = (p: Piece, sens: 'debit' | 'credit') =>
  (p.lignes || []).reduce((s, l) => s + (l[sens] || 0), 0)

const totalDebitUSD = computed(() =>
  (journal.value?.pieces || []).reduce((s, p) => s + pieceUSD(p, 'debit'), 0))
const totalCreditUSD = computed(() =>
  (journal.value?.pieces || []).reduce((s, p) => s + pieceUSD(p, 'credit'), 0))

const fmtUSD = (v: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(v || 0)
const fmtTaux = (v?: number | null) => (v ? new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(v) : '—')
const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '')

const journalCouleur: Record<string, string> = {
  CAISSE: 'teal',
  BANQUE: 'blue',
  MOBILE_MONEY: 'deep-orange',
  VENTES: 'green',
  ACHATS: 'brown',
  STOCK: 'indigo',
  OPERATIONS_DIVERSES: 'orange',
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Livre-journal</h1>
        <p class="page-sub">Enregistrement chronologique de toutes les pièces comptabilisées</p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-6 mb-4">
      <!-- hide-details sur les champs : sans lui, Vuetify réserve la place des
           messages sous l'input et le bouton se retrouve décalé vers le bas. -->
      <div class="lj-filtres">
        <v-text-field
          v-model="filtres.du"
          label="Du"
          type="date"
          variant="outlined"
          density="comfortable"
          rounded="lg"
          hide-details
        />
        <v-text-field
          v-model="filtres.au"
          label="Au"
          type="date"
          variant="outlined"
          density="comfortable"
          rounded="lg"
          hide-details
        />
        <v-btn
          color="primary"
          rounded="lg"
          prepend-icon="mdi-refresh"
          class="lj-filtres__btn"
          :loading="loading"
          @click="charger"
        >
          Actualiser
        </v-btn>
      </div>
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
            <div class="kpi-value">{{ fmtUSD(totalDebitUSD) }}</div>
          </v-card>
        </v-col>
        <v-col cols="6" md="4">
          <v-card class="classroom-card pa-4 text-center">
            <div class="kpi-label">Total crédit (USD)</div>
            <div class="kpi-value">{{ fmtUSD(totalCreditUSD) }}</div>
          </v-card>
        </v-col>
      </v-row>

      <v-card v-if="journal.pieces.length === 0" class="classroom-card pa-8 text-center">
        <v-icon icon="mdi-book-open-blank-variant" size="42" color="grey" class="mb-2" />
        <div class="text-medium-emphasis">Aucune pièce comptabilisée sur cette période.</div>
      </v-card>

      <v-expansion-panels v-else variant="accordion" class="journal-panels">
        <v-expansion-panel v-for="p in journal.pieces" :key="p.id" elevation="0" class="classroom-card mb-2">
          <v-expansion-panel-title>
            <div class="d-flex align-center flex-wrap ga-3" style="width: 100%">
              <v-chip size="small" :color="journalCouleur[p.journal] || 'primary'" variant="tonal">
                {{ p.journal }}
              </v-chip>
              <span class="font-weight-bold">{{ p.reference }}</span>
              <span class="text-medium-emphasis">{{ fmtDate(p.datePiece) }}</span>
              <span class="text-truncate flex-1-1">{{ p.libelle }}</span>
              <v-chip v-if="p.statut === 'ANNULEE'" size="small" color="error" variant="tonal">Annulée</v-chip>
              <span class="font-weight-medium">{{ fmtUSD(pieceUSD(p, 'debit')) }}</span>
            </div>
          </v-expansion-panel-title>
          <v-expansion-panel-text>
            <v-table density="compact">
              <thead>
                <tr>
                  <th>Compte</th>
                  <th>Libellé</th>
                  <th class="text-right">Taux</th>
                  <th class="text-right">Débit (USD)</th>
                  <th class="text-right">Crédit (USD)</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(l, i) in p.lignes" :key="i">
                  <td>
                    <span class="font-weight-medium">{{ l.compteNumero }}</span>
                    <span class="text-medium-emphasis"> — {{ l.compteLibelle }}</span>
                  </td>
                  <td>{{ l.libelle }}</td>
                  <td class="text-right text-medium-emphasis">{{ fmtTaux(l.tauxApplique) }}</td>
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
.lj-filtres { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.lj-filtres :deep(.v-input) { flex: 1 1 220px; }
.lj-filtres__btn { height: 48px; flex: 0 0 auto; }
@media (max-width: 600px) {
  .lj-filtres__btn { width: 100%; }
}
</style>
