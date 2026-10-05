<script setup lang="ts">
// Voir pages/notes-frais/index.vue : ni le DRH ni LOGISTIQUE n'ont acces a cet ecran.
definePageMeta({ roles: ['ADMIN', 'DG', 'DA', 'DFIN', 'DIRECTEUR', 'CAISSIER', 'COMPTABLE', 'GEST_PATRIMOINE', 'RESP_RESTAURANT', 'LOGISTIQUE'] })

interface Observation {
  auteurNom?: string
  /** Fonction inscrite sur la fiche de l'auteur ; absente si elle n'est pas renseignée. */
  auteurFonction?: string | null
  auteur?: string
  statut?: string
  statutAuMoment?: string
  commentaire: string
  date?: string
  dateAction?: string
}
interface LigneNoteFrais {
  id: number
  montant: number
  montantHtTotal?: number | null
  montantTtc?: number | null
  compteImputation?: string | null
  compteImputationLibelle?: string | null
  description?: string | null
  achatMarchandise?: boolean
  quantiteMarchandise?: number | null
  articleCode?: string | null
  articleLibelle?: string | null
  entrepotNom?: string | null
  soumisTva?: boolean
  compteTva?: string | null
  compteTvaLibelle?: string | null
  /** Transport et manutention, incorporés au coût des marchandises achetées. */
  fraisApproche?: boolean
}
interface PieceJointe {
  id: number
  nomFichier: string
  typeMime?: string | null
  taille?: number | null
  ajoutePar?: string | null
  dateAjout?: string | null
}
interface NoteDetail {
  id: number
  reference: string
  objet: string
  beneficiaire?: string
  montant: number
  devise?: string
  statut: string
  sens?: string | null
  priorite?: string | null
  description?: string
  demandeurNom?: string
  demandeurFonction?: string | null
  demandeurEmail?: string
  /** Contrôle budgétaire relevé à la soumission, justification fournie par le créateur, budget de référence. */
  statutBudget?: string | null
  justificationBudget?: string | null
  budgetReference?: string | null
  /** Notes du module DRH : PAIE ou IMPOT_PAIE (comptes imposés), avec leur mois de paie. */
  categorie?: string | null
  paieMois?: number | null
  paieAnnee?: number | null
  organismePaie?: string | null
  lignes: LigneNoteFrais[]
  piecesJointes: PieceJointe[]
  observations: Observation[]
}

import { useDisplay } from 'vuetify'

const route = useRoute()
const api = useApi()
const auth = useAuthStore()
const { mobile } = useDisplay()
const id = computed(() => route.params.id as string)
const parametresStore = useParametresStore()
onMounted(() => { parametresStore.charger() })

const note = ref<NoteDetail | null>(null)
const loading = ref(false)
const busy = ref(false)
const erreur = ref('')
const observation = ref('')
const prioriteChoisie = ref<string | null>(null)

const uploadInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const deletingPieceId = ref<number | null>(null)

const prioriteOptions = [
  { title: 'Haute', value: 'HAUTE' },
  { title: 'Moyenne', value: 'MOYENNE' },
  { title: 'Basse', value: 'BASSE' },
]

// Le dégradé de la bannière reflète le statut de la note (cohérent avec les
// cartes de la liste), et non plus un hasard par référence.
const statutMeta = computed(() => statutNoteMeta(note.value?.statut, note.value?.sens))
const heroGradient = computed(() => statutMeta.value.gradient)
const estEncaissement = computed(() => note.value?.sens === 'ENCAISSEMENT')

// Nom de l'auteur de l'étape ayant amené la note au statut donné (ex.
// VERIFIEE_DFIN), retrouvé dans l'historique : c'est la seule trace de QUI a
// vérifié/validé/payé — la note elle-même ne porte que le statut courant, pas
// l'identité de chaque intervenant. N'affiche donc un nom sur la ligne de
// signature que si l'étape a réellement eu lieu ; sinon la ligne reste vide,
// prête à être signée à la main.
function signataire(statutCible: string): string {
  const o = note.value?.observations.find((o) => (o.statutAuMoment || o.statut) === statutCible)
  return o?.auteurNom || o?.auteur || ''
}

const prioMeta: Record<string, { label: string; bg: string; color: string }> = {
  HAUTE:   { label: 'Haute',   bg: '#fee2e2', color: '#dc2626' },
  MOYENNE: { label: 'Moyenne', bg: '#ffedd5', color: '#ea580c' },
  BASSE:   { label: 'Basse',   bg: '#dbeafe', color: '#2563eb' },
}

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    note.value = await api<NoteDetail>(`/notes-frais/${id.value}`)
    prioriteChoisie.value = note.value?.priorite ?? null
  } catch (e: any) {
    // 404 : note inexistante, ou que le rôle de l'utilisateur ne lui permet pas de voir — le serveur ne
    // distingue volontairement pas les deux (une note cachée ne doit pas révéler son existence).
    const introuvable = (e?.response?.status ?? e?.statusCode ?? e?.status) === 404
    erreur.value = introuvable
      ? "Cette note est introuvable, ou vous n'êtes pas autorisé à la consulter : selon votre rôle, certaines notes ne vous sont pas accessibles."
      : (e?.data?.message || 'Note introuvable.')
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  await charger()
  // Ouverture directe en mode impression depuis la liste (?print=1)
  if (note.value && route.query.print === '1') {
    await nextTick()
    imprimer()
  }
})
// La navigation vers "la note suivante" (voir allerNoteSuivante) reste sur
// cette meme route dynamique : Vue Router reutilise l'instance du composant
// au lieu de la remonter, donc sans ce watcher la page garderait affichee
// l'ancienne note malgre l'URL a jour.
watch(id, charger)

const montantFmt = computed(() =>
  note.value
    ? new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(note.value.montant)
      + ' ' + (note.value.devise || 'CDF')
    : ''
)

const statut = computed(() => note.value?.statut)
const rolesStore = useRolesStore()
const lDFIN = computed(() => rolesStore.libelle('DFIN'))
const lDA = computed(() => rolesStore.libelle('DA'))
const isDFIN = computed(() => auth.hasAnyRole(['DFIN', 'ADMIN']))
const isDA = computed(() => auth.hasAnyRole(['DA', 'ADMIN']))
const isCreateur = computed(() => auth.hasAnyRole(['COMPTABLE', 'CAISSIER']))

// Une note d'encaissement ne suit pas le circuit DFIN/DA : "Soumettre" ne
// s'applique qu'au décaissement (isCreateur n'est qu'un contrôle de rôle,
// pas de propriété — un caissier verrait sinon ce bouton sur ses propres
// notes d'encaissement, alors que le backend rejette cette transition).
const peutSoumettre    = computed(() =>
  isCreateur.value && !estEncaissement.value && ['BROUILLON','REJETEE_DA'].includes(statut.value || ''))
const peutEncaisser    = computed(() =>
  auth.hasAnyRole(['CAISSIER', 'ADMIN']) && estEncaissement.value && statut.value === 'BROUILLON')
const peutVerifier     = computed(() => isDFIN.value && statut.value === 'SOUMISE')
// Le DFIN peut corriger le compte d'imputation des lignes pendant sa
// vérification (note SOUMISE), qu'il en soit ou non le créateur — voir
// NoteFraisService.modifierComptesLignes côté backend.
// Une note faite uniquement d'achats de marchandise n'a aucun compte a
// reimputer : le compte de chaque ligne decoule de son article.
// Notes de paie et notes fiscales (module DRH) : comptes imposés par le système, jamais réimputés.
const categorieDrh = computed(() => libelleCategorieNote(note.value))
const peutModifierComptes = computed(() => isDFIN.value && statut.value === 'SOUMISE' && !categorieDrh.value
  && (note.value?.lignes || []).some(l => !l.achatMarchandise))
