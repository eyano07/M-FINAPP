<script setup lang="ts">
definePageMeta({ module: 'VENTES' })

import QRCode from 'qrcode'

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
  entrepotId?: number | null
  lignes: LigneVente[]
}

interface Etablissement { id: number; nom: string }
interface ArticleOption { id: number; libelle: string; vendable: boolean; prixVente?: number }
interface EntrepotOption { id: number; code: string; nom: string }

const api = useApi()
const auth = useAuthStore()
const route = useRoute()
const parametresStore = useParametresStore()
onMounted(() => { parametresStore.charger() })
/**
 * Facture sans TVA d'une entreprise non assujettie : ni colonne ni ligne de TVA, mais la
 * mention « TVA non applicable ». Une vente qui porte de la TVA (enregistrée quand
 * l'entreprise était assujettie) l'affiche toujours.
 */
const sansTva = computed(() => parametresStore.parametres.assujettiTva === false && !(vente.value && vente.value.totalTva > 0))

const loading = ref(false)
const busy = ref(false)
const erreur = ref('')
const vente = ref<Vente | null>(null)
const tauxChange = ref(1)
const dateImpression = ref('')
const modeImpression = ref<'FACTURE' | 'TICKET'>('FACTURE')

// QR du numero de reference, genere cote client uniquement (evite tout
// rendu cote serveur d'une image encodee en base64 sur chaque chargement de
// la page — seul le ticket imprime en a besoin).
const qrDataUrl = ref('')
watch(() => vente.value?.reference, async (reference) => {
  if (!reference) { qrDataUrl.value = ''; return }
  try {
    qrDataUrl.value = await QRCode.toDataURL(reference, { margin: 1, width: 160 })
  } catch {
    qrDataUrl.value = ''
  }
}, { immediate: true })

const canWrite = computed(() => auth.hasAnyRole(['CAISSIER', 'ADMIN']))
const peutValider = computed(() => canWrite.value && vente.value?.statut === 'BROUILLON')
// Annuler defait une vente deja comptabilisee : reserve a l'administrateur.
const peutAnnuler = computed(() => auth.hasRole('ADMIN') && vente.value?.statut === 'VALIDEE')
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

// ── Ajout d'une ligne (vente encore BROUILLON) ────────────────────────────
const articlesDisponibles = ref<ArticleOption[]>([])
const entrepotsDisponibles = ref<EntrepotOption[]>([])
const nouvelleLigne = reactive({
  articleId: null as number | null,
  quantite: 1 as number | null,
  prixUnitaire: null as number | null,
  entrepotId: null as number | null,
})
const ajoutLigneBusy = ref(false)
const erreurLigne = ref('')

// Le prix catalogue de l'article est tenu en FC (voir ventes/nouvelle.vue
// prixCatalogue) : reconverti dans la devise de la vente au taux du jour
// avant de pre-remplir le prix unitaire — jamais recopie tel quel si la
// vente est en USD.
const arrondi = (v: number) => Math.round(v * 100) / 100
const proposition = (article?: ArticleOption) => {
  if (!article || article.prixVente == null) return null
  return vente.value?.devise === 'USD'
    ? (tauxChange.value > 0 ? arrondi(article.prixVente / tauxChange.value) : null)
    : article.prixVente
}
watch(() => nouvelleLigne.articleId, async (articleId) => {
  if (articleId == null) return
  const prix = proposition(articlesDisponibles.value.find((a) => a.id === articleId))
  if (prix != null) nouvelleLigne.prixUnitaire = prix
  // Le catalogue n'est chargé qu'une fois : un prix modifié depuis ne s'y
  // verrait pas. L'article choisi est relu, et la proposition corrigée tant
  // que le prix de la ligne n'a pas été retouché. Les lignes déjà
  // enregistrées gardent leur propre prix.
  try {
    const frais = await api<ArticleOption>(`/logistique/articles/${articleId}`)
    const k = articlesDisponibles.value.findIndex((a) => a.id === articleId)
    if (k >= 0) articlesDisponibles.value[k] = { ...articlesDisponibles.value[k], ...frais }
    const prixFrais = proposition(frais)
    if (nouvelleLigne.articleId === articleId && prixFrais != null
      && (prix == null || nouvelleLigne.prixUnitaire === prix)) {
      nouvelleLigne.prixUnitaire = prixFrais
    }
  } catch {
    // Relecture impossible (réseau) : la proposition du catalogue chargé reste.
  }
})

