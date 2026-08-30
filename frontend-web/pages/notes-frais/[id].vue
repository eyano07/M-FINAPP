<script setup lang="ts">
interface Observation {
  auteurNom?: string
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
  demandeurEmail?: string
  lignes: LigneNoteFrais[]
  piecesJointes: PieceJointe[]
  observations: Observation[]
}

const route = useRoute()
const api = useApi()
const auth = useAuthStore()
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
    erreur.value = e?.data?.message || 'Note introuvable.'
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

const montantFmt = computed(() =>
  note.value
    ? new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(note.value.montant)
      + ' ' + (note.value.devise || 'CDF')
    : ''
)

const statut = computed(() => note.value?.statut)
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
const peutModifierComptes = computed(() => isDFIN.value && statut.value === 'SOUMISE')
const peutValiderRejeter = computed(() => isDA.value && statut.value === 'VERIFIEE_DFIN')
const peutPrioriser    = computed(() => isDA.value && ['VALIDEE_DA', 'TRANSMISE_CAISSE'].includes(statut.value || ''))
const peutTransmettre  = computed(() => isDFIN.value && statut.value === 'VALIDEE_DA')
const peutAnnuler      = computed(() =>
  auth.hasAnyRole(['DIRECTEUR','COMPTABLE','CAISSIER','DFIN','ADMIN']) &&
  ['BROUILLON','SOUMISE','VERIFIEE_DFIN','VALIDEE_DA','REJETEE_DA'].includes(statut.value || ''))

/** Message contextuel quand aucune action n'est disponible. */
const messageAucuneAction = computed(() => {
  const s = statut.value || ''
  if (s === 'BROUILLON' && estEncaissement.value) return "Seul le caissier peut encaisser cette note directement (sans validation DFIN/DA)."
  if (s === 'TRANSMISE_CAISSE') return 'Cette note est transmise à la trésorerie et attend son paiement (caisse, banque ou mobile money).'
  if (s === 'PAYEE')            return estEncaissement.value ? 'Cette note a déjà été encaissée. Aucune action requise.' : 'Cette note a déjà été payée. Aucune action requise.'
  if (s === 'ANNULEE')          return 'Cette note est annulée.'
  if (s === 'SOUMISE')
    return 'En attente de vérification par le DFIN.'
  if (s === 'VERIFIEE_DFIN' && !isDA.value)
    return 'Note vérifiée par le DFIN. En attente de validation par le DA.'
  if (s === 'VALIDEE_DA' && !isDFIN.value)
    return 'Note validée par le DA. En attente de transmission à la trésorerie par le DFIN.'
  return 'Aucune action disponible pour votre rôle à ce stade.'
})