// Meme fenetre que peutModifierComptes : le DFIN corrige une saisie
// maladroite de l'employe pendant sa verification, sans renvoyer la note
// pour un nouveau cycle de soumission — voir NoteFraisService.modifierObjet.
const peutModifierObjet = computed(() => isDFIN.value && statut.value === 'SOUMISE')
const peutValiderRejeter = computed(() => isDA.value && statut.value === 'VERIFIEE_DFIN')
const peutPrioriser    = computed(() => isDA.value && ['VALIDEE_DA', 'TRANSMISE_CAISSE'].includes(statut.value || ''))
const peutTransmettre  = computed(() => isDFIN.value && statut.value === 'VALIDEE_DA')
const peutAnnuler      = computed(() =>
  auth.hasAnyRole(['DIRECTEUR','COMPTABLE','CAISSIER','DFIN','RESP_RESTAURANT','LOGISTIQUE','ADMIN']) &&
  ['BROUILLON','SOUMISE','VERIFIEE_DFIN','VALIDEE_DA','REJETEE_DA'].includes(statut.value || ''))

/** Message contextuel quand aucune action n'est disponible. */
const messageAucuneAction = computed(() => {
  const s = statut.value || ''
  if (s === 'BROUILLON' && estEncaissement.value) return "Seul le caissier peut encaisser cette note directement (sans validation "+lDFIN.value+"/"+lDA.value+")."
  if (s === 'TRANSMISE_CAISSE') return 'Cette note est transmise à la trésorerie et attend son paiement (caisse, banque ou mobile money).'
  if (s === 'PAYEE')            return estEncaissement.value ? 'Cette note a déjà été encaissée. Aucune action requise.' : 'Cette note a déjà été payée. Aucune action requise.'
  if (s === 'ANNULEE')          return 'Cette note est annulée.'
  if (s === 'SOUMISE')
    return `En attente de vérification par ${lDFIN.value}.`
  if (s === 'VERIFIEE_DFIN' && !isDA.value)
    return `Note vérifiée par ${lDFIN.value}. En attente de validation par ${lDA.value}.`
  if (s === 'VALIDEE_DA' && !isDFIN.value)
    return `Note validée par ${lDA.value}. En attente de transmission à la trésorerie par ${lDFIN.value}.`
  return 'Aucune action disponible pour votre rôle à ce stade.'
})

/**
 * Après une action de vérification/validation/transmission, enchaîne
 * directement sur la prochaine note en attente au même stade (même statut
 * que celui qui rendait le bouton visible) pour éviter les allers-retours
 * par la liste : le DA ou le DFIN traite sa file sans quitter l'écran de
 * validation. Reste sur la note qui vient d'être traitée s'il n'y en a pas
 * d'autre, ou si la liste des suivantes est indisponible.
 */
async function allerNoteSuivante(statutFile: string, noteActuelleId: string) {
  try {
    const suivantes = await api<{ id: number }[]>('/notes-frais', { query: { statut: statutFile } })
    const prochaine = suivantes.find(n => String(n.id) !== noteActuelleId)
    if (prochaine) {
      await navigateTo(`/notes-frais/${prochaine.id}`)
    }
  } catch {
    // Liste indisponible : on reste simplement sur la note traitee.
  }
}

// ── Contrôle budgétaire ──────────────────────────────────────────────
// Contrôle à jour (composant BudgetControleBudgetaire) : une note non couverte par le budget doit être
// justifiée avant la soumission, et le DA doit motiver sa validation.
const controleNote = ref<any | null>(null)
const rafraichirControle = ref(0)
const justificationSaisie = ref('')
const enregistrementJustification = ref(false)
const horsBudget = computed(() => !!controleNote.value?.justificationRequise)
const peutJustifier = computed(() =>
  isCreateur.value && !estEncaissement.value && ['BROUILLON', 'REJETEE_DA'].includes(statut.value || ''))
watch(() => note.value?.justificationBudget, (v) => { justificationSaisie.value = v || '' }, { immediate: true })

async function enregistrerJustification() {
  enregistrementJustification.value = true
  erreur.value = ''
  try {
    note.value = await api<NoteDetail>(`/notes-frais/${id.value}/justification-budget`, {
      method: 'PUT', body: { justification: justificationSaisie.value.trim() || null },
    })
  } catch (e: any) {
    erreur.value = e?.data?.message || "La justification n'a pas pu être enregistrée."
  } finally {
    enregistrementJustification.value = false
  }
}

async function action(chemin: string, requiertCommentaire = false, statutFileSuivante?: string) {
  if (requiertCommentaire && !observation.value.trim()) {
    erreur.value = chemin === 'valider'
      ? "Cette note n'est pas couverte par le budget : motivez votre validation dans le champ Observation."
      : 'Un motif est obligatoire pour cette action.'
    return
  }
  if (chemin === 'soumettre' && horsBudget.value && !(note.value?.justificationBudget || '').trim()) {
    erreur.value = "Cette dépense n'est pas couverte par le budget : enregistrez d'abord la justification budgétaire (encadré « Contrôle budgétaire »)."
    return
  }
  busy.value = true
  erreur.value = ''
  try {
    const noteActuelleId = id.value
    note.value = await api<NoteDetail>(`/notes-frais/${noteActuelleId}/${chemin}`, {
      method: 'POST',
      body: { commentaire: observation.value || null },
    })
    observation.value = ''
    prioriteChoisie.value = note.value?.priorite ?? null
    rafraichirControle.value++
    if (statutFileSuivante) {
      await allerNoteSuivante(statutFileSuivante, noteActuelleId)
    }
  } catch (e: any) {
    erreur.value = e?.data?.message || "Echec de l'action."
  } finally { busy.value = false }
}

async function encaisserNote() {
  busy.value = true
  erreur.value = ''
  try {
    // L'action retourne la transaction de caisse créée, pas la note : on
    // recharge la fiche pour refléter le nouveau statut et l'historique.
    await api(`/caisse/notes/${id.value}/encaisser`, { method: 'POST' })
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || "Echec de l'encaissement."
  } finally {
    busy.value = false
  }
}

async function definirPriorite() {
  if (!prioriteChoisie.value) { erreur.value = 'Choisissez une priorité.'; return }
  busy.value = true
  erreur.value = ''
  try {
    const noteActuelleId = id.value
    // Juste apres la validation (DA), definir la priorite est la derniere
    // etape avant d'enchainer sur la note suivante — voir action(). Un
    // ajustement de priorite plus tard (note deja TRANSMISE_CAISSE) n'a en
    // revanche aucune file "suivante" a laquelle rattacher cette action.
    const venaitDEtreValidee = statut.value === 'VALIDEE_DA'
    note.value = await api<NoteDetail>(`/notes-frais/${noteActuelleId}/priorite`, {
      method: 'POST',
      body: { priorite: prioriteChoisie.value, commentaire: observation.value || null },
    })
    observation.value = ''
    if (venaitDEtreValidee) {
      await allerNoteSuivante('VERIFIEE_DFIN', noteActuelleId)
    }
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Echec.'
  } finally { busy.value = false }
}

async function ajouterObservation() {
  if (!observation.value.trim()) { erreur.value = "L'observation ne peut pas être vide."; return }
  busy.value = true
  erreur.value = ''
  try {
    note.value = await api<NoteDetail>(`/notes-frais/${id.value}/observation`, {
      method: 'POST',
      body: { commentaire: observation.value },
    })
    observation.value = ''
  } catch (e: any) {
    erreur.value = e?.data?.message || "Echec."
  } finally { busy.value = false }
}

// ── Modification des comptes d'imputation (DFIN, note SOUMISE) ─────────
const editionComptes = ref(false)
const comptesEdites = ref<Record<number, string | null>>({})
const enregistrementComptes = ref(false)

function activerEditionComptes() {
  comptesEdites.value = Object.fromEntries(
    (note.value?.lignes || []).map(l => [l.id, l.compteImputation ?? null])
  )
  editionComptes.value = true
}

function annulerEditionComptes() {
  editionComptes.value = false
  comptesEdites.value = {}
}

async function enregistrerComptes() {
  const lignesModifiees = (note.value?.lignes || [])
    .filter(l => (comptesEdites.value[l.id] ?? null) !== (l.compteImputation ?? null))
    .map(l => ({ ligneId: l.id, compteImputation: comptesEdites.value[l.id] ?? null }))

  if (lignesModifiees.length === 0) {
    annulerEditionComptes()
    return
  }

  enregistrementComptes.value = true
  erreur.value = ''
  try {
    note.value = await api<NoteDetail>(`/notes-frais/${id.value}/comptes`, {
      method: 'PUT',
      body: { lignes: lignesModifiees },
    })
    editionComptes.value = false
    comptesEdites.value = {}
  } catch (e: any) {
    erreur.value = e?.data?.message || "Echec de la modification des comptes."
  } finally {
    enregistrementComptes.value = false
  }
}

