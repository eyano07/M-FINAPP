<script setup lang="ts">
// Extension du stock : même module, mêmes rôles que les autres écrans
// logistiques (le caissier reste exclu, comme sur Articles).
definePageMeta({ module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'GEST_PATRIMOINE', 'ADMIN'] })

interface Article { id: number; code: string; libelle: string; minerais?: boolean; actif: boolean }
interface Entrepot { id: number; nom: string; actif: boolean }
interface Camion {
  id: number
  articleId: number
  articleCode: string
  articleLibelle: string
  entrepotNom: string
  plaque: string
  dateReception: string
  prixAchat: number
  detteFournisseur: number
  coutAcquisition: number
  totalFraisConnexes: number
  statut: 'A_VALIDER' | 'EN_STOCK' | 'VENDU'
  regle: boolean
  mouvementReference?: string
  pieceReceptionReference?: string
  transactionReglementReference?: string
  noteFraisReglementReference?: string
}

interface Charge {
  id: number
  camionId: number
  camionPlaque: string
  libelle: string
  compteChargeNumero: string
  compteChargeLibelle: string
  montant: number
  dateCharge: string
  regle: boolean
  enAttente: boolean
}

const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()
const canWrite = computed(() => auth.hasAnyRole(['LOGISTIQUE', 'ADMIN']))

const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const camions = ref<Camion[]>([])
const minerais = ref<Article[]>([])
const entrepots = ref<Entrepot[]>([])
const tauxChange = ref(0)

const dialog = ref(false)
const form = reactive({
  articleId: null as number | null,
  entrepotId: null as number | null,
  plaque: '',
  dateReception: new Date().toISOString().slice(0, 10),
  prixAchatUSD: null as number | null,
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [cs, arts, ents, taux] = await Promise.all([
      api<Camion[]>('/logistique/minerais/camions'),
      api<Article[]>('/logistique/articles'),
      api<Entrepot[]>('/logistique/entrepots'),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
    ])
    camions.value = cs
    minerais.value = arts.filter(a => a.minerais && a.actif)
    entrepots.value = ents.filter(e => e.actif)
    tauxChange.value = taux.taux || 0
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les camions.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function ouvrirReception() {
  Object.assign(form, {
    articleId: minerais.value.length === 1 ? minerais.value[0].id : null,
    entrepotId: entrepots.value.length === 1 ? entrepots.value[0].id : null,
    plaque: '',
    dateReception: new Date().toISOString().slice(0, 10),
    prixAchatUSD: null,
  })
  erreur.value = ''
  dialog.value = true
}

async function receptionner() {
  if (!form.articleId || !form.entrepotId || !form.plaque.trim() || !form.prixAchatUSD) {
    erreur.value = 'Minerais, entrepôt, plaque et prix d\'achat sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    await api('/logistique/minerais/camions', {
      method: 'POST',
      body: {
        articleId: form.articleId,
        entrepotId: form.entrepotId,
        plaque: form.plaque.trim(),
        dateReception: form.dateReception,
        prixAchat: form.prixAchatUSD,
      },
    })
    dialog.value = false
    succes.value = `Camion ${form.plaque.trim().toUpperCase()} réceptionné, en attente de validation de l'achat par la caisse.`
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la réception.')
  } finally {
    saving.value = false
  }
}

// ── Duplication rapide d'un camion (même minerais, même prix, mêmes frais) ─
const dialogDupliquer = ref(false)
const camionSourceDupliquer = ref<Camion | null>(null)
const plaqueDupliquee = ref('')
const erreurDupliquer = ref('')
const envoiDupliquer = ref(false)

function ouvrirDupliquer(c: Camion) {
  camionSourceDupliquer.value = c
  plaqueDupliquee.value = ''
  erreurDupliquer.value = ''
  dialogDupliquer.value = true
}

async function dupliquerCamion() {
  if (!camionSourceDupliquer.value) return
  if (!plaqueDupliquee.value.trim()) {
    erreurDupliquer.value = 'La plaque est obligatoire.'
    return
  }
  envoiDupliquer.value = true
  erreurDupliquer.value = ''
  try {
    const plaque = plaqueDupliquee.value.trim().toUpperCase()
    await api(`/logistique/minerais/camions/${camionSourceDupliquer.value.id}/dupliquer`, {
      method: 'POST',
      body: { plaque },
    })
    dialogDupliquer.value = false
    succes.value = `Camion ${plaque} ajouté à partir de ${camionSourceDupliquer.value.plaque}.`
    await charger()
  } catch (e: any) {
    erreurDupliquer.value = messageErreurApi(e, 'Échec de la duplication.')
  } finally {
    envoiDupliquer.value = false
  }
}

