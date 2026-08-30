<script setup lang="ts">
definePageMeta({ module: 'CAISSE', niveau: 'ECRITURE' })

interface Transaction {
  id: number
  reference: string
  libelle?: string
  montant: number
  sens: 'ENCAISSEMENT' | 'DECAISSEMENT'
  numeroRecu?: string
  caissierNom?: string
  noteFraisId?: number
  noteFraisReference?: string
  dateOperation: string
  tauxJournalier?: number
}

interface Compte {
  id: number
  numero: string
  libelle: string
  type: string
}

/** Ligne de balance du grand livre (cumuls d'un compte, toutes origines). */
interface LigneBalance {
  compteNumero: string
  compteLibelle: string
  totalDebit: number
  totalCredit: number
  solde: number
}

/** Compte OHADA de la caisse : c'est lui qui porte la position réelle. */
const COMPTE_CAISSE = '571'

interface NoteAPayer {
  id: number
  reference: string
  objet: string
  beneficiaire?: string
  montant: number
  devise: string
  priorite?: string
  createurNom?: string
}

interface RecuData {
  sens: 'ENCAISSEMENT' | 'DECAISSEMENT'
  numeroRecu?: string
  reference: string
  montant: number
  dateOperation: string
  caissierNom?: string
  noteFraisReference?: string
  objet?: string
  beneficiaire?: string
}

const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()
onMounted(() => { parametresStore.charger() })

const loading = ref(false)
const erreur = ref('')
const transactions = ref<Transaction[]>([])
const comptes = ref<Compte[]>([])
const soldeComptable = ref<LigneBalance | null>(null)
const tauxChange = ref(1)
const notesAPayer = ref<NoteAPayer[]>([])
// Validées par le DA, en attente de transmission par le DFIN : pas encore
// payables, mais visibles pour que la caisse comprenne pourquoi.
const notesEnAttenteDfin = ref<NoteAPayer[]>([])
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
})

const rules = {
  montant: [(v: any) => (v && v > 0) || 'Montant obligatoire et positif'],
  compte: [(v: any) => !!v || 'Compte obligatoire'],
  libelle: [(v: any) => !!v || 'Libellé obligatoire'],
  noteFrais: [(v: any) => !!v || 'La note de frais à payer est obligatoire'],
}

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [txs, cpts, tauxData, bal] = await Promise.all([
      api<Transaction[]>('/caisse/transactions'),
      api<Compte[]>('/comptes'),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })) /* 0 et non 1 : un repli a 1 affichait les
        montants FC tels quels comme des USD (surevaluation d'un facteur
        egal au taux, ~2800x) sans que rien ne le signale. A 0, les
        convertisseurs (tous gardes par `taux > 0`) renvoient 0, valeur
        manifestement fausse plutot que plausible. */,
      // Position comptable reelle du compte 571, toutes origines confondues
      // (saisie caisse, journal importe, piece manuelle). Les transactions du
      // module ci-dessus n'en sont qu'une partie.
      api<LigneBalance[]>('/caisse/balance').catch(() => [] as LigneBalance[]),
    ])
    transactions.value = txs
    comptes.value = cpts
    soldeComptable.value = bal.find((l) => l.compteNumero === COMPTE_CAISSE) ?? null
    // 0 et non 1 : un repli a 1 traiterait les FC comme des USD sans le signaler.
    tauxChange.value = tauxData.taux || 0
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
    const [aPayer, enAttenteDfin] = await Promise.all([
      api<NoteAPayer[]>('/notes-frais?statut=TRANSMISE_CAISSE'),
      // Validées par le DA mais pas encore transmises par le DFIN : la caisse
      // ne peut pas encore les payer, mais doit savoir qu'elles existent —
      // sinon un état vide est indiscernable d'un circuit réellement à jour.
      api<NoteAPayer[]>('/notes-frais?statut=VALIDEE_DA').catch(() => []),
    ])
    notesAPayer.value = aPayer
    notesEnAttenteDfin.value = enAttenteDfin
  } catch {
    notesAPayer.value = []
    notesEnAttenteDfin.value = []
  } finally {
    loadingNotes.value = false
  }
}

onMounted(charger)

// ── Reçu de paiement (imprimable) ─────────────────────────────────────
const recuData = ref<RecuData | null>(null)
const dateEditionRecu = ref('')

