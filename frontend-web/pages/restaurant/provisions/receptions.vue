<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Demande d'achat de provisions (matieres premieres de cuisine), sur le meme
 * principe que /restaurant/receptions pour les boissons : cet ecran ne touche
 * jamais le stock lui-meme, il cree une note de frais speciale (achat de
 * marchandise, une ou plusieurs lignes, article PROVISION) et la soumet
 * aussitot au circuit habituel Creation -> DFIN -> DA -> DFIN -> Caisse.
 * L'entree en stock ne s'execute qu'au moment ou le caissier regle
 * effectivement la note (voir CaisseService.payerNote cote serveur) — jamais
 * avant.
 *
 * <p>Plusieurs provisions peuvent être ajoutées à un même panier (ex.
 * plusieurs matières premières achetées chez un même fournisseur en une
 * seule fois) : chaque provision devient sa propre ligne de la note — voir
 * NoteFraisService.validerNoteRespRestaurant côté serveur, qui valide
 * chaque ligne individuellement plutôt que d'imposer une ligne unique.</p>
 *
 * <p>Contrairement aux boissons, une provision n'a ni casier ni consigne : la
 * quantite se saisit directement dans son unite de mesure (kg, litre...).</p>
 */
interface Provision { id: number; code: string; libelle: string; uniteMesure?: string }
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
  provisionId: number
  provisionLibelle: string
  uniteMesure: string
  quantite: number
  prixUnitaire: number
  entrepotId: number
  entrepotLabel: string
}

const api = useApi()
/** Formatage des montants affichés (store du restaurant). */
const parametresRestaurant = useRestaurantParametresStore()
onMounted(() => { parametresRestaurant.charger() })
const auth = useAuthStore()
const saving = ref(false)
/** Note créée dont la transmission au DFIN a échoué : à terminer depuis Notes de frais. */
const noteInachevee = ref<{ id: number; reference: string } | null>(null)
const loading = ref(false)
const loadingHistorique = ref(false)
const erreur = ref('')
const succes = ref('')
const provisions = ref<Provision[]>([])
const entrepots = ref<Entrepot[]>([])
const historique = ref<NoteFrais[]>([])
const tauxChange = ref(0)
const panier = ref<LignePanier[]>([])

// /notes-frais retourne TOUTES les notes de l'utilisateur (le responsable
// restaurant peut aussi demander des boissons, hors de cet écran) : on ne
// garde que celles issues de cette page, reconnaissables à l'objet généré
// automatiquement par construireObjet() ci-dessous, jamais saisi librement.
const historiqueProvisions = computed(() =>
  historique.value.filter(n => n.objet?.startsWith('Achat de provisions')))

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT', 'ADMIN']))

// Devise commune à tout le panier (une seule note, un seul fournisseur réglé
// en une fois) : verrouillée dès la première ligne ajoutée pour éviter qu'un
// changement en cours de saisie ne fausse silencieusement le prix des lignes
// déjà ajoutées (voir ajouterAuPanier).
const form = reactive({
  provisionId: null as number | null,
  quantite: null as number | null,
  // Aligné sur /restaurant/receptions (achat de boissons), l'écran jumeau :
  // les deux affichaient par défaut une devise différente (USD ici, FC là-bas)
  // alors que le fournisseur est en général le même — un montant tapé par
  // habitude en FC partait pour USD sans que rien ne le signale clairement.
  devisePrix: 'CDF' as 'USD' | 'CDF',
  prixUnitaire: null as number | null,
  entrepotId: null as number | null,
  beneficiaire: '',
  /** Transport et manutention de la livraison, dans la devise de la note. */
  fraisApproche: null as number | null,
  description: '',
})

const provisionChoisie = computed(() =>
  provisions.value.find(p => p.id === form.provisionId) || null)

/** Équivalent indicatif dans l'autre devise, au taux du jour — n'influence ni la saisie ni l'enregistrement. */
const prixEquivalent = computed(() => {
  if (!form.prixUnitaire || tauxChange.value <= 0) return null
  return form.devisePrix === 'USD'
    ? `≈ ${parametresRestaurant.fmtDans(form.prixUnitaire * tauxChange.value, 'CDF')}`
    : `≈ ${parametresRestaurant.fmtDans(form.prixUnitaire / tauxChange.value, 'USD')}`
})

/** Montant de la ligne en cours (quantité × prix unitaire), dans la devise saisie. */
const montantLigne = computed(() => {
  if (!form.quantite || !form.prixUnitaire) return null
  const total = form.quantite * form.prixUnitaire
  return parametresRestaurant.fmtDans(total, form.devisePrix === 'USD' ? 'USD' : 'CDF')
})

