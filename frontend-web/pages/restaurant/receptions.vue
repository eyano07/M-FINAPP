<script setup lang="ts">
const rolesStore = useRolesStore()
definePageMeta({ module: 'RESTAURANT' })

/**
 * Demande d'achat de boissons, saisie en casiers.
 *
 * Contrairement à l'ancienne réception directe, cet écran ne touche plus le
 * stock : il crée une note de frais spéciale (achat de marchandise, une ou
 * plusieurs lignes, article BOISSON) et la soumet aussitôt au circuit habituel
 * Création → DFIN → DA → DFIN → Caisse. L'entrée en stock des bouteilles
 * pleines et, si demandé, l'échange de consigne des vides ne s'exécutent
 * qu'au moment où le caissier règle effectivement la note (voir
 * CaisseService.payerNote côté serveur) — jamais avant.
 *
 * <p>Plusieurs boissons peuvent être ajoutées à un même panier (ex. plusieurs
 * produits achetés chez un même fournisseur en une seule fois) : chaque
 * produit devient sa propre ligne de la note, avec son propre article,
 * quantité, prix et échange de consigne — voir
 * NoteFraisService.validerNoteRespRestaurant côté serveur, qui valide
 * chaque ligne individuellement plutôt que d'imposer une ligne unique.</p>
 *
 * <p>Le prix d'achat se saisit dans la devise réellement quotée par le
 * fournisseur (USD ou FC) — jamais convertie côté client. La note porte
 * cette devise telle quelle, commune à toutes les lignes du panier : un prix
 * en USD ne subit ensuite aucune conversion (risque de change nul) ; un prix
 * en FC suit le circuit habituel des notes de frais (taux figé à la
 * transmission, écart de change constaté au règlement) — le même mécanisme
 * que pour toute autre dépense de l'entreprise, plutôt qu'un taux du jour de
 * la saisie appliqué a posteriori à un règlement qui peut intervenir
 * plusieurs jours plus tard.</p>
 */
interface Emballage {
  id: number
  articleBoissonId: number
  articleBoissonCode: string
  articleBoissonLibelle: string
  contenanceCasier: number
  bouteillesVides: number
  casiers: number
  bouteillesRestantes: number
}
interface Entrepot { id: number; code: string; nom: string }
interface NoteFrais {
  id: number
  reference: string
  objet: string
  montant: number
  devise?: 'USD' | 'CDF'
  statut: string
  dateCreation: string
}
interface LignePanier {
  articleBoissonId: number
  articleLibelle: string
  nbCasiers: number | null
  nbBouteillesSupp: number | null
  nbBouteilles: number
  detailQuantite: string
  prixUnitaire: number
  entrepotId: number
  entrepotLabel: string
  echangeConsigne: boolean
}

const api = useApi()
/** Formatage des montants affichés (store du restaurant). */
const parametresRestaurant = useRestaurantParametresStore()
onMounted(() => { parametresRestaurant.charger() })
const auth = useAuthStore()
const saving = ref(false)
const loading = ref(false)
const loadingHistorique = ref(false)
const erreur = ref('')
const succes = ref('')
const emballages = ref<Emballage[]>([])
const entrepots = ref<Entrepot[]>([])
const historique = ref<NoteFrais[]>([])
const tauxChange = ref(0)
const panier = ref<LignePanier[]>([])

// /notes-frais retourne TOUTES les notes de l'utilisateur (le responsable
// restaurant peut aussi en soumettre d'autres, hors de cet écran) : on ne
// garde que celles issues de cette page, reconnaissables à l'objet genere
// automatiquement par construireObjet() ci-dessous, jamais saisi librement.
const historiqueBoissons = computed(() =>
  historique.value.filter(n => n.objet?.startsWith('Achat de boissons')))

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT', 'ADMIN']))