async function chargerArticlesPourAjout() {
  if (articlesDisponibles.value.length || entrepotsDisponibles.value.length) return
  const [arts, ents] = await Promise.all([
    api<ArticleOption[]>('/logistique/articles').catch(() => []),
    api<EntrepotOption[]>('/logistique/entrepots').catch(() => []),
  ])
  articlesDisponibles.value = arts.filter((a) => a.vendable)
  entrepotsDisponibles.value = ents
}

async function ajouterLigne() {
  if (!nouvelleLigne.articleId || !nouvelleLigne.quantite || nouvelleLigne.quantite <= 0) {
    erreurLigne.value = 'Choisissez un article et une quantité strictement positive.'
    return
  }
  if (nouvelleLigne.prixUnitaire == null || nouvelleLigne.prixUnitaire < 0) {
    erreurLigne.value = 'Le prix unitaire est obligatoire.'
    return
  }
  ajoutLigneBusy.value = true
  erreurLigne.value = ''
  try {
    vente.value = await api<Vente>(`/ventes/${route.params.id}/lignes`, {
      method: 'POST',
      body: {
        articleId: nouvelleLigne.articleId,
        quantite: nouvelleLigne.quantite,
        prixUnitaire: nouvelleLigne.prixUnitaire,
        entrepotId: nouvelleLigne.entrepotId,
      },
    })
    nouvelleLigne.articleId = null
    nouvelleLigne.quantite = 1
    nouvelleLigne.prixUnitaire = null
  } catch (e: any) {
    erreurLigne.value = messageErreurApi(e, "Échec de l'ajout de la ligne.")
  } finally {
    ajoutLigneBusy.value = false
  }
}

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
  // Arrivée depuis « Créer et valider » : la vente a été créée, mais sa
  // validation a échoué (voir ventes/nouvelle.vue) — on le dit ici, sur le
  // brouillon lui-même, plutôt que de laisser croire que rien n'a été fait.
  const erreurValidation = route.query.erreurValidation
  if (typeof erreurValidation === 'string' && erreurValidation) {
    erreur.value = `La vente a été enregistrée en brouillon, mais sa validation a échoué : ${erreurValidation}`
    await navigateTo({ path: route.path, query: {} }, { replace: true })
  }
  // Ouverture directe en mode impression depuis la liste (?print=1)
  if (vente.value && route.query.print === '1') {
    await nextTick()
    imprimer()
  }
  if (peutValider.value) {
    await chargerArticlesPourAjout()
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

// ── Confirmation avant de valider ou d'annuler ────────────────────────────
// Les deux engagent la comptabilité et le stock, et ne se défont pas d'un
// simple clic : un clic par erreur ne doit pas suffire.
const confirmation = ref<'valider' | 'annuler' | null>(null)
async function confirmer() {
  const quoi = confirmation.value
  if (!quoi) return
  await action(quoi)
  confirmation.value = null
}

function imprimer() {
  modeImpression.value = 'FACTURE'
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  nextTick(() => window.print())
}

/**
 * Ticket format imprimante thermique (58/80mm) : mise en page verticale a
 * une colonne, sans tableau ni carte. La largeur physique du papier est
 * imposee par le pilote de l'imprimante thermique choisie dans la boite de
 * dialogue d'impression — @page ne fait qu'aider les navigateurs qui la
 * respectent, ce n'est jamais garanti partout. La regle est injectee puis
 * retiree autour de l'impression pour ne jamais affecter le format Facture.
 */
function imprimerTicket() {
  modeImpression.value = 'TICKET'
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  nextTick(() => {
    const style = document.createElement('style')
    style.id = 'vd-ticket-page-style'
    style.textContent = '@page { size: 80mm auto; margin: 3mm; }'
    document.head.appendChild(style)
    window.print()
    window.addEventListener('afterprint', () => {
      document.getElementById('vd-ticket-page-style')?.remove()
      modeImpression.value = 'FACTURE'
    }, { once: true })
  })
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

// ── Rendu de monnaie (brouillon, paiement au comptant) — même mécanique
// que ventes/nouvelle.vue : un calculateur optionnel, jamais envoyé au
// serveur, qui aide le caissier à valider avec le bon rendu de monnaie en
// tête. Le client peut mélanger les devises (ex. 2 billets de 20$ + 1 de
// 5000 FC), chaque billet est ramené dans la devise de la vente.
interface BilletRecu { devise: 'CDF' | 'USD'; coupure: number | null; quantite: number | null }
const COUPURES: Record<'CDF' | 'USD', number[]> = {
  USD: [1, 2, 5, 10, 20, 50, 100],
  CDF: [50, 100, 200, 500, 1000, 5000, 10000, 20000],
}
const billetsRecus = ref<BilletRecu[]>([])
function ajouterBillet() {
  billetsRecus.value.push({ devise: deviseVente.value as 'CDF' | 'USD', coupure: null, quantite: 1 })
}
function supprimerBillet(i: number) {
  billetsRecus.value.splice(i, 1)
}
const montantRecuTotal = computed(() => {
  let total = 0
  let saisi = false
  for (const b of billetsRecus.value) {
    if (!b.coupure || !b.quantite || b.quantite <= 0) continue
    saisi = true
    const montantLigne = b.coupure * b.quantite
    if (b.devise === deviseVente.value) {
      total += montantLigne
    } else if (tauxVente.value > 0) {
      total += b.devise === 'USD' ? montantLigne * tauxVente.value : montantLigne / tauxVente.value
    }
  }
  return saisi ? arrondi(total) : null
})
const monnaieARendre = computed(() => {
  if (montantRecuTotal.value == null || !vente.value) return null
  return arrondi(montantRecuTotal.value - vente.value.totalTtc)
})
const fmtBillet = (montant: number, devise: 'CDF' | 'USD') =>
  devise === 'USD'
    ? new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(montant || 0)
    : `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(montant || 0)} FC`
const monnaieARendreEquivalent = computed(() => {
  if (monnaieARendre.value == null || monnaieARendre.value < 0 || tauxVente.value <= 0) return ''
  return deviseVente.value === 'USD'
    ? `≈ ${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(monnaieARendre.value * tauxVente.value)} FC`
    : `≈ ${new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(monnaieARendre.value / tauxVente.value)}`
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
            <v-btn variant="outlined" color="primary" prepend-icon="mdi-receipt-outline" rounded="lg" @click="imprimerTicket">
              Ticket
            </v-btn>
            <v-btn
              v-if="peutValider"
              color="primary"
              variant="flat"
              rounded="lg"
              prepend-icon="mdi-check-circle-outline"
              :loading="busy"
              @click="confirmation = 'valider'"
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
              @click="confirmation = 'annuler'"
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

      <template v-if="modeImpression === 'FACTURE'">
      <!-- ── En-tête de facture (impression uniquement) ──────── -->
      <div class="vd-print-header">
        <div class="vd-print-header__top">
          <div class="vd-print-header__brand">
            <div class="vd-print-header__logo" :class="{ 'vd-print-header__logo--image': parametresStore.parametres.logoUrl }">
              <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
              <v-icon v-else icon="mdi-finance" size="16" color="white" />
            </div>
            <div>
              <p class="vd-print-header__marque">{{ parametresStore.parametres.nom }}</p>
              <p class="vd-print-header__doc">Facture de vente</p>
            </div>
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
          <!-- Entrepôt et devise : utiles à l'écran, retirés de la facture
               imprimée (le taux figure déjà sous le net à payer). -->
          <div v-if="vente.entrepotNom" class="vd-info vd-noprint">
            <span class="vd-info__label">Entrepôt</span>
            <span class="vd-info__value">{{ vente.entrepotNom }}</span>
          </div>
          <div class="vd-info vd-noprint">
            <span class="vd-info__label">Devise</span>
            <span class="vd-info__value">
              {{ deviseVente === 'USD' ? 'Dollar américain (USD)' : 'Franc congolais (FC)' }}
              <template v-if="vente.tauxJournalier"> · {{ vente.tauxJournalier }} FC/$</template>
            </span>
          </div>
          <div v-if="sansTva" class="vd-info">
            <span class="vd-info__label">TVA</span>
            <span class="vd-info__value">Non applicable (entreprise non assujettie)</span>
          </div>
          <div v-else-if="vente.tauxTvaApplique != null" class="vd-info">
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
              <th class="text-right">{{ sansTva ? 'P.U.' : 'P.U. HT' }}</th>
              <th class="text-right">{{ sansTva ? 'Montant' : 'Montant HT' }}</th>
              <th v-if="!sansTva" class="text-right">TVA</th>
              <th v-if="!sansTva" class="text-right">Total TTC</th>
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
              <td v-if="!sansTva" class="text-right">
                <span v-if="l.soumisTva">{{ fmtUSD(l.montantTva) }}</span>
                <span v-else class="text-medium-emphasis">exonéré</span>
              </td>
              <td v-if="!sansTva" class="text-right font-weight-medium">{{ fmtUSD(l.montantTtc) }}</td>
            </tr>
          </tbody>
        </v-table>
      </v-card>

      <!-- ── Ajout d'une ligne (vente encore brouillon) ────────── -->
      <v-card v-if="peutValider" class="classroom-card mb-4 pa-4 vd-noprint">
        <p class="text-caption text-medium-emphasis mb-3">
          <v-icon icon="mdi-information-outline" size="14" class="mr-1" />
          Vente en brouillon : ajoutez une ligne si le client commande autre chose avant l'addition.
        </p>
        <v-alert v-if="erreurLigne" type="error" variant="tonal" density="compact" rounded="lg" class="mb-3">
          {{ erreurLigne }}
        </v-alert>
        <div class="d-flex ga-2 flex-wrap align-start">
          <v-autocomplete
            v-model="nouvelleLigne.articleId"
            :items="articlesDisponibles.map(a => ({ title: a.libelle, value: a.id }))"
            label="Article"
            variant="outlined"
            density="comfortable"
            hide-details
            class="flex-grow-1"
            style="min-width: 220px"
          />
          <v-text-field
            v-model.number="nouvelleLigne.quantite"
            type="number"
            label="Qté"
            variant="outlined"
            density="comfortable"
            hide-details
            style="max-width: 100px"
          />
          <v-text-field
            v-model.number="nouvelleLigne.prixUnitaire"
            type="number"
            label="P.U. HT"
            variant="outlined"
            density="comfortable"
            hide-details
            style="max-width: 140px"
          />
          <v-select
            v-if="!vente.entrepotId"
            v-model="nouvelleLigne.entrepotId"
            :items="entrepotsDisponibles.map(e => ({ title: `${e.code} — ${e.nom}`, value: e.id }))"
            label="Entrepôt"
            variant="outlined"
            density="comfortable"
            hide-details
            style="min-width: 180px"
          />
          <v-btn color="primary" variant="flat" rounded="lg" prepend-icon="mdi-plus" :loading="ajoutLigneBusy" @click="ajouterLigne">
            Ajouter
          </v-btn>
        </div>
      </v-card>

      <!-- ── Totaux ──────────────────────────────────────────── -->
      <div class="vd-totaux">
        <div v-if="sansTva" class="vd-total-row">
          <span>TVA non applicable — entreprise non assujettie</span>
        </div>
        <template v-else>
          <div class="vd-total-row">
            <span>Total HT</span><strong>{{ fmtUSD(vente.totalHt) }}</strong>
          </div>
          <div class="vd-total-row">
            <span>TVA<template v-if="vente.tauxTvaApplique != null"> ({{ vente.tauxTvaApplique }} %)</template></span>
            <strong>{{ fmtUSD(vente.totalTva) }}</strong>
          </div>
        </template>
        <div class="vd-total-row vd-total-row--ttc">
          <span>Net à payer</span><strong>{{ fmtUSD(vente.totalTtc) }}</strong>
        </div>
        <div v-if="contreValeur" class="vd-contre-valeur">
          {{ contreValeur }}
          <template v-if="vente.tauxJournalier"> · taux du jour : {{ vente.tauxJournalier }} FC/$</template>
        </div>
      </div>

      <!-- ── Rendu de monnaie (brouillon, paiement au comptant en caisse) ── -->
      <div v-if="peutValider && vente.modeReglement === 'CAISSE'" class="vd-rendu-monnaie vd-noprint">
        <div class="vd-rendu-monnaie__head">
          <span class="vd-section" style="margin: 0">Billets reçus du client</span>
          <v-btn size="small" variant="text" color="primary" prepend-icon="mdi-plus" @click="ajouterBillet">
            Ajouter un billet
          </v-btn>
        </div>
        <p v-if="!billetsRecus.length" class="text-caption text-medium-emphasis mb-2">
          Optionnel — précisez les billets remis pour calculer automatiquement la monnaie à rendre
          avant de valider (ex. 2 billets de 20$ + 1 billet de 5000 FC).
        </p>

        <div v-for="(b, i) in billetsRecus" :key="i" class="vd-billet-ligne">
          <v-btn-toggle
            v-model="b.devise"
            mandatory
            density="comfortable"
            variant="outlined"
            rounded="lg"
            @update:model-value="b.coupure = null"
          >
            <v-btn value="CDF" size="small">FC</v-btn>
            <v-btn value="USD" size="small">$US</v-btn>
          </v-btn-toggle>
          <v-select
            v-model.number="b.coupure"
            :items="COUPURES[b.devise]"
            label="Coupure"
            variant="outlined"
            density="comfortable"
            hide-details
            class="vd-billet-coupure"
          />
          <span class="vd-billet-x">×</span>
          <v-text-field
            v-model.number="b.quantite"
            type="number"
            min="1"
            label="Qté"
            variant="outlined"
            density="comfortable"
            hide-details
            class="vd-billet-qte"
          />
          <span class="vd-billet-total">{{ fmtBillet((b.coupure || 0) * (b.quantite || 0), b.devise) }}</span>
          <v-btn icon="mdi-close" size="x-small" variant="text" color="grey" @click="supprimerBillet(i)" />
        </div>

        <template v-if="montantRecuTotal != null">
          <div class="vd-total-row" style="margin-top: 8px">
            <span>Total reçu</span>
            <strong>{{ fmtUSD(montantRecuTotal) }}</strong>
          </div>
          <div class="vd-total-row vd-total-row--ttc" :class="{ 'vd-rendu-monnaie--negatif': monnaieARendre != null && monnaieARendre < 0 }">
            <span>{{ (monnaieARendre ?? 0) >= 0 ? 'Monnaie à rendre' : 'Montant manquant' }}</span>
            <strong>{{ fmtUSD(Math.abs(monnaieARendre ?? 0)) }}</strong>
          </div>
          <div v-if="monnaieARendreEquivalent" class="vd-contre-valeur">{{ monnaieARendreEquivalent }}</div>
        </template>
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

      <!-- ── Ticket imprimante thermique (impression uniquement) ─── -->
      <div v-else class="vd-ticket">
        <div class="vd-ticket__marque">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo" class="vd-ticket__logo">
          <p class="vd-ticket__nom">{{ parametresStore.parametres.nom }}</p>
          <p class="vd-ticket__type">Facture</p>
        </div>
        <div class="vd-ticket__sep" />
        <p class="vd-ticket__ref">{{ vente.reference }}</p>
        <p>{{ fmtDate(vente.dateVente) }}</p>
        <p v-if="vente.clientNom">Client : {{ vente.clientNom }}</p>
        <p v-if="vente.createdByNom">Vendeur : {{ vente.createdByNom }}</p>
        <div class="vd-ticket__sep" />
        <div v-for="l in vente.lignes" :key="l.id" class="vd-ticket__ligne">
          <div>{{ l.designation }}</div>
          <div class="vd-ticket__ligne-detail">
            <span>{{ fmtQte(l.quantite) }} × {{ fmtUSD(l.prixUnitaire) }}</span>
            <span>{{ fmtUSD(l.montantTtc) }}</span>
          </div>
        </div>
        <div class="vd-ticket__sep" />
        <template v-if="!sansTva">
          <div class="vd-ticket__total-row"><span>Total HT</span><span>{{ fmtUSD(vente.totalHt) }}</span></div>
          <div class="vd-ticket__total-row">
            <span>TVA<template v-if="vente.tauxTvaApplique != null"> ({{ vente.tauxTvaApplique }} %)</template></span>
            <span>{{ fmtUSD(vente.totalTva) }}</span>
          </div>
        </template>
        <div class="vd-ticket__total-row vd-ticket__total-row--net"><span>NET À PAYER</span><span>{{ fmtUSD(vente.totalTtc) }}</span></div>
        <p v-if="sansTva" class="vd-ticket__contre">TVA non applicable — entreprise non assujettie</p>
        <p v-if="contreValeur" class="vd-ticket__contre">{{ contreValeur }}</p>
        <div class="vd-ticket__sep" />
        <p>Règlement : {{ reglementLabel[vente.modeReglement] }}</p>
        <p v-if="vente.entrepotNom">Entrepôt : {{ vente.entrepotNom }}</p>
        <div class="vd-ticket__sep" />
        <div v-if="qrDataUrl" class="vd-ticket__qr">
          <img :src="qrDataUrl" alt="QR" class="vd-ticket__qr-img">
        </div>
        <p class="vd-ticket__merci">Merci de votre achat !</p>
        <p v-if="dateImpression" class="vd-ticket__horodatage">{{ dateImpression }}</p>
      </div>
    </template>

    <!-- ── Encaissement de la créance ──────────────────────── -->
    <v-dialog
      :model-value="!!confirmation"
      max-width="500"
      class="no-print"
      :persistent="busy"
      @update:model-value="v => { if (!v && !busy) confirmation = null }"
    >
      <v-card v-if="confirmation && vente" rounded="lg">
        <v-card-title class="text-subtitle-1 font-weight-bold text-wrap">
          {{ confirmation === 'valider' ? 'Valider' : 'Annuler' }} la vente {{ vente.reference }} ?
        </v-card-title>
        <v-card-text>
          <template v-if="confirmation === 'valider'">
            <p class="mb-1">Client : <strong>{{ vente.clientNom }}</strong></p>
            <p class="mb-1">
              Règlement : <strong>{{ reglementLabel[vente.modeReglement] }}</strong>
              <template v-if="vente.etablissementNom"> · {{ vente.etablissementNom }}</template>
            </p>
            <p class="mb-1">
              Net à payer : <strong>{{ fmtUSD(vente.totalTtc) }}</strong>
              <span v-if="contreValeur" class="text-medium-emphasis"> ({{ contreValeur }})</span>
            </p>
            <p v-if="monnaieARendre != null && monnaieARendre >= 0" class="mb-1">
              Monnaie à rendre : <strong>{{ fmtUSD(monnaieARendre) }}</strong>
            </p>
            <p v-else-if="monnaieARendre != null" class="mb-1 text-error">
              Montant reçu insuffisant : il manque <strong>{{ fmtUSD(-monnaieARendre) }}</strong>.
            </p>
            <p class="text-medium-emphasis mt-3 mb-0">
              La vente sera comptabilisée et les articles sortiront du stock<template
                v-if="vente.modeReglement === 'CAISSE'">, le montant entrera en caisse</template><template
                v-else-if="vente.modeReglement === 'CREDIT'">, une créance sera ouverte au nom du client</template><template
                v-else>, le règlement sera enregistré ({{ reglementLabel[vente.modeReglement] }})</template>.
              Elle ne pourra plus être modifiée, seulement annulée par un administrateur.
            </p>
          </template>
          <template v-else>
            <p class="mb-2">
              Les articles reviennent en stock et les écritures de la vente
              ({{ fmtUSD(vente.totalTtc) }}) sont extournées.
            </p>
            <p class="text-medium-emphasis mb-0">Une vente annulée ne peut pas être rétablie.</p>
          </template>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="busy" @click="confirmation = null">Retour</v-btn>
          <v-btn
            :color="confirmation === 'valider' ? 'primary' : 'error'"
            variant="flat"
            :loading="busy"
            @click="confirmer"
          >
            {{ confirmation === 'valider' ? 'Oui, valider' : 'Oui, annuler la vente' }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

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
.vd-total-row--ttc strong { font-size: 1.15rem; color: var(--color-primary); }
.vd-contre-valeur { text-align: right; font-size: 0.75rem; color: #9ca3af; padding-top: 4px; }

.vd-rendu-monnaie {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed #e5e7eb;
}
.vd-rendu-monnaie__head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; }
.vd-rendu-monnaie--negatif strong { color: #dc2626 !important; }
.vd-rendu-monnaie .vd-total-row, .vd-rendu-monnaie .vd-contre-valeur { max-width: 380px; margin-left: auto; }

.vd-billet-ligne {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid #f3f4f6;
}
.vd-billet-coupure { max-width: 110px; }
.vd-billet-qte { max-width: 72px; }
.vd-billet-x { color: #9ca3af; font-size: 0.85rem; }
.vd-billet-total {
  flex: 1;
  text-align: right;
  font-size: 0.875rem;
  font-weight: 600;
  color: #111827;
  font-variant-numeric: tabular-nums;
}

.vd-lien { font-size: 0.82rem; color: #374151; display: flex; align-items: center; }
.vd-lien code { margin-left: 4px; }

/* En-tête de facture, signatures et ticket : impression uniquement */
.vd-print-header, .vd-signature, .vd-ticket { display: none; }

@media print {
  .vd-noprint { display: none !important; }

  .vd-print-header { display: block; margin-bottom: 20px; }
  .vd-print-header__top {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 24px;
    padding-bottom: 12px;
    border-bottom: 3px solid var(--color-primary);
  }
  .vd-print-header__brand { display: flex; align-items: center; gap: 11px; }
  .vd-print-header__logo {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    border-radius: 10px;
    background: var(--color-primary);
    flex-shrink: 0;
    overflow: hidden;
  }
  .vd-print-header__logo--image { background: #fff; border: 1px solid #e5e7eb; }
  .vd-print-header__logo img { width: 100%; height: 100%; object-fit: contain; padding: 3px; }
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

  /* ── Ticket imprimante thermique ──────────────────────────── */
  .vd-ticket {
    display: block;
    width: 100%;
    max-width: 74mm;
    /* Pas de "margin: auto" : certains moteurs d'impression ("Enregistrer en
       PDF" notamment) n'honorent pas toujours le @page size 80mm injecte par
       imprimerTicket() et gardent une page bien plus large — un centrage
       horizontal y ferait flotter le ticket au milieu au lieu de partir du
       bord, comme sur une vraie imprimante thermique. */
    margin: 0;
    font-family: 'Courier New', monospace;
    font-size: 11px;
    line-height: 1.4;
    color: #000;
  }
  .vd-ticket p { margin: 0; }
  .vd-ticket__marque { text-align: center; margin-bottom: 4px; }
  .vd-ticket__logo { max-width: 40mm; max-height: 18mm; object-fit: contain; margin-bottom: 4px; }
  .vd-ticket__nom { font-size: 13px; font-weight: 700; text-transform: uppercase; }
  .vd-ticket__type { font-size: 10px; text-transform: uppercase; letter-spacing: 1px; }
  .vd-ticket__ref { font-weight: 700; }
  .vd-ticket__sep {
    border-top: 1px dashed #000;
    margin: 6px 0;
  }
  .vd-ticket__qr { text-align: center; margin: 4px 0; }
  .vd-ticket__qr-img { width: 28mm; height: 28mm; }
  .vd-ticket__ligne { margin-bottom: 3px; }
  .vd-ticket__ligne-detail { display: flex; justify-content: space-between; padding-left: 8px; }
  .vd-ticket__total-row { display: flex; justify-content: space-between; }
  .vd-ticket__total-row--net { font-weight: 700; font-size: 13px; margin-top: 2px; }
  .vd-ticket__contre { text-align: right; font-size: 10px; }
  .vd-ticket__merci { text-align: center; font-weight: 700; margin-top: 4px; }
  .vd-ticket__horodatage { text-align: center; font-size: 9px; color: #444; margin-top: 6px; }
}
</style>