async function supprimer(c: Camion) {
  saving.value = true
  erreur.value = ''
  try {
    await api(`/logistique/minerais/camions/${c.id}`, { method: 'DELETE' })
    succes.value = `Camion ${c.plaque} supprimé, stock et écritures extournés.`
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Suppression impossible.')
  } finally {
    saving.value = false
  }
}

// ── Charges connexes (frais accessoires d'achat) ──────────────────────────
const dialogCharges = ref(false)
const camionCharges = ref<Camion | null>(null)
const charges = ref<Charge[]>([])
const chargesLoading = ref(false)
const formCharge = reactive({
  libelle: '',
  compteChargeNumero: '' as string | null,
  montantUSD: null as number | null,
  dateCharge: new Date().toISOString().slice(0, 10),
  tousLesCamions: false,
})

// Frais courants sur un chargement de minerais, chacun avec son compte de
// charge : choisir la nature preselectionne le compte, plus personne n'a a
// connaitre le plan comptable pour saisir un peage. Le compte reste
// modifiable ensuite — la preselection n'est qu'un defaut.
const NATURES_FRAIS = [
  { libelle: 'Transport sur achat', compte: '618.3' },
  { libelle: 'Péage routier', compte: '618.1' },
  { libelle: 'Per diem de route', compte: '618.2' },
  { libelle: 'Pont bascule', compte: '638.1' },
  { libelle: 'Manutention', compte: '638.2' },
  { libelle: 'Document de chargement', compte: '646.1' },
  { libelle: 'Document de déchargement', compte: '646.1' },
]
const LIBELLES_COURANTS = NATURES_FRAIS.map(n => n.libelle)

/** Nature choisie -> compte correspondant. Sans effet sur un libelle libre. */
function onNatureChoisie(valeur: string | null) {
  const nature = NATURES_FRAIS.find(n => n.libelle === (valeur ?? '').trim())
  if (nature) {
    formCharge.compteChargeNumero = nature.compte
  }
}

const totalCharges = computed(() => charges.value.reduce((s, c) => s + Number(c.montant), 0))
const totalChargesEnAttente = computed(() =>
  charges.value.filter(c => c.enAttente).reduce((s, c) => s + Number(c.montant), 0))

async function ouvrirCharges(c: Camion) {
  camionCharges.value = c
  Object.assign(formCharge, {
    libelle: '', compteChargeNumero: '', montantUSD: null,
    dateCharge: new Date().toISOString().slice(0, 10),
    tousLesCamions: false,
  })
  erreur.value = ''
  dialogCharges.value = true
  await chargerCharges()
}

// Frais standards du minerais : rejoues automatiquement a chaque reception.
interface ModeleCharge {
  id: number
  libelle: string
  compteChargeNumero: string
  compteChargeLibelle: string
  montant: number
}
const modeles = ref<ModeleCharge[]>([])

async function chargerCharges() {
  if (!camionCharges.value) return
  chargesLoading.value = true
  try {
    const [lignes, std] = await Promise.all([
      api<Charge[]>(`/logistique/minerais/camions/${camionCharges.value.id}/charges`),
      api<ModeleCharge[]>(`/logistique/minerais/camions/modeles?articleId=${camionCharges.value.articleId}`)
        .catch(() => [] as ModeleCharge[]),
    ])
    charges.value = lignes
    modeles.value = std
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les frais.')
  } finally {
    chargesLoading.value = false
  }
}

/** Retire un frais des standards : les lignes deja creees sur les camions restent. */
async function retirerModele(m: ModeleCharge) {
  saving.value = true
  erreur.value = ''
  try {
    await api(`/logistique/minerais/camions/modeles/${m.id}`, { method: 'DELETE' })
    succes.value = `« ${m.libelle} » ne sera plus appliqué automatiquement aux prochaines réceptions.`
    await chargerCharges()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec du retrait.')
  } finally {
    saving.value = false
  }
}

