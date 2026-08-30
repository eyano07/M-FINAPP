<script setup lang="ts">
definePageMeta({ module: 'VENTES' })

interface LigneVente {
  id: number
  articleCode?: string
  designation: string
  type?: 'MARCHANDISE' | 'SERVICE'
  quantite: number
  prixUnitaire: number
  soumisTva: boolean
  montantHt: number
  montantTva: number
  montantTtc: number
}

interface Vente {
  id: number
  reference: string
  dateVente: string
  clientNom: string
  statut: 'BROUILLON' | 'VALIDEE' | 'ANNULEE'
  modeReglement: 'CREDIT' | 'CAISSE' | 'BANQUE' | 'MOBILE_MONEY'
  etablissementNom?: string
  entrepotNom?: string
  totalHt: number
  totalTva: number
  totalTtc: number
  devise?: 'CDF' | 'USD'
  tauxJournalier?: number | null
  tauxTvaApplique?: number
  pieceReference?: string
  mouvementReference?: string
  createdByNom?: string
  reglee?: boolean
  dateReglement?: string | null
  lignes: LigneVente[]
}

interface Etablissement { id: number; nom: string }

const api = useApi()
const auth = useAuthStore()
const route = useRoute()
const parametresStore = useParametresStore()
onMounted(() => { parametresStore.charger() })

const loading = ref(false)
const busy = ref(false)
const erreur = ref('')
const vente = ref<Vente | null>(null)
const tauxChange = ref(1)
const dateImpression = ref('')

const canWrite = computed(() => auth.hasAnyRole(['CAISSIER']))
const peutValider = computed(() => canWrite.value && vente.value?.statut === 'BROUILLON')
const peutAnnuler = computed(() => canWrite.value && vente.value?.statut === 'VALIDEE')
// Une créance ne se règle que sur une vente à crédit validée et non encore soldée.
const peutRegler = computed(() =>
  canWrite.value
  && vente.value?.statut === 'VALIDEE'
  && vente.value?.modeReglement === 'CREDIT'
  && !vente.value?.reglee)

const dialogReglement = ref(false)
const banques = ref<Etablissement[]>([])
const operateurs = ref<Etablissement[]>([])
const formReglement = reactive({
  modeReglement: 'CAISSE' as 'CAISSE' | 'BANQUE' | 'MOBILE_MONEY',
  etablissementId: null as number | null,
  dateReglement: new Date().toISOString().slice(0, 10),
})
const besoinEtablissement = computed(() =>
  formReglement.modeReglement === 'BANQUE' || formReglement.modeReglement === 'MOBILE_MONEY')
const etablissementsOptions = computed(() =>
  (formReglement.modeReglement === 'BANQUE' ? banques.value : operateurs.value)
    .map((e) => ({ title: e.nom, value: e.id })))

async function ouvrirReglement() {
  formReglement.etablissementId = null
  erreur.value = ''
  dialogReglement.value = true
  if (banques.value.length === 0 && operateurs.value.length === 0) {
    const [bqs, ops] = await Promise.all([
      api<Etablissement[]>('/etablissements?type=BANQUE').catch(() => []),
      api<Etablissement[]>('/etablissements?type=MOBILE_MONEY').catch(() => []),
    ])
    banques.value = bqs
    operateurs.value = ops
  }
}

async function confirmerReglement() {
  if (besoinEtablissement.value && !formReglement.etablissementId) {
    erreur.value = formReglement.modeReglement === 'BANQUE'
      ? 'Choisissez la banque encaisseuse.'
      : "Choisissez l'opérateur mobile money."
    return
  }
  busy.value = true
  erreur.value = ''
  try {
    await api(`/ventes/${route.params.id}/regler`, {
      method: 'POST',
      body: {
        modeReglement: formReglement.modeReglement,
        etablissementId: besoinEtablissement.value ? formReglement.etablissementId : null,
        dateReglement: formReglement.dateReglement,
      },
    })
    dialogReglement.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "L'encaissement a échoué.")
  } finally {
    busy.value = false
  }
}

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [data, taux] = await Promise.all([
      api<Vente>(`/ventes/${route.params.id}`),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })) /* 0 et non 1 : un repli a 1 affichait les
        montants FC tels quels comme des USD (surevaluation d'un facteur
        egal au taux, ~2800x) sans que rien ne le signale. A 0, les
        convertisseurs (tous gardes par `taux > 0`) renvoient 0, valeur
        manifestement fausse plutot que plausible. */,
    ])
    vente.value = data
    tauxChange.value = taux.taux || 0
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger la vente.')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await charger()
  // Ouverture directe en mode impression depuis la liste (?print=1)
  if (vente.value && route.query.print === '1') {
    await nextTick()
    imprimer()
  }
})

