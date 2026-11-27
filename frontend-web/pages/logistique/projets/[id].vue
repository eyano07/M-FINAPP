<script setup lang="ts">
/**
 * Un projet (27/11/2026) : liste de besoins (envoyée au comptable), fiche de stock projet (entrées par les achats payés
 * sur ses comptes « Projet … », sorties, transferts, ajustements — aucune pièce comptable), acquisitions affectées et
 * dépenses en argent. Impression de la fiche et export Excel.
 */
definePageMeta({ module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN'] })

interface Projet {
  id: number, code: string, nom: string, description: string | null, statut: string, motifRetour: string | null,
  budgetId: number | null, budgetReference: string | null, budgetStatut: string | null, nombreBesoins: number,
  prevuUsd: number, payeUsd: number
}
interface Article {
  id: number, designation: string, unite: string | null, quantitePrevue: number, prixEstimeUsd: number, nature: 'ACHAT' | 'ACQUISITION',
  compteNumero: string | null, compteLibelle: string | null, entrees: number, sorties: number, solde: number, valeurUsd: number, payeUsd: number
}
interface Mouvement {
  id: number, designation: string, date: string, type: string, quantite: number, montantUsd: number, noteId: number | null,
  noteReference: string | null, projetLie: string | null, commentaire: string | null, auteur: string | null
}
interface Acquisition { id: number, reference: string, libelle: string, compte: string | null, dateAcquisition: string | null, valeurUsd: number, statut: string }
interface NoteLiee { id: number, reference: string, objet: string, statut: string, montantUsd: number }
interface Depenses { prevuBudget: number | null, prevuBesoins: number, engage: number, paye: number, reste: number, notes: NoteLiee[] }
interface Detail { projet: Projet, articles: Article[], mouvements: Mouvement[], acquisitions: Acquisition[], depenses: Depenses }

const route = useRoute()
const id = computed(() => Number(route.params.id))
const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()
const peutEcrire = computed(() => auth.hasRole('LOGISTIQUE'))
const detail = ref<Detail | null>(null)
const tous = ref<Projet[]>([])
const onglet = ref('stock')
const erreur = ref('')
const succes = ref('')
const occupe = ref(false)

useHead({ title: computed(() => (detail.value ? `Projet ${detail.value.projet.code} — ${detail.value.projet.nom}` : 'Projet')) })

const modifiable = computed(() => ['BROUILLON', 'RETOURNE'].includes(detail.value?.projet.statut || ''))

async function charger() {
  erreur.value = ''
  try {
    const [d, l] = await Promise.all([api<Detail>(`/logistique/projets/${id.value}`), api<Projet[]>('/logistique/projets')])
    detail.value = d
    tous.value = l
    besoins.value = d.articles.map(a => ({ designation: a.designation, unite: a.unite || '', quantite: Number(a.quantitePrevue),
      prixEstimeUsd: Number(a.prixEstimeUsd), nature: a.nature }))
    if (!besoins.value.length && modifiable.value) { onglet.value = 'besoins'; ajouterBesoin() }
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Projet introuvable.')
  }
}
onMounted(() => { charger(); parametresStore.charger() })

// ── Besoins ──────────────────────────────────────────────────────────────
interface Besoin { designation: string, unite: string, quantite: number | null, prixEstimeUsd: number | null, nature: 'ACHAT' | 'ACQUISITION' }
const besoins = ref<Besoin[]>([])
function ajouterBesoin() { besoins.value.push({ designation: '', unite: '', quantite: 1, prixEstimeUsd: 0, nature: 'ACHAT' }) }
const totalBesoins = computed(() => besoins.value.reduce((t, b) => t + (Number(b.quantite) || 0) * (Number(b.prixEstimeUsd) || 0), 0))
const besoinsValides = computed(() => besoins.value.every(b => b.designation.trim() && Number(b.quantite) > 0 && Number(b.prixEstimeUsd) >= 0))

async function enregistrerBesoins(puisEnvoyer = false) {
  occupe.value = true
  erreur.value = ''
  try {
    await api(`/logistique/projets/${id.value}/besoins`, { method: 'PUT', body: { besoins: besoins.value.map(b => ({
      designation: b.designation.trim(), unite: b.unite.trim() || null, quantite: Number(b.quantite), prixEstimeUsd: Number(b.prixEstimeUsd), nature: b.nature })) } })
    if (puisEnvoyer) {
      await api(`/logistique/projets/${id.value}/envoyer`, { method: 'POST' })
      succes.value = 'Projet envoyé au comptable, qui va créer son budget.'
    } else {
      succes.value = 'Besoins enregistrés.'
    }
    await charger()
  } catch (e) {
    erreur.value = messageErreurApi(e, "Les besoins n'ont pas été enregistrés.")
  } finally {
    occupe.value = false
  }
}

// ── Mouvements de stock ──────────────────────────────────────────────────
const mv = reactive({ ouvert: false, type: 'SORTIE' as 'SORTIE' | 'AJUSTEMENT' | 'TRANSFERT', article: null as Article | null,
  quantite: null as number | null, projetCibleId: null as number | null, commentaire: '', enCours: false })
function ouvrirMouvement(a: Article, type: 'SORTIE' | 'AJUSTEMENT' | 'TRANSFERT') {
  Object.assign(mv, { ouvert: true, type, article: a, quantite: null, projetCibleId: null, commentaire: '' })
}
const autresProjets = computed(() => tous.value.filter(p => p.id !== id.value && p.statut !== 'CLOTURE'))
async function enregistrerMouvement() {
  if (!mv.article) return
  mv.enCours = true
  try {
    detail.value = await api<Detail>(`/logistique/projets/${id.value}/mouvements`, { method: 'POST', body: {
      articleId: mv.article.id, type: mv.type, quantite: Number(mv.quantite), projetCibleId: mv.projetCibleId, commentaire: mv.commentaire || null } })
    mv.ouvert = false
    succes.value = 'Mouvement enregistré.'
  } catch (e) {
    erreur.value = messageErreurApi(e, "Le mouvement n'a pas été enregistré.")
    mv.ouvert = false
  } finally {
    mv.enCours = false
  }
}

// ── Acquisitions ─────────────────────────────────────────────────────────
const acq = reactive({ ouvert: false, liste: [] as Acquisition[], choix: null as number | null, enCours: false })
async function ouvrirAffectation() {
  acq.ouvert = true
  acq.choix = null
  acq.liste = await api<Acquisition[]>('/logistique/projets/acquisitions-affectables').catch(() => [])
}
async function affecterAcquisition(immoId: number, projetId: number | null) {
  acq.enCours = true
  try {
    await api(`/logistique/projets/acquisitions/${immoId}/affectation`, { method: 'POST', body: { projetId } })
    acq.ouvert = false
    await charger()
  } catch (e) {
    erreur.value = messageErreurApi(e, "L'affectation a échoué.")
  } finally {
    acq.enCours = false
  }
}

// ── Actions ──────────────────────────────────────────────────────────────
const dialogues = useDialogues()
async function cloturer() {
  if (!(await dialogues.confirmer({ titre: 'Clôturer le projet ?', message: 'Sa fiche de stock reste consultable ; plus aucun transfert ne pourra le viser.', bouton: 'Clôturer' }))) return
  await api(`/logistique/projets/${id.value}/cloturer`, { method: 'POST' }).catch(e => { erreur.value = messageErreurApi(e, 'Clôture impossible.') })
  await charger()
}
async function supprimer() {
  if (!(await dialogues.confirmer({ titre: 'Supprimer ce projet ?', message: 'Le projet et sa liste de besoins seront supprimés.', bouton: 'Supprimer', nature: 'danger' }))) return
  try {
    await api(`/logistique/projets/${id.value}`, { method: 'DELETE' })
    await navigateTo('/logistique/projets')
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Suppression impossible.')
  }
}
const exportEnCours = ref(false)
async function exporter() {
  exportEnCours.value = true
  try {
    await telechargerFichier(api, `/logistique/projets/${id.value}/export`, `Projet_${detail.value?.projet.code}_${new Date().toISOString().slice(0, 10)}.xlsx`)
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Export impossible.')
  } finally {
    exportEnCours.value = false
  }
}
const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  nextTick(() => window.print())
}

const STATUTS: Record<string, { libelle: string, couleur: string }> = {
  BROUILLON: { libelle: 'Brouillon', couleur: 'grey' }, ENVOYE: { libelle: 'Envoyé au comptable', couleur: 'blue' },
  RETOURNE: { libelle: 'Retourné', couleur: 'warning' }, BUDGETE: { libelle: 'Budgété', couleur: 'success' },
  CLOTURE: { libelle: 'Clôturé', couleur: 'deep-purple' },
}
const TYPES: Record<string, string> = {
  ACHAT: 'Achat payé', SORTIE: 'Sortie', TRANSFERT_SORTIE: 'Transfert sortant', TRANSFERT_ENTREE: 'Transfert entrant',
  RETOUR_FONDS: 'Retour de fonds', AJUSTEMENT: 'Ajustement',
}
const fmt = (v: number | null | undefined) => new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(v || 0))
const fmtQ = (v: number | null | undefined) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 3 }).format(Number(v || 0))
const fmtDate = (d: string | null) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
const totalValeur = computed(() => (detail.value?.articles || []).reduce((t, a) => t + Number(a.valeurUsd), 0))
</script>

