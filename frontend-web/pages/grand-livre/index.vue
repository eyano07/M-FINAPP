<script setup lang="ts">
definePageMeta({ module: 'CAISSE' })

interface Ecriture {
  id: number
  compteNumero: string
  compteLibelle: string
  debit: number
  credit: number
  libelle: string
  dateEcriture: string
  transactionReference?: string
  tauxApplique?: number | null
}

interface NoteAPayer {
  id: number
  reference: string
  objet: string
  montant: number
  devise: string
  priorite?: string
  nombreLignes?: number
}

const api = useApi()
const auth = useAuthStore()

const loading = ref(false)
const erreur = ref('')
const ecritures = ref<Ecriture[]>([])
const notesAPayer = ref<NoteAPayer[]>([])
const loadingNotes = ref(false)

// ── Saisie directe ──────────────────────────────────────────────────────────
const canWrite = computed(() => auth.hasAnyRole(['CAISSIER', 'DFIN']))
const dialogOuvert = ref(false)
const formRef = ref()
const envoi = ref(false)
const erreurEnvoi = ref('')

const form = reactive({
  noteFraisId: null as number | null,
  debit: null as number | null,
  credit: null as number | null,
  libelle: '',
  dateEcriture: new Date().toISOString().slice(0, 10),
})

const rules = {
  noteFrais: [(v: any) => !!v || 'La note de frais à payer est obligatoire'],
  libelle: [(v: any) => !!v || 'Libellé obligatoire'],
}

const noteSelectionnee = computed(() =>
  notesAPayer.value.find((n) => n.id === form.noteFraisId) || null
)

async function chargerNotes() {
  loadingNotes.value = true
  try {
    notesAPayer.value = await api<NoteAPayer[]>('/notes-frais?statut=TRANSMISE_CAISSE')
  } catch {
    notesAPayer.value = []
  } finally {
    loadingNotes.value = false
  }
}

const notesOptions = computed(() =>
  notesAPayer.value.map((n) => ({
    title: `${n.reference} — ${n.objet} (${new Intl.NumberFormat('fr-FR').format(n.montant)} ${n.devise || 'FC'})${n.priorite ? ' [' + n.priorite + ']' : ''}`,
    value: n.id,
  }))
)

function onNoteFraisSelect(id: number | null) {
  if (!id) return
  const note = notesAPayer.value.find((n) => n.id === id)
  if (!note) return
  form.libelle = note.objet || ''
  form.debit = note.montant
  form.credit = null
}

function ouvrirDialog() {
  form.noteFraisId = null
  form.debit = null
  form.credit = null
  form.libelle = ''
  form.dateEcriture = new Date().toISOString().slice(0, 10)
  erreurEnvoi.value = ''
  chargerNotes()
  dialogOuvert.value = true
}

async function soumettre() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  envoi.value = true
  erreurEnvoi.value = ''
  try {
    // Paiement via le workflow note de frais -> PAYEE + écritures double-entrée
    await api(`/caisse/notes/${form.noteFraisId}/payer`, { method: 'POST' })
    // Recharger le grand livre pour afficher les nouvelles écritures
    await charger()
    dialogOuvert.value = false
  } catch (e: any) {
    erreurEnvoi.value = e?.data?.message || "Erreur lors de l'enregistrement."
  } finally {
    envoi.value = false
  }
}

const headers = [
  { title: 'N°', key: 'numero', align: 'center' as const, sortable: false, width: 70 },
  { title: 'Date', key: 'dateEcriture' },
  { title: 'Compte', key: 'compte', sortable: false },
  { title: 'Libellé', key: 'libelle', sortable: false },
  { title: 'Taux', key: 'tauxApplique', align: 'end' as const, sortable: false },
  {
    title: 'Montants (USD)',
    align: 'center' as const,
    children: [
      { title: 'Débit', key: 'debit', align: 'end' as const },
      { title: 'Crédit', key: 'credit', align: 'end' as const },
    ],
  },
]

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    ecritures.value = await api<Ecriture[]>('/caisse/grand-livre')
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger le grand livre.'
  } finally {
    loading.value = false
  }
}

onMounted(charger)

// Le Grand Livre est tenu en devise de base (USD) : debit/credit sont deja
// exprimes en USD par le serveur, `tauxApplique` n'est plus qu'une donnee
// d'audit indiquant le taux d'une eventuelle ligne en devise etrangere.
const totalDebit = computed(() => ecritures.value.reduce((s, e) => s + (e.debit || 0), 0))
const totalCredit = computed(() => ecritures.value.reduce((s, e) => s + (e.credit || 0), 0))

const fmtUSD = (v: number) =>
  v ? new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(v) : '-'