async function action(chemin: string) {
  busy.value = true
  erreur.value = ''
  try {
    await api(`/ventes/${route.params.id}/${chemin}`, { method: 'POST' })
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "L'opération a échoué.")
  } finally {
    busy.value = false
  }
}

function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  window.print()
}

const statutMeta: Record<string, { label: string; color: string }> = {
  BROUILLON: { label: 'Brouillon', color: 'grey' },
  VALIDEE:   { label: 'Validée',   color: 'success' },
  ANNULEE:   { label: 'Annulée',   color: 'error' },
}
const reglementLabel: Record<string, string> = {
  CREDIT: 'À crédit', CAISSE: 'Caisse', BANQUE: 'Banque', MOBILE_MONEY: 'Mobile Money',
}

// La facture s'exprime dans la devise de la vente. Le taux figé au moment de
// l'opération sert à donner la contre-valeur, jamais à réécrire les montants.
const deviseVente = computed(() => vente.value?.devise ?? 'CDF')
const tauxVente = computed(() => {
  const fige = vente.value?.tauxJournalier
  return fige && fige > 0 ? fige : tauxChange.value
})

const fmtUSD = (montant: number) =>
  deviseVente.value === 'USD'
    ? new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(montant || 0)
    : `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(montant || 0)} FC`

/** Contre-valeur du net à payer dans l'autre devise, au taux de l'opération. */
const contreValeur = computed(() => {
  const ttc = vente.value?.totalTtc || 0
  if (!ttc || tauxVente.value <= 0) return ''
  return deviseVente.value === 'USD'
    ? `≈ ${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(ttc * tauxVente.value)} FC`
    : `≈ ${new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(ttc / tauxVente.value)}`
})

const fmtQte = (q: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 3 }).format(q || 0)
const fmtDate = (d?: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '')
</script>