// Devise et entrepôt sont communs à tout le panier (une seule note, un seul
// fournisseur réglé en une fois) : la devise se verrouille dès la première
// ligne ajoutée pour eviter qu'un changement en cours de saisie ne fausse
// silencieusement le prix des lignes déjà ajoutées (voir ajouterAuPanier).
const form = reactive({
  articleBoissonId: null as number | null,
  nbCasiers: null as number | null,
  nbBouteillesSupp: null as number | null,
  devisePrix: 'CDF' as 'USD' | 'CDF',
  prixUnitaire: null as number | null,
  entrepotId: null as number | null,
  beneficiaire: '',
  /** Transport et manutention de la livraison, dans la devise de la note. */
  fraisApproche: null as number | null,
  description: '',
  echangeConsigne: true,
})

/** Facture proforma ou justificatif fournisseur, joint à la note dès sa création. */
const fichierJoint = ref<File | null>(null)
/** Change à chaque envoi : recrée le champ fichier, qui sinon afficherait encore le justificatif déjà envoyé. */
const cleChampFichier = ref(0)
/** Même plafond que NoteFraisService.ajouterPieceJointe, vérifié AVANT de créer la note. */
const TAILLE_MAX_JUSTIFICATIF = 20 * 1024 * 1024
/** Note créée dont une étape suivante (justificatif, transmission) a échoué : à terminer depuis Notes de frais. */
const noteInachevee = ref<{ id: number; reference: string; etape: string } | null>(null)
function onFichierJoint(f: File | File[] | null) {
  fichierJoint.value = Array.isArray(f) ? (f[0] ?? null) : f
}

/** Équivalent indicatif dans l'autre devise, au taux du jour — n'influence ni la saisie ni l'enregistrement. */
const prixEquivalent = computed(() => {
  if (!form.prixUnitaire || tauxChange.value <= 0) return null
  return form.devisePrix === 'USD'
    ? `≈ ${parametresRestaurant.fmtDans(form.prixUnitaire * tauxChange.value, 'CDF')}`
    : `≈ ${parametresRestaurant.fmtDans(form.prixUnitaire / tauxChange.value, 'USD')}`
})

const emballageChoisi = computed(() =>
  emballages.value.find(e => e.articleBoissonId === form.articleBoissonId) || null)

const nbBouteilles = computed(() => {
  const e = emballageChoisi.value
  if (!e) return 0
  return e.contenanceCasier * (form.nbCasiers || 0) + (form.nbBouteillesSupp || 0)
})

/** "3 casier(s) + 9 bouteille(s)" ou juste l'un des deux si l'autre est nul. */
const detailQuantite = computed(() => {
  const parts: string[] = []
  if (form.nbCasiers) parts.push(`${form.nbCasiers} casier(s)`)
  if (form.nbBouteillesSupp) parts.push(`${form.nbBouteillesSupp} bouteille(s)`)
  return parts.join(' + ')
})

/** Bouteilles déjà réservées pour l'échange de consigne par d'autres lignes de CE panier, pour la même boisson. */
function videsReservesDansPanier(articleBoissonId: number | null): number {
  if (!articleBoissonId) return 0
  return panier.value
    .filter(l => l.articleBoissonId === articleBoissonId && l.echangeConsigne)
    .reduce((s, l) => s + l.nbBouteilles, 0)
}

/** Aperçu informatif : l'échange ne sera réellement appliqué qu'au paiement par la caisse. */
const apercuVides = computed(() => {
  const e = emballageChoisi.value
  if (!e || !form.echangeConsigne || !nbBouteilles.value) return null
  const disponible = e.bouteillesVides - videsReservesDansPanier(form.articleBoissonId)
  const apres = disponible - nbBouteilles.value
  if (apres < 0) return { invalide: true, disponible, texte: '' }
  return { invalide: false, disponible, texte: formatCasiers(apres, e.contenanceCasier) }
})

async function charger() {
  loading.value = true
  loadingHistorique.value = true
  erreur.value = ''
  try {
    const [emb, ents, tauxData] = await Promise.all([
      api<Emballage[]>('/restaurant/emballages'),
      api<Entrepot[]>('/restaurant/entrepots').catch(() => []),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
    ])
    emballages.value = emb
    entrepots.value = ents
    tauxChange.value = tauxData.taux || 0
    if (ents.length === 1) form.entrepotId = ents[0].id
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les données de réception.')
  } finally {
    loading.value = false
  }
  try {
    historique.value = await api<NoteFrais[]>('/notes-frais')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible de charger l'historique des demandes.")
  } finally {
    loadingHistorique.value = false
  }
}
onMounted(charger)

