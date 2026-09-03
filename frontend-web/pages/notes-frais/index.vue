<script setup lang="ts">
// Ni le DRH (paie/RH, pas de notes personnelles) ni LOGISTIQUE (son suivi
// passe par son propre tableau de bord, /logistique) ne gerent leurs notes
// de frais via cet ecran (voir NavigationDrawer.vue, AVEC_NOTES_FRAIS) :
// garde repetee ici pour que l'URL directe /notes-frais reste elle aussi fermee.
definePageMeta({ roles: ['ADMIN', 'DG', 'DA', 'DFIN', 'DIRECTEUR', 'CAISSIER', 'COMPTABLE', 'GEST_PATRIMOINE', 'RESP_RESTAURANT'] })

interface NoteFrais {
  id: number
  reference: string
  objet: string
  montant: number
  devise?: string
  statut: string
  sens?: string | null
  priorite?: string | null
  createurNom?: string
}

const api = useApi()
const auth = useAuthStore()
const dialog = ref(false)
const filterStatut = ref<string | null>(null)
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')

const STATUTS_DECAISSEMENT = [
  'BROUILLON', 'SOUMISE', 'VERIFIEE_DFIN', 'VALIDEE_DA',
  'REJETEE_DA', 'TRANSMISE_CAISSE', 'PAYEE', 'ANNULEE',
]
// L'encaissement ne suit pas le circuit DFIN/DA : seuls les statuts
// atteignables (brouillon -> encaissée, ou annulée) sont proposés en filtre.
const STATUTS_ENCAISSEMENT = ['BROUILLON', 'PAYEE', 'ANNULEE']

// Le caissier "pur" (sans role DFIN/DA/DG/ADMIN) voit, cote backend, les
// notes deja validees par le DA quel qu'en soit le createur, plus ses
// propres notes a n'importe quel stade : tous les statuts sont donc des
// filtres valides pour lui (les siens en plus, ceux des autres createurs
// restreints comme avant).

const activeSens = ref<'DECAISSEMENT' | 'ENCAISSEMENT'>('DECAISSEMENT')
const peutCreerEncaissement = computed(() => auth.hasAnyRole(['CAISSIER', 'ADMIN']))
// Miroir de NoteFraisService.creer/soumettre cote backend : comptable et
// caissier sont desormais les deux seuls a pouvoir emettre une note de
// decaissement (le circuit DFIN/DA/DG reste reserve a la validation).
const peutCreerDecaissement = computed(() => auth.hasAnyRole(['COMPTABLE', 'CAISSIER']))

const statuts = computed(() => activeSens.value === 'ENCAISSEMENT' ? STATUTS_ENCAISSEMENT : STATUTS_DECAISSEMENT)

const notes = ref<NoteFrais[]>([])
const notesFiltrees = computed(() =>
  notes.value.filter(n => (n.sens || 'DECAISSEMENT') === activeSens.value)
)

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const query = filterStatut.value ? { statut: filterStatut.value } : undefined
    notes.value = await api<NoteFrais[]>('/notes-frais', { query })
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger les notes de frais.'
  } finally {
    loading.value = false
  }
}

onMounted(charger)
watch(filterStatut, charger)
// Changer d'onglet réinitialise le filtre statut : la liste de statuts
// proposée change (l'encaissement n'a pas de circuit DFIN/DA), un filtre
// devenu invalide laisserait sinon une grille vide sans chip actif visible.
watch(activeSens, () => { filterStatut.value = null })

const lignesVides = () => [{
  montant: null as number | null, compteImputation: null as string | null, description: '',
  achatMarchandise: false, quantiteMarchandise: null as number | null,
  articleId: null as number | null, articleNom: null as string | null,
  soumisTva: false,
}]

const form = reactive({
  objet: '', beneficiaire: '', devise: 'CDF', description: '', sens: 'DECAISSEMENT' as 'DECAISSEMENT' | 'ENCAISSEMENT',
  lignes: lignesVides(),
})
const devises = ['CDF', 'USD']

function ouvrirDialog() {
  form.sens = activeSens.value
  dialog.value = true
}

