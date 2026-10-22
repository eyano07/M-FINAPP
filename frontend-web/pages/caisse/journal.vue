<script setup lang="ts">
definePageMeta({ module: 'CAISSE' })

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
  /** Devise des montants renvoyés (USD, ou CDF quand le journal est affiché en francs) et taux utilisé (FC pour 1 USD). */
  devise: DeviseCaisse
  taux?: number | null
}

const api = useApi()
const { devise, choisir } = useDeviseCaisse()
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
    const params = new URLSearchParams({ du: filtres.du, au: filtres.au, devise: devise.value })
    journal.value = await api<LivreJournal>(`/caisse/journal?${params}`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le journal.')
    journal.value = null
  } finally {
    loading.value = false
  }
}

onMounted(charger)

// Le grand livre est tenu en USD (devise de base). Pour l'afficher en francs, le SERVEUR convertit chaque ligne au taux
// du dernier jour de la période et recalcule les totaux sur les lignes converties : on redemande donc le journal.
// Si la conversion est impossible (aucun taux défini), on revient à la devise précédente et on garde le message.
async function changerDevise(nouvelle: DeviseCaisse) {
  if (nouvelle === devise.value) return
  const avant = devise.value
  choisir(nouvelle)
  await charger()
  if (!journal.value || journal.value.devise !== nouvelle) {
    const message = erreur.value
    choisir(avant)
    await charger()
    erreur.value = message
  }
}

// Les montants sont ceux de la réponse du serveur, dans SA devise (et non celle du bouton, qui peut avoir changé
// pendant qu'une réponse est en route).
const deviseAffichee = computed<DeviseCaisse>(() => journal.value?.devise ?? devise.value)
const fmt = (montant: number) => fmtMontantCaisse(montant, deviseAffichee.value)
const unite = computed(() => uniteCaisse(deviseAffichee.value))
const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '')
const fmtTaux = (t: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 4 }).format(t)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Journal de caisse</h1>
        <p class="page-sub">Écritures comptabilisées par la caisse, en ordre chronologique</p>
      </div>
      <div class="page-head-actions">
        <CaisseBasculeDevise :model-value="devise" :disabled="loading" @update:model-value="changerDevise" />
        <v-btn variant="outlined" color="primary" prepend-icon="mdi-refresh" rounded="lg" @click="charger">
          Actualiser
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <!-- Rapports de caisse : le jour, la semaine (lundi au dimanche) ou le mois, imprimables en PDF. -->
    <v-card class="classroom-card pa-4 mb-4">
      <div class="d-flex align-center flex-wrap ga-3">
        <div class="d-flex align-center ga-2 mr-2">
          <v-icon icon="mdi-file-chart-outline" color="primary" />
          <div>
            <div class="font-weight-medium">Rapport de caisse</div>
            <div class="text-caption text-medium-emphasis">Entrées, sorties et solde, avec impression PDF</div>
          </div>
        </div>
        <v-spacer />
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-calendar-today"
          :to="{ path: '/caisse/rapport', query: { periode: 'JOUR' } }">
          Rapport du jour
        </v-btn>
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-calendar-week"
          :to="{ path: '/caisse/rapport', query: { periode: 'SEMAINE' } }">
          Rapport de la semaine
        </v-btn>
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-calendar-month"
          :to="{ path: '/caisse/rapport', query: { periode: 'MOIS' } }">
          Rapport du mois
        </v-btn>
      </div>
    </v-card>

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
      <v-alert v-if="journal.devise === 'CDF' && journal.taux" type="info" variant="tonal" density="compact" class="mb-4"
        icon="mdi-swap-horizontal">
        Montants convertis en francs congolais au taux de <strong>{{ fmtTaux(journal.taux) }} FC pour 1 USD</strong>.
        La comptabilité est tenue en USD : de petits écarts d'arrondi sont possibles.
      </v-alert>

      <v-row class="mb-4">
        <v-col cols="6" md="4">
          <v-card class="classroom-card pa-4 text-center">
            <div class="kpi-label">Pièces</div>
            <div class="kpi-value">{{ journal.pieces.length }}</div>
          </v-card>
        </v-col>
        <v-col cols="6" md="4">
          <v-card class="classroom-card pa-4 text-center">
            <div class="kpi-label">Total débit ({{ unite }})</div>
            <div class="kpi-value">{{ fmt(journal.totalDebit) }}</div>
          </v-card>
        </v-col>
        <v-col cols="6" md="4">
          <v-card class="classroom-card pa-4 text-center">
            <div class="kpi-label">Total crédit ({{ unite }})</div>
            <div class="kpi-value">{{ fmt(journal.totalCredit) }}</div>
          </v-card>
        </v-col>
      </v-row>

      <v-card v-if="journal.pieces.length === 0" class="classroom-card pa-8 text-center">
        <v-icon icon="mdi-cash-register" size="42" color="grey" class="mb-2" />
        <div class="text-medium-emphasis">Aucune écriture de caisse sur cette période.</div>
      </v-card>

      <v-expansion-panels v-else variant="accordion" class="journal-panels">
        <v-expansion-panel v-for="p in journal.pieces" :key="p.id" elevation="0" class="classroom-card mb-2">
          <v-expansion-panel-title>
            <div class="d-flex align-center flex-wrap ga-3" style="width: 100%">
              <span class="font-weight-bold">{{ p.reference }}</span>
              <span class="text-medium-emphasis">{{ fmtDate(p.datePiece) }}</span>
              <span class="text-truncate flex-1-1">{{ p.libelle }}</span>
              <v-chip v-if="p.statut === 'ANNULEE'" size="small" color="error" variant="tonal">Annulée</v-chip>
              <span class="font-weight-medium">{{ fmt(p.totalDebit) }}</span>
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
                  <td class="text-right">{{ l.debit ? fmt(l.debit) : '—' }}</td>
                  <td class="text-right">{{ l.credit ? fmt(l.credit) : '—' }}</td>
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
.page-head-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.kpi-label { font-size: 0.72rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.25rem; font-weight: 700; color: #111827; margin-top: 4px; }
.journal-panels :deep(.v-expansion-panel) { border-radius: 12px !important; }
</style>