function construireRecu(tx: Transaction, beneficiaire?: string): RecuData {
  return {
    sens: tx.sens,
    numeroRecu: tx.numeroRecu,
    reference: tx.reference,
    montant: tx.montant,
    dateOperation: tx.dateOperation,
    caissierNom: tx.caissierNom,
    noteFraisReference: tx.noteFraisReference,
    objet: tx.libelle,
    beneficiaire,
  }
}

function afficherRecu(tx: Transaction, beneficiaire?: string) {
  recuData.value = construireRecu(tx, beneficiaire)
  dateEditionRecu.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
}

function fermerRecu() {
  recuData.value = null
}

function imprimerRecu() {
  nextTick(() => window.print())
}

/** Réimpression depuis l'historique : va rechercher le bénéficiaire (créateur de la note) au besoin. */
async function voirRecu(item: Transaction) {
  let beneficiaire: string | undefined
  if (item.sens === 'DECAISSEMENT' && item.noteFraisId) {
    try {
      const note = await api<{ beneficiaire?: string; demandeurNom?: string }>(`/notes-frais/${item.noteFraisId}`)
      beneficiaire = note?.beneficiaire || note?.demandeurNom
    } catch {
      // Reçu affiché sans le nom du bénéficiaire si la note n'est plus consultable.
    }
  }
  afficherRecu(item, beneficiaire)
}

function ouvrirDialog() {
  form.montantUSD = null
  form.sens = 'ENCAISSEMENT'
  form.compteContrepartie = ''
  form.libelle = ''
  form.noteFraisId = null
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
  // Pre-fill montant et libellé depuis la note. La note peut déjà être en
  // USD (aucune conversion à faire) ou en CDF (conversion FC → USD via le
  // taux du jour, uniquement pour l'affichage : le paiement réel est
  // recalculé côté serveur à partir du montant et de la devise de la note).
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
      // Paiement d'une note de frais transmise à la caisse
      const notePayee = notesAPayer.value.find((n) => n.id === form.noteFraisId) || null
      tx = await api<Transaction>(`/caisse/notes/${form.noteFraisId}/payer`, { method: 'POST' })
      notesAPayer.value = notesAPayer.value.filter((n) => n.id !== form.noteFraisId)
      transactions.value.unshift(tx)
      dialogOuvert.value = false
      // Le reçu s'affiche juste après le paiement, prêt à imprimer.
      afficherRecu(tx, notePayee?.beneficiaire || notePayee?.createurNom)
    } else {
      // Encaissement direct : le Grand Livre est tenu en USD (devise de
      // base), le montant saisi en USD est donc envoye tel quel — aucune
      // conversion a faire ici, le serveur n'en applique aucune non plus
      // pour cette saisie directe.
      tx = await api<Transaction>('/caisse/transactions', {
        method: 'POST',
        body: {
          montant: form.montantUSD,
          sens: form.sens,
          compteContrepartie: form.compteContrepartie,
          libelle: form.libelle,
        },
      })
      transactions.value.unshift(tx)
      dialogOuvert.value = false
    }
  } catch (e: any) {
    erreurEnvoi.value = e?.data?.message || "Erreur lors de l'enregistrement."
  } finally {
    envoi.value = false
  }
}

