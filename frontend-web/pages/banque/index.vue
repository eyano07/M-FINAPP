<script setup lang="ts">
definePageMeta({ module: 'BANQUE', niveau: 'ECRITURE' })

interface Transaction {
  id: number
  reference: string
  libelle?: string
  montant: number
  sens: 'ENCAISSEMENT' | 'DECAISSEMENT'
  numeroRecu?: string
  operateurNom?: string
  etablissementNom?: string
  noteFraisId?: number
  noteFraisReference?: string
  dateOperation: string
  tauxJournalier?: number
}

interface Etablissement {
  id: number
  nom: string
  actif: boolean
  solde: number
}

interface Compte {
  id: number
  numero: string
  libelle: string
  type: string
}

interface NoteAPayer {
  id: number
  reference: string
  objet: string
  montant: number
  devise: string
  priorite?: string
}

const api = useApi()
const auth = useAuthStore()

const loading = ref(false)
const erreur = ref('')
const transactions = ref<Transaction[]>([])
const comptes = ref<Compte[]>([])
const tauxChange = ref(1)
const notesAPayer = ref<NoteAPayer[]>([])
const loadingNotes = ref(false)

// Dialog
const dialogOuvert = ref(false)
const formRef = ref()
const envoi = ref(false)
const erreurEnvoi = ref('')

const form = reactive({
  montantUSD: null as number | null,
  sens: 'ENCAISSEMENT' as 'ENCAISSEMENT' | 'DECAISSEMENT',
  compteContrepartie: '',
  libelle: '',
  noteFraisId: null as number | null,
  etablissementId: null as number | null,
})

const rules = {
  montant: [(v: any) => (v && v > 0) || 'Montant obligatoire et positif'],
  compte: [(v: any) => !!v || 'Compte obligatoire'],
  libelle: [(v: any) => !!v || 'Libellé obligatoire'],
  noteFrais: [(v: any) => !!v || 'La note de frais à payer est obligatoire'],
  etablissement: [(v: any) => !!v || 'Banque obligatoire'],
}

// Les banques sont gérées par l'administrateur : la liste vient du serveur.
const etablissements = ref<Etablissement[]>([])
const banquesOptions = computed(() =>
  etablissements.value.filter((e) => e.actif).map((e) => ({ title: e.nom, value: e.id }))
)

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [txs, cpts, tauxData, etabs] = await Promise.all([
      api<Transaction[]>('/banque/transactions'),
      api<Compte[]>('/comptes'),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })) /* 0 et non 1 : un repli a 1 affichait les
        montants FC tels quels comme des USD (surevaluation d'un facteur
        egal au taux, ~2800x) sans que rien ne le signale. A 0, les
        convertisseurs (tous gardes par `taux > 0`) renvoient 0, valeur
        manifestement fausse plutot que plausible. */,
      api<Etablissement[]>('/etablissements?type=BANQUE').catch(() => []),
    ])
    transactions.value = txs
    comptes.value = cpts
    // 0 et non 1 : un repli a 1 traiterait les FC comme des USD sans le signaler.
    tauxChange.value = tauxData.taux || 0
    etablissements.value = etabs
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger les données.'
  } finally {
    loading.value = false
  }
  await chargerNotes()
}

async function chargerNotes() {
  loadingNotes.value = true
  try {
    const notes = await api<NoteAPayer[]>('/notes-frais?statut=TRANSMISE_CAISSE')
    notesAPayer.value = notes
  } catch {
    notesAPayer.value = []
  } finally {
    loadingNotes.value = false
  }
}

onMounted(charger)

function ouvrirDialog() {
  form.montantUSD = null
  form.sens = 'ENCAISSEMENT'
  form.compteContrepartie = ''
  form.libelle = ''
  form.noteFraisId = null
  form.etablissementId = null
  erreurEnvoi.value = ''
  iaSuggestion.value = null
  dialogOuvert.value = true
}

// ── Suggestion IA du compte contrepartie (encaissement) ──────────────────
// Sur perte de focus du libellé, l'IA propose un compte de produit adapté
// (ex. "Vente de minerais" → 701 Ventes de marchandises), applique
// uniquement si le compte n'est pas déjà renseigné.
interface SuggestionCompte { compteNumero: string; compteLibelle: string }
const iaSuggestion = ref<SuggestionCompte | null>(null)
const chargementSuggestion = ref(false)