// ── Libellé (objet), correction par le DFIN pendant la vérification ──────
const editionObjet = ref(false)
const objetEdite = ref('')
const enregistrementObjet = ref(false)

function activerEditionObjet() {
  objetEdite.value = note.value?.objet || ''
  editionObjet.value = true
}
function annulerEditionObjet() {
  editionObjet.value = false
}
async function enregistrerObjet() {
  const valeur = objetEdite.value.trim()
  if (!valeur || valeur === note.value?.objet) {
    annulerEditionObjet()
    return
  }
  enregistrementObjet.value = true
  erreur.value = ''
  try {
    note.value = await api<NoteDetail>(`/notes-frais/${id.value}/objet`, {
      method: 'PUT',
      body: { objet: valeur },
    })
    editionObjet.value = false
  } catch (e: any) {
    erreur.value = e?.data?.message || "Echec de la modification du libellé."
  } finally {
    enregistrementObjet.value = false
  }
}

function fmtDate(d?: string) {
  return d ? new Date(d).toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' }) : ''
}

const dateImpression = ref('')
function imprimer() {
  // Une édition de comptes en cours n'a pas de rendu imprimable (le champ de
  // saisie est masqué à l'impression) : on la referme d'abord pour ne pas
  // imprimer une ligne de dépense vide.
  if (editionComptes.value) annulerEditionComptes()
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  window.print()
}

function fmtMontant(v: number) {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(v)
}

function fmtTaille(o?: number | null) {
  if (!o) return ''
  if (o < 1024) return `${o} o`
  if (o < 1024 * 1024) return `${(o / 1024).toFixed(0)} Ko`
  return `${(o / (1024 * 1024)).toFixed(1)} Mo`
}

function iconePiece(typeMime?: string | null) {
  if (typeMime === 'application/pdf') return 'mdi-file-pdf-box'
  if (typeMime?.startsWith('image/')) return 'mdi-file-image-outline'
  return 'mdi-file-outline'
}

// ── Pièces jointes ──────────────────────────────────────────────────────
function declencherUpload() {
  uploadInput.value?.click()
}

async function onFichierChoisi(e: Event) {
  const input = e.target as HTMLInputElement
  const fichier = input.files?.[0]
  if (!fichier) return
  uploading.value = true
  erreur.value = ''
  try {
    const formData = new FormData()
    formData.append('fichier', fichier)
    note.value = await api<NoteDetail>(`/notes-frais/${id.value}/pieces-jointes`, {
      method: 'POST',
      body: formData,
    })
  } catch (e: any) {
    erreur.value = e?.data?.message || "Echec de l'ajout de la pièce jointe."
  } finally {
    uploading.value = false
    input.value = ''
  }
}

async function telechargerPiece(piece: PieceJointe) {
  try {
    const blob = await api<Blob>(`/notes-frais/${id.value}/pieces-jointes/${piece.id}`, {
      responseType: 'blob',
    })
    const url = URL.createObjectURL(blob as Blob)
    const a = document.createElement('a')
    a.href = url
    a.download = piece.nomFichier
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Echec du téléchargement.'
  }
}

// ── Aperçu (au lieu de forcer le téléchargement pour voir le contenu) ──
const apercuOuvert = ref(false)
const apercuPieceActuelle = ref<PieceJointe | null>(null)
const apercuUrl = ref<string | null>(null)
const apercuLoading = ref(false)
const apercuErreur = ref('')

const apercuEstImage = computed(() => !!apercuPieceActuelle.value?.typeMime?.startsWith('image/'))
const apercuEstPdf = computed(() => apercuPieceActuelle.value?.typeMime === 'application/pdf')
// #view=FitH : demande au lecteur PDF intégré du navigateur d'ajuster la
// page à la largeur du cadre — sans ça, le zoom par défaut ("Automatique")
// centre une page plus étroite que l'iframe, avec de grandes bandes vides.
const apercuPdfUrl = computed(() => apercuUrl.value ? `${apercuUrl.value}#view=FitH` : null)

function revoquerApercu() {
  if (apercuUrl.value) {
    URL.revokeObjectURL(apercuUrl.value)
    apercuUrl.value = null
  }
}

async function ouvrirApercu(piece: PieceJointe) {
  apercuPieceActuelle.value = piece
  apercuOuvert.value = true
  apercuErreur.value = ''
  revoquerApercu()

  const previsualisable = piece.typeMime?.startsWith('image/') || piece.typeMime === 'application/pdf'
  if (!previsualisable) return

  apercuLoading.value = true
  try {
    const blob = await api<Blob>(`/notes-frais/${id.value}/pieces-jointes/${piece.id}`, {
      responseType: 'blob',
    })
    apercuUrl.value = URL.createObjectURL(blob as Blob)
  } catch (e: any) {
    apercuErreur.value = e?.data?.message || "Impossible de charger l'aperçu."
  } finally {
    apercuLoading.value = false
  }
}

function fermerApercu() {
  apercuOuvert.value = false
  revoquerApercu()
  apercuPieceActuelle.value = null
}

onBeforeUnmount(() => revoquerApercu())

async function supprimerPiece(piece: PieceJointe) {
  deletingPieceId.value = piece.id
  erreur.value = ''
  try {
    note.value = await api<NoteDetail>(`/notes-frais/${id.value}/pieces-jointes/${piece.id}`, {
      method: 'DELETE',
    })
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Echec de la suppression.'
  } finally {
    deletingPieceId.value = null
  }
}

const peutGererPieces = computed(() =>
  auth.hasAnyRole(['DIRECTEUR', 'COMPTABLE', 'CAISSIER', 'DFIN', 'DA', 'ADMIN']))
</script>