/** Le formulaire ne compose qu'UNE ligne à la fois : true dès qu'elle contient un début de saisie non ajouté au panier. */
const ligneEnCoursNonAjoutee = computed(() =>
  form.articleBoissonId !== null || !!form.nbCasiers || !!form.nbBouteillesSupp || !!form.prixUnitaire)

const totalPanier = computed(() => panier.value.reduce((s, l) => s + l.prixUnitaire * l.nbBouteilles, 0))

// ── Transport et manutention ─────────────────────────────────────────────
// Envoyés sur une ligne à part de la note ; au paiement, le serveur les
// répartit PAR BOUTEILLE sur le coût d'entrée en stock de chaque boisson
// (voir RegleTresorerieService.repartirFraisApproche). L'aperçu ci-dessous
// applique la même règle.
const totalBouteillesPanier = computed(() => panier.value.reduce((s, l) => s + l.nbBouteilles, 0))
const fraisApprocheSaisis = computed(() => (form.fraisApproche && form.fraisApproche > 0 ? form.fraisApproche : 0))
const fraisParBouteille = computed(() =>
  totalBouteillesPanier.value > 0 ? fraisApprocheSaisis.value / totalBouteillesPanier.value : 0)
const totalNote = computed(() => totalPanier.value + fraisApprocheSaisis.value)

function ajouterAuPanier() {
  erreur.value = ''
  if (!form.articleBoissonId || nbBouteilles.value <= 0) {
    erreur.value = 'Choisissez une boisson et une quantité (casiers et/ou bouteilles) positive.'
    return
  }
  if (!form.entrepotId) {
    erreur.value = "Choisissez l'entrepôt de réception."
    return
  }
  if (!form.prixUnitaire || form.prixUnitaire <= 0) {
    erreur.value = "Le prix d'achat d'une bouteille est obligatoire : sans lui, le coût de revient serait nul."
    return
  }
  if (apercuVides.value?.invalide) {
    erreur.value = 'Vides insuffisants pour cet échange de consigne : décochez-le ou réduisez la quantité.'
    return
  }
  const emb = emballageChoisi.value!
  const entrepot = entrepots.value.find(e => e.id === form.entrepotId)!
  panier.value.push({
    articleBoissonId: form.articleBoissonId,
    articleLibelle: emb.articleBoissonLibelle,
    nbCasiers: form.nbCasiers,
    nbBouteillesSupp: form.nbBouteillesSupp,
    nbBouteilles: nbBouteilles.value,
    detailQuantite: detailQuantite.value,
    prixUnitaire: form.prixUnitaire,
    entrepotId: form.entrepotId,
    entrepotLabel: `${entrepot.code} — ${entrepot.nom}`,
    echangeConsigne: form.echangeConsigne,
  })
  // Devise et entrepôt persistent (même fournisseur, même livraison) ; le
  // reste se remet à vide pour saisir le produit suivant.
  form.articleBoissonId = null
  form.nbCasiers = null
  form.nbBouteillesSupp = null
  form.prixUnitaire = null
  form.echangeConsigne = true
}

function retirerDuPanier(index: number) {
  panier.value.splice(index, 1)
}

function fmtPrixLigne(montant: number): string {
  return parametresRestaurant.fmtDans(montant, form.devisePrix === 'USD' ? 'USD' : 'CDF')
}

/** Garde le libellé historique exact pour un panier à un seul produit (compatibilité avec l'historique existant). */
function construireObjet(): string {
  if (panier.value.length === 1) {
    const l = panier.value[0]
    const objet = `Achat de boissons — ${l.articleLibelle} (${l.detailQuantite}, soit ${l.nbBouteilles} bouteilles)`
    return objet.length > 200 ? objet.slice(0, 197) + '…' : objet
  }
  const totalBouteilles = panier.value.reduce((s, l) => s + l.nbBouteilles, 0)
  const noms = panier.value.map(l => l.articleLibelle).join(', ')
  const objet = `Achat de boissons — ${panier.value.length} produits (${totalBouteilles} bouteilles) : ${noms}`
  return objet.length > 200 ? objet.slice(0, 197) + '…' : objet
}