async function action(chemin: string, requiertCommentaire = false) {
  if (requiertCommentaire && !observation.value.trim()) {
    erreur.value = 'Un motif est obligatoire pour cette action.'
    return
  }
  busy.value = true
  erreur.value = ''
  try {
    note.value = await api<NoteDetail>(`/notes-frais/${id.value}/${chemin}`, {
      method: 'POST',
      body: { commentaire: observation.value || null },
    })
    observation.value = ''
    prioriteChoisie.value = note.value?.priorite ?? null
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
    note.value = await api<NoteDetail>(`/notes-frais/${id.value}/priorite`, {
      method: 'POST',
      body: { priorite: prioriteChoisie.value, commentaire: observation.value || null },
    })
    observation.value = ''
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

    <v-skeleton-loader v-if="loading" type="card, article" class="mt-4" />

    <div v-else-if="note" class="nd-layout">

      <!-- Entête d'impression (visible uniquement sur le document imprimé) -->
      <div class="nd-print-header">
        <div class="nd-print-header__brand">
          <div class="nd-print-header__logo" :class="{ 'nd-print-header__logo--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
            <v-icon v-else icon="mdi-finance" size="16" color="white" />
          </div>
          <div>
            <span class="nd-print-header__company">{{ parametresStore.parametres.nom }}</span>
            <span class="nd-print-header__doc">{{ estEncaissement ? "Note d'encaissement" : 'Note de frais' }}</span>
          </div>
        </div>
        <div class="nd-print-header__meta">
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
              <span v-if="note.priorite" class="nd-badge"
                :style="{ background: prioMeta[note.priorite]?.bg, color: prioMeta[note.priorite]?.color }">
                Priorité {{ prioMeta[note.priorite]?.label }}
              </span>
            </div>
          </div>

          <div class="nd-hero__body">
            <h1 class="nd-hero__objet">{{ note.objet }}</h1>
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
                <span class="nd-info-val">{{ note.demandeurNom || '—' }}</span>
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
              <div v-if="editionComptes" class="nd-ligne__compte-edit nd-noprint">
                <ComptabiliteSelecteurCompte
                  v-model="comptesEdites[l.id]"
                  label="Compte d'imputation"
                  :disabled="enregistrementComptes"
                />
              </div>
              <div v-else class="nd-ligne__compte">
                <v-icon icon="mdi-book-open-variant" size="15" class="mr-1" />
                {{ l.compteImputation ? `${l.compteImputation} — ${l.compteImputationLibelle || ''}` : (estEncaissement ? 'Compte par défaut (758)' : 'Compte par défaut (6588)') }}
              </div>
              <p v-if="l.description" class="nd-ligne__desc">{{ l.description }}</p>
              <div v-if="l.achatMarchandise || l.soumisTva" class="nd-ligne__badges">
                <v-chip v-if="l.achatMarchandise" size="x-small" variant="tonal" color="teal" prepend-icon="mdi-package-variant">
                  {{ l.articleLibelle || 'Marchandise' }}{{ l.quantiteMarchandise ? ` · ${l.quantiteMarchandise} × ${fmtMontant(l.montant)}` : '' }}{{ l.entrepotNom ? ` · ${l.entrepotNom}` : '' }}
                </v-chip>
                <v-chip v-if="l.soumisTva" size="x-small" variant="tonal" color="indigo" prepend-icon="mdi-percent-outline">
                  TVA récupérable{{ l.compteTva ? ` · ${l.compteTva}` : '' }}
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

        <!-- Pièces jointes -->
        <div class="nd-card">
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

        <!-- Timeline -->
        <div class="nd-card">
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
                  <strong class="nd-timeline-item__author">{{ o.auteurNom || o.auteur }}</strong>
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
            <template v-if="!estEncaissement">
              <div class="nd-print-sign">
                <span class="nd-print-sign__label">Vérifié DFIN</span>
                <div class="nd-print-sign__line" />
                <span class="nd-print-sign__hint">Signature et cachet</span>
              </div>
              <div class="nd-print-sign">
                <span class="nd-print-sign__label">Validé DA</span>
                <div class="nd-print-sign__line" />
                <span class="nd-print-sign__hint">Signature et cachet</span>
              </div>
            </template>
            <div class="nd-print-sign">
              <span class="nd-print-sign__label">Caissier</span>
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
                prepend-icon="mdi-check-circle" :loading="busy" @click="action('verifier')">
                Vérifier (DFIN)
              </v-btn>

              <template v-if="peutValiderRejeter">
                <v-btn color="success" block rounded="lg" elevation="0"
                  prepend-icon="mdi-check" :loading="busy" @click="action('valider')">
                  Valider (DA)
                </v-btn>
                <v-btn color="error" block rounded="lg" variant="tonal"
                  prepend-icon="mdi-close" :loading="busy" @click="action('rejeter', true)">
                  Rejeter (DA)
                </v-btn>
              </template>

              <template v-if="peutPrioriser">
                <div class="nd-divider" />
                <div class="nd-field">
                  <label class="nd-label">Priorité de paiement (DA)</label>
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
                prepend-icon="mdi-send" :loading="busy" @click="action('transmettre')">
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
  margin: 0 0 10px;
  line-height: 1.3;
}
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
  color: #16a34a;
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
  background: #16a34a;
  border: 3px solid #dcfce7;
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
.nd-timeline-item__author { font-size: 0.875rem; color: #111827; }
.nd-timeline-item__date   { font-size: 0.72rem; color: #9ca3af; white-space: nowrap; }
.nd-timeline-item__statut {
  display: inline-block;
  font-size: 0.68rem;
  font-weight: 700;
  color: #16a34a;
  background: #dcfce7;
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

  .nd-page { max-width: 100%; padding: 0; font-size: 12.5px; color: #1f2937; }
  .nd-layout { display: block; }
  .nd-left { width: 100%; }

  /* ── Entête à en-tête ─────────────────────────────────────── */
  .nd-print-header {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 16px;
    padding-bottom: 12px;
    margin-bottom: 14px;
    border-bottom: 3px solid #16a34a;
  }
  .nd-print-header__brand { display: flex; align-items: center; gap: 11px; }
  .nd-print-header__logo {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    border-radius: 10px;
    background: #16a34a;
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
    color: #16a34a;
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
    border-radius: 12px;
    margin-bottom: 10px;
    padding: 14px 18px;
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
    font-size: 0.85rem;
    font-weight: 600;
    margin-bottom: 2px;
    opacity: 0.85;
  }
  .nd-hero__montant {
    color: var(--print-text, #111827) !important;
    font-size: 1.6rem;
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
    border-radius: 12px;
    margin-bottom: 9px;
  }
  /* Le circuit d'approbation peut compter de nombreuses etapes : on
     l'autorise a se repartir sur plusieurs pages plutot que de forcer toute
     la carte (et son entete) sur une page neuve, ce qui laissait un grand
     vide en bas de la precedente. Chaque etape individuelle reste en revanche
     insecable (regle plus bas). */
  .nd-card:has(.nd-timeline) { break-inside: auto; }

  .nd-card__section-title {
    padding: 8px 14px 6px 27px;
    font-size: 0.62rem;
    position: relative;
  }
  .nd-card__section-title::before {
    content: '';
    position: absolute;
    left: 14px;
    top: 11px;
    width: 7px;
    height: 7px;
    border-radius: 2px;
    background: #16a34a;
  }
  .nd-card__section-title--row { padding-bottom: 8px; }

  .nd-info-grid { padding: 0; }
  .nd-info-row { padding: 6px 14px; gap: 8px; }
  .nd-info-row:hover { background: none; }
  .nd-info-icon { width: 22px; height: 22px; background: #f3f4f6 !important; }
  .nd-info-label { font-size: 0.62rem; margin-bottom: 0; }
  .nd-info-val { font-size: 0.78rem; }

  /* ── Lignes de depense : presentation facture ─────────────── */
  .nd-lignes { padding: 2px 14px; }
  .nd-ligne { padding: 7px 0; gap: 2px 10px; }
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
    margin: 4px 14px 10px;
    padding: 8px 12px;
    background: #f0fdf4;
    border-radius: 8px;
    font-size: 0.8rem;
    color: #15803d;
  }

  .nd-pieces { padding: 0 14px 8px; gap: 0; }
  .nd-piece { padding: 5px 0; gap: 8px; }
  .nd-piece__nom { font-size: 0.74rem; }
  .nd-piece__meta { font-size: 0.62rem; }

  /* ── Timeline ─────────────────────────────────────────────── */
  .nd-timeline { padding: 3px 14px 10px; }
  .nd-timeline-item { padding: 5px 0; gap: 10px; break-inside: avoid; }
  .nd-timeline-item__dot { width: 9px; height: 9px; border-width: 2px; margin-top: 2px; }
  .nd-timeline-item:not(:last-child)::after { top: 20px; background: #d1d5db; }
  .nd-timeline-item__author { font-size: 0.76rem; }
  .nd-timeline-item__date { font-size: 0.62rem; }
  .nd-timeline-item__statut { font-size: 0.62rem; padding: 1px 8px; margin-bottom: 2px; }
  .nd-timeline-item__comment { font-size: 0.76rem; }

  /* ── Signatures ───────────────────────────────────────────── */
  .nd-print-signatures {
    display: block;
    margin-top: 20px;
    padding: 16px 18px 14px;
    border: 1px solid #e5e7eb;
    border-radius: 12px;
    break-inside: avoid;
  }
  .nd-print-signatures__title {
    margin: 0 0 16px;
    font-size: 0.62rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.6px;
    color: #9ca3af;
  }
  .nd-print-signatures__row { display: flex; justify-content: space-between; gap: 16px; }
  /* Colonne en flex avec le trait pousse en bas (margin-top:auto) : seul le
     demandeur porte un nom pre-rempli, sans cela son trait de signature se
     retrouvait plus bas que celui des autres approbateurs. */
  .nd-print-sign {
    flex: 1;
    text-align: center;
    display: flex;
    flex-direction: column;
    min-height: 58px;
  }
  .nd-print-sign__label {
    display: block;
    font-size: 0.66rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.4px;
    color: #374151;
  }
  .nd-print-sign__name {
    display: block;
    font-size: 0.72rem;
    color: #111827;
    margin: auto 0 3px;
  }
  .nd-print-sign__line { border-top: 1px solid #111827; margin-top: auto; }
  .nd-print-sign__name + .nd-print-sign__line { margin-top: 0; }
  .nd-print-sign__hint {
    display: block;
    font-size: 0.58rem;
    color: #9ca3af;
    font-style: italic;
    margin-top: 4px;
  }
}
</style>