async function suggererCompteEncaissement() {
  if (form.sens !== 'ENCAISSEMENT' || form.compteContrepartie
    || !form.libelle || form.libelle.trim().length < 4) {
    return
  }
  chargementSuggestion.value = true
  try {
    const res = await api<SuggestionCompte>('/ia/suggestion-compte', {
      method: 'POST',
      body: { description: form.libelle, sens: 'ENCAISSEMENT' },
    })
    if (res?.compteNumero && !form.compteContrepartie) {
      form.compteContrepartie = res.compteNumero
      iaSuggestion.value = res
    }
  } catch {
    // Suggestion indisponible : l'utilisateur reste libre de choisir le compte manuellement.
  } finally {
    chargementSuggestion.value = false
  }
}

function annulerSuggestionCompte() {
  if (!iaSuggestion.value) return
  if (form.compteContrepartie === iaSuggestion.value.compteNumero) {
    form.compteContrepartie = ''
  }
  iaSuggestion.value = null
}

/** Ouvre directement le dialogue de décaissement pré-rempli pour une note. */
function payerNoteDepuisListe(note: NoteAPayer) {
  form.sens = 'DECAISSEMENT'
  form.compteContrepartie = ''
  form.etablissementId = null
  erreurEnvoi.value = ''
  form.noteFraisId = note.id
  onNoteFraisSelect(note.id)
  dialogOuvert.value = true
}

const prioriteMeta: Record<string, { label: string; color: string }> = {
  HAUTE:   { label: 'Haute',   color: 'error' },
  MOYENNE: { label: 'Moyenne', color: 'warning' },
  BASSE:   { label: 'Basse',   color: 'info' },
}

function onNoteFraisSelect(id: number | null) {
  if (!id) return
  const note = notesAPayer.value.find((n) => n.id === id)
  if (!note) return
  const estUSD = (note.devise || 'CDF').toUpperCase() === 'USD'
  form.montantUSD = estUSD
    ? note.montant
    : (tauxChange.value > 0 ? note.montant / tauxChange.value : note.montant)
  form.libelle = note.objet || ''
}

const notesOptions = computed(() =>
  notesAPayer.value.map((n) => ({
    title: `${n.reference} — ${n.objet} (${new Intl.NumberFormat('fr-FR').format(n.montant)} ${n.devise || 'FC'})${n.priorite ? ' [' + n.priorite + ']' : ''}`,
    value: n.id,
  }))
)

async function soumettre() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  envoi.value = true
  erreurEnvoi.value = ''
  try {
    let tx: Transaction
    if (form.sens === 'DECAISSEMENT' && form.noteFraisId) {
      // Paiement d'une note de frais transmise à la trésorerie
      tx = await api<Transaction>(`/banque/notes/${form.noteFraisId}/payer`, {
        method: 'POST',
        body: { etablissementId: form.etablissementId },
      })
    } else {
      // Encaissement direct : le Grand Livre est tenu en USD (devise de
      // base), le montant saisi en USD est donc envoye tel quel — aucune
      // conversion a faire ici, le serveur n'en applique aucune non plus
      // pour cette saisie directe.
      tx = await api<Transaction>('/banque/transactions', {
        method: 'POST',
        body: {
          montant: form.montantUSD,
          sens: form.sens,
          compteContrepartie: form.compteContrepartie,
          libelle: form.libelle,
          etablissementId: form.etablissementId,
        },
      })
    }
    transactions.value.unshift(tx)
    if (form.sens === 'DECAISSEMENT' && form.noteFraisId) {
      notesAPayer.value = notesAPayer.value.filter((n) => n.id !== form.noteFraisId)
    }
    dialogOuvert.value = false
  } catch (e: any) {
    erreurEnvoi.value = e?.data?.message || "Erreur lors de l'enregistrement."
  } finally {
    envoi.value = false
  }
}