<template>
  <div class="nd-page">
    <!-- Back + Print -->
    <div class="nd-header-row nd-noprint">
      <nuxt-link to="/notes-frais" class="nd-back">
        <v-icon icon="mdi-arrow-left" size="16" class="mr-1" />Retour aux notes
      </nuxt-link>
      <v-btn
        v-if="note"
        variant="tonal"
        color="primary"
        rounded="lg"
        prepend-icon="mdi-printer-outline"
        @click="imprimer"
      >
        Imprimer
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>
    <v-alert v-if="note && categorieDrh" type="info" variant="tonal" rounded="lg" class="mb-4 nd-noprint" icon="mdi-account-cash-outline">
      <template v-if="note.categorie === 'PAIE'">
        Note de paie générée depuis les bulletins de paie : ses comptes et montants découlent des bulletins et ne se
        modifient pas. Son paiement écrit aussi la constatation de la paie du mois (charges de personnel, IPR, CNSS,
        INPP, ONEM) selon le SYSCOHADA.
      </template>
      <template v-else>
        Note de versement fiscal générée depuis la paie : elle solde la dette constatée au paiement de la note de paie.
        Ses comptes et montants ne se modifient pas.
      </template>
    </v-alert>

    <v-skeleton-loader v-if="loading" type="card, article" class="mt-4" />

    <div v-else-if="note" class="nd-layout">

      <!-- Entête d'impression (visible uniquement sur le document imprimé) -->
      <div class="nd-print-header print-entete">
        <div class="nd-print-header__brand print-entete__marque">
          <div class="nd-print-header__logo print-entete__logo" :class="{ 'nd-print-header__logo--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
            <v-icon v-else icon="mdi-finance" size="16" color="white" />
          </div>
          <div>
            <span class="nd-print-header__company print-entete__nom">{{ parametresStore.parametres.nom }}</span>
            <span class="nd-print-header__doc">{{ estEncaissement ? "Note d'encaissement" : 'Note de frais' }}</span>
          </div>
        </div>
        <div class="nd-print-header__meta print-entete__meta">
          <span class="nd-print-header__ref">{{ note.reference }}</span>
          <span class="nd-print-status-pill" :style="{ background: statutMeta.bg, color: statutMeta.text }">
            {{ statutMeta.label }}
          </span>
          <span class="nd-print-header__date">Imprimé le : {{ dateImpression }}</span>
        </div>
      </div>

      <!-- ── Left column ──────────────────────────────────── -->
      <div class="nd-left">

        <!-- Hero card -->
        <div
          class="nd-hero"
          :style="{ background: heroGradient, '--print-tint': statutMeta.bg, '--print-text': statutMeta.text, '--print-accent': statutMeta.color }"
        >
          <div class="nd-hero__blob nd-hero__blob--a" />
          <div class="nd-hero__blob nd-hero__blob--b" />

          <div class="nd-hero__top">
            <span class="nd-hero__ref">{{ note.reference }}</span>
            <div class="nd-hero__chips">
              <!-- Statut et sens sont deja portes par l'entete du document
                   imprime (pastille couleur + type de document) : on les
                   masque ici a l'impression pour eviter la redondance. -->
              <span v-if="note.statut" class="nd-badge nd-badge--redondant-impression"
                :style="{ background:'rgba(255,255,255,0.18)', color:'#fff', border:'1px solid rgba(255,255,255,0.22)' }">
                {{ statutMeta.label }}
              </span>
              <span v-if="estEncaissement" class="nd-badge nd-badge--redondant-impression"
                :style="{ background:'rgba(255,255,255,0.18)', color:'#fff', border:'1px solid rgba(255,255,255,0.22)' }">
                <v-icon icon="mdi-cash-plus" size="12" class="mr-1" />Encaissement
              </span>
              <span v-if="categorieDrh" class="nd-badge"
                :style="{ background:'rgba(255,255,255,0.18)', color:'#fff', border:'1px solid rgba(255,255,255,0.22)' }">
                <v-icon icon="mdi-account-cash-outline" size="12" class="mr-1" />{{ categorieDrh }}
              </span>
              <span v-if="note.priorite" class="nd-badge"
                :style="{ background: prioMeta[note.priorite]?.bg, color: prioMeta[note.priorite]?.color }">
                Priorité {{ prioMeta[note.priorite]?.label }}
              </span>
            </div>
          </div>

          <div class="nd-hero__body">
            <div v-if="!editionObjet" class="nd-hero__objet-row">
              <h1 class="nd-hero__objet">{{ note.objet }}</h1>
              <button
                v-if="peutModifierObjet"
                type="button" class="nd-hero__edit-btn nd-noprint"
                title="Modifier le libellé" @click="activerEditionObjet"
              >
                <v-icon icon="mdi-pencil-outline" size="15" />
              </button>
            </div>
            <div v-else class="nd-hero__objet-edit nd-noprint">
              <v-text-field
                v-model="objetEdite"
                density="compact" variant="solo" hide-details bg-color="white"
                maxlength="200"
                class="nd-hero__objet-input"
                @keyup.enter="enregistrerObjet"
                @keyup.esc="annulerEditionObjet"
              />
              <v-btn size="small" variant="flat" color="indigo" icon="mdi-check"
                :loading="enregistrementObjet" title="Enregistrer" @click="enregistrerObjet" />
              <v-btn size="small" variant="tonal" icon="mdi-close"
                :disabled="enregistrementObjet" title="Annuler" @click="annulerEditionObjet" />
            </div>
            <p class="nd-hero__montant">{{ montantFmt }}</p>
          </div>
        </div>

        <!-- Info card -->
        <div class="nd-card">
          <p class="nd-card__section-title">Informations</p>
          <div class="nd-info-grid">
            <div class="nd-info-row">
              <div class="nd-info-icon"><v-icon icon="mdi-account-outline" size="16" /></div>
              <div>
                <span class="nd-info-label">Demandeur</span>
                <span class="nd-info-val">
                  <span v-if="note.demandeurFonction" class="nd-fonction">{{ note.demandeurFonction }}<span aria-hidden="true">&nbsp;·&nbsp;</span></span>
                  <span>{{ note.demandeurNom || '—' }}</span>
                </span>
              </div>
            </div>
            <div class="nd-info-row">
              <div class="nd-info-icon"><v-icon icon="mdi-account-cash-outline" size="16" /></div>
              <div>
                <span class="nd-info-label">Bénéficiaire</span>
                <span class="nd-info-val">{{ note.beneficiaire || '—' }}</span>
              </div>
            </div>
            <div class="nd-info-row">
              <div class="nd-info-icon"><v-icon icon="mdi-email-outline" size="16" /></div>
              <div>
                <span class="nd-info-label">Email</span>
                <span class="nd-info-val">{{ note.demandeurEmail || '—' }}</span>
              </div>
            </div>
            <div v-if="note.description" class="nd-info-row nd-info-row--full">
              <div class="nd-info-icon"><v-icon icon="mdi-text" size="16" /></div>
              <div>
                <span class="nd-info-label">Description</span>
                <span class="nd-info-val">{{ note.description }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Lignes de dépense -->
        <div class="nd-card">
          <div class="nd-card__section-title nd-card__section-title--row">
            <span>{{ estEncaissement ? 'Recettes' : 'Dépenses' }} ({{ note.lignes.length }})</span>
            <template v-if="peutModifierComptes">
              <v-btn v-if="!editionComptes" class="nd-noprint" size="small" variant="tonal" color="indigo"
                prepend-icon="mdi-book-edit-outline" @click="activerEditionComptes">
                Modifier les comptes
              </v-btn>
              <div v-else class="nd-noprint d-flex ga-2">
                <v-btn size="small" variant="text" color="grey" :disabled="enregistrementComptes" @click="annulerEditionComptes">
                  Annuler
                </v-btn>
                <v-btn size="small" variant="flat" color="indigo" :loading="enregistrementComptes" @click="enregistrerComptes">
                  Enregistrer
                </v-btn>
              </div>
            </template>
          </div>
          <div class="nd-lignes">
            <div v-for="l in note.lignes" :key="l.id" class="nd-ligne">
              <!-- Une ligne d'achat de marchandise garde le compte d'achat de son
                   article : le serveur refuse de la réimputer (le stock serait
                   compté deux fois), le sélecteur n'est donc pas proposé. -->
              <div v-if="editionComptes && !l.achatMarchandise" class="nd-ligne__compte-edit nd-noprint">
                <ComptabiliteSelecteurCompte
                  v-model="comptesEdites[l.id]"
                  label="Compte d'imputation"
                  :disabled="enregistrementComptes"
                />
              </div>
              <div v-else class="nd-ligne__compte">
                <v-icon icon="mdi-book-open-variant" size="15" class="mr-1" />
                {{ l.compteImputation ? `${l.compteImputation} — ${l.compteImputationLibelle || ''}` : (estEncaissement ? 'Compte par défaut (758)' : 'Compte par défaut (6588)') }}
                <span v-if="editionComptes" class="text-caption text-medium-emphasis ml-1">· fixé par l'article</span>
              </div>
              <p v-if="l.description" class="nd-ligne__desc">{{ l.description }}</p>
              <div v-if="l.achatMarchandise || l.soumisTva || l.fraisApproche" class="nd-ligne__badges">
                <v-chip v-if="l.fraisApproche" size="x-small" variant="tonal" color="teal" prepend-icon="mdi-truck-outline">
                  Frais d'approche · incorporés au coût des marchandises de la note
                </v-chip>
                <v-chip v-if="l.achatMarchandise" size="x-small" variant="tonal" color="teal" prepend-icon="mdi-package-variant">
                  {{ l.articleLibelle || 'Marchandise' }}{{ l.quantiteMarchandise ? ` · ${l.quantiteMarchandise} × ${fmtMontant(l.montant)}` : '' }}{{ l.entrepotNom ? ` · ${l.entrepotNom}` : '' }}
                </v-chip>
                <v-chip v-if="l.soumisTva" size="x-small" variant="tonal" color="indigo" prepend-icon="mdi-percent-outline">
                  {{ estEncaissement ? 'TVA collectée' : 'TVA récupérable' }}{{ l.compteTva ? ` · ${l.compteTva}` : '' }}
                </v-chip>
              </div>
              <span class="nd-ligne__montant">
                <template v-if="l.soumisTva && l.montantTtc">
                  HT {{ fmtMontant(l.montantHtTotal ?? l.montant) }} · TTC {{ fmtMontant(l.montantTtc) }} {{ note.devise || 'CDF' }}
                </template>
                <template v-else-if="l.achatMarchandise && l.montantHtTotal">
                  HT {{ fmtMontant(l.montantHtTotal) }} {{ note.devise || 'CDF' }}
                </template>
                <template v-else>{{ fmtMontant(l.montant) }} {{ note.devise || 'CDF' }}</template>
              </span>
            </div>
          </div>
          <div class="nd-lignes-total">
            <span>Total</span>
            <strong>{{ montantFmt }}</strong>
          </div>
        </div>

        <!-- Contrôle budgétaire : chaque dépense doit être couverte par le budget en exécution, sinon justifiée. -->
        <div v-if="!estEncaissement" class="nd-card nd-card--budget">
          <div class="nd-card__body">
            <BudgetControleBudgetaire :note-id="note.id" :sens="note.sens" :rafraichir="rafraichirControle" @resultat="(r: any) => controleNote = r" />
            <div v-if="peutJustifier && (horsBudget || note.justificationBudget)" class="nd-justif">
              <label class="nd-label">Justification budgétaire {{ horsBudget ? '*' : '' }}</label>
              <v-textarea
                v-model="justificationSaisie"
                rows="2"
                auto-grow
                hide-details
                placeholder="Pourquoi cette dépense n'est-elle pas couverte par le budget ? (urgence, dépense imprévue, arbitrage de la direction...)"
              />
              <v-btn class="mt-2" size="small" color="primary" variant="tonal" prepend-icon="mdi-content-save-outline"
                :loading="enregistrementJustification" :disabled="justificationSaisie === (note.justificationBudget || '')"
                @click="enregistrerJustification">
                Enregistrer la justification
              </v-btn>
            </div>
            <div v-else-if="note.justificationBudget" class="nd-justif nd-justif--lecture">
              <span class="nd-label">Justification budgétaire du demandeur</span>
              <p>{{ note.justificationBudget }}</p>
            </div>
          </div>
        </div>

        <!-- Pièces jointes : masquée à l'impression si vide, la carte vide
             (icône + texte) n'apportant rien sur un document imprimé. -->
        <div class="nd-card" :class="{ 'nd-print-hide-if-empty': note.piecesJointes.length === 0 }">
          <div class="nd-card__section-title nd-card__section-title--row">
            <span>Pièces jointes ({{ note.piecesJointes.length }})</span>
            <v-btn
              v-if="peutGererPieces"
              class="nd-noprint"
              size="small"
              variant="tonal"
              color="primary"
              prepend-icon="mdi-paperclip"
              :loading="uploading"
              @click="declencherUpload"
            >
              Joindre un fichier
            </v-btn>
            <input
              ref="uploadInput"
              type="file"
              accept="application/pdf,image/jpeg,image/png,image/webp,image/gif"
              class="nd-hidden-input"
              @change="onFichierChoisi"
            >
          </div>
          <div v-if="note.piecesJointes.length === 0" class="nd-timeline-empty">
            <v-icon icon="mdi-paperclip-off" size="28" color="#d1d5db" />
            <p>Aucun justificatif (PDF ou image) joint pour l'instant.</p>
          </div>
          <div v-else class="nd-pieces">
            <div v-for="pj in note.piecesJointes" :key="pj.id" class="nd-piece">
              <v-icon :icon="iconePiece(pj.typeMime)" size="22" color="#6b7280" />
              <div class="nd-piece__info">
                <span class="nd-piece__nom">{{ pj.nomFichier }}</span>
                <span class="nd-piece__meta">
                  {{ fmtTaille(pj.taille) }}<template v-if="pj.ajoutePar"> · {{ pj.ajoutePar }}</template>
                </span>
              </div>
              <v-btn class="nd-noprint" icon="mdi-eye-outline" variant="text" size="small" @click="ouvrirApercu(pj)" />
              <v-btn class="nd-noprint" icon="mdi-download" variant="text" size="small" @click="telechargerPiece(pj)" />
              <v-btn
                v-if="peutGererPieces"
                class="nd-noprint"
                icon="mdi-delete-outline"
                variant="text"
                size="small"
                color="error"
                :loading="deletingPieceId === pj.id"
                @click="supprimerPiece(pj)"
              />
            </div>
          </div>
        </div>

        <!-- Timeline : masquée à l'impression (le document imprimé sert de
             pièce justificative signée, pas de journal d'activité). -->
        <div class="nd-card nd-noprint">
          <p class="nd-card__section-title">Historique du circuit</p>
          <div v-if="note.observations.length === 0" class="nd-timeline-empty">
            <v-icon icon="mdi-timeline-outline" size="28" color="#d1d5db" />
            <p>Aucune action dans le circuit pour l'instant.</p>
          </div>
          <div v-else class="nd-timeline">
            <div v-for="(o, i) in note.observations" :key="i" class="nd-timeline-item">
              <div class="nd-timeline-item__dot" />
              <div class="nd-timeline-item__content">
                <div class="nd-timeline-item__header">
                  <span class="nd-timeline-item__who">
                    <span v-if="o.auteurFonction" class="nd-fonction">{{ o.auteurFonction }}<span aria-hidden="true">&nbsp;·&nbsp;</span></span>
                    <strong class="nd-timeline-item__author">{{ o.auteurNom || o.auteur }}</strong>
                  </span>
                  <span class="nd-timeline-item__date">{{ fmtDate(o.dateAction || o.date) }}</span>
                </div>
                <span
                  v-if="o.statutAuMoment || o.statut"
                  class="nd-timeline-item__statut"
                  :style="{ background: statutNoteMeta(o.statutAuMoment || o.statut, note.sens).bg, color: statutNoteMeta(o.statutAuMoment || o.statut, note.sens).text }"
                >
                  {{ statutNoteMeta(o.statutAuMoment || o.statut, note.sens).label }}
                </span>
                <p class="nd-timeline-item__comment">{{ o.commentaire }}</p>
              </div>
            </div>
          </div>
        </div>

        <!-- Signatures (visible uniquement sur le document imprimé) -->
        <div class="nd-print-signatures">
          <p class="nd-print-signatures__title">Approbations</p>
          <div class="nd-print-signatures__row">
            <div class="nd-print-sign">
              <span class="nd-print-sign__label">Demandeur</span>
              <span class="nd-print-sign__name">{{ note.demandeurNom || '—' }}</span>
              <div class="nd-print-sign__line" />
              <span class="nd-print-sign__hint">Signature et cachet</span>
            </div>
            <div class="nd-print-sign">
              <span class="nd-print-sign__label">Bénéficiaire</span>
              <span class="nd-print-sign__name">{{ note.beneficiaire || '—' }}</span>
              <div class="nd-print-sign__line" />
              <span class="nd-print-sign__hint">Signature et cachet</span>
            </div>
            <template v-if="!estEncaissement">
              <div class="nd-print-sign">
                <span class="nd-print-sign__label">Vérifié {{ lDFIN }}</span>
                <span v-if="signataire('VERIFIEE_DFIN')" class="nd-print-sign__name">{{ signataire('VERIFIEE_DFIN') }}</span>
                <div class="nd-print-sign__line" />
                <span class="nd-print-sign__hint">Signature et cachet</span>
              </div>
              <div class="nd-print-sign">
                <span class="nd-print-sign__label">Validé {{ lDA }}</span>
                <span v-if="signataire('VALIDEE_DA')" class="nd-print-sign__name">{{ signataire('VALIDEE_DA') }}</span>
                <div class="nd-print-sign__line" />
                <span class="nd-print-sign__hint">Signature et cachet</span>
              </div>
            </template>
            <div class="nd-print-sign">
              <span class="nd-print-sign__label">Caissier</span>
              <span v-if="signataire('PAYEE')" class="nd-print-sign__name">{{ signataire('PAYEE') }}</span>
              <div class="nd-print-sign__line" />
              <span class="nd-print-sign__hint">Signature et cachet</span>
            </div>
          </div>
        </div>
      </div>

      <!-- ── Right column · Actions ───────────────────────── -->
      <div class="nd-right nd-noprint">
        <div class="nd-action-card">
          <div class="nd-action-card__head">
            <v-icon icon="mdi-gesture-tap-button" size="18" class="mr-2" />
            Actions disponibles
          </div>

          <div class="nd-action-card__body">
            <div class="nd-field">
              <label class="nd-label">Observation / motif</label>
              <v-textarea
                v-model="observation"
                rows="3"
                placeholder="Obligatoire pour un rejet ou une remarque"
                hide-details
              />
            </div>

            <div class="nd-actions-list">
              <v-btn v-if="peutSoumettre" color="primary" block rounded="lg" elevation="0"
                prepend-icon="mdi-send-circle" :loading="busy" @click="action('soumettre')">
                Soumettre au DFIN
              </v-btn>

              <v-btn v-if="peutEncaisser" color="teal" block rounded="lg" elevation="0"
                prepend-icon="mdi-cash-check" :loading="busy" @click="encaisserNote">
                Encaisser
              </v-btn>

              <v-btn v-if="peutVerifier" color="indigo" block rounded="lg" elevation="0"
                prepend-icon="mdi-check-circle" :loading="busy" @click="action('verifier', false, 'SOUMISE')">
                Vérifier (DFIN)
              </v-btn>

              <template v-if="peutValiderRejeter">
                <p v-if="horsBudget" class="nd-hint-budget">
                  Note non couverte par le budget : motivez votre validation dans le champ Observation.
                </p>
                <v-btn color="success" block rounded="lg" elevation="0"
                  prepend-icon="mdi-check" :loading="busy" @click="action('valider', horsBudget)">
                  {{ horsBudget ? 'Valider hors budget (DA)' : 'Valider (DA)' }}
                </v-btn>
                <v-btn color="error" block rounded="lg" variant="tonal"
                  prepend-icon="mdi-close" :loading="busy" @click="action('rejeter', true, 'VERIFIEE_DFIN')">
                  Rejeter (DA)
                </v-btn>
              </template>

              <template v-if="peutPrioriser">
                <div class="nd-divider" />
                <div class="nd-field">
                  <label class="nd-label">Priorité de paiement ({{ lDA }})</label>
                  <v-select v-model="prioriteChoisie" :items="prioriteOptions" density="comfortable" hide-details />
                </div>
                <v-btn color="deep-purple" block rounded="lg" elevation="0"
                  prepend-icon="mdi-flag" :loading="busy" @click="definirPriorite">
                  Définir la priorité
                </v-btn>
                <v-btn color="blue-grey" block rounded="lg" variant="tonal"
                  prepend-icon="mdi-comment-plus" :loading="busy" @click="ajouterObservation">
                  Ajouter une observation
                </v-btn>
              </template>

              <v-btn v-if="peutTransmettre" color="teal" block rounded="lg" elevation="0"
                prepend-icon="mdi-send" :loading="busy" @click="action('transmettre', false, 'VALIDEE_DA')">
                Transmettre à la trésorerie
              </v-btn>

              <v-btn v-if="peutAnnuler" color="grey" block rounded="lg" variant="text"
                prepend-icon="mdi-cancel" :loading="busy" @click="action('annuler')">
                Annuler la note
              </v-btn>

              <div v-if="!peutSoumettre && !peutEncaisser && !peutVerifier && !peutValiderRejeter && !peutPrioriser && !peutTransmettre && !peutAnnuler"
                class="nd-no-action">
                <v-icon icon="mdi-lock-outline" size="20" color="#d1d5db" />
                <p>{{ messageAucuneAction }}</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Aperçu d'une pièce jointe : plein écran sur mobile (bouton "X" pour
         quitter), fenêtre centrée sur desktop — évite d'avoir à télécharger
         le fichier juste pour voir son contenu. -->
    <v-dialog
      v-model="apercuOuvert"
      :fullscreen="mobile"
      :width="mobile ? undefined : '90vw'"
      :height="mobile ? undefined : '92vh'"
      :max-width="mobile ? undefined : 1400"
      scrollable
      @update:model-value="(v) => { if (!v) fermerApercu() }"
    >
      <v-card class="nd-apercu-card">
        <div class="nd-apercu-head">
          <span class="nd-apercu-head__nom">{{ apercuPieceActuelle?.nomFichier }}</span>
          <div class="nd-apercu-head__actions">
            <v-btn icon="mdi-download" variant="text" size="small" @click="telechargerPiece(apercuPieceActuelle!)" />
            <v-btn icon="mdi-close" variant="text" size="small" @click="fermerApercu" />
          </div>
        </div>
        <div class="nd-apercu-body">
          <v-progress-circular v-if="apercuLoading" indeterminate color="primary" />
          <v-alert v-else-if="apercuErreur" type="error" variant="tonal">{{ apercuErreur }}</v-alert>
          <img v-else-if="apercuEstImage && apercuUrl" :src="apercuUrl" class="nd-apercu-img" alt="Aperçu">
          <iframe v-else-if="apercuEstPdf && apercuPdfUrl" :src="apercuPdfUrl" class="nd-apercu-pdf" title="Aperçu du document" />
          <div v-else class="nd-apercu-non-supporte">
            <v-icon icon="mdi-file-question-outline" size="48" color="#d1d5db" />
            <p>Aperçu non disponible pour ce type de fichier.</p>
            <v-btn color="primary" variant="tonal" prepend-icon="mdi-download" @click="telechargerPiece(apercuPieceActuelle!)">
              Télécharger
            </v-btn>
          </div>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.nd-page { max-width: 1200px; margin: 0 auto; padding-bottom: 48px; }