const lignesValides = computed(() =>
  form.lignes.filter(l => l.montant && l.montant > 0)
)
const peutCreer = computed(() => !!form.objet && !!form.beneficiaire && lignesValides.value.length > 0)

// ── Pièces jointes à joindre dès la création ──────────────────────────
const TYPES_AUTORISES = ['application/pdf', 'image/jpeg', 'image/jpg', 'image/png', 'image/webp', 'image/gif']
const TAILLE_MAX_OCTETS = 20 * 1024 * 1024
const fichiersAJoindre = ref<File[]>([])
const nouvelleNoteUploadInput = ref<HTMLInputElement | null>(null)

function declencherAjoutFichier() {
  nouvelleNoteUploadInput.value?.click()
}

function onFichiersChoisis(e: Event) {
  const input = e.target as HTMLInputElement
  const fichiers = Array.from(input.files || [])
  for (const f of fichiers) {
    if (!TYPES_AUTORISES.includes(f.type)) {
      erreur.value = `Type de fichier non autorisé pour « ${f.name} ». Formats acceptés : PDF, JPEG, PNG, WEBP, GIF.`
      continue
    }
    if (f.size > TAILLE_MAX_OCTETS) {
      erreur.value = `« ${f.name} » dépasse la taille maximale autorisée (20 Mo).`
      continue
    }
    fichiersAJoindre.value.push(f)
  }
  input.value = ''
}

function retirerFichier(index: number) {
  fichiersAJoindre.value = fichiersAJoindre.value.filter((_, i) => i !== index)
}

function fmtTailleFichier(o: number) {
  if (o < 1024) return `${o} o`
  if (o < 1024 * 1024) return `${(o / 1024).toFixed(0)} Ko`
  return `${(o / (1024 * 1024)).toFixed(1)} Mo`
}

function iconeFichier(type: string) {
  if (type === 'application/pdf') return 'mdi-file-pdf-box'
  if (type.startsWith('image/')) return 'mdi-file-image-outline'
  return 'mdi-file-outline'
}

// ── Capture photo (webcam sur desktop, caméra arrière sur mobile) ────────
// Un seul flux getUserMedia sert les deux cas : facingMode 'environment' est
// ignoré sans erreur sur un poste sans caméra arrière (webcam classique), et
// pris en compte sur un téléphone — évite de détecter la plateforme.
const dialogCamera = ref(false)
const cameraVideo = ref<HTMLVideoElement | null>(null)
const cameraCanvas = ref<HTMLCanvasElement | null>(null)
const cameraStream = ref<MediaStream | null>(null)
const erreurCamera = ref('')
const photoCapturee = ref<string | null>(null)

function messageErreurCamera(e: any): string {
  if (e?.name === 'NotAllowedError' || e?.name === 'PermissionDeniedError') {
    return "Accès à la caméra refusé. Autorisez-le dans les paramètres du navigateur pour ce site."
  }
  if (e?.name === 'NotFoundError' || e?.name === 'DevicesNotFoundError') {
    return 'Aucune caméra détectée sur cet appareil.'
  }
  if (e?.name === 'NotReadableError') {
    return 'La caméra est déjà utilisée par une autre application.'
  }
  if (import.meta.client && location.protocol !== 'https:' && location.hostname !== 'localhost') {
    return 'La caméra nécessite une connexion sécurisée (HTTPS).'
  }
  return "Impossible d'accéder à la caméra."
}

function arreterCamera() {
  cameraStream.value?.getTracks().forEach((t) => t.stop())
  cameraStream.value = null
}

async function ouvrirCamera() {
  erreurCamera.value = ''
  photoCapturee.value = null
  dialogCamera.value = true
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: 'environment' } },
      audio: false,
    })
    cameraStream.value = stream
    await nextTick()
    if (cameraVideo.value) cameraVideo.value.srcObject = stream
  } catch (e: any) {
    erreurCamera.value = messageErreurCamera(e)
  }
}