<template>
  <div v-if="detail" class="projet">
    <div class="page-head no-print">
      <div style="min-width: 0">
        <h1 class="page-title">{{ detail.projet.nom }}
          <v-chip size="small" variant="tonal" :color="STATUTS[detail.projet.statut]?.color || STATUTS[detail.projet.statut]?.couleur" class="ml-2 align-middle">
            {{ STATUTS[detail.projet.statut]?.libelle }}</v-chip>
        </h1>
        <p class="page-sub">{{ detail.projet.code }}<template v-if="detail.projet.budgetReference"> · budget
          <NuxtLink v-if="auth.hasAnyRole(['COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN', 'CAISSIER'])" :to="`/budgets/${detail.projet.budgetId}`">{{ detail.projet.budgetReference }}</NuxtLink>
          <template v-else>{{ detail.projet.budgetReference }}</template></template>
          <template v-if="detail.projet.description"> · {{ detail.projet.description }}</template></p>
      </div>
      <div class="d-flex flex-wrap ga-2 justify-end">
        <v-btn variant="tonal" rounded="lg" prepend-icon="mdi-printer-outline" @click="imprimer">Imprimer</v-btn>
        <v-btn color="success" variant="tonal" rounded="lg" prepend-icon="mdi-file-excel-outline" :loading="exportEnCours" @click="exporter">Excel</v-btn>
        <v-btn v-if="peutEcrire && detail.projet.statut === 'BUDGETE'" variant="tonal" rounded="lg" prepend-icon="mdi-archive-outline" @click="cloturer">Clôturer</v-btn>
        <v-btn v-if="peutEcrire && modifiable" variant="text" color="error" rounded="lg" prepend-icon="mdi-delete-outline" @click="supprimer">Supprimer</v-btn>
        <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/logistique/projets">Projets</v-btn>
      </div>
    </div>

    <!-- En-tête d'impression -->
    <div class="etat-print-header print-entete">
      <div class="etat-print-header__brand print-entete__marque">
        <div>
          <span class="etat-print-header__company print-entete__nom">{{ parametresStore.parametres.nom }}</span>
          <span class="etat-print-header__doc">Fiche de stock projet — {{ detail.projet.code }} {{ detail.projet.nom }}</span>
        </div>
      </div>
      <div class="etat-print-header__meta print-entete__meta">
        <span>Budget : <strong>{{ detail.projet.budgetReference || '—' }}</strong></span>
        <span>Imprimé le : {{ dateImpression }}</span>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4 no-print" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="detail.projet.statut === 'RETOURNE' && detail.projet.motifRetour" type="warning" variant="tonal" class="mb-4">
      Retourné par le comptable : {{ detail.projet.motifRetour }}
    </v-alert>

    <!-- Dépenses en un coup d'œil -->
    <div class="kpis mb-4">
      <div class="kpi"><span>Prévu</span><strong>{{ fmt(detail.depenses.prevuBudget ?? detail.depenses.prevuBesoins) }}</strong>
        <small>{{ detail.depenses.prevuBudget != null ? 'au budget' : 'besoins estimés' }}</small></div>
      <div class="kpi"><span>Engagé</span><strong>{{ fmt(detail.depenses.engage) }}</strong><small>notes validées non payées</small></div>
      <div class="kpi"><span>Payé</span><strong>{{ fmt(detail.depenses.paye) }}</strong><small>achats entrés au stock</small></div>
      <div class="kpi" :class="{ 'kpi--negatif': detail.depenses.reste < 0 }"><span>Reste</span><strong>{{ fmt(detail.depenses.reste) }}</strong><small>USD</small></div>
      <div class="kpi"><span>Valeur en stock</span><strong>{{ fmt(totalValeur) }}</strong><small>USD</small></div>
    </div>

    <v-tabs v-model="onglet" color="primary" class="mb-3 no-print">
      <v-tab value="besoins" prepend-icon="mdi-format-list-checks">Besoins</v-tab>
      <v-tab value="stock" prepend-icon="mdi-package-variant">Fiche de stock</v-tab>
      <v-tab value="acquisitions" prepend-icon="mdi-office-building-cog-outline">Acquisitions</v-tab>
      <v-tab value="depenses" prepend-icon="mdi-cash-multiple">Dépenses</v-tab>
    </v-tabs>

    <!-- Besoins -->
    <v-card v-show="onglet === 'besoins'" class="classroom-card mb-4 bloc">
      <div class="pa-4 pb-2 bloc-titre">Liste des besoins</div>
      <v-table density="compact">
        <thead><tr><th>Désignation</th><th>Nature</th><th>Unité</th><th class="text-end">Quantité</th><th class="text-end">Prix estimé (USD)</th><th class="text-end">Total</th><th v-if="modifiable && peutEcrire" /></tr></thead>
        <tbody v-if="modifiable && peutEcrire">
          <tr v-for="(b, i) in besoins" :key="i">
            <td><v-text-field v-model="b.designation" density="compact" variant="outlined" hide-details placeholder="ex. Ciment 50 kg" /></td>
            <td style="width: 170px"><v-select v-model="b.nature" :items="[{ title: 'Achat divers', value: 'ACHAT' }, { title: 'Acquisition', value: 'ACQUISITION' }]" density="compact" variant="outlined" hide-details /></td>
            <td style="width: 110px"><v-text-field v-model="b.unite" density="compact" variant="outlined" hide-details placeholder="sac" /></td>
            <td style="width: 110px"><v-text-field v-model.number="b.quantite" type="number" density="compact" variant="outlined" hide-details /></td>
            <td style="width: 140px"><v-text-field v-model.number="b.prixEstimeUsd" type="number" density="compact" variant="outlined" hide-details /></td>
            <td class="text-end text-no-wrap">{{ fmt((Number(b.quantite) || 0) * (Number(b.prixEstimeUsd) || 0)) }}</td>
            <td><v-btn icon="mdi-delete-outline" size="small" variant="text" color="error" @click="besoins.splice(i, 1)" /></td>
          </tr>
        </tbody>
        <tbody v-else>
          <tr v-for="a in detail.articles" :key="a.id">
            <td>{{ a.designation }}<div v-if="a.compteNumero" class="text-caption text-medium-emphasis">{{ a.compteNumero }} · {{ a.compteLibelle }}</div></td>
            <td>{{ a.nature === 'ACHAT' ? 'Achat divers' : 'Acquisition' }}</td>
            <td>{{ a.unite || '—' }}</td>
            <td class="text-end">{{ fmtQ(a.quantitePrevue) }}</td>
            <td class="text-end">{{ fmt(a.prixEstimeUsd) }}</td>
            <td class="text-end">{{ fmt(a.quantitePrevue * a.prixEstimeUsd) }}</td>
          </tr>
        </tbody>
        <tfoot><tr class="font-weight-bold"><td colspan="5">Total estimé</td><td class="text-end">{{ fmt(modifiable ? totalBesoins : detail.projet.prevuUsd) }}</td><td v-if="modifiable && peutEcrire" /></tr></tfoot>
      </v-table>
      <div v-if="modifiable && peutEcrire" class="pa-4 d-flex flex-wrap ga-2 no-print">
        <v-btn variant="tonal" prepend-icon="mdi-plus" @click="ajouterBesoin">Ajouter un besoin</v-btn>
        <v-spacer />
        <v-btn variant="outlined" :loading="occupe" :disabled="!besoinsValides" prepend-icon="mdi-content-save-outline" @click="enregistrerBesoins(false)">Enregistrer</v-btn>
        <v-btn color="primary" :loading="occupe" :disabled="!besoinsValides || !besoins.length" prepend-icon="mdi-send" @click="enregistrerBesoins(true)">Envoyer au comptable</v-btn>
      </div>
      <p v-else-if="detail.projet.statut === 'ENVOYE'" class="pa-4 mb-0 text-medium-emphasis">En attente du comptable, qui crée le budget du projet.</p>
    </v-card>

    <!-- Fiche de stock -->
    <v-card v-show="onglet === 'stock'" class="classroom-card mb-4 bloc bloc--imprime">
      <div class="pa-4 pb-2 bloc-titre">Fiche de stock projet</div>
      <p class="px-4 text-caption text-medium-emphasis">Entrées : achats payés sur les comptes « Projet … » du projet. Séparée du stock global : aucune variation de stock, aucune écriture.</p>
      <v-table density="compact">
        <thead><tr><th>Désignation</th><th>Compte</th><th class="text-end">Prévu</th><th class="text-end">Entrées</th><th class="text-end">Sorties</th><th class="text-end">Solde</th><th class="text-end">Valeur (USD)</th><th v-if="peutEcrire" class="no-print" /></tr></thead>
        <tbody>
          <tr v-if="!detail.articles.length"><td colspan="8" class="text-center text-medium-emphasis py-4">Aucun article : les besoins deviennent des articles quand le budget est créé.</td></tr>
          <tr v-for="a in detail.articles" :key="a.id">
            <td>{{ a.designation }}<span v-if="a.unite" class="text-caption text-medium-emphasis"> ({{ a.unite }})</span>
              <v-chip v-if="a.nature === 'ACQUISITION'" size="x-small" variant="tonal" color="indigo" class="ml-1">Acquisition</v-chip></td>
            <td class="text-caption">{{ a.compteNumero || '—' }}</td>
            <td class="text-end">{{ fmtQ(a.quantitePrevue) }}</td>
            <td class="text-end">{{ fmtQ(a.entrees) }}</td>
            <td class="text-end">{{ fmtQ(a.sorties) }}</td>
            <td class="text-end font-weight-bold">{{ fmtQ(a.solde) }}</td>
            <td class="text-end">{{ fmt(a.valeurUsd) }}</td>
            <td v-if="peutEcrire" class="no-print text-no-wrap">
              <v-btn size="small" variant="text" :disabled="a.solde <= 0" @click="ouvrirMouvement(a, 'SORTIE')">Sortie</v-btn>
              <v-btn size="small" variant="text" :disabled="a.solde <= 0" @click="ouvrirMouvement(a, 'TRANSFERT')">Transfert</v-btn>
              <v-btn size="small" variant="text" @click="ouvrirMouvement(a, 'AJUSTEMENT')">Ajuster</v-btn>
            </td>
          </tr>
        </tbody>
        <tfoot><tr class="font-weight-bold"><td colspan="6">Valeur totale</td><td class="text-end">{{ fmt(totalValeur) }}</td><td v-if="peutEcrire" class="no-print" /></tr></tfoot>
      </v-table>
      <div class="pa-4 pb-2 bloc-titre">Mouvements</div>
      <v-table density="compact">
        <thead><tr><th>Date</th><th>Article</th><th>Mouvement</th><th class="text-end">Quantité</th><th class="text-end">Montant (USD)</th><th>Pièce / projet</th><th>Commentaire</th></tr></thead>
        <tbody>
          <tr v-if="!detail.mouvements.length"><td colspan="7" class="text-center text-medium-emphasis py-4">Aucun mouvement.</td></tr>
          <tr v-for="m in detail.mouvements" :key="m.id">
            <td class="text-no-wrap">{{ fmtDate(m.date) }}</td>
            <td>{{ m.designation }}</td>
            <td>{{ TYPES[m.type] || m.type }}</td>
            <td class="text-end" :class="m.quantite < 0 ? 'text-error' : 'text-success'">{{ m.quantite > 0 ? '+' : '' }}{{ fmtQ(m.quantite) }}</td>
            <td class="text-end">{{ fmt(m.montantUsd) }}</td>
            <td class="text-caption"><NuxtLink v-if="m.noteId" :to="`/notes-frais/${m.noteId}`">{{ m.noteReference }}</NuxtLink><template v-else>{{ m.projetLie || '—' }}</template></td>
            <td class="text-caption">{{ m.commentaire }}</td>
          </tr>
        </tbody>
      </v-table>
    </v-card>

    <!-- Acquisitions -->
    <v-card v-show="onglet === 'acquisitions'" class="classroom-card mb-4 bloc">
      <div class="pa-4 pb-2 d-flex align-center">
        <span class="bloc-titre">Acquisitions affectées au projet</span><v-spacer />
        <v-btn v-if="peutEcrire" size="small" variant="tonal" prepend-icon="mdi-link-variant-plus" class="no-print" @click="ouvrirAffectation">Affecter une acquisition</v-btn>
      </div>
      <v-table density="compact">
        <thead><tr><th>Référence</th><th>Bien</th><th>Compte</th><th>Acquis le</th><th class="text-end">Valeur (USD)</th><th v-if="peutEcrire" class="no-print" /></tr></thead>
        <tbody>
          <tr v-if="!detail.acquisitions.length"><td colspan="6" class="text-center text-medium-emphasis py-4">Aucune acquisition affectée.</td></tr>
          <tr v-for="a in detail.acquisitions" :key="a.id">
            <td>{{ a.reference }}</td><td>{{ a.libelle }}</td><td class="text-caption">{{ a.compte }}</td>
            <td>{{ fmtDate(a.dateAcquisition) }}</td><td class="text-end">{{ fmt(a.valeurUsd) }}</td>
            <td v-if="peutEcrire" class="no-print"><v-btn size="small" variant="text" color="error" @click="affecterAcquisition(a.id, null)">Retirer</v-btn></td>
          </tr>
        </tbody>
      </v-table>
    </v-card>

    <!-- Dépenses -->
    <v-card v-show="onglet === 'depenses'" class="classroom-card mb-4 bloc">
      <div class="pa-4 pb-2 bloc-titre">Notes de frais du projet</div>
      <v-table density="compact">
        <thead><tr><th>Note</th><th>Objet</th><th>Statut</th><th class="text-end">Montant (USD)</th></tr></thead>
        <tbody>
          <tr v-if="!detail.depenses.notes.length"><td colspan="4" class="text-center text-medium-emphasis py-4">Aucune note sur les comptes du projet.</td></tr>
          <tr v-for="n in detail.depenses.notes" :key="n.id">
            <td><NuxtLink :to="`/notes-frais/${n.id}`">{{ n.reference }}</NuxtLink></td><td>{{ n.objet }}</td>
            <td class="text-caption">{{ n.statut }}</td><td class="text-end">{{ fmt(n.montantUsd) }}</td>
          </tr>
        </tbody>
      </v-table>
    </v-card>

    <!-- Mouvement -->
    <v-dialog v-model="mv.ouvert" max-width="480">
      <v-card v-if="mv.article" rounded="xl">
        <v-card-title class="pt-4 px-5">{{ mv.type === 'SORTIE' ? 'Sortie' : mv.type === 'TRANSFERT' ? 'Transfert vers un autre projet' : 'Ajustement' }} — {{ mv.article.designation }}</v-card-title>
        <v-card-text class="px-5">
          <p class="text-body-2 mb-3">En stock : <strong>{{ fmtQ(mv.article.solde) }} {{ mv.article.unite || '' }}</strong></p>
          <v-text-field v-model.number="mv.quantite" type="number" :label="mv.type === 'AJUSTEMENT' ? 'Quantité (+ ou −)' : 'Quantité *'" variant="outlined" density="comfortable" class="mb-3" />
          <v-select v-if="mv.type === 'TRANSFERT'" v-model="mv.projetCibleId" :items="autresProjets" item-value="id" :item-title="(p: Projet) => `${p.code} — ${p.nom}`" label="Projet qui reçoit *" variant="outlined" density="comfortable" class="mb-3" />
          <v-textarea v-model="mv.commentaire" label="Commentaire" rows="2" variant="outlined" density="comfortable" />
        </v-card-text>
        <v-card-actions class="px-5 pb-4">
          <v-spacer /><v-btn variant="text" @click="mv.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="mv.enCours" :disabled="!mv.quantite || (mv.type === 'TRANSFERT' && !mv.projetCibleId)" @click="enregistrerMouvement">Enregistrer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Affecter une acquisition -->
    <v-dialog v-model="acq.ouvert" max-width="560">
      <v-card rounded="xl">
        <v-card-title class="pt-4 px-5">Affecter une acquisition au projet</v-card-title>
        <v-card-text class="px-5">
          <v-autocomplete v-model="acq.choix" :items="acq.liste" item-value="id" :item-title="(a: Acquisition) => `${a.reference} — ${a.libelle} (${fmt(a.valeurUsd)} USD)`"
            label="Immobilisation sans projet" variant="outlined" density="comfortable" no-data-text="Aucune acquisition libre" />
        </v-card-text>
        <v-card-actions class="px-5 pb-4">
          <v-spacer /><v-btn variant="text" @click="acq.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :disabled="!acq.choix" :loading="acq.enCours" @click="affecterAcquisition(acq.choix!, id)">Affecter</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
  <v-skeleton-loader v-else-if="!erreur" type="heading, card, table" />
  <v-alert v-else type="error" variant="tonal">{{ erreur }}</v-alert>
</template>

<style scoped>
.kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }
.kpi { display: flex; flex-direction: column; padding: 12px 14px; border-radius: 14px; background: rgba(var(--v-theme-surface), .7); border: 1px solid rgba(var(--v-border-color), .12); }
.kpi span { font-size: 12px; opacity: .7; }
.kpi strong { font-size: 18px; }
.kpi small { font-size: 11px; opacity: .6; }
.kpi--negatif strong { color: rgb(var(--v-theme-error)); }
.bloc-titre { font-weight: 700; }
@media print {
  .bloc { display: none !important; }
  .bloc--imprime { display: block !important; }
}
</style>