.nd-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}
.nd-back {
  display: inline-flex;
  align-items: center;
  font-size: 0.875rem;
  font-weight: 600;
  color: #6b7280;
  text-decoration: none;
  transition: color 0.15s;
}
.nd-back:hover { color: #111827; }

/* ── Entête et signatures d'impression (masqués à l'écran) ──── */
.nd-print-header,
.nd-print-signatures { display: none; }

/* ── Layout ──────────────────────────────────────────────── */
.nd-layout {
  display: grid;
  grid-template-columns: 1fr 340px;
  gap: 20px;
  align-items: start;
}
/* min-width:0 : un enfant de grille est par défaut min-width:auto, donc ne
   rétrécit jamais sous la largeur intrinsèque de son contenu (ex. le bouton
   "Joindre un fichier"). Sans ça, un seul élément trop large pousse toute la
   colonne — puis la page entière — au-delà du viewport mobile, provoquant un
   défilement horizontal global. */
.nd-left, .nd-right { min-width: 0; }
@media (max-width: 900px) {
  .nd-layout { grid-template-columns: 1fr; }
}

/* ── Hero card ───────────────────────────────────────────── */
.nd-hero {
  position: relative;
  overflow: hidden;
  border-radius: 20px;
  padding: 28px 28px 24px;
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.nd-hero__blob {
  position: absolute;
  border-radius: 50%;
  background: rgba(255,255,255,0.09);
  pointer-events: none;
}
.nd-hero__blob--a { width: 200px; height: 200px; top: -60px; right: -60px; }
.nd-hero__blob--b { width: 120px; height: 120px; bottom: -30px; left: -30px; }

.nd-hero__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  position: relative; z-index: 1;
}
.nd-hero__ref {
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 1px;
  text-transform: uppercase;
  color: rgba(255,255,255,0.72);
}
.nd-hero__chips { display: flex; gap: 6px; flex-wrap: wrap; }

.nd-badge {
  display: inline-flex;
  align-items: center;
  font-size: 0.72rem;
  font-weight: 700;
  padding: 4px 12px;
  border-radius: 100px;
}

.nd-hero__body { position: relative; z-index: 1; }
.nd-hero__objet {
  font-size: clamp(1.1rem, 2vw, 1.5rem);
  font-weight: 700;
  color: #fff;
  margin: 0;
  line-height: 1.3;
}
.nd-hero__objet-row { display: flex; align-items: center; gap: 8px; margin: 0 0 10px; }
.nd-hero__edit-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px; height: 24px;
  flex-shrink: 0;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
  border: none;
  cursor: pointer;
}
.nd-hero__edit-btn:hover { background: rgba(255, 255, 255, 0.3); }
.nd-hero__objet-edit {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
  max-width: 480px;
}
.nd-hero__objet-input :deep(.v-field) { border-radius: 8px; }
.nd-hero__montant {
  font-size: 2rem;
  font-weight: 800;
  color: #fff;
  letter-spacing: -1px;
  margin: 0;
  font-variant-numeric: tabular-nums;
}