function fermerCamera() {
  dialogCamera.value = false
}
// Couvre aussi la fermeture par Échap/clic hors dialog (pas seulement le
// bouton de fermeture explicite) : une caméra qu'on oublie de couper reste
// un temoin allume tant que l'onglet est ouvert.
watch(dialogCamera, (ouvert) => {
  if (!ouvert) {
    arreterCamera()
    photoCapturee.value = null
  }
})
watch(dialog, (ouvert) => { if (!ouvert) dialogCamera.value = false })

function capturerPhoto() {
  const video = cameraVideo.value
  const canvas = cameraCanvas.value
  if (!video || !canvas || !video.videoWidth) return
  canvas.width = video.videoWidth
  canvas.height = video.videoHeight
  canvas.getContext('2d')?.drawImage(video, 0, 0)
  photoCapturee.value = canvas.toDataURL('image/jpeg', 0.92)
}

function reprendrePhoto() {
  photoCapturee.value = null
}

function utiliserPhoto() {
  const canvas = cameraCanvas.value
  if (!canvas) return
  canvas.toBlob((blob) => {
    if (!blob) return
    fichiersAJoindre.value.push(new File([blob], `photo-${Date.now()}.jpg`, { type: 'image/jpeg' }))
    fermerCamera()
  }, 'image/jpeg', 0.92)
}

function resetForm() {
  Object.assign(form, { objet: '', beneficiaire: '', devise: 'CDF', description: '', sens: activeSens.value, lignes: lignesVides() })
  fichiersAJoindre.value = []
}

const soumission = ref(false)

async function creerNote(executerEnsuite: boolean) {
  if (!peutCreer.value) {
    erreur.value = 'Renseignez un objet, le bénéficiaire et au moins une ligne avec un montant.'
    return
  }
  if (executerEnsuite) soumission.value = true
  else saving.value = true
  erreur.value = ''
  try {
    const note = await api<{ id: number }>('/notes-frais', {
      method: 'POST',
      body: {
        objet: form.objet,
        beneficiaire: form.beneficiaire,
        devise: form.devise,
        description: form.description || null,
        sens: form.sens,
        lignes: lignesValides.value.map(l => ({
          montant: l.montant,
          compteImputation: l.compteImputation || null,
          description: l.description || null,
          achatMarchandise: l.achatMarchandise,
          quantiteMarchandise: l.achatMarchandise ? l.quantiteMarchandise : null,
          articleId: l.achatMarchandise ? l.articleId : null,
          articleNom: l.achatMarchandise ? l.articleNom : null,
          soumisTva: l.soumisTva,
        })),
      },
    })

    // Envoi séquentiel des pièces jointes sélectionnées avant la création.
    let echecsUpload = 0
    for (const fichier of fichiersAJoindre.value) {
      try {
        const formData = new FormData()
        formData.append('fichier', fichier)
        await api(`/notes-frais/${note.id}/pieces-jointes`, { method: 'POST', body: formData })
      } catch {
        echecsUpload++
      }
    }

    // Exécution immédiate si demandée : soumission au DFIN pour un
    // décaissement, encaissement direct (sans validation) pour une recette
    // de caisse. Sinon la note reste en brouillon.
    if (executerEnsuite) {
      try {
        if (form.sens === 'ENCAISSEMENT') {
          await api(`/caisse/notes/${note.id}/encaisser`, { method: 'POST' })
        } else {
          await api(`/notes-frais/${note.id}/soumettre`, { method: 'POST', body: {} })
        }
      } catch (e: any) {
        erreur.value = e?.data?.message || "La note a été créée mais n'a pas pu être exécutée automatiquement."
      }
    }

    dialog.value = false
    resetForm()
    if (echecsUpload > 0) {
      erreur.value = `Note créée, mais ${echecsUpload} pièce(s) jointe(s) n'ont pas pu être envoyées. Réessayez depuis la fiche de la note.`
    }
    await navigateTo(`/notes-frais/${note.id}`)
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Echec de la creation de la note.'
  } finally {
    saving.value = false
    soumission.value = false
  }
}