<template>
  <div class="vd-page">
    <div v-if="loading" class="pa-10 text-center">
      <v-progress-circular indeterminate color="primary" size="32" />
    </div>

    <v-alert v-else-if="erreur && !vente" type="error" variant="tonal">{{ erreur }}</v-alert>

    <template v-else-if="vente">
      <!-- ── Barre d'actions (masquée à l'impression) ────────── -->
      <div class="vd-noprint">
        <div class="page-head">
          <div>
            <v-btn variant="text" size="small" prepend-icon="mdi-arrow-left" to="/ventes" class="mb-2">
              Retour aux ventes
            </v-btn>
            <h1 class="page-title">
              Vente {{ vente.reference }}
              <v-chip :color="statutMeta[vente.statut]?.color" size="small" variant="tonal" class="ml-2">
                {{ statutMeta[vente.statut]?.label }}
              </v-chip>
            </h1>
            <p class="page-sub">{{ vente.clientNom }} · {{ fmtDate(vente.dateVente) }}</p>
          </div>
          <div class="page-head-actions">
            <v-btn variant="outlined" color="primary" prepend-icon="mdi-printer-outline" rounded="lg" @click="imprimer">
              Imprimer
            </v-btn>
            <v-btn
              v-if="peutValider"
              color="primary"
              variant="flat"
              rounded="lg"
              prepend-icon="mdi-check-circle-outline"
              :loading="busy"
              @click="action('valider')"
            >
              Valider
            </v-btn>
            <v-btn
              v-if="peutRegler"
              color="success"
              variant="flat"
              rounded="lg"
              prepend-icon="mdi-cash-check"
              :loading="busy"
              @click="ouvrirReglement"
            >
              Encaisser la créance
            </v-btn>
            <v-btn
              v-if="peutAnnuler"
              color="error"
              variant="outlined"
              rounded="lg"
              prepend-icon="mdi-close-circle-outline"
              :loading="busy"
              @click="action('annuler')"
            >
              Annuler la vente
            </v-btn>
          </div>
        </div>

        <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">
          {{ erreur }}
        </v-alert>

        <v-alert v-if="vente.statut === 'BROUILLON'" type="info" variant="tonal" class="mb-4" density="comfortable">
          Cette vente est un brouillon : le stock n'est pas encore décrémenté et aucune écriture comptable n'a été
          générée. Validez-la pour la comptabiliser.
        </v-alert>

        <v-alert v-if="vente.statut === 'VALIDEE' && vente.modeReglement === 'CREDIT' && !vente.reglee"
                 type="warning" variant="tonal" class="mb-4 no-print" density="comfortable">
          Créance client ouverte : le montant est porté au compte 4111 et reste dû.
          Encaissez-la pour solder la créance.
        </v-alert>
        <v-alert v-if="vente.reglee && vente.dateReglement"
                 type="success" variant="tonal" class="mb-4 no-print" density="comfortable">
          Créance encaissée le {{ fmtDate(vente.dateReglement) }} — le compte client est soldé.
        </v-alert>
      </div>

      <!-- ── En-tête de facture (impression uniquement) ──────── -->
      <div class="vd-print-header">
        <div class="vd-print-header__top">
          <div>
            <p class="vd-print-header__marque">{{ parametresStore.parametres.nom }}</p>
            <p class="vd-print-header__doc">Facture de vente</p>
          </div>
          <div class="vd-print-header__meta">
            <p><strong>{{ vente.reference }}</strong></p>
            <p>{{ fmtDate(vente.dateVente) }}</p>
            <p v-if="dateImpression">Imprimé le {{ dateImpression }}</p>
          </div>
        </div>
      </div>

      <!-- ── Informations ────────────────────────────────────── -->
      <v-card class="classroom-card pa-6 mb-4">
        <div class="vd-infos">
          <div class="vd-info">
            <span class="vd-info__label">Client</span>
            <span class="vd-info__value">{{ vente.clientNom }}</span>
          </div>
          <div class="vd-info">
            <span class="vd-info__label">Règlement</span>
            <span class="vd-info__value">
              {{ reglementLabel[vente.modeReglement] }}
              <template v-if="vente.etablissementNom"> · {{ vente.etablissementNom }}</template>
            </span>
          </div>
          <div v-if="vente.entrepotNom" class="vd-info">
            <span class="vd-info__label">Entrepôt</span>
            <span class="vd-info__value">{{ vente.entrepotNom }}</span>
          </div>
          <div class="vd-info">
            <span class="vd-info__label">Devise</span>
            <span class="vd-info__value">
              {{ deviseVente === 'USD' ? 'Dollar américain (USD)' : 'Franc congolais (FC)' }}
              <template v-if="vente.tauxJournalier"> · {{ vente.tauxJournalier }} FC/$</template>
            </span>
          </div>
          <div v-if="vente.tauxTvaApplique != null" class="vd-info">
            <span class="vd-info__label">Taux de TVA</span>
            <span class="vd-info__value">{{ vente.tauxTvaApplique }} %</span>
          </div>
          <div v-if="vente.createdByNom" class="vd-info">
            <span class="vd-info__label">Vendeur</span>
            <span class="vd-info__value">{{ vente.createdByNom }}</span>
          </div>
        </div>
      </v-card>

      <!-- ── Lignes ──────────────────────────────────────────── -->
      <v-card class="classroom-card mb-4">
        <v-table density="comfortable">
          <thead>
            <tr>
              <th>Article</th>
              <th>Désignation</th>
              <th class="text-right">Qté</th>
              <th class="text-right">P.U. HT</th>
              <th class="text-right">Montant HT</th>
              <th class="text-right">TVA</th>
              <th class="text-right">Total TTC</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="l in vente.lignes" :key="l.id">
              <td><code class="text-caption">{{ l.articleCode }}</code></td>
              <td>
                {{ l.designation }}
                <span v-if="l.type === 'SERVICE'" class="vd-badge-service">service</span>
              </td>
              <td class="text-right">{{ fmtQte(l.quantite) }}</td>
              <td class="text-right">{{ fmtUSD(l.prixUnitaire) }}</td>
              <td class="text-right">{{ fmtUSD(l.montantHt) }}</td>
              <td class="text-right">
                <span v-if="l.soumisTva">{{ fmtUSD(l.montantTva) }}</span>
                <span v-else class="text-medium-emphasis">exonéré</span>
              </td>
              <td class="text-right font-weight-medium">{{ fmtUSD(l.montantTtc) }}</td>
            </tr>
          </tbody>
        </v-table>
      </v-card>

      <!-- ── Totaux ──────────────────────────────────────────── -->
      <div class="vd-totaux">
        <div class="vd-total-row">
          <span>Total HT</span><strong>{{ fmtUSD(vente.totalHt) }}</strong>
        </div>
        <div class="vd-total-row">
          <span>TVA<template v-if="vente.tauxTvaApplique != null"> ({{ vente.tauxTvaApplique }} %)</template></span>
          <strong>{{ fmtUSD(vente.totalTva) }}</strong>
        </div>
        <div class="vd-total-row vd-total-row--ttc">
          <span>Net à payer</span><strong>{{ fmtUSD(vente.totalTtc) }}</strong>
        </div>
        <div v-if="contreValeur" class="vd-contre-valeur">
          {{ contreValeur }}
          <template v-if="vente.tauxJournalier"> · taux du jour : {{ vente.tauxJournalier }} FC/$</template>
        </div>
      </div>

      <!-- ── Rattachements comptables ────────────────────────── -->
      <v-card v-if="vente.pieceReference || vente.mouvementReference" class="classroom-card pa-4 mt-4 vd-noprint">
        <p class="vd-section">Rattachements comptables</p>
        <div class="d-flex flex-wrap ga-4">
          <div v-if="vente.pieceReference" class="vd-lien">
            <v-icon icon="mdi-file-document-outline" size="16" class="mr-1" />
            Journal des ventes : <code>{{ vente.pieceReference }}</code>
          </div>
          <div v-if="vente.mouvementReference" class="vd-lien">
            <v-icon icon="mdi-package-variant" size="16" class="mr-1" />
            Sortie de stock : <code>{{ vente.mouvementReference }}</code>
          </div>
        </div>
      </v-card>

      <!-- ── Signature (impression uniquement) ───────────────── -->
      <div class="vd-signature">
        <div class="vd-signature__bloc">
          <span>Le vendeur</span>
        </div>
        <div class="vd-signature__bloc">
          <span>Le client</span>
        </div>
      </div>
    </template>

    <!-- ── Encaissement de la créance ──────────────────────── -->
    <v-dialog v-model="dialogReglement" max-width="480" class="no-print">
      <v-card class="classroom-card pa-6">
        <div class="d-flex align-center ga-3 mb-4">
          <v-icon icon="mdi-cash-check" color="success" size="28" />
          <span class="text-h6 font-weight-bold">Encaisser la créance</span>
        </div>

        <p class="text-medium-emphasis mb-4">
          Le compte client sera soldé du montant exact qui y a été porté à la vente.
          <template v-if="deviseVente !== 'USD'">
            La contre-valeur encaissée est calculée au taux du jour du règlement ;
            tout écart avec le taux de la vente est comptabilisé en gain ou perte de change.
          </template>
        </p>

        <v-select
          v-model="formReglement.modeReglement"
          :items="[
            { title: 'Caisse', value: 'CAISSE' },
            { title: 'Banque', value: 'BANQUE' },
            { title: 'Mobile Money', value: 'MOBILE_MONEY' },
          ]"
          label="Canal d'encaissement"
          variant="outlined"
          density="comfortable"
          class="mb-3"
        />
        <v-select
          v-if="besoinEtablissement"
          v-model="formReglement.etablissementId"
          :items="etablissementsOptions"
          :label="formReglement.modeReglement === 'BANQUE' ? 'Banque' : 'Opérateur'"
          variant="outlined"
          density="comfortable"
          class="mb-3"
        />
        <v-text-field
          v-model="formReglement.dateReglement"
          label="Date d'encaissement"
          type="date"
          variant="outlined"
          density="comfortable"
          class="mb-4"
        />

        <div class="d-flex ga-3 justify-end">
          <v-btn variant="text" @click="dialogReglement = false">Annuler</v-btn>
          <v-btn color="success" variant="flat" :loading="busy" @click="confirmerReglement">
            Confirmer l'encaissement
          </v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.vd-page { max-width: 1000px; margin: 0 auto; padding-bottom: 48px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 20px; flex-wrap: wrap; }