async function ajouterCharge() {
  if (!camionCharges.value) return
  if (!formCharge.libelle.trim() || !formCharge.compteChargeNumero || !formCharge.montantUSD) {
    erreur.value = 'Libellé, compte de charge et montant sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    const creees = await api<Charge[]>(`/logistique/minerais/camions/${camionCharges.value.id}/charges`, {
      method: 'POST',
      body: {
        libelle: formCharge.libelle.trim(),
        compteChargeNumero: formCharge.compteChargeNumero,
        montant: formCharge.montantUSD,
        dateCharge: formCharge.dateCharge,
        appliquerATousLesCamions: formCharge.tousLesCamions,
      },
    })
    const nb = Array.isArray(creees) ? creees.length : 1
    const enAttente = camionCharges.value.statut === 'A_VALIDER'
    Object.assign(formCharge, {
      libelle: '', compteChargeNumero: '', montantUSD: null, tousLesCamions: false,
    })
    succes.value = enAttente
      ? "Frais enregistré, en attente de validation de l'achat par la caisse."
      : nb > 1
        ? `Frais incorporé au coût d'acquisition de ${nb} camions.`
        : 'Frais incorporé au coût d\'acquisition du camion.'
    await Promise.all([chargerCharges(), charger()])
    camionCharges.value = camions.value.find(c => c.id === camionCharges.value?.id) || camionCharges.value
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'ajout du frais.")
  } finally {
    saving.value = false
  }
}

async function supprimerCharge(ch: Charge) {
  saving.value = true
  erreur.value = ''
  try {
    await api(`/logistique/minerais/camions/charges/${ch.id}`, { method: 'DELETE' })
    succes.value = ch.enAttente ? 'Frais retiré.' : 'Frais retiré, écritures extournées.'
    await Promise.all([chargerCharges(), charger()])
    camionCharges.value = camions.value.find(c => c.id === camionCharges.value?.id) || camionCharges.value
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Suppression impossible.')
  } finally {
    saving.value = false
  }
}

// ── Filtres + impression ──────────────────────────────────────────────────
const filtreRecherche = ref('')
const filtreArticle = ref<number | 'TOUS'>('TOUS')
const filtreStatut = ref<'TOUS' | 'EN_STOCK' | 'VENDU'>('TOUS')
const filtreReglement = ref<'TOUS' | 'REGLES' | 'A_REGLER'>('TOUS')

const camionsFiltres = computed(() => {
  const q = filtreRecherche.value.trim().toLowerCase()
  return camions.value.filter((c) => {
    if (q && !c.plaque.toLowerCase().includes(q) && !c.articleLibelle.toLowerCase().includes(q)) return false
    if (filtreArticle.value !== 'TOUS' && c.articleId !== filtreArticle.value) return false
    if (filtreStatut.value !== 'TOUS' && c.statut !== filtreStatut.value) return false
    if (filtreReglement.value === 'REGLES' && !c.regle) return false
    if (filtreReglement.value === 'A_REGLER' && c.regle) return false
    return true
  })
})

const resumeFiltres = computed(() => {
  const parts: string[] = []
  if (filtreArticle.value !== 'TOUS') {
    parts.push(minerais.value.find(m => m.id === filtreArticle.value)?.libelle ?? '')
  }
  if (filtreStatut.value !== 'TOUS') {
    parts.push(filtreStatut.value === 'A_VALIDER' ? 'À valider' : filtreStatut.value === 'EN_STOCK' ? 'En stock' : 'Vendus')
  }
  if (filtreReglement.value !== 'TOUS') parts.push(filtreReglement.value === 'REGLES' ? 'Réglés' : 'À régler')
  if (filtreRecherche.value.trim()) parts.push(`« ${filtreRecherche.value.trim()} »`)
  return parts.filter(Boolean).length ? parts.filter(Boolean).join(' · ') : 'Tous les camions'
})

const aValider = computed(() => camions.value.filter(c => c.statut === 'A_VALIDER').length)
const enStock = computed(() => camions.value.filter(c => c.statut === 'EN_STOCK').length)
// Un camion A_VALIDER n'a encore aucune dette : l'achat n'est pas constate,
// rien ne serait a regler dans les ecritures — memes exclusion que le
// backend (findByRegleFalseAndStatutNotOrderByDateReceptionAscIdAsc).
const aRegler = computed(() => camions.value.filter(c => !c.regle && c.statut !== 'A_VALIDER'))
const detteFournisseur = computed(() => aRegler.value.reduce((s, c) => s + Number(c.prixAchat), 0))

// ── Note de frais de règlement (circuit DFIN/DA/Trésorerie) ───────────────
// N'importe quel camion non réglé et sans note déjà en cours peut être
// sélectionné — y compris un camion encore À valider : c'est justement le
// paiement de cette note, en bout de circuit, qui validera son achat (voir
// NoteFraisService.creerReglementCamionsMinerai côté serveur, qui revalide
// ces mêmes conditions à la création — source de vérité).
function eligiblePourReglement(c: Camion) {
  return !c.regle && !c.noteFraisReglementReference
}
const selectionnes = ref<number[]>([])
const camionsSelectionnes = computed(() => camions.value.filter(c => selectionnes.value.includes(c.id)))
const totalSelectionne = computed(() => camionsSelectionnes.value.reduce((s, c) => s + Number(c.detteFournisseur), 0))
const selectionInclutAValider = computed(() => camionsSelectionnes.value.some(c => c.statut === 'A_VALIDER'))