/* ── Info card ───────────────────────────────────────────── */
.nd-card {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 18px;
  overflow: hidden;
  margin-bottom: 16px;
}
.nd-card__section-title {
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.6px;
  text-transform: uppercase;
  color: #9ca3af;
  padding: 16px 20px 12px;
  border-bottom: 1px solid #f3f4f6;
  margin: 0;
}
.nd-card__section-title--row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 10px;
  border-bottom: none;
  padding-bottom: 12px;
}

/* ── Lignes de dépense ───────────────────────────────────── */
.nd-lignes { padding: 6px 20px 4px; display: flex; flex-direction: column; }
.nd-ligne {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: baseline;
  gap: 4px 12px;
  padding: 12px 0;
  border-bottom: 1px solid #f6f6f6;
}
.nd-ligne:last-child { border-bottom: none; }
.nd-ligne__compte {
  display: flex;
  align-items: center;
  font-size: 0.82rem;
  font-weight: 600;
  color: #374151;
}
.nd-ligne__compte-edit { grid-column: 1 / 2; max-width: 420px; padding: 4px 0; }
.nd-ligne__desc {
  grid-column: 1 / 2;
  font-size: 0.78rem;
  color: #9ca3af;
  margin: 0;
}
.nd-ligne__badges {
  grid-column: 1 / 2;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}