async function charger() {
  loading.value = true
  loadingHistorique.value = true
  erreur.value = ''
  try {
    const [provs, ents, tauxData] = await Promise.all([
      api<Provision[]>('/restaurant/provisions'),
      api<Entrepot[]>('/restaurant/entrepots').catch(() => []),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
    ])
    provisions.value = provs
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
  form.provisionId !== null || !!form.quantite || !!form.prixUnitaire)

const totalPanier = computed(() => panier.value.reduce((s, l) => s + l.prixUnitaire * l.quantite, 0))

// ── Transport et manutention ─────────────────────────────────────────────
// Envoyés sur une ligne à part de la note ; au paiement, le serveur les
// répartit AU PRORATA DU MONTANT de chaque provision (les unités — kg,
// litres, sacs — ne s'additionnent pas), sur son coût d'entrée en stock :
// voir RegleTresorerieService.repartirFraisApproche. Même règle ici.
const fraisApprocheSaisis = computed(() => (form.fraisApproche && form.fraisApproche > 0 ? form.fraisApproche : 0))
const partFrais = (l: { prixUnitaire: number; quantite: number }) =>
  totalPanier.value > 0 ? fraisApprocheSaisis.value * (l.prixUnitaire * l.quantite) / totalPanier.value : 0
const totalNote = computed(() => totalPanier.value + fraisApprocheSaisis.value)

function ajouterAuPanier() {
  erreur.value = ''
  if (!form.provisionId) {
    erreur.value = 'Choisissez une provision.'
    return
  }
  if (!form.quantite || form.quantite <= 0) {
    erreur.value = 'Indiquez une quantité positive.'
    return
  }
  if (!form.entrepotId) {
    erreur.value = "Choisissez l'entrepôt de réception."
    return
  }
  if (!form.prixUnitaire || form.prixUnitaire <= 0) {
    erreur.value = "Le prix d'achat unitaire est obligatoire : sans lui, le coût de revient serait nul."
    return
  }
  const provision = provisionChoisie.value!
  const entrepot = entrepots.value.find(e => e.id === form.entrepotId)!
  panier.value.push({
    provisionId: form.provisionId,
    provisionLibelle: provision.libelle,
    uniteMesure: provision.uniteMesure || 'unité(s)',
    quantite: form.quantite,
    prixUnitaire: form.prixUnitaire,
    entrepotId: form.entrepotId,
    entrepotLabel: `${entrepot.code} — ${entrepot.nom}`,
  })
  // Devise et entrepôt persistent (même fournisseur, même livraison) ; le
  // reste se remet à vide pour saisir la provision suivante.
  form.provisionId = null
  form.quantite = null
  form.prixUnitaire = null
}

function retirerDuPanier(index: number) {
  panier.value.splice(index, 1)
}

function fmtPrixLigne(montant: number): string {
  return parametresRestaurant.fmtDans(montant, form.devisePrix === 'USD' ? 'USD' : 'CDF')
}

/** Garde le libellé historique exact pour un panier à une seule provision (compatibilité avec l'historique existant). */
function construireObjet(): string {
  if (panier.value.length === 1) {
    const l = panier.value[0]
    const objet = `Achat de provisions — ${l.provisionLibelle} (${l.quantite} ${l.uniteMesure})`
    return objet.length > 200 ? objet.slice(0, 197) + '…' : objet
  }
  const noms = panier.value.map(l => `${l.provisionLibelle} (${l.quantite} ${l.uniteMesure})`).join(', ')
  const objet = `Achat de provisions — ${panier.value.length} produits : ${noms}`
  return objet.length > 200 ? objet.slice(0, 197) + '…' : objet
}

async function envoyerDemande() {
  erreur.value = ''
  if (!panier.value.length) {
    erreur.value = 'Ajoutez au moins une provision au panier avant d\'envoyer la demande.'
    return
  }
  if (ligneEnCoursNonAjoutee.value) {
    erreur.value = "Une ligne en cours de saisie n'a pas été ajoutée au panier : cliquez sur "
      + '"Ajouter au panier", ou effacez la provision sélectionnée pour l\'ignorer.'
    return
  }
  if (form.fraisApproche != null && form.fraisApproche < 0) {
    erreur.value = 'Le transport et la manutention ne peuvent pas être négatifs.'
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
        beneficiaire: form.beneficiaire.trim() || 'Fournisseur non précisé',
        description: form.description || null,
        devise: form.devisePrix,
        sens: 'DECAISSEMENT',
        lignes: panier.value.map(l => ({
          montant: l.prixUnitaire,
          description: form.description || null,
          achatMarchandise: true,
          quantiteMarchandise: l.quantite,
          articleId: l.provisionId,
          entrepotId: l.entrepotId,
          soumisTva: false,
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
  // la transmission échouait.
  const recapitulatif = `${panier.value.length} produit(s)`
    + (fraisApprocheSaisis.value > 0 ? `, transport et manutention ${fmtPrixLigne(fraisApprocheSaisis.value)}` : '')
  panier.value = []
  form.beneficiaire = ''
  form.fraisApproche = null
  form.description = ''

  try {
    await api(`/notes-frais/${note.id}/soumettre`, { method: 'POST', body: {} })
    succes.value = `Demande ${note.reference} envoyée au DFIN pour validation (${recapitulatif}).`
  } catch (e: any) {
    noteInachevee.value = { id: note.id, reference: note.reference }
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
        <h1 class="page-title">Achat de provisions</h1>
        <p class="page-sub">Demande d'achat — validée par le circuit Direction financière / Direction administrative avant paiement par la caisse</p>
      </div>
      <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-sack" to="/restaurant/provisions">
        Stock de provisions
      </v-btn>
    </div>

    <v-alert v-if="noteInachevee" type="warning" variant="tonal" rounded="lg" class="mb-4" closable @click:close="noteInachevee = null; erreur = ''">
      La demande <strong>{{ noteInachevee.reference }}</strong> a été créée en brouillon, mais sa transmission au DFIN a échoué
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

    <v-alert v-if="!loading && !provisions.length" type="info" variant="tonal" rounded="lg" class="mb-4">
      Aucune provision n'est référencée. Créez-en une avant de demander un achat.
    </v-alert>

    <v-card v-if="canWrite" class="classroom-card pa-6 mb-6" max-width="640">
      <p class="text-caption text-medium-emphasis mb-4">
        <v-icon icon="mdi-information-outline" size="14" class="mr-1" />
        L'entrée en stock ne s'exécute qu'au règlement de la note par la caisse, après validation DFIN puis DA.
        Ajoutez autant de provisions que nécessaire au panier avant d'envoyer : elles formeront une seule demande.
      </p>

      <v-autocomplete
        v-model="form.provisionId"
        :items="provisions.map(p => ({ title: `${p.code} — ${p.libelle}`, value: p.id }))"
        label="Provision *"
        variant="outlined"
        density="comfortable"
        clearable
        class="mb-3"
      />

      <v-text-field
        v-model.number="form.quantite"
        type="number"
        :label="`Quantité${provisionChoisie?.uniteMesure ? ' (' + provisionChoisie.uniteMesure + ')' : ''} *`"
        variant="outlined"
        density="comfortable"
        class="mb-3"
      />

      <div class="d-flex ga-2 mb-1 align-start">
        <v-text-field
          v-model.number="form.prixUnitaire"
          type="number"
          :label="`Prix d'achat unitaire (${form.devisePrix}) *`"
          hint="Sans prix, le coût de revient reste nul"
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
      <p class="text-caption text-medium-emphasis mb-1" style="min-height: 1.2em">
        {{ prixEquivalent }}
        <template v-if="panier.length">· devise verrouillée : videz le panier pour en changer</template>
      </p>
      <p v-if="montantLigne" class="text-body-2 text-medium-emphasis mb-3">
        Soit <strong>{{ montantLigne }}</strong> pour cette ligne.
      </p>

      <v-select
        v-model="form.entrepotId"
        :items="entrepots.map(e => ({ title: `${e.code} — ${e.nom}`, value: e.id }))"
        label="Entrepôt de réception *"
        variant="outlined"
        density="comfortable"
        class="mb-4"
      />

      <div class="d-flex justify-end mb-2">
        <v-btn
          color="primary"
          variant="tonal"
          rounded="lg"
          prepend-icon="mdi-cart-plus"
          :disabled="!provisions.length || saving"
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
            <p class="panier-ligne__titre">{{ l.provisionLibelle }}</p>
            <p class="panier-ligne__detail">
              {{ l.quantite }} {{ l.uniteMesure }} · {{ fmtPrixLigne(l.prixUnitaire) }}/{{ l.uniteMesure }}
              <template v-if="fraisApprocheSaisis > 0">
                · + {{ fmtPrixLigne(partFrais(l)) }} de frais, soit
                <strong>{{ fmtPrixLigne(l.prixUnitaire + partFrais(l) / l.quantite) }}</strong>/{{ l.uniteMesure }}
              </template>
              · {{ l.entrepotLabel }}
            </p>
          </div>
          <div class="panier-ligne__montant">{{ fmtPrixLigne(l.prixUnitaire * l.quantite) }}</div>
          <v-btn
            icon="mdi-close"
            size="x-small"
            variant="text"
            density="comfortable"
            :disabled="saving"
            :title="`Retirer ${l.provisionLibelle} du panier`"
            :aria-label="`Retirer ${l.provisionLibelle} du panier`"
            @click="retirerDuPanier(i)"
          />
        </div>
        <div v-if="fraisApprocheSaisis > 0" class="panier-frais">
          <span>Transport et manutention · répartis au prorata du montant de chaque provision</span>
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
          label="Fournisseur (bénéficiaire du paiement)"
          hint="Facultatif : laissez vide si le fournisseur n'est pas nommé (achat au marché...)"
          persistent-hint
          variant="outlined"
          density="comfortable"
          class="champ-fournisseur"
        />
        <v-text-field
          v-model.number="form.fraisApproche"
          type="number"
          min="0"
          :label="`Transport et manutention (${form.devisePrix === 'USD' ? '$US' : 'FC'})`"
          hint="Réparti au prorata du montant de chaque provision"
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
        class="mb-4"
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
      Seul le responsable restaurant peut demander un achat de provisions.
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
        :items="historiqueProvisions"
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
