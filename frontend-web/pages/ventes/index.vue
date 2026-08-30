<script setup lang="ts">
definePageMeta({ module: 'VENTES' })

interface Vente {
  id: number
  reference: string
  dateVente: string
  clientNom: string
  statut: 'BROUILLON' | 'VALIDEE' | 'ANNULEE'
  modeReglement: 'CREDIT' | 'CAISSE' | 'BANQUE' | 'MOBILE_MONEY'
  etablissementNom?: string
  totalHt: number
  totalTva: number
  totalTtc: number
  devise?: 'CDF' | 'USD'
  tauxJournalier?: number | null
  pieceReference?: string
  mouvementReference?: string
  createdByNom?: string
}

const api = useApi()
const auth = useAuthStore()

const loading = ref(false)
const erreur = ref('')
const ventes = ref<Vente[]>([])
const tauxChange = ref(1)

const canWrite = computed(() => auth.hasAnyRole(['CAISSIER']))

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [data, tauxData] = await Promise.all([
      api<Vente[]>('/ventes'),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })) /* 0 et non 1 : un repli a 1 affichait les
        montants FC tels quels comme des USD (surevaluation d'un facteur
        egal au taux, ~2800x) sans que rien ne le signale. A 0, les
        convertisseurs (tous gardes par `taux > 0`) renvoient 0, valeur
        manifestement fausse plutot que plausible. */,
    ])
    ventes.value = data
    tauxChange.value = tauxData.taux || 0
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les ventes.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

// Chaque vente porte sa devise et le taux figé au moment de l'opération :
// les cumuls se font donc vente par vente, ramenées en USD avec LEUR taux.
const enUSD = (montant: number, v: Vente) => {
  if (!montant) return 0
  if (v.devise === 'USD') return montant
  const taux = v.tauxJournalier && v.tauxJournalier > 0 ? v.tauxJournalier : tauxChange.value
  return taux > 0 ? montant / taux : 0
}

// Seules les ventes validées alimentent le chiffre d'affaires.
const validees = computed(() => ventes.value.filter((v) => v.statut === 'VALIDEE'))
const caHt = computed(() => validees.value.reduce((s, v) => s + enUSD(v.totalHt, v), 0))
const tvaCollectee = computed(() => validees.value.reduce((s, v) => s + enUSD(v.totalTva, v), 0))

const statutMeta: Record<string, { label: string; color: string }> = {
  BROUILLON: { label: 'Brouillon', color: 'grey' },
  VALIDEE:   { label: 'Validée',   color: 'success' },
  ANNULEE:   { label: 'Annulée',   color: 'error' },
}

const reglementMeta: Record<string, { label: string; icon: string }> = {
  CREDIT:       { label: 'À crédit',     icon: 'mdi-account-clock-outline' },
  CAISSE:       { label: 'Caisse',       icon: 'mdi-cash-register' },
  BANQUE:       { label: 'Banque',       icon: 'mdi-bank' },
  MOBILE_MONEY: { label: 'Mobile Money', icon: 'mdi-cellphone' },
}

/** Montant d'une vente, affiché dans SA devise. */
const fmtMontant = (montant: number, v: Vente) =>
  v.devise === 'USD'
    ? new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(montant || 0)
    : `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(montant || 0)} FC`