.nd-ligne__montant {
  grid-row: 1 / 3;
  grid-column: 2;
  font-size: 0.9rem;
  font-weight: 700;
  color: #111827;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.nd-lignes-total {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px 16px;
  font-size: 0.85rem;
  font-weight: 700;
  color: var(--color-primary);
}

/* ── Pièces jointes ──────────────────────────────────────── */
.nd-hidden-input { display: none; }
.nd-pieces { padding: 4px 20px 16px; display: flex; flex-direction: column; gap: 4px; }
.nd-piece {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid #f6f6f6;
}
.nd-piece:last-child { border-bottom: none; }
.nd-piece__info { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.nd-piece__nom {
  font-size: 0.85rem;
  font-weight: 600;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nd-piece__meta { font-size: 0.72rem; color: #9ca3af; }

/* ── Aperçu pièce jointe ─────────────────────────────────── */
.nd-apercu-card { display: flex; flex-direction: column; height: 100%; }
.nd-apercu-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 8px 10px 18px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}
.nd-apercu-head__nom {
  font-size: 0.85rem;
  font-weight: 600;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nd-apercu-head__actions { display: flex; align-items: center; gap: 2px; flex-shrink: 0; }
.nd-apercu-body {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: auto;
  background: #f3f4f6;
  padding: 16px;
}
.nd-apercu-img { max-width: 100%; max-height: 100%; object-fit: contain; border-radius: 4px; }
.nd-apercu-pdf { width: 100%; height: 100%; border: none; background: #fff; }
.nd-apercu-non-supporte {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  color: #9ca3af;
  text-align: center;
}
.nd-apercu-non-supporte p { margin: 0; }

.nd-info-grid { padding: 8px 0; }
.nd-info-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 20px;
}
.nd-info-row:hover { background: #fafafa; }
.nd-info-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px; height: 30px;
  border-radius: 8px;
  background: #f3f4f6;
  color: #6b7280;
  flex-shrink: 0;
  margin-top: 1px;
}
.nd-info-label {
  display: block;
  font-size: 0.72rem;
  font-weight: 600;
  color: #9ca3af;
  margin-bottom: 2px;
}
.nd-info-val {
  display: block;
  font-size: 0.875rem;
  font-weight: 500;
  color: #111827;
}

/* ── Timeline ────────────────────────────────────────────── */
.nd-timeline-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 20px;
  color: #9ca3af;
  font-size: 0.85rem;
  text-align: center;
}
.nd-timeline-empty p { margin: 0; }

.nd-timeline { padding: 8px 20px 16px; }
.nd-timeline-item {
  display: flex;
  gap: 14px;
  padding: 12px 0;
  position: relative;
}
.nd-timeline-item:not(:last-child)::after {
  content: '';
  position: absolute;
  left: 6px;
  top: 30px;
  bottom: -12px;
  width: 2px;
  background: #f0f0f0;
}
.nd-timeline-item__dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--color-primary);
  border: 3px solid var(--color-primary-light);
  flex-shrink: 0;
  margin-top: 4px;
}
.nd-timeline-item__content { flex: 1; min-width: 0; }
.nd-timeline-item__header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
}
/* Fonction de l'utilisateur, devant son nom : « Directeur Financier · Jean Dupont ». */
.nd-fonction { color: #6b7280; font-weight: 500; }
.nd-timeline-item__who { min-width: 0; overflow-wrap: anywhere; }
.nd-timeline-item__who .nd-fonction { font-size: 0.8rem; }
.nd-timeline-item__author { font-size: 0.875rem; color: #111827; }
.nd-timeline-item__date   { font-size: 0.72rem; color: #9ca3af; white-space: nowrap; }
.nd-timeline-item__statut {
  display: inline-block;
  font-size: 0.68rem;
  font-weight: 700;
  color: var(--color-primary);
  background: var(--color-primary-light);
  padding: 2px 8px;
  border-radius: 100px;
  margin-bottom: 4px;
}
.nd-timeline-item__comment { font-size: 0.85rem; color: #374151; margin: 0; }

/* ── Action card ─────────────────────────────────────────── */
.nd-action-card {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 18px;
  overflow: hidden;
  position: sticky;
  top: 80px;
}
.nd-action-card__head {
  display: flex;
  align-items: center;
  font-size: 0.78rem;
  font-weight: 700;
  letter-spacing: 0.4px;
  text-transform: uppercase;
  color: #9ca3af;
  padding: 16px 20px 12px;
  border-bottom: 1px solid #f3f4f6;
}
.nd-action-card__body {
  padding: 18px 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.nd-actions-list { display: flex; flex-direction: column; gap: 8px; }

.nd-field { display: flex; flex-direction: column; gap: 6px; }
.nd-label { font-size: 0.8rem; font-weight: 600; color: #374151; }

.nd-divider { height: 1px; background: #f3f4f6; }

.nd-no-action {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px;
  text-align: center;
  color: #9ca3af;
  font-size: 0.82rem;
}
.nd-no-action p { margin: 0; }

/* ── Impression : document moderne tenant efficacement sur la page ── */
@media print {
  .nd-noprint,
  .nd-hidden-input { display: none !important; }

  * { box-shadow: none !important; }

  /* padding-bottom : degage la place du pied de page fixe (approbations)
     pour qu'aucun contenu ne passe dessous. */
  .nd-page { max-width: 100%; padding: 0 0 95px; font-size: 11.5px; color: #1f2937; }
  .nd-layout { display: block; }
  .nd-left { width: 100%; }

  /* Carte sans contenu utile a l'impression (ex. aucune piece jointe) :
     l'etat vide (icone + texte) n'a de sens qu'a l'ecran. */
  .nd-print-hide-if-empty { display: none !important; }

  /* ── Entête à en-tête ─────────────────────────────────────── */
  .nd-print-header {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 16px;
    padding-bottom: 8px;
    margin-bottom: 10px;
    border-bottom: 3px solid var(--color-primary);
  }
  .nd-print-header__brand { display: flex; align-items: center; gap: 11px; }
  .nd-print-header__logo {
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
  .nd-print-header__logo--image { background: #fff; border: 1px solid #e5e7eb; }
  .nd-print-header__logo img { width: 100%; height: 100%; object-fit: contain; padding: 3px; }
  .nd-print-header__company { display: block; font-size: 1.15rem; font-weight: 800; color: #111827; letter-spacing: -0.2px; }
  .nd-print-header__doc {
    display: block;
    font-size: 0.68rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 1.2px;
    color: var(--color-primary);
    margin-top: 1px;
  }
  .nd-print-header__meta {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .nd-print-header__ref {
    font-size: 0.8rem;
    font-weight: 800;
    color: #111827;
    font-variant-numeric: tabular-nums;
  }
  .nd-print-status-pill {
    display: inline-block;
    font-size: 0.66rem;
    font-weight: 800;
    text-transform: uppercase;
    letter-spacing: 0.3px;
    padding: 3px 11px;
    border-radius: 100px;
  }
  .nd-print-header__date { font-size: 0.66rem; color: #9ca3af; }

  /* ── Banniere objet / montant, teintee selon le statut ───────── */
  .nd-hero {
    background: var(--print-tint, #f3f4f6) !important;
    border: 1px solid var(--print-text, #d1d5db);
    border-left: 4px solid var(--print-accent, #9ca3af);
    border-radius: 10px;
    margin-bottom: 7px;
    padding: 9px 16px;
    gap: 8px;
    display: flex;
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
  }
  .nd-hero__blob { display: none; }
  .nd-hero__top { flex-direction: column; align-items: flex-start; gap: 6px; }
  .nd-hero__ref { display: none; }
  .nd-hero__chips { gap: 5px; }
  .nd-hero__body { text-align: right; }
  .nd-hero__objet {
    color: var(--print-text, #111827) !important;
    font-size: 0.8rem;
    font-weight: 600;
    margin-bottom: 1px;
    opacity: 0.85;
  }
  .nd-hero__montant {
    color: var(--print-text, #111827) !important;
    font-size: 1.35rem;
    letter-spacing: -0.5px;
  }
  .nd-badge {
    font-size: 0.62rem;
    padding: 2px 10px;
    border: 1px solid rgba(0, 0, 0, 0.08);
  }
  /* Ces pastilles sont blanches sur fond degrade a l'ecran : sur la teinte
     claire du document imprime elles deviendraient illisibles, et leur
     information figure deja dans l'entete. */
  .nd-badge--redondant-impression { display: none !important; }

  /* ── Cartes de section ────────────────────────────────────── */
  .nd-card {
    break-inside: avoid;
    border: 1px solid #e5e7eb;
    border-radius: 10px;
    margin-bottom: 6px;
  }
  /* Le circuit d'approbation peut compter de nombreuses etapes : on
     l'autorise a se repartir sur plusieurs pages plutot que de forcer toute
     la carte (et son entete) sur une page neuve, ce qui laissait un grand
     vide en bas de la precedente. Chaque etape individuelle reste en revanche
     insecable (regle plus bas). */
  .nd-card:has(.nd-timeline) { break-inside: auto; }

  .nd-card__section-title {
    padding: 6px 14px 4px 25px;
    font-size: 0.6rem;
    position: relative;
  }
  .nd-card__section-title::before {
    content: '';
    position: absolute;
    left: 14px;
    top: 9px;
    width: 6px;
    height: 6px;
    border-radius: 2px;
    background: var(--color-primary);
  }
  .nd-card__section-title--row { padding-bottom: 6px; }

  .nd-info-grid { padding: 0; }
  .nd-info-row { padding: 4px 14px; gap: 8px; }
  .nd-info-row:hover { background: none; }
  .nd-info-icon { width: 22px; height: 22px; background: #f3f4f6 !important; }
  .nd-info-label { font-size: 0.62rem; margin-bottom: 0; }
  .nd-info-val { font-size: 0.78rem; }

  /* ── Lignes de depense : presentation facture ─────────────── */
  .nd-lignes { padding: 0 14px; }
  .nd-ligne { padding: 4px 0; gap: 2px 10px; }
  .nd-ligne__compte {
    font-size: 0.7rem;
    font-weight: 700;
    color: #fff;
    background: #374151;
    display: inline-flex;
    padding: 1px 7px;
    border-radius: 5px;
    letter-spacing: 0.2px;
  }
  .nd-ligne__desc { font-size: 0.7rem; margin-top: 2px; }
  .nd-ligne__montant { font-size: 0.82rem; }
  .nd-lignes-total {
    margin: 2px 14px 6px;
    padding: 5px 12px;
    background: var(--color-primary-lighter);
    border-radius: 7px;
    font-size: 0.78rem;
    color: var(--color-primary-dark);
  }

  .nd-pieces { padding: 0 14px 5px; gap: 0; }
  .nd-piece { padding: 3px 0; gap: 8px; }
  .nd-piece__nom { font-size: 0.72rem; }
  .nd-piece__meta { font-size: 0.6rem; }

  /* ── Timeline ─────────────────────────────────────────────── */
  .nd-timeline { padding: 1px 14px 6px; }
  .nd-timeline-item { padding: 3px 0; gap: 10px; break-inside: avoid; }
  .nd-timeline-item__dot { width: 8px; height: 8px; border-width: 2px; margin-top: 2px; }
  .nd-timeline-item:not(:last-child)::after { top: 17px; background: #d1d5db; }
  .nd-timeline-item__author { font-size: 0.74rem; }
  .nd-timeline-item__date { font-size: 0.6rem; }
  .nd-timeline-item__statut { font-size: 0.6rem; padding: 1px 8px; margin-bottom: 1px; }
  .nd-timeline-item__comment { font-size: 0.74rem; }

  /* ── Signatures ───────────────────────────────────────────── */
  /* Épinglées en pied de page (et non a la suite du contenu) : une note
     signee doit presenter ses approbations au meme endroit quel que soit
     son nombre de lignes. position:fixed est repris sur CHAQUE page si le
     document en compte plusieurs — c'est le comportement natif de Chrome en
     impression, il n'existe pas d'equivalent "derniere page seulement"
     fiable en CSS ; sans consequence ici, une note tient presque toujours
     sur une page. Le padding-bottom de .nd-page (plus bas) reserve la place
     necessaire pour que le contenu qui defile ne passe jamais dessous. */
  .nd-print-signatures {
    display: block;
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    background: #fff;
    padding: 10px 16px 9px;
    border: 1px solid #e5e7eb;
    border-radius: 10px;
    break-inside: avoid;
  }
  .nd-print-signatures__title {
    margin: 0 0 9px;
    font-size: 0.6rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.6px;
    color: #9ca3af;
  }
  /* wrap plutôt que d'écraser : 5 colonnes (Demandeur, Bénéficiaire, DFIN,
     DA, Caissier) sur une feuille A4 restent lisibles, mais mieux vaut
     passer sur deux lignes que les compresser sur un format plus étroit. */
  .nd-print-signatures__row { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 10px 14px; }
  /* Colonne en flex avec le trait pousse en bas (margin-top:auto) : seul le
     demandeur et le beneficiaire portent un nom pre-rempli, sans cela leur
     trait de signature se retrouvait plus bas que celui des approbateurs. */
  .nd-print-sign {
    flex: 1;
    min-width: 90px;
    text-align: center;
    display: flex;
    flex-direction: column;
    min-height: 42px;
  }
  .nd-print-sign__label {
    display: block;
    font-size: 0.64rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.4px;
    color: #374151;
  }
  .nd-print-sign__name {
    display: block;
    font-size: 0.7rem;
    color: #111827;
    margin: auto 0 2px;
  }
  .nd-print-sign__line { border-top: 1px solid #111827; margin-top: auto; }
  .nd-print-sign__name + .nd-print-sign__line { margin-top: 0; }
  .nd-print-sign__hint {
    display: block;
    font-size: 0.56rem;
    color: #9ca3af;
    font-style: italic;
    margin-top: 3px;
  }
}

/* ── Contrôle budgétaire ───────────────────────────────────── */
.nd-card--budget .nd-card__body { padding: 4px 20px 14px; }
.nd-justif { margin-top: 6px; }
.nd-justif--lecture p { margin: 4px 0 0; font-size: 0.85rem; color: #374151; white-space: pre-line; }
.nd-hint-budget { font-size: 0.78rem; color: #b45309; margin: 0 0 6px; font-weight: 600; }
</style>