const dialogNote = ref(false)
const beneficiaireNote = ref('')
const descriptionNote = ref('')
const erreurNote = ref('')
const envoiNote = ref(false)

function ouvrirNoteReglement() {
  if (!selectionnes.value.length) return
  beneficiaireNote.value = ''
  descriptionNote.value = ''
  erreurNote.value = ''
  dialogNote.value = true
}

async function creerNoteReglement() {
  if (!beneficiaireNote.value.trim()) {
    erreurNote.value = 'Le bénéficiaire est obligatoire.'
    return
  }
  envoiNote.value = true
  erreurNote.value = ''
  try {
    const nb = selectionnes.value.length
    await api('/notes-frais/reglement-camions-minerai', {
      method: 'POST',
      body: {
        camionIds: selectionnes.value,
        beneficiaire: beneficiaireNote.value.trim(),
        description: descriptionNote.value.trim() || null,
      },
    })
    dialogNote.value = false
    succes.value = `Note de frais créée pour ${nb} camion${nb > 1 ? 's' : ''} et soumise au DFIN.`
    selectionnes.value = []
    await charger()
  } catch (e: any) {
    erreurNote.value = messageErreurApi(e, 'Échec de la création de la note.')
  } finally {
    envoiNote.value = false
  }
}

const lignesParPage = ref(25)
const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  window.print()
}
onMounted(() => {
  window.addEventListener('beforeprint', () => { lignesParPage.value = -1 })
  window.addEventListener('afterprint', () => { lignesParPage.value = 25 })
})

