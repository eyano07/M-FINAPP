<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Demande d'achat de boissons, saisie en casiers.
 *
 * Contrairement à l'ancienne réception directe, cet écran ne touche plus le
 * stock : il crée une note de frais spéciale (achat de marchandise, une seule
 * ligne, article BOISSON) et la soumet aussitôt au circuit habituel
 * Création → DFIN → DA → DFIN → Caisse. L'entrée en stock des bouteilles
 * pleines et, si demandé, l'échange de consigne des vides ne s'exécutent
 * qu'au moment où le caissier règle effectivement la note (voir
 * CaisseService.payerNote côté serveur) — jamais avant.
 *
 * <p>Le prix d'achat se saisit dans la devise réellement quotée par le
 * fournisseur (USD ou FC) — jamais convertie côté client. La note porte
 * cette devise telle quelle : un prix en USD ne subit ensuite aucune
 * conversion (risque de change nul) ; un prix en FC suit le circuit habituel
 * des notes de frais (taux figé à la transmission, écart de change constaté
 * au règlement) — le même mécanisme que pour toute autre dépense de
 * l'entreprise, plutôt qu'un taux du jour de la saisie appliqué a posteriori
 * à un règlement qui peut intervenir plusieurs jours plus tard.</p>
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

const api = useApi()
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

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT']))

const form = reactive({
  articleBoissonId: null as number | null,
  nbCasiers: null as number | null,
  devisePrix: 'USD' as 'USD' | 'CDF',
  prixUnitaire: null as number | null,
  entrepotId: null as number | null,
  beneficiaire: '',
  description: '',
  echangeConsigne: true,
})

/** Équivalent indicatif dans l'autre devise, au taux du jour — n'influence ni la saisie ni l'enregistrement. */
const prixEquivalent = computed(() => {
  if (!form.prixUnitaire || tauxChange.value <= 0) return null
  return form.devisePrix === 'USD'
    ? `≈ ${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(form.prixUnitaire * tauxChange.value)} FC`
    : `≈ ${new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD' }).format(form.prixUnitaire / tauxChange.value)}`
})

const emballageChoisi = computed(() =>
  emballages.value.find(e => e.articleBoissonId === form.articleBoissonId) || null)

const nbBouteilles = computed(() => {
  const e = emballageChoisi.value
  return e && form.nbCasiers ? e.contenanceCasier * form.nbCasiers : 0
})

/** Aperçu informatif : l'échange ne sera réellement appliqué qu'au paiement par la caisse. */
const apercuVides = computed(() => {
  const e = emballageChoisi.value
  if (!e || !form.echangeConsigne || !nbBouteilles.value) return null
  const apres = e.bouteillesVides - nbBouteilles.value
  if (apres < 0) return { invalide: true, apres, texte: '' }
  return { invalide: false, apres, texte: formatCasiers(apres, e.contenanceCasier) }
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

async function envoyerDemande() {
  if (!form.articleBoissonId || !form.nbCasiers || form.nbCasiers <= 0) {
    erreur.value = 'Choisissez une boisson et un nombre de casiers.'
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
  if (!form.beneficiaire.trim()) {
    erreur.value = 'Indiquez le fournisseur (bénéficiaire du paiement).'
    return
  }
  const emb = emballageChoisi.value!
  saving.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const note = await api<{ id: number; reference: string }>('/notes-frais', {
      method: 'POST',
      body: {
        objet: `Achat de boissons — ${emb.articleBoissonLibelle} `
          + `(${form.nbCasiers} casier(s), ${nbBouteilles.value} bouteilles)`,
        beneficiaire: form.beneficiaire,
        description: form.description || null,
        devise: form.devisePrix,
        sens: 'DECAISSEMENT',
        lignes: [{
          montant: form.prixUnitaire,
          description: form.description || null,
          achatMarchandise: true,
          quantiteMarchandise: nbBouteilles.value,
          articleId: form.articleBoissonId,
          entrepotId: form.entrepotId,
          soumisTva: false,
          echangeConsigne: form.echangeConsigne,
        }],
      },
    })
    await api(`/notes-frais/${note.id}/soumettre`, { method: 'POST', body: {} })

    succes.value = `Demande ${note.reference} envoyée au DFIN pour validation `
      + `(${form.nbCasiers} casier(s), soit ${nbBouteilles.value} bouteilles).`
    form.nbCasiers = null
    form.prixUnitaire = null
    form.beneficiaire = ''
    form.description = ''
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'envoi de la demande d'achat.")
  } finally {
    saving.value = false
  }
}

function fmtMontantNote(montant: number, devise?: 'USD' | 'CDF') {
  return devise === 'USD'
    ? new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD' }).format(montant)
    : new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(montant) + ' FC'
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

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
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
        après validation DFIN puis DA.
      </p>

      <v-select
        v-model="form.articleBoissonId"
        :items="emballages.map(e => ({ title: e.articleBoissonLibelle, value: e.articleBoissonId }))"
        label="Boisson *"
        variant="outlined"
        density="comfortable"
        class="mb-3"
      />

      <v-text-field
        v-model.number="form.nbCasiers"
        type="number"
        label="Nombre de casiers *"
        variant="outlined"
        density="comfortable"
        class="mb-1"
      />
      <p v-if="emballageChoisi && nbBouteilles" class="text-body-2 text-medium-emphasis mb-3">
        Soit <strong>{{ nbBouteilles }}</strong> bouteilles
        ({{ form.nbCasiers }} × {{ emballageChoisi.contenanceCasier }}).
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
        <v-btn-toggle v-model="form.devisePrix" mandatory density="comfortable" variant="outlined" rounded="lg" style="margin-top: 4px">
          <v-btn value="USD" size="small">$US</v-btn>
          <v-btn value="CDF" size="small">FC</v-btn>
        </v-btn-toggle>
      </div>
      <p class="text-caption text-medium-emphasis mb-3" style="min-height: 1.2em">{{ prixEquivalent }}</p>

      <v-select
        v-model="form.entrepotId"
        :items="entrepots.map(e => ({ title: `${e.code} — ${e.nom}`, value: e.id }))"
        label="Entrepôt de réception *"
        variant="outlined"
        density="comfortable"
        class="mb-3"
      />

      <v-text-field
        v-model="form.beneficiaire"
        label="Fournisseur (bénéficiaire du paiement) *"
        variant="outlined"
        density="comfortable"
        class="mb-3"
      />

      <v-textarea
        v-model="form.description"
        label="Précisions (facultatif)"
        rows="2"
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
          Vides insuffisants pour cet échange : il ne reste que
          {{ emballageChoisi!.bouteillesVides }} bouteille(s) vide(s), l'échange en demande {{ nbBouteilles }}.
          Décochez l'échange de consigne, ou réduisez la quantité.
        </template>
        <template v-else>
          Stock de vides après l'échange (au paiement) : <strong>{{ apercuVides.texte }}</strong>.
        </template>
      </v-alert>

      <div class="d-flex justify-end">
        <v-btn
          color="primary"
          variant="flat"
          rounded="lg"
          prepend-icon="mdi-send-outline"
          :loading="saving"
          :disabled="apercuVides?.invalide || !emballages.length"
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
        :items="historique"
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
</style>