// FC → USD conversion
// Les transactions sont deja exprimees en USD (devise de base) : aucune
// conversion a faire pour l'affichage.
const fmtUSD = (usd: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(usd || 0)

const totalEncaissement = computed(() =>
  transactions.value.filter((t) => t.sens === 'ENCAISSEMENT').reduce((s, t) => s + t.montant, 0)
)
const totalDecaissement = computed(() =>
  transactions.value.filter((t) => t.sens === 'DECAISSEMENT').reduce((s, t) => s + t.montant, 0)
)
const solde = computed(() => totalEncaissement.value - totalDecaissement.value)

const comptesOptions = computed(() =>
  comptes.value.map((c) => ({ title: `${c.numero} — ${c.libelle}`, value: c.numero }))
)

const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '')
const fmtHeure = (d: string) => (d ? new Date(d).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) : '')
const fmtTauxOperation = (t?: number) => (t ? `${new Intl.NumberFormat('fr-FR').format(t)} FC` : '—')
const fmtTaux = computed(() =>
  tauxChange.value > 1 ? `1 USD = ${new Intl.NumberFormat('fr-FR').format(tauxChange.value)} FC` : '—'
)
</script>

<template>
  <div>
    <!-- ── En-tête ──────────────────────────────────────────────────── -->
    <div class="page-head">
      <div>
        <h1 class="page-title">Banque</h1>
        <p class="page-sub">Opérations bancaires · <span class="taux-badge">{{ fmtTaux }}</span></p>
      </div>
      <div class="page-head-actions">
        <v-btn variant="outlined" color="primary" prepend-icon="mdi-refresh" rounded="lg" @click="charger">
          Actualiser
        </v-btn>
        <v-btn variant="outlined" color="primary" prepend-icon="mdi-notebook-outline" rounded="lg" to="/banque/journal">
          Journal de banque
        </v-btn>
        <v-btn color="primary" prepend-icon="mdi-cash-plus" rounded="lg" @click="ouvrirDialog">
          Encaissement
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <!-- ── Cartes de synthèse (USD) ──────────────────────────────────── -->
    <div class="caisse-stats">
      <div class="caisse-stat caisse-stat--enc">
        <div class="caisse-stat__icon"><v-icon icon="mdi-cash-plus" size="22" /></div>
        <div>
          <div class="caisse-stat__label">Total encaissements</div>
          <div class="caisse-stat__value">{{ fmtUSD(totalEncaissement) }}</div>
        </div>
      </div>
      <div class="caisse-stat caisse-stat--dec">
        <div class="caisse-stat__icon"><v-icon icon="mdi-cash-minus" size="22" /></div>
        <div>
          <div class="caisse-stat__label">Total décaissements</div>
          <div class="caisse-stat__value">{{ fmtUSD(totalDecaissement) }}</div>
        </div>
      </div>
      <div class="caisse-stat" :class="solde >= 0 ? 'caisse-stat--pos' : 'caisse-stat--neg'">
        <div class="caisse-stat__icon"><v-icon icon="mdi-scale-balance" size="22" /></div>
        <div>
          <div class="caisse-stat__label">Solde net</div>
          <div class="caisse-stat__value">{{ fmtUSD(solde) }}</div>
        </div>
      </div>
    </div>

    <!-- ── Solde par banque ─────────────────────────────────────────── -->
    <div v-if="etablissements.length" class="operateur-soldes">
      <div v-for="e in etablissements" :key="e.id" class="operateur-solde">
        <v-icon icon="mdi-bank" size="16" class="operateur-solde__icon" />
        <span class="operateur-solde__nom">{{ e.nom }}</span>
        <span class="operateur-solde__montant">{{ fmtUSD(e.solde) }}</span>
      </div>
    </div>

    <!-- ── Notes de frais en attente de paiement ───────────────────────── -->
    <v-card class="classroom-card mt-4">
      <v-card-title class="text-subtitle-1 font-weight-semibold pa-4 pb-2 d-flex align-center">
        Notes en attente de paiement
        <v-chip v-if="notesAPayer.length" size="small" color="primary" variant="tonal" class="ml-2">
          {{ notesAPayer.length }}
        </v-chip>
        <v-spacer />
        <v-btn size="small" variant="text" icon="mdi-refresh" :loading="loadingNotes" @click="chargerNotes" />
      </v-card-title>

      <div v-if="loadingNotes" class="pa-6 text-center">
        <v-progress-circular indeterminate color="primary" size="28" />
      </div>
      <div v-else-if="notesAPayer.length === 0" class="caisse-empty">
        <v-icon icon="mdi-check-circle-outline" size="28" color="#d1d5db" />
        <p>Aucune note en attente de paiement.</p>
      </div>
      <div v-else class="caisse-notes">
        <div v-for="n in notesAPayer" :key="n.id" class="caisse-note">
          <div class="caisse-note__main">
            <div class="caisse-note__head">
              <code class="caisse-note__ref">{{ n.reference }}</code>
              <v-chip
                v-if="n.priorite"
                size="x-small"
                :color="prioriteMeta[n.priorite]?.color || 'grey'"
                variant="tonal"
              >
                {{ prioriteMeta[n.priorite]?.label || n.priorite }}
              </v-chip>
            </div>
            <p class="caisse-note__objet">{{ n.objet }}</p>
          </div>
          <div class="caisse-note__montant">
            {{ new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(n.montant) }} {{ n.devise || 'FC' }}
          </div>
          <v-btn
            color="primary"
            variant="flat"
            rounded="lg"
            size="small"
            prepend-icon="mdi-cash-check"
            @click="payerNoteDepuisListe(n)"
          >
            Payer
          </v-btn>
        </div>
      </div>
    </v-card>

    <!-- ── Tableau des transactions ──────────────────────────────────── -->
    <v-card class="classroom-card mt-4">
      <v-card-title class="text-subtitle-1 font-weight-semibold pa-4 pb-2">
        Historique des opérations
      </v-card-title>
      <v-data-table
        :items="transactions"
        :loading="loading"
        density="comfortable"
        items-per-page="15"
        :headers="[
          { title: 'Date', key: 'dateOperation' },
          { title: 'Heure', key: 'heureOperation', sortable: false },
          { title: 'Référence', key: 'reference' },
          { title: 'Libellé', key: 'libelle', sortable: false },
          { title: 'Note de frais', key: 'noteFraisReference', sortable: false },
          { title: 'Sens', key: 'sens', align: 'center' },
          { title: 'Montant (USD)', key: 'montant', align: 'end' },
          { title: 'Taux du jour', key: 'tauxJournalier', align: 'end', sortable: false },
          { title: 'Banque', key: 'etablissementNom', sortable: false },
          { title: 'Agent', key: 'operateurNom', sortable: false },
          { title: 'Reçu', key: 'numeroRecu', sortable: false },
        ]"
      >
        <template #[`item.dateOperation`]="{ item }">{{ fmtDate(item.dateOperation) }}</template>
        <template #[`item.heureOperation`]="{ item }">
          <span class="text-medium-emphasis">{{ fmtHeure(item.dateOperation) }}</span>
        </template>
        <template #[`item.tauxJournalier`]="{ item }">
          <span class="text-medium-emphasis">{{ fmtTauxOperation(item.tauxJournalier) }}</span>
        </template>
        <template #[`item.sens`]="{ item }">
          <v-chip
            :color="item.sens === 'ENCAISSEMENT' ? 'success' : 'error'"
            size="small"
            variant="tonal"
          >
            {{ item.sens === 'ENCAISSEMENT' ? 'Encaissement' : 'Décaissement' }}
          </v-chip>
        </template>
        <template #[`item.montant`]="{ item }">
          <span :class="item.sens === 'ENCAISSEMENT' ? 'text-success font-weight-medium' : 'text-error font-weight-medium'">
            {{ fmtUSD(item.montant) }}
          </span>
        </template>
        <template #[`item.etablissementNom`]="{ item }">
          <v-chip v-if="item.etablissementNom" size="small" variant="tonal" color="blue">
            {{ item.etablissementNom }}
          </v-chip>
          <span v-else class="text-medium-emphasis">—</span>
        </template>
        <template #[`item.operateurNom`]="{ item }">
          <span class="text-medium-emphasis">{{ item.operateurNom || '—' }}</span>
        </template>
        <template #[`item.noteFraisReference`]="{ item }">
          <code v-if="item.noteFraisReference" class="text-caption text-primary">{{ item.noteFraisReference }}</code>
          <span v-else class="text-medium-emphasis">—</span>
        </template>
        <template #[`item.numeroRecu`]="{ item }">
          <code v-if="item.numeroRecu" class="text-caption">{{ item.numeroRecu }}</code>
          <span v-else class="text-medium-emphasis">—</span>
        </template>
      </v-data-table>
    </v-card>

    <!-- ── Dialog nouvelle opération ────────────────────────────────── -->
    <v-dialog v-model="dialogOuvert" max-width="500" persistent>
      <v-card class="dialog-card" rounded="xl">
        <div class="dialog-header dialog-header--banque">
          <v-icon icon="mdi-bank" size="28" color="white" class="mb-2" />
          <div class="text-h6 font-weight-bold text-white">Nouvelle opération bancaire</div>
          <div class="text-caption text-white" style="opacity:0.85">
            Enregistré par : {{ auth.fullName || auth.user?.email }}
          </div>
        </div>

        <v-card-text class="pa-6 pt-5">
          <v-alert v-if="erreurEnvoi" type="error" variant="tonal" class="mb-4" density="compact">
            {{ erreurEnvoi }}
          </v-alert>

          <v-form ref="formRef">
            <!-- Sens : determine par le point d'entree (bouton Encaissement ou Payer). -->
            <v-chip
              :color="form.sens === 'ENCAISSEMENT' ? 'success' : 'error'"
              variant="tonal"
              class="mb-4"
              size="large"
            >
              <v-icon :icon="form.sens === 'ENCAISSEMENT' ? 'mdi-cash-plus' : 'mdi-cash-minus'" class="mr-1" size="18" />
              {{ form.sens === 'ENCAISSEMENT' ? 'Encaissement' : 'Décaissement' }}
            </v-chip>

            <!-- Banque (obligatoire, encaissement et décaissement) -->
            <v-select
              v-model="form.etablissementId"
              :items="banquesOptions"
              label="Banque *"
              prepend-inner-icon="mdi-bank"
              no-data-text="Aucune banque configurée — voir Administration"
              variant="outlined"
              density="comfortable"
              rounded="lg"
              :rules="rules.etablissement"
              class="mb-3"
            />

            <!-- Note de frais à payer (obligatoire pour un décaissement) -->
            <template v-if="form.sens === 'DECAISSEMENT'">
              <v-autocomplete
                v-model="form.noteFraisId"
                :items="notesOptions"
                :loading="loadingNotes"
                label="Note de frais à payer *"
                prepend-inner-icon="mdi-file-document-outline"
                no-data-text="Aucune note en attente de paiement"
                variant="outlined"
                density="comfortable"
                rounded="lg"
                :rules="rules.noteFrais"
                class="mb-3"
                @update:model-value="onNoteFraisSelect"
              />
            </template>

            <!-- Montant USD (visible pour encaissement, ou informatif pour décaissement) -->
            <v-text-field
              v-model.number="form.montantUSD"
              label="Montant (USD)"
              type="number"
              min="0.01"
              step="0.01"
              prepend-inner-icon="mdi-currency-usd"
              variant="outlined"
              density="comfortable"
              rounded="lg"
              :rules="rules.montant"
              :hint="form.montantUSD ? `≈ ${new Intl.NumberFormat('fr-FR').format(Math.round((form.montantUSD ?? 0) * tauxChange))} FC` : ''"
              persistent-hint
              :readonly="form.sens === 'DECAISSEMENT' && !!form.noteFraisId"
              class="mb-3"
            />

            <!-- Compte contrepartie (uniquement pour encaissement) -->
            <template v-if="form.sens === 'ENCAISSEMENT'">
              <v-autocomplete
                :model-value="form.compteContrepartie"
                :items="comptesOptions"
                label="Compte contrepartie"
                prepend-inner-icon="mdi-format-list-numbered"
                variant="outlined"
                density="comfortable"
                rounded="lg"
                :rules="rules.compte"
                class="mb-1"
                readonly
                hint="Déterminé automatiquement par l'IA à partir du libellé"
                persistent-hint
              />
              <div v-if="chargementSuggestion" class="ia-suggestion ia-suggestion--loading mb-3">
                <v-progress-circular indeterminate size="14" width="2" color="primary" class="mr-1" />
                L'IA recherche le compte adapté...
              </div>
              <div v-else-if="iaSuggestion && form.compteContrepartie === iaSuggestion.compteNumero" class="ia-suggestion mb-3">
                <v-icon icon="mdi-creation" size="14" color="#7c3aed" class="mr-1" />
                <span>Compte suggéré par l'IA : {{ iaSuggestion.compteLibelle }}</span>
                <button type="button" class="ia-suggestion__dismiss" title="Annuler la suggestion" @click="annulerSuggestionCompte">
                  <v-icon icon="mdi-close" size="12" />
                </button>
              </div>
            </template>

            <!-- Libellé -->
            <v-text-field
              v-model="form.libelle"
              label="Libellé"
              prepend-inner-icon="mdi-text-short"
              variant="outlined"
              density="comfortable"
              rounded="lg"
              :rules="rules.libelle"
              :readonly="form.sens === 'DECAISSEMENT' && !!form.noteFraisId"
              @blur="suggererCompteEncaissement"
            />
          </v-form>
        </v-card-text>

        <v-card-actions class="px-6 pb-5 pt-0 gap-2">
          <v-btn variant="tonal" rounded="lg" @click="dialogOuvert = false" :disabled="envoi">
            Annuler
          </v-btn>
          <v-spacer />
          <v-btn
            color="primary"
            variant="flat"
            rounded="lg"
            :loading="envoi"
            prepend-icon="mdi-check"
            @click="soumettre"
          >
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
.taux-badge {
  font-size: 0.78rem;
  background: #eff6ff;
  color: #2563eb;
  border: 1px solid #bfdbfe;
  border-radius: 6px;
  padding: 1px 7px;
  font-weight: 600;
}