// Les transactions de caisse sont deja exprimees en USD (devise de base) :
// aucune conversion a faire pour l'affichage.
const fmtUSD = (usd: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(usd || 0)

// Les trois indicateurs reflètent le COMPTE 571 au grand livre, pas seulement
// les opérations saisies dans ce module : un journal importé ou une pièce
// manuelle mouvementent la caisse sans créer de transaction ici, et afficher
// 0 alors que la caisse détient réellement des fonds serait faux.
const totalEncaissement = computed(() =>
  soldeComptable.value
    ? soldeComptable.value.totalDebit
    : transactions.value.filter((t) => t.sens === 'ENCAISSEMENT').reduce((s, t) => s + t.montant, 0)
)
const totalDecaissement = computed(() =>
  soldeComptable.value
    ? soldeComptable.value.totalCredit
    : transactions.value.filter((t) => t.sens === 'DECAISSEMENT').reduce((s, t) => s + t.montant, 0)
)
const solde = computed(() => totalEncaissement.value - totalDecaissement.value)

/** true si la caisse est mouvementée au grand livre sans aucune opération saisie ici. */
const mouvementeeHorsModule = computed(() =>
  transactions.value.length === 0 && Math.abs(solde.value) > 0.005)

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
  <div class="caisse-page-content">
    <!-- ── En-tête ──────────────────────────────────────────────────── -->
    <div class="page-head">
      <div>
        <h1 class="page-title">Caisse</h1>
        <p class="page-sub">Opérations de caisse · <span class="taux-badge">{{ fmtTaux }}</span></p>
      </div>
      <div class="page-head-actions">
        <v-btn variant="outlined" color="primary" prepend-icon="mdi-refresh" rounded="lg" @click="charger">
          Actualiser
        </v-btn>
        <v-btn variant="outlined" color="primary" prepend-icon="mdi-notebook-outline" rounded="lg" to="/caisse/journal">
          Journal de caisse
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
        <p>Aucune note transmise à la caisse pour le moment.</p>
        <!-- Toujours expliquer un état vide qui pourrait être pris pour un
             bug : ces notes existent, mais ce n'est pas encore le tour de
             la caisse — le DFIN doit d'abord les transmettre. -->
        <v-alert
          v-if="notesEnAttenteDfin.length"
          type="info"
          variant="tonal"
          density="compact"
          rounded="lg"
          class="mt-3 text-left"
        >
          {{ notesEnAttenteDfin.length }} note{{ notesEnAttenteDfin.length > 1 ? 's' : '' }}
          validée{{ notesEnAttenteDfin.length > 1 ? 's' : '' }} par le DA
          {{ notesEnAttenteDfin.length > 1 ? 'attendent' : 'attend' }} encore la transmission
          à la trésorerie par le DFIN — elle{{ notesEnAttenteDfin.length > 1 ? 's' : '' }}
          apparaîtr{{ notesEnAttenteDfin.length > 1 ? 'ont' : 'a' }} ici une fois transmise{{ notesEnAttenteDfin.length > 1 ? 's' : '' }}.
        </v-alert>
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

      <!-- Un historique vide alors que la caisse détient des fonds n'est pas
           une anomalie : les mouvements peuvent venir d'un journal importé ou
           d'une pièce manuelle, qui alimentent le compte 571 sans passer par
           la saisie de ce module. Le dire explicitement évite de laisser
           croire à une perte de données. -->
      <v-alert
        v-if="mouvementeeHorsModule"
        type="info"
        variant="tonal"
        density="comfortable"
        class="mx-4 mb-2"
      >
        Le compte 571 « Caisse » présente un solde de <strong>{{ fmtUSD(solde) }}</strong>,
        mais aucune opération n'a été saisie depuis ce module : ces mouvements
        proviennent d'un journal importé ou de pièces comptables saisies
        directement. Ils figurent au
        <NuxtLink :to="{ path: '/comptabilite/grand-livre', query: { compte: COMPTE_CAISSE } }" class="text-primary">Grand livre</NuxtLink>
        et dans la Balance ; seules les opérations enregistrées ici apparaissent
        dans le tableau ci-dessous.
      </v-alert>
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
          { title: 'Caissier', key: 'caissierNom', sortable: false },
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
        <template #[`item.caissierNom`]="{ item }">
          <span class="text-medium-emphasis">{{ item.caissierNom || '—' }}</span>
        </template>
        <template #[`item.noteFraisReference`]="{ item }">
          <code v-if="item.noteFraisReference" class="text-caption text-primary">{{ item.noteFraisReference }}</code>
          <span v-else class="text-medium-emphasis">—</span>
        </template>
        <template #[`item.numeroRecu`]="{ item }">
          <span v-if="item.numeroRecu" class="d-flex align-center ga-1">
            <code class="text-caption">{{ item.numeroRecu }}</code>
            <v-btn
              icon="mdi-printer-outline"
              variant="text"
              size="x-small"
              title="Voir / imprimer le reçu"
              @click="voirRecu(item)"
            />
          </span>
          <span v-else class="text-medium-emphasis">—</span>
        </template>
      </v-data-table>
    </v-card>

    <!-- ── Dialog nouvelle opération ────────────────────────────────── -->
    <v-dialog v-model="dialogOuvert" max-width="500" persistent>
      <v-card class="dialog-card" rounded="xl">
        <div class="dialog-header dialog-header--caisse">
          <v-icon icon="mdi-cash-register" size="28" color="white" class="mb-2" />
          <div class="text-h6 font-weight-bold text-white">Nouvelle opération de caisse</div>
          <div class="text-caption text-white" style="opacity:0.85">
            Enregistré par : {{ auth.fullName || auth.user?.email }}
          </div>
        </div>

        <v-card-text class="pa-6 pt-5">
          <v-alert v-if="erreurEnvoi" type="error" variant="tonal" class="mb-4" density="compact">
            {{ erreurEnvoi }}
          </v-alert>

          <v-form ref="formRef">
            <!-- Sens : determine par le point d'entree (bouton Encaissement ou Payer),
                 non modifiable : plus besoin de choix puisque le decaissement ne
                 passe plus que par le paiement d'une note de frais. -->
            <v-chip
              :color="form.sens === 'ENCAISSEMENT' ? 'success' : 'error'"
              variant="tonal"
              class="mb-4"
              size="large"
            >
              <v-icon :icon="form.sens === 'ENCAISSEMENT' ? 'mdi-cash-plus' : 'mdi-cash-minus'" class="mr-1" size="18" />
              {{ form.sens === 'ENCAISSEMENT' ? 'Encaissement' : 'Décaissement' }}
            </v-chip>

            <!-- Note de frais à payer (obligatoire pour un décaissement) -->
            <template v-if="form.sens === 'DECAISSEMENT'">
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

  <!-- ── Reçu de paiement (imprimable) ─────────────────────────────── -->
  <div v-if="recuData" class="recu-overlay">
    <div class="recu-overlay__backdrop no-print" @click="fermerRecu" />
    <div class="recu-card">
      <div class="recu-card__head no-print">
        <span>{{ recuData.sens === 'ENCAISSEMENT' ? "Reçu d'encaissement" : 'Reçu de décaissement' }}</span>
        <button class="recu-card__close" @click="fermerRecu">
          <v-icon icon="mdi-close" size="18" />
        </button>
      </div>

      <div class="recu-card__doc">
        <div class="recu-doc__brand">
          <div class="recu-doc__logo" :class="{ 'recu-doc__logo--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
            <v-icon v-else icon="mdi-finance" size="18" color="white" />
          </div>
          <div>
            <div class="recu-doc__company">{{ parametresStore.parametres.nom }}</div>
            <div class="recu-doc__type">
              {{ recuData.sens === 'ENCAISSEMENT' ? "Reçu d'encaissement" : 'Reçu de décaissement' }}
            </div>
          </div>
        </div>

        <div class="recu-doc__numero">N° {{ recuData.numeroRecu || '—' }}</div>

        <div class="recu-doc__rows">
          <div class="recu-doc__row">
            <span>Date</span>
            <strong>{{ fmtDate(recuData.dateOperation) }} à {{ fmtHeure(recuData.dateOperation) }}</strong>
          </div>
          <div class="recu-doc__row">
            <span>Référence opération</span>
            <strong>{{ recuData.reference }}</strong>
          </div>
          <div v-if="recuData.noteFraisReference" class="recu-doc__row">
            <span>Note de frais</span>
            <strong>{{ recuData.noteFraisReference }}</strong>
          </div>
          <div v-if="recuData.beneficiaire" class="recu-doc__row">
            <span>{{ recuData.sens === 'ENCAISSEMENT' ? 'Payeur' : 'Bénéficiaire' }}</span>
            <strong>{{ recuData.beneficiaire }}</strong>
          </div>
          <div class="recu-doc__row">
            <span>Motif</span>
            <strong>{{ recuData.objet || '—' }}</strong>
          </div>
          <div class="recu-doc__row">
            <span>Caissier</span>
            <strong>{{ recuData.caissierNom || '—' }}</strong>
          </div>
        </div>

        <div class="recu-doc__montant">
          <span>Montant {{ recuData.sens === 'ENCAISSEMENT' ? 'encaissé' : 'payé' }}</span>
          <strong>{{ new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(recuData.montant) }} USD</strong>
        </div>

        <div class="recu-doc__signatures">
          <div class="recu-doc__sign">
            <span>{{ recuData.sens === 'ENCAISSEMENT' ? 'Payeur' : 'Bénéficiaire' }}</span>
            <div class="recu-doc__sign-line" />
          </div>
          <div class="recu-doc__sign">
            <span>Caissier</span>
            <div class="recu-doc__sign-line" />
          </div>
        </div>

        <p class="recu-doc__footer">Édité le {{ dateEditionRecu }}</p>
      </div>

      <div class="recu-card__actions no-print">
        <v-btn variant="tonal" rounded="lg" @click="fermerRecu">Fermer</v-btn>
        <v-spacer />
        <v-btn color="primary" variant="flat" rounded="lg" prepend-icon="mdi-printer-outline" @click="imprimerRecu">
          Imprimer
        </v-btn>
      </div>
    </div>
  </div>
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
  background: #f0fdf4;
  color: #16a34a;
  border: 1px solid #bbf7d0;
  border-radius: 6px;
  padding: 1px 7px;
  font-weight: 600;
}