// Mini stats (calculées sur l'onglet actif : décaissement et encaissement
// ne partagent pas le même circuit, les mélanger fausserait les compteurs).
const total = computed(() => notesFiltrees.value.length)
const enAttente = computed(() => notesFiltrees.value.filter(n => !['PAYEE','ANNULEE'].includes(n.statut)).length)
const payees = computed(() => notesFiltrees.value.filter(n => n.statut === 'PAYEE').length)
const labelEnAttente = computed(() => activeSens.value === 'ENCAISSEMENT' ? 'À encaisser' : 'En attente')
const labelPayees = computed(() => activeSens.value === 'ENCAISSEMENT' ? 'Encaissées' : 'Payées')
</script>

<template>
  <div class="nf-page">

    <!-- ── Header ──────────────────────────────────────────── -->
    <header class="nf-header">
      <div class="nf-header__left">
        <h1 class="page-title">Notes de frais</h1>
        <p class="page-sub">
          {{ activeSens === 'ENCAISSEMENT'
            ? 'Recette de caisse émise et encaissée directement par le caissier'
            : 'Circuit de validation DFIN → DA → Caisse' }}
        </p>
      </div>
      <v-btn
        v-if="(activeSens === 'DECAISSEMENT' && peutCreerDecaissement) || (activeSens === 'ENCAISSEMENT' && peutCreerEncaissement)"
        color="primary"
        prepend-icon="mdi-plus"
        rounded="lg"
        elevation="0"
        class="nf-header__btn"
        @click="ouvrirDialog"
      >
        {{ activeSens === 'ENCAISSEMENT' ? 'Nouvel encaissement' : 'Nouvelle note' }}
      </v-btn>
      <p v-else class="nf-header__hint">
        <v-icon icon="mdi-information-outline" size="15" class="mr-1" />
        {{ activeSens === 'ENCAISSEMENT'
          ? "Seul un caissier peut émettre une note d'encaissement."
          : 'Seuls le comptable et le caissier peuvent émettre une note de décaissement.' }}
      </p>
    </header>

    <!-- ── Onglets Décaissement / Encaissement ─────────────── -->
    <div class="nf-sens-tabs">
      <button
        class="nf-sens-tab"
        :class="{ 'nf-sens-tab--active': activeSens === 'DECAISSEMENT' }"
        @click="activeSens = 'DECAISSEMENT'"
      >
        <v-icon icon="mdi-cash-minus" size="17" class="mr-1" />
        Décaissement
      </button>
      <button
        class="nf-sens-tab"
        :class="{ 'nf-sens-tab--active': activeSens === 'ENCAISSEMENT' }"
        @click="activeSens = 'ENCAISSEMENT'"
      >
        <v-icon icon="mdi-cash-plus" size="17" class="mr-1" />
        Encaissement
      </button>
    </div>

    <!-- ── Mini stats strip ────────────────────────────────── -->
    <div class="nf-stats">
      <div class="nf-stat nf-stat--total">
        <v-icon icon="mdi-receipt-text-outline" size="18" />
        <span class="nf-stat__val">{{ total }}</span>
        <span class="nf-stat__lbl">Total</span>
      </div>
      <div class="nf-stat nf-stat--pending">
        <v-icon icon="mdi-clock-outline" size="18" />
        <span class="nf-stat__val">{{ enAttente }}</span>
        <span class="nf-stat__lbl">{{ labelEnAttente }}</span>
      </div>
      <div class="nf-stat nf-stat--paid">
        <v-icon icon="mdi-check-circle-outline" size="18" />
        <span class="nf-stat__val">{{ payees }}</span>
        <span class="nf-stat__lbl">{{ labelPayees }}</span>
      </div>
    </div>

    <!-- ── Filter bar ──────────────────────────────────────── -->
    <div class="nf-filter">
      <v-icon icon="mdi-filter-outline" size="18" color="#9ca3af" />
      <div class="nf-filter__chips">
        <button
          class="nf-filter__chip"
          :class="{ 'nf-filter__chip--active': filterStatut === null }"
          :style="filterStatut === null ? { background: '#16a34a', borderColor: '#16a34a', color: '#fff' } : {}"
          @click="filterStatut = null"
        >
          Tous
        </button>
        <button
          v-for="s in statuts"
          :key="s"
          class="nf-filter__chip"
          :class="{ 'nf-filter__chip--active': filterStatut === s }"
          :style="filterStatut === s
            ? { background: statutNoteMeta(s, activeSens).color, borderColor: statutNoteMeta(s, activeSens).color, color: '#fff' }
            : { background: statutNoteMeta(s, activeSens).bg, color: statutNoteMeta(s, activeSens).text, borderColor: statutNoteMeta(s, activeSens).bg }"
          @click="filterStatut = s"
        >
          <span class="nf-filter__dot" :style="{ background: filterStatut === s ? '#fff' : statutNoteMeta(s, activeSens).color }" />
          {{ statutNoteMeta(s, activeSens).label }}
        </button>
      </div>
    </div>

    <!-- ── Error ───────────────────────────────────────────── -->
    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <!-- ── Cards grid ──────────────────────────────────────── -->
    <v-skeleton-loader v-if="loading" type="card, card, card" />

    <div v-else-if="notesFiltrees.length > 0" class="nf-grid">
      <CardsNoteFraisCard
        v-for="n in notesFiltrees"
        :key="n.id"
        :note="n"
        @open="navigateTo(`/notes-frais/${n.id}`)"
      />
    </div>

    <div v-else-if="!loading" class="nf-empty">
      <v-icon :icon="activeSens === 'ENCAISSEMENT' ? 'mdi-cash-plus' : 'mdi-receipt-text-outline'" size="48" color="#d1d5db" />
      <p class="nf-empty__title">
        {{ activeSens === 'ENCAISSEMENT' ? "Aucune note d'encaissement" : 'Aucune note de frais' }}
      </p>
      <p class="nf-empty__sub">
        {{ filterStatut
          ? 'Aucune note ne correspond à ce statut.'
          : (activeSens === 'ENCAISSEMENT' ? 'Créez votre première note d\'encaissement.' : 'Créez votre première note de frais.') }}
      </p>
    </div>

    <!-- ── Dialog nouvelle note ────────────────────────────── -->
    <v-dialog v-model="dialog" max-width="720" scrollable>
      <div class="nf-dialog">
        <!-- Dialog header gradient -->
        <div class="nf-dialog__head" :class="{ 'nf-dialog__head--encaissement': form.sens === 'ENCAISSEMENT' }">
          <div class="nf-dialog__head-blob" />
          <div class="nf-dialog__head-icon">
            <v-icon :icon="form.sens === 'ENCAISSEMENT' ? 'mdi-cash-plus' : 'mdi-receipt-text-plus-outline'" size="22" color="white" />
          </div>
          <div>
            <p class="nf-dialog__head-title">
              {{ form.sens === 'ENCAISSEMENT' ? "Nouvelle note d'encaissement" : 'Nouvelle note de frais' }}
            </p>
            <p class="nf-dialog__head-sub">
              {{ form.sens === 'ENCAISSEMENT'
                ? 'Recette de caisse encaissée directement, sans validation DFIN/DA'
                : 'Une note peut regrouper plusieurs dépenses' }}
            </p>
          </div>
          <button class="nf-dialog__close" @click="dialog = false">
            <v-icon icon="mdi-close" size="18" />
          </button>
        </div>

        <div class="nf-dialog__body">
          <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" density="compact" class="mb-4">{{ erreur }}</v-alert>

          <div class="nf-row">
            <div class="nf-field" style="flex:2">
              <label class="nf-label">Objet de la note *</label>
              <v-text-field v-model="form.objet" placeholder="ex: Mission terrain Kinshasa" hide-details="auto" />
            </div>
            <div class="nf-field" style="flex:1">
              <label class="nf-label">Devise</label>
              <v-select v-model="form.devise" :items="devises" hide-details />
            </div>
          </div>

          <div class="nf-field">
            <label class="nf-label">Bénéficiaire *</label>
            <v-text-field v-model="form.beneficiaire" placeholder="Nom de la personne ou de l'entité payée" hide-details="auto" />
          </div>

          <div class="nf-field">
            <label class="nf-label">Description générale</label>
            <v-textarea v-model="form.description" rows="2" placeholder="Détails supplémentaires..." hide-details="auto" />
          </div>

          <NotesFraisLignesNoteFrais v-model="form.lignes" :devise="form.devise" :sens="form.sens" />

          <div class="nf-field">
            <div class="nf-pieces__head">
              <label class="nf-label">Pièces justificatives</label>
              <div class="nf-pieces__actions">
                <v-btn size="small" variant="tonal" color="primary" prepend-icon="mdi-paperclip" @click="declencherAjoutFichier">
                  Joindre un fichier
                </v-btn>
                <v-btn size="small" variant="tonal" color="primary" prepend-icon="mdi-camera-outline" @click="ouvrirCamera">
                  Prendre une photo
                </v-btn>
              </div>
              <input
                ref="nouvelleNoteUploadInput"
                type="file"
                accept="application/pdf,image/jpeg,image/png,image/webp,image/gif"
                multiple
                class="nf-hidden-input"
                @change="onFichiersChoisis"
              >
            </div>

            <div v-if="fichiersAJoindre.length === 0" class="nf-pieces__empty">
              Aucun fichier sélectionné. Formats acceptés : PDF, JPEG, PNG, WEBP, GIF (20 Mo max).
            </div>
            <div v-else class="nf-pieces__list">
              <div v-for="(f, i) in fichiersAJoindre" :key="i" class="nf-pieces__item">
                <v-icon :icon="iconeFichier(f.type)" size="20" color="#6b7280" />
                <div class="nf-pieces__info">
                  <span class="nf-pieces__nom">{{ f.name }}</span>
                  <span class="nf-pieces__taille">{{ fmtTailleFichier(f.size) }}</span>
                </div>
                <v-btn icon="mdi-close" variant="text" size="x-small" @click="retirerFichier(i)" />
              </div>
            </div>
          </div>
        </div>

        <div class="nf-dialog__footer">
          <button class="nf-btn nf-btn--ghost" :disabled="saving || soumission" @click="dialog = false">Annuler</button>
          <v-btn
            variant="outlined"
            color="primary"
            rounded="lg"
            prepend-icon="mdi-content-save-outline"
            :loading="saving"
            :disabled="!peutCreer || soumission"
            @click="creerNote(false)"
          >
            Enregistrer en brouillon
          </v-btn>
          <v-btn
            color="primary"
            rounded="lg"
            elevation="0"
            :prepend-icon="form.sens === 'ENCAISSEMENT' ? 'mdi-cash-check' : 'mdi-send-circle-outline'"
            :loading="soumission"
            :disabled="!peutCreer || saving"
            @click="creerNote(true)"
          >
            {{ form.sens === 'ENCAISSEMENT' ? 'Créer et encaisser' : 'Créer et soumettre' }}
          </v-btn>
        </div>
      </div>
    </v-dialog>

    <!-- ── Dialog capture photo (webcam / caméra mobile) ────────────── -->
    <v-dialog v-model="dialogCamera" max-width="520" persistent>
      <div class="nf-dialog">
        <div class="nf-dialog__head">
          <div class="nf-dialog__head-blob" />
          <div class="nf-dialog__head-icon">
            <v-icon icon="mdi-camera-outline" size="22" color="white" />
          </div>
          <div>
            <p class="nf-dialog__head-title">Prendre une photo</p>
            <p class="nf-dialog__head-sub">Webcam sur ordinateur, caméra arrière sur mobile</p>
          </div>
          <button class="nf-dialog__close" @click="fermerCamera">
            <v-icon icon="mdi-close" size="18" />
          </button>
        </div>

        <div class="nf-dialog__body">
          <v-alert v-if="erreurCamera" type="error" variant="tonal" rounded="lg" density="compact">
            {{ erreurCamera }}
          </v-alert>

          <template v-else>
            <div class="nf-camera__viewport">
              <video v-show="!photoCapturee" ref="cameraVideo" autoplay muted playsinline class="nf-camera__media" />
              <img v-if="photoCapturee" :src="photoCapturee" alt="Photo capturée" class="nf-camera__media">
            </div>
            <canvas ref="cameraCanvas" class="nf-camera__canvas" />
          </template>
        </div>

        <div class="nf-dialog__footer">
          <template v-if="erreurCamera">
            <button class="nf-btn nf-btn--ghost" @click="fermerCamera">Fermer</button>
          </template>
          <template v-else-if="!photoCapturee">
            <button class="nf-btn nf-btn--ghost" @click="fermerCamera">Annuler</button>
            <v-btn color="primary" rounded="lg" elevation="0" prepend-icon="mdi-camera" @click="capturerPhoto">
              Capturer
            </v-btn>
          </template>
          <template v-else>
            <button class="nf-btn nf-btn--ghost" @click="reprendrePhoto">Reprendre</button>
            <v-btn color="primary" rounded="lg" elevation="0" prepend-icon="mdi-check" @click="utiliserPhoto">
              Utiliser cette photo
            </v-btn>
          </template>
        </div>
      </div>
    </v-dialog>
  </div>