const fmtTaux = (v?: number | null) => (v ? new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(v) : '—')
const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '')
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Grand Livre</h1>
        <p class="page-sub">Toutes les écritures comptables, converties en USD au taux du jour de chaque opération</p>
      </div>
      <div class="page-head-actions">
        <v-btn variant="outlined" color="primary" prepend-icon="mdi-refresh" rounded="lg" @click="charger">
          Actualiser
        </v-btn>
        <v-btn v-if="canWrite" color="primary" prepend-icon="mdi-cash-check" rounded="lg" @click="ouvrirDialog">
          Payer une note
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-card class="classroom-card">
      <v-data-table
        :headers="headers"
        :items="ecritures"
        :loading="loading"
        density="comfortable"
        items-per-page="10"
      >
        <template #[`item.numero`]="{ index }">{{ index + 1 }}</template>
        <template #[`item.dateEcriture`]="{ item }">{{ fmtDate(item.dateEcriture) }}</template>
        <template #[`item.compte`]="{ item }">
          <span class="font-weight-medium">{{ item.compteNumero }}</span>
          <span class="text-medium-emphasis"> — {{ item.compteLibelle }}</span>
        </template>
        <template #[`item.tauxApplique`]="{ item }">
          <span class="text-medium-emphasis">{{ fmtTaux(item.tauxApplique) }}</span>
        </template>
        <template #[`item.debit`]="{ item }">{{ fmtUSD(item.debit) }}</template>
        <template #[`item.credit`]="{ item }">{{ fmtUSD(item.credit) }}</template>

        <template #tfoot>
          <tfoot>
            <tr class="font-weight-bold">
              <td colspan="5" class="text-right">Totaux (USD)</td>
              <td class="text-right">{{ fmtUSD(totalDebit) }}</td>
              <td class="text-right">{{ fmtUSD(totalCredit) }}</td>
            </tr>
          </tfoot>
        </template>
      </v-data-table>
    </v-card>

    <!-- ── Dialog saisie directe ─────────────────────────────────── -->
    <v-dialog v-model="dialogOuvert" max-width="500" persistent>
      <v-card class="dialog-card" rounded="xl">
        <div class="dialog-header">
          <v-icon icon="mdi-cash-check" size="28" color="white" class="mb-2" />
          <div class="text-h6 font-weight-bold text-white">Paiement d'une note de frais</div>
          <div class="text-caption text-white" style="opacity:0.85">
            Saisie par : {{ auth.fullName || auth.user?.email }}
          </div>
        </div>

        <v-card-text class="pa-6 pt-5">
          <v-alert v-if="erreurEnvoi" type="error" variant="tonal" class="mb-4" density="compact">
            {{ erreurEnvoi }}
          </v-alert>

          <v-form ref="formRef">
            <!-- Note de frais à payer (obligatoire) -->
            <v-autocomplete
              v-model="form.noteFraisId"
              :items="notesOptions"
              :loading="loadingNotes"
              label="Note de frais à payer *"
              prepend-inner-icon="mdi-file-document-outline"
              no-data-text="Aucune note transmise à la caisse"
              variant="outlined"
              density="comfortable"
              rounded="lg"
              :rules="rules.noteFrais"
              class="mb-3"
              @update:model-value="onNoteFraisSelect"
            />

            <!-- Ventilation comptable : automatique, ligne par ligne -->
            <v-alert
              v-if="noteSelectionnee"
              type="info"
              variant="tonal"
              density="compact"
              rounded="lg"
              class="mb-3"
            >
              Ventilée automatiquement sur {{ noteSelectionnee.nombreLignes || 1 }}
              ligne(s) de dépense (compte propre à chaque ligne).
            </v-alert>

            <!-- Montant (auto-rempli, lecture seule) -->
            <div class="d-flex gap-3 mb-3">
              <v-text-field
                v-model.number="form.debit"
                :label="`Débit (${noteSelectionnee?.devise || 'FC'})`"
                type="number"
                min="0"
                step="0.01"
                prepend-inner-icon="mdi-arrow-left"
                variant="outlined"
                density="comfortable"
                rounded="lg"
                hide-details
                readonly
                class="flex-1-1"
              />
              <v-text-field
                v-model.number="form.credit"
                :label="`Crédit (${noteSelectionnee?.devise || 'FC'})`"
                type="number"
                min="0"
                step="0.01"
                prepend-inner-icon="mdi-arrow-right"
                variant="outlined"
                density="comfortable"
                rounded="lg"
                hide-details
                readonly
                class="flex-1-1"
              />
            </div>

            <v-text-field
              v-model="form.dateEcriture"
              label="Date d'écriture"
              type="date"
              prepend-inner-icon="mdi-calendar"
              variant="outlined"
              density="comfortable"
              rounded="lg"
              class="mb-3"
            />

            <!-- Libellé (auto-rempli, lecture seule) -->
            <v-text-field
              v-model="form.libelle"
              label="Libellé"
              prepend-inner-icon="mdi-text-short"
              variant="outlined"
              density="comfortable"
              rounded="lg"
              :rules="rules.libelle"
              readonly
            />
          </v-form>
        </v-card-text>

        <v-card-actions class="px-6 pb-5 pt-0">
          <v-btn variant="tonal" rounded="lg" @click="dialogOuvert = false" :disabled="envoi">Annuler</v-btn>
          <v-spacer />
          <v-btn color="primary" variant="flat" rounded="lg" :loading="envoi" prepend-icon="mdi-check" @click="soumettre">
            Enregistrer
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}
.page-head-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.dialog-card { overflow: hidden; }
.dialog-header {
  padding: 28px 28px 20px;
  text-align: center;
  background: linear-gradient(140deg, #818cf8 0%, #4f46e5 50%, #1e1b4b 100%);
}
</style>