.caisse-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
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
  color: #16a34a;
  background: #f0fdf4;
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
.dialog-header--caisse {
  background: linear-gradient(140deg, #34d399 0%, #16a34a 50%, #14532d 100%);
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

/* ── Reçu de paiement ─────────────────────────────────────────────── */
.recu-overlay {
  position: fixed;
  inset: 0;
  z-index: 2400;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
.recu-overlay__backdrop {
  position: absolute;
  inset: 0;
  background: rgba(17, 24, 39, 0.55);
}
.recu-card {
  position: relative;
  width: 100%;
  max-width: 420px;
  max-height: 90vh;
  overflow-y: auto;
  background: #fff;
  border-radius: 20px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.25);
}
.recu-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px;
  font-weight: 700;
  color: #111827;
  border-bottom: 1px solid #f3f4f6;
}
.recu-card__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  border: none;
  background: #f3f4f6;
  color: #6b7280;
  cursor: pointer;
}
.recu-card__close:hover { background: #e5e7eb; }

.recu-card__doc { padding: 24px 24px 8px; }
.recu-doc__brand { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; }
.recu-doc__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: #16a34a;
  flex-shrink: 0;
  overflow: hidden;
}
.recu-doc__logo--image { background: #fff; border: 1px solid #f0f0f0; }
.recu-doc__logo img { width: 100%; height: 100%; object-fit: contain; padding: 3px; }
.recu-doc__company { font-size: 1rem; font-weight: 800; color: #111827; }
.recu-doc__type {
  font-size: 0.68rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.8px;
  color: #16a34a;
  margin-top: 1px;
}
.recu-doc__numero {
  text-align: center;
  font-size: 0.95rem;
  font-weight: 700;
  color: #111827;
  background: #f0fdf4;
  border: 1px dashed #bbf7d0;
  border-radius: 10px;
  padding: 8px;
  margin-bottom: 18px;
}
.recu-doc__rows { display: flex; flex-direction: column; gap: 10px; margin-bottom: 18px; }
.recu-doc__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  font-size: 0.84rem;
}
.recu-doc__row span { color: #9ca3af; }
.recu-doc__row strong { color: #111827; text-align: right; }
.recu-doc__montant {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 12px;
  background: #f0fdf4;
  border: 1px solid #dcfce7;
  margin-bottom: 22px;
}
.recu-doc__montant span { font-size: 0.78rem; font-weight: 600; color: #166534; text-transform: uppercase; letter-spacing: 0.4px; }
.recu-doc__montant strong { font-size: 1.15rem; color: #15803d; }
.recu-doc__signatures { display: flex; gap: 24px; margin-bottom: 16px; }
.recu-doc__sign { flex: 1; text-align: center; }
.recu-doc__sign span { display: block; font-size: 0.7rem; color: #9ca3af; margin-bottom: 24px; }
.recu-doc__sign-line { border-top: 1px solid #d1d5db; }
.recu-doc__footer { text-align: center; font-size: 0.68rem; color: #d1d5db; margin: 0 0 4px; }

.recu-card__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px 20px;
}
</style>

<style>
/* Le reçu est un overlay hors dialog Vuetify (les dialogs sont masqués à
   l'impression par .v-overlay-container dans classroom.scss) : à
   l'impression, on masque tout le contenu normal de la page et les
   éléments d'interface du reçu lui-même, pour ne laisser que le document. */
@media print {
  .caisse-page-content,
  .no-print {
    display: none !important;
  }
  .recu-overlay {
    position: static;
    padding: 0;
  }
  .recu-card {
    max-width: none;
    max-height: none;
    overflow: visible;
    border-radius: 0;
    box-shadow: none;
  }
}
</style>