</template>

<style scoped>
.nf-page { max-width: 1200px; margin: 0 auto; padding-bottom: 48px; }

/* ── Header ──────────────────────────────────────────────── */
.nf-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 24px;
}
.nf-header__btn { flex-shrink: 0; height: 44px !important; }
.nf-header__hint {
  display: flex;
  align-items: center;
  font-size: 0.8rem;
  color: #9ca3af;
  margin: 0;
  flex-shrink: 0;
}

/* ── Onglets sens (Décaissement / Encaissement) ─────────────── */
.nf-sens-tabs {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  background: #f3f4f6;
  border-radius: 12px;
  margin-bottom: 20px;
}
.nf-sens-tab {
  display: inline-flex;
  align-items: center;
  font-size: 0.84rem;
  font-weight: 600;
  padding: 8px 16px;
  border-radius: 9px;
  border: none;
  background: transparent;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.15s;
}
.nf-sens-tab:hover { color: #374151; }
.nf-sens-tab--active {
  background: #fff;
  color: #111827;
  box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}

/* ── Mini stats ──────────────────────────────────────────── */
.nf-stats {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}
.nf-stat {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 18px;
  border-radius: 12px;
  font-size: 0.875rem;
}
.nf-stat--total   { background: linear-gradient(135deg,#f0fdf4,#dcfce7); color: #15803d; }
.nf-stat--pending { background: linear-gradient(135deg,#fff7ed,#ffedd5); color: #c2410c; }
.nf-stat--paid    { background: linear-gradient(135deg,#eff6ff,#dbeafe); color: #1d4ed8; }
.nf-stat__val { font-size: 1.1rem; font-weight: 800; letter-spacing: -0.5px; }
.nf-stat__lbl { font-weight: 500; opacity: 0.75; }

/* ── Filter bar ──────────────────────────────────────────── */
.nf-filter {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 24px;
  overflow-x: auto;
  padding-bottom: 4px;
}
.nf-filter__chips { display: flex; gap: 6px; flex-wrap: nowrap; }
.nf-filter__chip {
  font-size: 0.78rem;
  font-weight: 600;
  padding: 6px 14px;
  border-radius: 100px;
  border: 1.5px solid #e5e7eb;
  background: #fff;
  color: #6b7280;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s;
}
.nf-filter__chip:hover { filter: brightness(0.95); }
.nf-filter__chip--active { font-weight: 700; }
.nf-filter__dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 5px;
}

/* ── Grid ────────────────────────────────────────────────── */
.nf-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

/* ── Empty ───────────────────────────────────────────────── */
.nf-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 72px 24px;
  background: #fff;
  border: 1.5px dashed #e5e7eb;
  border-radius: 20px;
  text-align: center;
}
.nf-empty__title { font-size: 1rem; font-weight: 600; color: #374151; margin: 0; }
.nf-empty__sub   { font-size: 0.85rem; color: #9ca3af; margin: 0; }

/* ── Dialog ──────────────────────────────────────────────── */
.nf-dialog {
  background: #fff;
  border-radius: 20px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  max-height: 88vh;
}

.nf-dialog__head {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 24px 24px 22px;
  background: linear-gradient(140deg, #22c55e 0%, #16a34a 50%, #14532d 100%);
  flex-shrink: 0;
}
.nf-dialog__head--encaissement {
  background: linear-gradient(140deg, #22d3ee 0%, #0891b2 50%, #164e63 100%);
}
.nf-dialog__head-blob {
  position: absolute;
  width: 120px; height: 120px;
  border-radius: 50%;
  background: rgba(255,255,255,0.09);
  top: -30px; right: -30px;
}
.nf-dialog__head-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px; height: 46px;
  border-radius: 12px;
  background: rgba(255,255,255,0.18);
  backdrop-filter: blur(6px);
  border: 1px solid rgba(255,255,255,0.22);
  flex-shrink: 0;
  position: relative; z-index: 1;
}
.nf-dialog__head-title {
  font-size: 1rem;
  font-weight: 700;
  color: #fff;
  margin: 0 0 2px;
  position: relative; z-index: 1;
}
.nf-dialog__head-sub {
  font-size: 0.8rem;
  color: rgba(255,255,255,0.72);
  margin: 0;
  position: relative; z-index: 1;
}
.nf-dialog__close {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px; height: 32px;
  border-radius: 8px;
  background: rgba(255,255,255,0.15);
  border: none;
  color: rgba(255,255,255,0.80);
  cursor: pointer;
  position: relative; z-index: 1;
  transition: background 0.15s;
}
.nf-dialog__close:hover { background: rgba(255,255,255,0.25); }

.nf-dialog__body {
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow-y: auto;
  flex: 1 1 auto;
  min-height: 0;
  scrollbar-width: thin;
  scrollbar-color: #d1d5db transparent;
}
.nf-dialog__body::-webkit-scrollbar { width: 8px; }
.nf-dialog__body::-webkit-scrollbar-track { background: transparent; }
.nf-dialog__body::-webkit-scrollbar-thumb { background: #d1d5db; border-radius: 8px; }
.nf-dialog__body::-webkit-scrollbar-thumb:hover { background: #9ca3af; }

.nf-row { display: flex; gap: 12px; }
.nf-field { display: flex; flex-direction: column; gap: 6px; }
.nf-label { font-size: 0.8125rem; font-weight: 600; color: #374151; }
.nf-hint {
  display: flex;
  align-items: center;
  font-size: 0.78rem;
  color: #9ca3af;
  margin: 0;
}

/* ── Pièces jointes (création) ──────────────────────────────── */
.nf-hidden-input { display: none; }
.nf-pieces__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.nf-pieces__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

/* ── Capture photo ─────────────────────────────────────────────────── */
.nf-camera__viewport {
  position: relative;
  width: 100%;
  aspect-ratio: 4 / 3;
  background: #111827;
  border-radius: 12px;
  overflow: hidden;
}
.nf-camera__media {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.nf-camera__canvas { display: none; }
.nf-pieces__empty {
  font-size: 0.78rem;
  color: #9ca3af;
  padding: 14px;
  text-align: center;
  border: 1.5px dashed #e5e7eb;
  border-radius: 12px;
}
.nf-pieces__list { display: flex; flex-direction: column; gap: 6px; }
.nf-pieces__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid #f0f0f0;
  border-radius: 10px;
  background: #fafafa;
}
.nf-pieces__info { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.nf-pieces__nom {
  font-size: 0.82rem;
  font-weight: 600;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nf-pieces__taille { font-size: 0.7rem; color: #9ca3af; }

.nf-dialog__footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 24px;
  border-top: 1px solid #f3f4f6;
  flex-wrap: wrap;
  flex-shrink: 0;
}

.nf-btn--ghost {
  font-size: 0.875rem;
  font-weight: 600;
  padding: 10px 20px;
  border-radius: 10px;
  background: none;
  border: 1.5px solid #e5e7eb;
  color: #6b7280;
  cursor: pointer;
  transition: background 0.15s;
}
.nf-btn--ghost:hover { background: #f9fafb; }
.nf-btn--ghost:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