async function envoyerDemande() {
  erreur.value = ''
  if (!panier.value.length) {
    erreur.value = 'Ajoutez au moins un produit au panier avant d\'envoyer la demande.'
    return
  }
  if (ligneEnCoursNonAjoutee.value) {
    erreur.value = "Une ligne en cours de saisie n'a pas été ajoutée au panier : cliquez sur "
      + '"Ajouter au panier", ou effacez la boisson sélectionnée pour l\'ignorer.'
    return
  }
  if (!form.beneficiaire.trim()) {
    erreur.value = 'Indiquez le fournisseur (bénéficiaire du paiement).'
    return
  }
  if (form.fraisApproche != null && form.fraisApproche < 0) {
    erreur.value = 'Le transport et la manutention ne peuvent pas être négatifs.'
    return
  }
  if (fichierJoint.value && fichierJoint.value.size > TAILLE_MAX_JUSTIFICATIF) {
    erreur.value = 'Le justificatif dépasse 20 Mo : choisissez un fichier plus léger.'
    return
  }
  saving.value = true
  succes.value = ''
  noteInachevee.value = null
  let note: { id: number; reference: string }
  try {
    note = await api<{ id: number; reference: string }>('/notes-frais', {
      method: 'POST',
      body: {
        objet: construireObjet(),
        beneficiaire: form.beneficiaire,
        description: form.description || null,
        devise: form.devisePrix,
        sens: 'DECAISSEMENT',
        lignes: panier.value.map(l => ({
          montant: l.prixUnitaire,
          description: form.description || null,
          achatMarchandise: true,
          quantiteMarchandise: l.nbBouteilles,
          articleId: l.articleBoissonId,
          entrepotId: l.entrepotId,
          soumisTva: false,
          echangeConsigne: l.echangeConsigne,
        })).concat(fraisApprocheSaisis.value > 0
          ? [{
              montant: fraisApprocheSaisis.value,
              description: 'Transport et manutention',
              achatMarchandise: false,
              soumisTva: false,
              fraisApproche: true,
            } as any]
          : []),
      },
    })
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'envoi de la demande d'achat.")
    saving.value = false
    return
  }

  // La note existe désormais : le panier est vidé tout de suite. Le laisser
  // plein invitait à renvoyer, donc à créer une seconde note identique, si
  // l'étape suivante échouait.
  const recapitulatif = `${panier.value.length} produit(s), `
    + `${panier.value.reduce((s, l) => s + l.nbBouteilles, 0)} bouteilles au total`
    + (fraisApprocheSaisis.value > 0 ? `, transport et manutention ${fmtPrixLigne(fraisApprocheSaisis.value)}` : '')
  const justificatif = fichierJoint.value
  panier.value = []
  form.beneficiaire = ''
  form.fraisApproche = null
  form.description = ''
  fichierJoint.value = null
  cleChampFichier.value++

  let etape = `sa transmission à ${rolesStore.libelle('DFIN')}`
  try {
    if (justificatif) {
      etape = "l'ajout du justificatif"
      const formData = new FormData()
      formData.append('fichier', justificatif)
      await api(`/notes-frais/${note.id}/pieces-jointes`, { method: 'POST', body: formData })
      etape = `sa transmission à ${rolesStore.libelle('DFIN')}`
    }
    await api(`/notes-frais/${note.id}/soumettre`, { method: 'POST', body: {} })
    succes.value = `Demande ${note.reference} envoyée à ${rolesStore.libelle('DFIN')} pour validation (${recapitulatif}).`
  } catch (e: any) {
    noteInachevee.value = { id: note.id, reference: note.reference, etape }
    erreur.value = messageErreurApi(e, 'Erreur inconnue.')
  } finally {
    saving.value = false
    await charger()
  }
}

