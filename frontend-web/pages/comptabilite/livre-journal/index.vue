<script setup lang="ts">
// DA exclu explicitement : voir NavigationDrawer.vue (comptabiliteItems).
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DG', 'COMPTABLE'] })

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

// Recherche (15/11/2026) : par pièce (n° ou libellé) et par compte (numéro, début de numéro ou intitulé), sur
// les pièces déjà chargées. Le filtre « compte » garde les pièces dont au moins une ligne mouvemente ce compte.
const recherche = reactive({ piece: '', compte: '' })
const normaliser = (v?: string | null) =>
  (v || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim()

const piecesFiltrees = computed(() => {
  const qPiece = normaliser(recherche.piece)
  const qCompte = normaliser(recherche.compte)
  return (journal.value?.pieces || []).filter((p) => {
    if (qPiece && !normaliser(p.reference).includes(qPiece) && !normaliser(p.libelle).includes(qPiece)) return false
    if (qCompte) {
      const chiffres = /^\d+$/.test(qCompte)
      return (p.lignes || []).some(l => chiffres
        ? (l.compteNumero || '').startsWith(qCompte)
        : normaliser(l.compteLibelle).includes(qCompte))
    }
    return true
  })
})
const filtreActif = computed(() => !!(normaliser(recherche.piece) || normaliser(recherche.compte)))

const totalDebitUSD = computed(() =>
  piecesFiltrees.value.reduce((s, p) => s + pieceUSD(p, 'debit'), 0))
const totalCreditUSD = computed(() =>
  piecesFiltrees.value.reduce((s, p) => s + pieceUSD(p, 'credit'), 0))

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
  ANALYTIQUE: 'purple',
  PAIE: 'pink',
  A_NOUVEAUX: 'blue-grey',
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
      <div class="lj-filtres mt-4">
        <v-text-field
          v-model="recherche.piece"
          label="Pièce (n° ou libellé)"
          placeholder="ex. PC-2026-000537"
          prepend-inner-icon="mdi-file-search-outline"
          variant="outlined"
          density="comfortable"
          rounded="lg"
          clearable
          hide-details
        />
        <v-text-field
          v-model="recherche.compte"
          label="Compte (numéro ou intitulé)"
          placeholder="ex. 571, 62 ou Caisse"
          prepend-inner-icon="mdi-magnify"
          variant="outlined"
          density="comfortable"
          rounded="lg"
          clearable
          hide-details
        />
      </div>
    </v-card>

    <template v-if="journal">
      <div class="kpi-grid">
        <v-card class="classroom-card pa-4 text-center">
          <div class="kpi-label">Pièces</div>
          <div class="kpi-value">
            {{ piecesFiltrees.length }}<span v-if="filtreActif" class="text-body-2 text-medium-emphasis"> / {{ journal.pieces.length }}</span>
          </div>
        </v-card>
        <v-card class="classroom-card pa-4 text-center">
          <div class="kpi-label">Total débit (USD)</div>
          <div class="kpi-value">{{ fmtUSD(totalDebitUSD) }}</div>
        </v-card>
        <v-card class="classroom-card pa-4 text-center">
          <div class="kpi-label">Total crédit (USD)</div>
          <div class="kpi-value">{{ fmtUSD(totalCreditUSD) }}</div>
        </v-card>
      </div>

      <v-card v-if="piecesFiltrees.length === 0" class="classroom-card pa-8 text-center">
        <v-icon icon="mdi-book-open-blank-variant" size="42" color="grey" class="mb-2" />
        <div class="text-medium-emphasis">
          {{ filtreActif ? 'Aucune pièce ne correspond à la recherche.' : 'Aucune pièce comptabilisée sur cette période.' }}
        </div>
      </v-card>

      <CommunPiecesJournal v-else :pieces="piecesFiltrees" :fmt="fmtUSD" :montant="(p) => pieceUSD(p, 'debit')"
        :couleurs-journal="journalCouleur" :fmt-taux="fmtTaux" unite="USD" />
    </template>
  </div>
</template>

<style scoped>
.lj-filtres { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.lj-filtres :deep(.v-input) { flex: 1 1 220px; }
.lj-filtres__btn { height: 48px; flex: 0 0 auto; }
@media (max-width: 600px) {
  .lj-filtres__btn { width: 100%; }
}
</style>