.caisse-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
}

/* ── Solde par banque ────────────────────────────────────────────── */
.operateur-soldes {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 12px;
}
.operateur-solde {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 14px;
  border-radius: 10px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
}
.operateur-solde__icon { color: #2563eb; }
.operateur-solde__nom { font-size: 0.8rem; font-weight: 600; color: #1e3a8a; }
.operateur-solde__montant {
  font-size: 0.8rem;
  font-weight: 700;
  color: #111827;
  font-variant-numeric: tabular-nums;
}
.caisse-stat {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0,0,0,0.07);
  border: 1px solid #f0f0f0;
}
.caisse-stat__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  flex-shrink: 0;
}
.caisse-stat--enc .caisse-stat__icon { background: #dcfce7; color: #16a34a; }
.caisse-stat--dec .caisse-stat__icon { background: #fee2e2; color: #dc2626; }
.caisse-stat--pos .caisse-stat__icon { background: #dbeafe; color: #2563eb; }
.caisse-stat--neg .caisse-stat__icon { background: #fef3c7; color: #d97706; }
.caisse-stat__label { font-size: 0.78rem; color: #9ca3af; margin-bottom: 2px; }
.caisse-stat__value { font-size: 1.1rem; font-weight: 700; color: #111827; }

/* ── Notes en attente de paiement ────────────────────────────────── */
.caisse-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 20px;
  color: #9ca3af;
  font-size: 0.85rem;
  text-align: center;
}
.caisse-empty p { margin: 0; }

.caisse-notes { display: flex; flex-direction: column; }
.caisse-note {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 20px;
  border-top: 1px solid #f3f4f6;
}
.caisse-note__main { flex: 1; min-width: 0; }
.caisse-note__head { display: flex; align-items: center; gap: 8px; margin-bottom: 3px; }
.caisse-note__ref {
  font-size: 0.72rem;
  font-weight: 700;
  color: #2563eb;
  background: #eff6ff;
  padding: 2px 7px;
  border-radius: 6px;
}
.caisse-note__objet {
  font-size: 0.875rem;
  font-weight: 500;
  color: #111827;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.caisse-note__montant {
  font-size: 0.95rem;
  font-weight: 700;
  color: #111827;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.dialog-card { overflow: hidden; }
.dialog-header { padding: 28px 28px 20px; text-align: center; }
.dialog-header--banque {
  background: linear-gradient(140deg, #60a5fa 0%, #2563eb 50%, #1e3a8a 100%);
}

/* ── Suggestion IA du compte ─────────────────────────────────────── */
.ia-suggestion {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 0.74rem;
  color: #6d28d9;
  background: #f5f3ff;
  border: 1px solid #ede9fe;
  border-radius: 8px;
  padding: 5px 8px;
}
.ia-suggestion--loading { color: #6b7280; background: #f9fafb; border-color: #f0f0f0; }
.ia-suggestion__dismiss {
  display: inline-flex;
  align-items: center;
  margin-left: auto;
  background: none;
  border: none;
  color: #9ca3af;
  cursor: pointer;
  padding: 0;
}
</style>