/** Cumuls multi-devises : toujours exprimés en USD. */
const fmtUSD = (usd: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(usd || 0)
const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '')
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Ventes</h1>
        <p class="page-sub">Marchandises et services · facturation et TVA collectée</p>
      </div>
      <div class="page-head-actions">
        <v-btn variant="outlined" color="primary" prepend-icon="mdi-refresh" rounded="lg" @click="charger">
          Actualiser
        </v-btn>
        <v-btn variant="outlined" color="primary" prepend-icon="mdi-account-multiple-outline" rounded="lg" to="/ventes/clients">
          Clients
        </v-btn>
        <v-btn v-if="canWrite" color="primary" prepend-icon="mdi-plus" rounded="lg" to="/ventes/nouvelle">
          Nouvelle vente
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <!-- ── Synthèse ──────────────────────────────────────────── -->
    <div class="vte-stats">
      <div class="vte-stat vte-stat--ca">
        <div class="vte-stat__icon"><v-icon icon="mdi-chart-line" size="22" /></div>
        <div>
          <div class="vte-stat__label">Chiffre d'affaires HT</div>
          <div class="vte-stat__value">{{ fmtUSD(caHt) }}</div>
        </div>
      </div>
      <div class="vte-stat vte-stat--tva">
        <div class="vte-stat__icon"><v-icon icon="mdi-percent-outline" size="22" /></div>
        <div>
          <div class="vte-stat__label">TVA collectée</div>
          <div class="vte-stat__value">{{ fmtUSD(tvaCollectee) }}</div>
        </div>
      </div>
      <div class="vte-stat vte-stat--nb">
        <div class="vte-stat__icon"><v-icon icon="mdi-receipt-text-outline" size="22" /></div>
        <div>
          <div class="vte-stat__label">Ventes validées</div>
          <div class="vte-stat__value">{{ validees.length }}</div>
        </div>
      </div>
    </div>

    <!-- ── Liste ─────────────────────────────────────────────── -->
    <v-card class="classroom-card mt-4">
      <v-card-title class="text-subtitle-1 font-weight-semibold pa-4 pb-2">
        Historique des ventes
      </v-card-title>
      <v-data-table
        :items="ventes"
        :loading="loading"
        density="comfortable"
        items-per-page="15"
        :headers="[
          { title: 'Date', key: 'dateVente' },
          { title: 'Référence', key: 'reference' },
          { title: 'Client', key: 'clientNom', sortable: false },
          { title: 'Règlement', key: 'modeReglement', sortable: false },
          { title: 'Total HT', key: 'totalHt', align: 'end' },
          { title: 'TVA', key: 'totalTva', align: 'end' },
          { title: 'Total TTC', key: 'totalTtc', align: 'end' },
          { title: 'Statut', key: 'statut', align: 'center' },
          { title: 'Pièce', key: 'pieceReference', sortable: false },
        ]"
        @click:row="(_: unknown, { item }: any) => navigateTo(`/ventes/${item.id}`)"
      >
        <template #[`item.dateVente`]="{ item }">{{ fmtDate(item.dateVente) }}</template>
        <template #[`item.reference`]="{ item }">
          <code class="text-caption text-primary">{{ item.reference }}</code>
        </template>
        <template #[`item.modeReglement`]="{ item }">
          <span class="d-inline-flex align-center">
            <v-icon :icon="reglementMeta[item.modeReglement]?.icon" size="15" class="mr-1 text-medium-emphasis" />
            {{ reglementMeta[item.modeReglement]?.label || item.modeReglement }}
            <span v-if="item.etablissementNom" class="text-medium-emphasis ml-1">· {{ item.etablissementNom }}</span>
          </span>
        </template>
        <template #[`item.totalHt`]="{ item }">{{ fmtMontant(item.totalHt, item) }}</template>
        <template #[`item.totalTva`]="{ item }">
          <span class="text-medium-emphasis">{{ fmtMontant(item.totalTva, item) }}</span>
        </template>
        <template #[`item.totalTtc`]="{ item }">
          <span class="font-weight-bold">{{ fmtMontant(item.totalTtc, item) }}</span>
        </template>
        <template #[`item.statut`]="{ item }">
          <v-chip :color="statutMeta[item.statut]?.color" size="small" variant="tonal">
            {{ statutMeta[item.statut]?.label || item.statut }}
          </v-chip>
        </template>
        <template #[`item.pieceReference`]="{ item }">
          <code v-if="item.pieceReference" class="text-caption">{{ item.pieceReference }}</code>
          <span v-else class="text-medium-emphasis">—</span>
        </template>
        <template #no-data>
          <div class="vte-empty">
            <v-icon icon="mdi-receipt-text-outline" size="42" color="#d1d5db" class="mb-2" />
            <p>Aucune vente enregistrée.</p>
          </div>
        </template>
      </v-data-table>
    </v-card>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-head-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }

.vte-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
}
.vte-stat {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0,0,0,0.07);
  border: 1px solid #f0f0f0;
}
.vte-stat__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px; height: 44px;
  border-radius: 12px;
  flex-shrink: 0;
}
.vte-stat--ca  .vte-stat__icon { background: #dcfce7; color: #16a34a; }
.vte-stat--tva .vte-stat__icon { background: #ede9fe; color: #7c3aed; }
.vte-stat--nb  .vte-stat__icon { background: #dbeafe; color: #2563eb; }
.vte-stat__label { font-size: 0.78rem; color: #9ca3af; margin-bottom: 2px; }
.vte-stat__value { font-size: 1.1rem; font-weight: 700; color: #111827; }

.vte-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 20px;
  color: #9ca3af;
  font-size: 0.85rem;
}
.vte-empty p { margin: 0; }
</style>