function fmtMontantNote(montant: number, devise?: 'USD' | 'CDF') {
  return parametresRestaurant.fmtDans(montant, devise === 'USD' ? 'USD' : 'CDF')
}
function fmtDate(iso: string) {
  return new Date(iso).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' })
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Achat de boissons</h1>
        <p class="page-sub">Demande d'achat — validée par le circuit Direction financière / Direction administrative avant paiement par la caisse</p>
      </div>
      <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-bottle-wine-outline" to="/restaurant/emballages">
        Stock de vides
      </v-btn>
    </div>

    <v-alert v-if="noteInachevee" type="warning" variant="tonal" rounded="lg" class="mb-4" closable @click:close="noteInachevee = null; erreur = ''">
      La demande <strong>{{ noteInachevee.reference }}</strong> a été créée en brouillon, mais {{ noteInachevee.etape }} a échoué
      <template v-if="erreur">({{ erreur }})</template>.
      Ne la renvoyez pas d'ici, vous créeriez une seconde demande : terminez-la depuis sa fiche.
      <div class="mt-2">
        <v-btn size="small" variant="tonal" color="warning" :to="`/notes-frais/${noteInachevee.id}`" prepend-icon="mdi-open-in-app">
          Ouvrir {{ noteInachevee.reference }}
        </v-btn>
      </div>
    </v-alert>
    <v-alert v-else-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" class="mb-4" closable @click:close="succes = ''">
      {{ succes }}
    </v-alert>

    <v-alert v-if="!loading && !emballages.length" type="info" variant="tonal" rounded="lg" class="mb-4">
      Aucune boisson n'a de conditionnement défini. Créez-en un avant de demander un achat.
    </v-alert>

    <v-card v-if="canWrite" class="classroom-card pa-6 mb-6" max-width="640">
      <p class="text-caption text-medium-emphasis mb-4">
        <v-icon icon="mdi-information-outline" size="14" class="mr-1" />
        L'entrée en stock et l'échange de consigne ne s'exécutent qu'au règlement de la note par la caisse,
        après validation {{ rolesStore.libelle('DFIN') }} puis {{ rolesStore.libelle('DA') }}. Ajoutez autant de produits que nécessaire au panier avant d'envoyer :
        ils formeront une seule demande.
      </p>

      <v-autocomplete
        v-model="form.articleBoissonId"
        :items="emballages.map(e => ({ title: e.articleBoissonLibelle, value: e.articleBoissonId }))"
        label="Boisson *"
        variant="outlined"
        density="comfortable"
        clearable
        class="mb-3"
      />

      <div class="d-flex ga-2 mb-1">
        <v-text-field
          v-model.number="form.nbCasiers"
          type="number"
          label="Nombre de casiers"
          hint="Casiers complets"
          persistent-hint
          variant="outlined"
          density="comfortable"
          class="flex-grow-1"
        />
        <v-text-field
          v-model.number="form.nbBouteillesSupp"
          type="number"
          label="Bouteilles en plus"
          hint="En plus des casiers complets"
          persistent-hint
          variant="outlined"
          density="comfortable"
          class="flex-grow-1"
        />
      </div>
      <p v-if="emballageChoisi && nbBouteilles" class="text-body-2 text-medium-emphasis mb-3">
        Soit <strong>{{ nbBouteilles }}</strong> bouteilles au total
        <template v-if="form.nbCasiers && form.nbBouteillesSupp">
          ({{ form.nbCasiers }} × {{ emballageChoisi.contenanceCasier }} + {{ form.nbBouteillesSupp }})
        </template>
        <template v-else-if="form.nbCasiers">
          ({{ form.nbCasiers }} × {{ emballageChoisi.contenanceCasier }})
        </template>.
      </p>

      <div class="d-flex ga-2 mb-1 align-start">
        <v-text-field
          v-model.number="form.prixUnitaire"
          type="number"
          :label="`Prix d'achat d'une bouteille (${form.devisePrix}) *`"
          hint="Sans prix, le coût de revient reste nul et la marge affichée serait de 100 %"
          persistent-hint
          variant="outlined"
          density="comfortable"
          class="flex-grow-1"
        />
        <div>
          <v-btn-toggle
            v-model="form.devisePrix"
            mandatory
            density="comfortable"
            variant="outlined"
            rounded="lg"
            :disabled="panier.length > 0"
            style="margin-top: 4px"
          >
            <v-btn value="USD" size="small">$US</v-btn>
            <v-btn value="CDF" size="small">FC</v-btn>
          </v-btn-toggle>
        </div>
      </div>
      <p class="text-caption text-medium-emphasis mb-3" style="min-height: 1.2em">
        {{ prixEquivalent }}
        <template v-if="panier.length">· devise verrouillée : videz le panier pour en changer</template>
      </p>

      <v-select
        v-model="form.entrepotId"
        :items="entrepots.map(e => ({ title: `${e.code} — ${e.nom}`, value: e.id }))"
        label="Entrepôt de réception *"
        variant="outlined"
        density="comfortable"
        class="mb-3"
      />

      <v-switch
        v-model="form.echangeConsigne"
        label="Échange de consigne : rendre les casiers vides"
        color="primary"
        density="compact"
        hide-details
        class="mb-2"
      />
      <p class="text-caption text-medium-emphasis mb-4">
        Décochez pour un premier achat, lorsqu'il n'y a pas encore de vides à rendre.
      </p>

      <v-alert v-if="apercuVides" :type="apercuVides.invalide ? 'error' : 'info'" variant="tonal" density="compact" rounded="lg" class="mb-4">
        <template v-if="apercuVides.invalide">
          Vides insuffisants pour cet échange : il ne reste que {{ apercuVides.disponible }} bouteille(s) vide(s)
          disponible(s) (après les autres lignes du panier), l'échange en demande {{ nbBouteilles }}.
          Décochez l'échange de consigne, ou réduisez la quantité.
        </template>
        <template v-else>
          Stock de vides après l'échange (au paiement) : <strong>{{ apercuVides.texte }}</strong>.
        </template>
      </v-alert>

      <div class="d-flex justify-end mb-2">
        <v-btn
          color="primary"
          variant="tonal"
          rounded="lg"
          prepend-icon="mdi-cart-plus"
          :disabled="!emballages.length || saving"
          @click="ajouterAuPanier"
        >
          Ajouter au panier
        </v-btn>
      </div>

      <template v-if="panier.length">
        <v-divider class="my-4" />
        <p class="text-caption text-medium-emphasis text-uppercase font-weight-bold mb-2">
          Panier — {{ panier.length }} produit(s)
        </p>
        <div v-for="(l, i) in panier" :key="i" class="panier-ligne">
          <div class="panier-ligne__info">
            <p class="panier-ligne__titre">{{ l.articleLibelle }}</p>
            <p class="panier-ligne__detail">
              {{ l.detailQuantite || `${l.nbBouteilles} bouteille(s)` }} · {{ fmtPrixLigne(l.prixUnitaire) }}/bouteille
              <template v-if="fraisApprocheSaisis > 0">
                · coût de revient <strong>{{ fmtPrixLigne(l.prixUnitaire + fraisParBouteille) }}</strong>/bouteille
              </template>
              · {{ l.entrepotLabel }}
              <v-icon v-if="l.echangeConsigne" icon="mdi-swap-horizontal" size="13" class="ml-1" title="Échange de consigne" />
            </p>
          </div>
          <div class="panier-ligne__montant">{{ fmtPrixLigne(l.prixUnitaire * l.nbBouteilles) }}</div>
          <v-btn
            icon="mdi-close"
            size="x-small"
            variant="text"
            density="comfortable"
            :disabled="saving"
            :title="`Retirer ${l.articleLibelle} du panier`"
            :aria-label="`Retirer ${l.articleLibelle} du panier`"
            @click="retirerDuPanier(i)"
          />
        </div>
        <div v-if="fraisApprocheSaisis > 0" class="panier-frais">
          <span>
            Transport et manutention · {{ fmtPrixLigne(fraisParBouteille) }} par bouteille
            ({{ totalBouteillesPanier }} bouteilles)
          </span>
          <span>{{ fmtPrixLigne(fraisApprocheSaisis) }}</span>
        </div>
        <div class="panier-total">
          <span>Total</span>
          <strong>{{ fmtPrixLigne(totalNote) }}</strong>
        </div>
        <v-divider class="my-4" />
      </template>

      <div class="d-flex flex-wrap ga-3 mb-3">
        <v-text-field
          v-model="form.beneficiaire"
          label="Fournisseur (bénéficiaire du paiement) *"
          variant="outlined"
          density="comfortable"
          class="champ-fournisseur"
        />
        <v-text-field
          v-model.number="form.fraisApproche"
          type="number"
          min="0"
          :label="`Transport et manutention (${form.devisePrix === 'USD' ? '$US' : 'FC'})`"
          hint="Réparti par bouteille sur le coût de revient"
          persistent-hint
          variant="outlined"
          density="comfortable"
          class="champ-frais"
        />
      </div>

      <v-textarea
        v-model="form.description"
        label="Précisions (facultatif)"
        rows="2"
        variant="outlined"
        density="comfortable"
        class="mb-3"
      />

      <v-file-input
        label="Facture proforma ou justificatif (facultatif)"
        accept="application/pdf,image/jpeg,image/png,image/webp,image/gif"
        variant="outlined"
        density="comfortable"
        prepend-icon=""
        prepend-inner-icon="mdi-paperclip"
        show-size
        class="mb-3"
        :key="cleChampFichier"
        @update:model-value="onFichierJoint"
      />

      <div class="d-flex justify-end">
        <v-btn
          color="primary"
          variant="flat"
          rounded="lg"
          prepend-icon="mdi-send-outline"
          :loading="saving"
          :disabled="!panier.length"
          @click="envoyerDemande"
        >
          Envoyer la demande d'achat
        </v-btn>
      </div>
    </v-card>

    <v-alert v-else type="info" variant="tonal" rounded="lg" class="mb-6">
      Seul le responsable restaurant peut demander un achat de boissons.
    </v-alert>

    <v-card class="classroom-card">
      <div class="rdb-card-head">
        <v-icon icon="mdi-history" size="18" class="mr-2" />
        Mes demandes d'achat
      </div>
      <v-data-table
        :headers="[
          { title: 'Référence', key: 'reference' },
          { title: 'Objet', key: 'objet' },
          { title: 'Montant', key: 'montant', align: 'end' },
          { title: 'Statut', key: 'statut' },
          { title: 'Date', key: 'dateCreation' },
        ]"
        :items="historiqueBoissons"
        :loading="loadingHistorique"
        items-per-page="10"
        @click:row="(_e: Event, row: any) => navigateTo(`/notes-frais/${row.item.id}`)"
        style="cursor: pointer"
      >
        <template #item.montant="{ item }">{{ fmtMontantNote(item.montant, item.devise) }}</template>
        <template #item.statut="{ item }">
          <v-chip
            size="small"
            variant="flat"
            :style="{ background: statutNoteMeta(item.statut).bg, color: statutNoteMeta(item.statut).text }"
          >
            {{ statutNoteMeta(item.statut).label }}
          </v-chip>
        </template>
        <template #item.dateCreation="{ item }">{{ fmtDate(item.dateCreation) }}</template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">Aucune demande d'achat pour le moment.</div>
        </template>
      </v-data-table>
    </v-card>
  </div>
</template>

<style scoped>
.rdb-card-head {
  display: flex; align-items: center; font-size: 0.8rem; font-weight: 700;
  letter-spacing: 0.3px; text-transform: uppercase; color: #6b7280; padding: 16px 20px 12px;
}
.panier-ligne {
  display: flex; align-items: center; gap: 10px; padding: 8px 0;
  border-bottom: 1px dashed #e5e7eb;
}
.panier-ligne__info { flex: 1 1 auto; min-width: 0; }
.panier-ligne__titre { font-size: 0.875rem; font-weight: 600; color: #111827; margin: 0; }
.panier-ligne__detail { font-size: 0.75rem; color: #6b7280; margin: 2px 0 0; }
.panier-ligne__montant { font-size: 0.875rem; font-weight: 600; color: #374151; white-space: nowrap; }
.champ-fournisseur { flex: 2 1 260px; }
.champ-frais { flex: 1 1 200px; }
.panier-frais {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 0.8125rem;
  color: #4b5563;
  padding: 8px 0 0;
}
.panier-total {
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 0 0; font-size: 0.9rem; color: #111827;
}
</style>