const fmt = (v: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(v || 0)
const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Camions de minerais</h1>
        <p class="page-sub">
          Réception chargement par chargement — chaque camion se revend à son propre prix
        </p>
      </div>
      <div class="d-flex ga-2">
        <v-btn v-if="camionsFiltres.length" color="error" variant="tonal" rounded="lg"
          prepend-icon="mdi-printer-outline" @click="imprimer">
          Imprimer
        </v-btn>
        <v-btn v-if="canWrite" color="primary" variant="flat" rounded="lg"
          prepend-icon="mdi-dump-truck" :disabled="!minerais.length" @click="ouvrirReception">
          Réceptionner un camion
        </v-btn>
      </div>
    </div>

    <div class="etat-print-header">
      <div class="etat-print-header__brand">
        <div class="etat-print-header__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-finance" size="16" color="white" />
        </div>
        <div>
          <span class="etat-print-header__company">{{ parametresStore.parametres.nom }}</span>
          <span class="etat-print-header__doc">Camions de minerais</span>
          <span class="etat-print-header__service">{{ resumeFiltres }}</span>
        </div>
      </div>
      <div class="etat-print-header__meta"><span>Imprimé le : {{ dateImpression }}</span></div>
    </div>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4 no-print" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" class="mb-4 no-print" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-alert v-if="!loading && !minerais.length" type="info" variant="tonal" class="mb-4 no-print">
      Aucun minerais au catalogue. Créez un article <strong>Marchandise</strong> et activez l'option
      <strong>Minerais</strong> depuis <NuxtLink to="/logistique/articles" class="text-primary">Articles</NuxtLink>.
    </v-alert>

    <v-row v-if="camions.length" class="mb-2 no-print">
      <v-col cols="6" md="3">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">À valider</div>
          <div class="kpi-value" :class="aValider ? 'text-warning' : ''">{{ aValider }}</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="3">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Camions en stock</div>
          <div class="kpi-value">{{ enStock }}</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="3">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">À régler</div>
          <div class="kpi-value">{{ aRegler.length }}</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="3">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Dette fournisseur (4011)</div>
          <div class="kpi-value" :class="detteFournisseur ? 'text-error' : 'text-success'">{{ fmt(detteFournisseur) }}</div>
        </v-card>
      </v-col>
    </v-row>

    <v-card v-if="camions.length" class="classroom-card pa-4 mb-4 no-print">
      <v-row align="center" dense>
        <v-col cols="12" md="4">
          <v-text-field v-model="filtreRecherche" label="Rechercher (plaque, minerais)" variant="outlined"
            density="comfortable" hide-details prepend-inner-icon="mdi-magnify" clearable />
        </v-col>
        <v-col cols="12" md="3">
          <v-select v-model="filtreArticle"
            :items="[{ title: 'Tous les minerais', value: 'TOUS' }, ...minerais.map(m => ({ title: m.libelle, value: m.id }))]"
            label="Minerais" variant="outlined" density="comfortable" hide-details />
        </v-col>
        <v-col cols="6" md="2">
          <v-select v-model="filtreStatut" :items="[
            { title: 'Tous', value: 'TOUS' },
            { title: 'À valider', value: 'A_VALIDER' },
            { title: 'En stock', value: 'EN_STOCK' },
            { title: 'Vendus', value: 'VENDU' },
          ]" label="Statut" variant="outlined" density="comfortable" hide-details />
        </v-col>
        <v-col cols="6" md="3">
          <v-select v-model="filtreReglement" :items="[
            { title: 'Tous règlements', value: 'TOUS' },
            { title: 'Réglés', value: 'REGLES' },
            { title: 'À régler', value: 'A_REGLER' },
          ]" label="Règlement" variant="outlined" density="comfortable" hide-details />
        </v-col>
      </v-row>
    </v-card>

    <v-card v-if="selectionnes.length" class="classroom-card pa-4 mb-4 no-print" color="primary" variant="tonal">
      <div class="d-flex align-center flex-wrap ga-3">
        <div>
          <strong>{{ selectionnes.length }}</strong> camion{{ selectionnes.length > 1 ? 's' : '' }} sélectionné{{ selectionnes.length > 1 ? 's' : '' }}
          — dette totale <strong>{{ fmt(totalSelectionne) }}</strong>
        </div>
        <v-spacer />
        <v-btn variant="text" @click="selectionnes = []">Désélectionner</v-btn>
        <v-btn color="primary" variant="flat" prepend-icon="mdi-file-document-plus-outline" @click="ouvrirNoteReglement">
          Créer une note de frais
        </v-btn>
      </div>
    </v-card>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Date de réception', key: 'dateReception' },
          { title: 'Plaque', key: 'plaque' },
          { title: 'Minerais', key: 'articleLibelle' },
          { title: 'Entrepôt', key: 'entrepotNom' },
          { title: 'Prix d\'achat', key: 'prixAchat', align: 'end' },
          { title: 'Frais connexes', key: 'totalFraisConnexes', align: 'end' },
          { title: 'Coût d\'acquisition', key: 'coutAcquisition', align: 'end' },
          { title: 'Statut', key: 'statut' },
          { title: 'Règlement', key: 'regle' },
          { title: '', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="camionsFiltres"
        :loading="loading"
        :items-per-page="lignesParPage"
        show-select
        v-model="selectionnes"
        item-value="id"
        :item-selectable="eligiblePourReglement"
      >
        <template #item.plaque="{ item }"><span class="font-weight-bold">{{ item.plaque }}</span></template>
        <template #item.articleLibelle="{ item }">
          <div class="minerais-cell">
            <span>{{ item.articleLibelle }}</span>
            <v-btn
              v-if="canWrite"
              class="minerais-cell__dupliquer no-print"
              size="x-small" variant="text" color="primary" icon="mdi-content-copy"
              title="Ajouter un autre camion du même minerais (même prix, mêmes frais)"
              @click="ouvrirDupliquer(item)" />
          </div>
        </template>
        <template #item.dateReception="{ item }">{{ fmtDate(item.dateReception) }}</template>
        <template #item.prixAchat="{ item }">{{ fmt(item.prixAchat) }}</template>
        <template #item.totalFraisConnexes="{ item }">
          <span v-if="!item.totalFraisConnexes" class="text-medium-emphasis">—</span>
          <span v-else>
            {{ fmt(item.totalFraisConnexes) }}
            <span v-if="item.statut === 'A_VALIDER'" class="text-caption text-warning d-block">en attente</span>
          </span>
        </template>
        <template #item.coutAcquisition="{ item }">
          <span :class="item.coutAcquisition > item.prixAchat ? 'font-weight-bold text-primary' : ''">
            {{ fmt(item.coutAcquisition) }}
          </span>
        </template>
        <template #item.statut="{ item }">
          <v-chip
            :color="item.statut === 'A_VALIDER' ? 'warning' : item.statut === 'EN_STOCK' ? 'success' : 'grey'"
            size="small" variant="tonal">
            {{ item.statut === 'A_VALIDER' ? 'À valider' : item.statut === 'EN_STOCK' ? 'En stock' : 'Vendu' }}
          </v-chip>
        </template>
        <template #item.regle="{ item }">
          <!-- Une note en cours prime sur le statut A_VALIDER : c'est
               justement son paiement qui validera l'achat. -->
          <v-chip v-if="!item.regle && item.noteFraisReglementReference" color="info" size="small" variant="tonal"
            :title="`Note ${item.noteFraisReglementReference} en cours de validation`">
            Note en cours
          </v-chip>
          <!-- A_VALIDER sans note : aucune dette n'existe encore, l'achat
               n'etant pas constate — different d'un "a regler" reel. -->
          <span v-else-if="item.statut === 'A_VALIDER'" class="text-medium-emphasis text-caption">—</span>
          <v-chip v-else :color="item.regle ? 'success' : 'warning'" size="small" variant="tonal">
            {{ item.regle ? 'Réglé' : 'À régler' }}
          </v-chip>
        </template>
        <template #item.actions="{ item }">
          <v-btn v-if="canWrite && item.statut !== 'VENDU'" class="no-print"
            size="small" variant="text" color="primary" icon="mdi-cash-plus"
            title="Frais accessoires (transport, pont bascule, péage...)" @click="ouvrirCharges(item)" />
          <v-btn v-if="canWrite && item.statut !== 'VENDU' && !item.regle && !item.noteFraisReglementReference" class="no-print"
            size="small" variant="text" color="error" icon="mdi-delete-outline"
            :title="item.statut === 'A_VALIDER' ? 'Retirer (aucune écriture à extourner)' : 'Supprimer (extourne stock et écritures)'"
            :disabled="saving" @click="supprimer(item)" />
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">
            {{ camions.length ? 'Aucun camion ne correspond aux filtres.' : 'Aucun camion réceptionné.' }}
          </div>
        </template>
      </v-data-table>
    </v-card>

    <!-- ── Réception ────────────────────────────────────────────── -->
    <v-dialog v-model="dialog" max-width="520">
      <v-card class="pa-6">
        <h2 class="text-h6 mb-1">Réceptionner un camion</h2>
        <p class="text-caption text-medium-emphasis mb-4">
          Ceci ne fait qu'enregistrer l'arrivée du camion — aucune écriture, aucun stock.
          Sélectionnez-le ensuite pour créer une note de règlement : son achat (prix, TVA,
          dette fournisseur D 601 / C 4011, entrée en stock D 311 / C 6031) sera constaté
          automatiquement au paiement de cette note par la caisse.
        </p>
        <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>

        <v-select v-model="form.articleId" :items="minerais.map(m => ({ title: `${m.code} — ${m.libelle}`, value: m.id }))"
          label="Minerais *" variant="outlined" density="comfortable" class="mb-3" hide-details />
        <v-select v-model="form.entrepotId" :items="entrepots.map(e => ({ title: e.nom, value: e.id }))"
          label="Entrepôt *" variant="outlined" density="comfortable" class="mb-3" hide-details />
        <v-text-field v-model="form.plaque" label="Plaque du camion *" variant="outlined" density="comfortable"
          class="mb-3" hide-details placeholder="Ex: AB 1234 CD" />
        <v-text-field v-model="form.dateReception" type="date" label="Date de réception *" variant="outlined"
          density="comfortable" class="mb-3" hide-details />
        <v-text-field v-model.number="form.prixAchatUSD" type="number" min="0" step="0.01"
          label="Prix d'achat proposé (USD) *" prepend-inner-icon="mdi-currency-usd"
          variant="outlined" density="comfortable" hide-details
          :hint="form.prixAchatUSD && tauxChange > 0 ? `≈ ${new Intl.NumberFormat('fr-FR').format(Math.round(form.prixAchatUSD * tauxChange))} FC` : ''"
          persistent-hint class="mb-2" />

        <div class="d-flex justify-end ga-3 mt-4">
          <v-btn variant="text" :disabled="saving" @click="dialog = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="saving" @click="receptionner">Réceptionner</v-btn>
        </div>
      </v-card>
    </v-dialog>

    <!-- ── Duplication rapide d'un camion ──────────────────────────── -->
    <v-dialog v-model="dialogDupliquer" max-width="420">
      <v-card class="pa-6">
        <h2 class="text-h6 mb-1">Ajouter un autre camion</h2>
        <p class="text-caption text-medium-emphasis mb-4">
          {{ camionSourceDupliquer?.articleLibelle }} · {{ camionSourceDupliquer?.entrepotNom }} ·
          prix d'achat {{ fmt(camionSourceDupliquer?.prixAchat ?? 0) }}<template v-if="camionSourceDupliquer?.totalFraisConnexes"> ·
          frais connexes {{ fmt(camionSourceDupliquer.totalFraisConnexes) }}</template> — repris à l'identique,
          seule la plaque change.
        </p>
        <v-alert v-if="erreurDupliquer" type="error" variant="tonal" density="compact" class="mb-3">{{ erreurDupliquer }}</v-alert>

        <v-text-field v-model="plaqueDupliquee" label="Plaque du camion *" variant="outlined" density="comfortable"
          hide-details placeholder="Ex: AB 1234 CD" autofocus @keyup.enter="dupliquerCamion" />

        <div class="d-flex justify-end ga-3 mt-4">
          <v-btn variant="text" :disabled="envoiDupliquer" @click="dialogDupliquer = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="envoiDupliquer" @click="dupliquerCamion">Ajouter</v-btn>
        </div>
      </v-card>
    </v-dialog>

    <!-- ── Note de frais de règlement (circuit DFIN/DA/Trésorerie) ──── -->
    <v-dialog v-model="dialogNote" max-width="480">
      <v-card class="pa-6">
        <h2 class="text-h6 mb-1">Créer une note de frais</h2>
        <p class="text-caption text-medium-emphasis mb-4">
          Règlement de <strong>{{ selectionnes.length }}</strong> camion{{ selectionnes.length > 1 ? 's' : '' }}
          — {{ camionsSelectionnes.map(c => c.plaque).join(', ') }} —
          pour un total de <strong>{{ fmt(totalSelectionne) }}</strong>. La note sera soumise
          directement au DFIN, puis suivra le circuit DA / Trésorerie.
          <template v-if="selectionInclutAValider">
            Le paiement validera aussi l'achat des camions pas encore validés.
          </template>
        </p>
        <v-alert v-if="erreurNote" type="error" variant="tonal" density="compact" class="mb-3">{{ erreurNote }}</v-alert>

        <v-text-field v-model="beneficiaireNote" label="Bénéficiaire (fournisseur à payer) *" variant="outlined"
          density="comfortable" class="mb-3" hide-details autofocus />
        <v-textarea v-model="descriptionNote" label="Description (facultatif)" variant="outlined"
          density="comfortable" rows="2" hide-details />

        <div class="d-flex justify-end ga-3 mt-4">
          <v-btn variant="text" :disabled="envoiNote" @click="dialogNote = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="envoiNote" @click="creerNoteReglement">
            Créer et soumettre
          </v-btn>
        </div>
      </v-card>
    </v-dialog>

    <!-- ── Frais accessoires d'achat ─────────────────────────────── -->
    <v-dialog v-model="dialogCharges" max-width="760" scrollable>
      <v-card class="pa-6">
        <h2 class="text-h6 mb-1">
          Frais accessoires — camion {{ camionCharges?.plaque }}
        </h2>
        <p class="text-caption text-medium-emphasis mb-4">
          Transport, pont bascule, péage, documents de chargement… Chaque frais est constaté par
          nature (D 6x / C 4011) puis incorporé au stock (D 311 / C 6031) : il entre dans le coût
          d'acquisition du camion, et non dans les charges de la période.
        </p>
        <v-alert v-if="camionCharges?.statut === 'A_VALIDER'" type="info" variant="tonal" density="compact" class="mb-4">
          Ce camion n'est pas encore validé : les frais saisis ici restent
          <strong>en attente</strong> et ne seront incorporés au stock qu'à la validation de l'achat,
          au paiement de sa note de règlement.
        </v-alert>

        <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>

        <v-row class="mb-1" dense>
          <v-col cols="6" md="4">
            <div class="kpi-label">Prix d'achat</div>
            <div class="kpi-value">{{ fmt(camionCharges?.prixAchat ?? 0) }}</div>
          </v-col>
          <v-col cols="6" md="4">
            <div class="kpi-label">Frais saisis</div>
            <div class="kpi-value">{{ fmt(totalCharges) }}</div>
            <div v-if="totalChargesEnAttente" class="text-caption text-warning">
              dont {{ fmt(totalChargesEnAttente) }} en attente
            </div>
          </v-col>
          <v-col cols="12" md="4">
            <div class="kpi-label">Coût d'acquisition</div>
            <div class="kpi-value text-primary">{{ fmt(camionCharges?.coutAcquisition ?? 0) }}</div>
            <div v-if="totalChargesEnAttente" class="text-caption text-warning">
              {{ fmt((camionCharges?.coutAcquisition ?? 0) + totalChargesEnAttente) }} après validation
            </div>
          </v-col>
        </v-row>

        <v-divider class="my-4" />

        <v-row dense align="center">
          <v-col cols="12" md="4">
            <v-combobox v-model="formCharge.libelle" :items="LIBELLES_COURANTS" label="Nature du frais *"
              variant="outlined" density="comfortable" hide-details
              @update:model-value="onNatureChoisie" />
          </v-col>
          <v-col cols="12" md="4">
            <ComptabiliteSelecteurCompte v-model="formCharge.compteChargeNumero"
              label="Compte de charge *" />
          </v-col>
          <v-col cols="6" md="2">
            <v-text-field v-model.number="formCharge.montantUSD" type="number" min="0" step="0.01"
              label="Montant (USD) *" variant="outlined" density="comfortable" hide-details />
          </v-col>
          <v-col cols="6" md="2">
            <v-btn color="primary" variant="flat" block :loading="saving"
              :disabled="camionCharges?.statut === 'VENDU'" @click="ajouterCharge">
              Ajouter
            </v-btn>
          </v-col>
          <v-col cols="12">
            <!-- Beaucoup de frais (pont bascule, autorisation) sont identiques
                 sur tout un arrivage : les saisir une fois plutot que camion
                 par camion. Chaque camion recoit malgre tout sa propre ligne,
                 modifiable ou supprimable individuellement ensuite. -->
            <v-checkbox v-model="formCharge.tousLesCamions" color="primary" density="compact" hide-details
              :disabled="camionCharges?.statut === 'VENDU'">
              <template #label>
                <span class="text-body-2">
                  Frais standard de « {{ camionCharges?.articleLibelle }} » :
                  l'appliquer à <strong>tous les camions en stock</strong>
                  <strong>et à chaque future réception</strong>
                  <span class="text-medium-emphasis">
                    — une ligne par camion, modifiable individuellement ensuite
                  </span>
                </span>
              </template>
            </v-checkbox>
          </v-col>

          <!-- Frais standards deja enregistres : visibles et retirables, sinon
               ils s'appliqueraient indefiniment sans moyen de les arreter. -->
          <v-col v-if="modeles.length" cols="12">
            <div class="modeles-bloc">
              <div class="modeles-titre">
                <v-icon icon="mdi-autorenew" size="15" class="mr-1" />
                Frais standards de « {{ camionCharges?.articleLibelle }} » —
                réappliqués à chaque réception
              </div>
              <div class="modeles-liste">
                <v-chip v-for="m in modeles" :key="m.id" size="small" variant="tonal" color="primary"
                  closable :disabled="saving" @click:close="retirerModele(m)">
                  {{ m.libelle }} · {{ fmt(m.montant) }}
                </v-chip>
              </div>
            </div>
          </v-col>
        </v-row>

        <v-table density="compact" class="mt-4">
          <thead>
            <tr>
              <th>Date</th><th>Nature</th><th>Compte</th>
              <th class="text-right">Montant</th><th>Règlement</th><th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ch in charges" :key="ch.id">
              <td>{{ fmtDate(ch.dateCharge) }}</td>
              <td>{{ ch.libelle }}</td>
              <td><code class="text-caption">{{ ch.compteChargeNumero }}</code> — {{ ch.compteChargeLibelle }}</td>
              <td class="text-right">{{ fmt(ch.montant) }}</td>
              <td>
                <v-chip v-if="ch.enAttente" color="info" size="x-small" variant="tonal">
                  En attente
                </v-chip>
                <v-chip v-else :color="ch.regle ? 'success' : 'warning'" size="x-small" variant="tonal">
                  {{ ch.regle ? 'Réglé' : 'À régler' }}
                </v-chip>
              </td>
              <td class="text-right">
                <v-btn v-if="canWrite && !ch.regle" size="small" variant="text" color="error"
                  icon="mdi-delete-outline" :disabled="saving" @click="supprimerCharge(ch)" />
              </td>
            </tr>
            <tr v-if="!charges.length && !chargesLoading">
              <td colspan="6" class="text-center text-medium-emphasis pa-4">
                Aucun frais accessoire. Le coût d'acquisition se limite au prix d'achat.
              </td>
            </tr>
          </tbody>
        </v-table>

        <div class="d-flex justify-end mt-4">
          <v-btn variant="text" @click="dialogCharges = false">Fermer</v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
/* Frais standards du minerais, rejoues a chaque reception. */
.modeles-bloc { background: #f0f9ff; border: 1px solid #bae6fd; border-radius: 10px; padding: 10px 12px; }
.modeles-titre { font-size: 0.72rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.4px; color: #0369a1; margin-bottom: 8px; }
.modeles-liste { display: flex; flex-wrap: wrap; gap: 6px; }

/* Bouton "dupliquer" du camion : discret, révélé au survol de la cellule. */
.minerais-cell { display: flex; align-items: center; gap: 2px; }
.minerais-cell__dupliquer { opacity: 0; transition: opacity 0.15s; }
.minerais-cell:hover .minerais-cell__dupliquer, .minerais-cell__dupliquer:focus-visible { opacity: 1; }

.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.kpi-label { font-size: 0.72rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.25rem; font-weight: 700; color: #111827; margin-top: 4px; }
</style>