.page-head-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.page-title { font-size: 1.4rem; font-weight: 700; color: #111827; margin: 0; display: flex; align-items: center; flex-wrap: wrap; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }

.vd-section {
  font-size: 0.72rem;
  font-weight: 700;
  color: #9ca3af;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 0 0 10px;
}

.vd-infos { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; }
.vd-info { display: flex; flex-direction: column; gap: 2px; }
.vd-info__label { font-size: 0.72rem; color: #9ca3af; text-transform: uppercase; letter-spacing: 0.4px; }
.vd-info__value { font-size: 0.9rem; font-weight: 600; color: #111827; }

.vd-badge-service {
  font-size: 0.65rem;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 100px;
  background: #ede9fe;
  color: #6d28d9;
  margin-left: 6px;
}

.vd-totaux {
  padding: 16px 20px;
  background: #f9fafb;
  border: 1px solid #f0f0f0;
  border-radius: 14px;
  max-width: 380px;
  margin-left: auto;
}
.vd-total-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  font-size: 0.875rem;
  color: #6b7280;
  padding: 4px 0;
  font-variant-numeric: tabular-nums;
}
.vd-total-row strong { color: #111827; }
.vd-total-row--ttc {
  margin-top: 8px;
  padding-top: 12px;
  border-top: 1px solid #e5e7eb;
  font-size: 1rem;
}
.vd-total-row--ttc strong { font-size: 1.15rem; color: #16a34a; }
.vd-contre-valeur { text-align: right; font-size: 0.75rem; color: #9ca3af; padding-top: 4px; }

.vd-lien { font-size: 0.82rem; color: #374151; display: flex; align-items: center; }
.vd-lien code { margin-left: 4px; }

/* En-tête de facture et signatures : impression uniquement */
.vd-print-header, .vd-signature { display: none; }

@media print {
  .vd-noprint { display: none !important; }

  .vd-print-header { display: block; margin-bottom: 20px; }
  .vd-print-header__top {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 24px;
    padding-bottom: 12px;
    border-bottom: 3px solid #16a34a;
  }
  .vd-print-header__marque { font-size: 1.2rem; color: #111827; margin: 0; }
  .vd-print-header__doc { font-size: 0.85rem; color: #6b7280; margin: 2px 0 0; text-transform: uppercase; letter-spacing: 1px; }
  .vd-print-header__meta { text-align: right; font-size: 0.8rem; color: #374151; }
  .vd-print-header__meta p { margin: 0 0 2px; }

  .vd-page { max-width: none; padding: 0; }
  .classroom-card { border: 1px solid #e5e7eb !important; box-shadow: none !important; }
  .vd-totaux { background: #fff; }

  .vd-signature {
    display: flex;
    justify-content: space-between;
    gap: 40px;
    margin-top: 48px;
  }
  .vd-signature__bloc {
    flex: 1;
    border-top: 1px solid #9ca3af;
    padding-top: 6px;
    font-size: 0.78rem;
    color: #6b7280;
    text-align: center;
  }
}
</style>
