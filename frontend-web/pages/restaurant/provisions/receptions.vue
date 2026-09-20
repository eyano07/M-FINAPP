<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Demande d'achat de provisions (matieres premieres de cuisine), sur le meme
 * principe que /restaurant/receptions pour les boissons : cet ecran ne touche
 * jamais le stock lui-meme, il cree une note de frais speciale (achat de
 * marchandise, une seule ligne, article PROVISION) et la soumet aussitot au
 * circuit habituel Creation -> DFIN -> DA -> DFIN -> Caisse. L'entree en stock
 * ne s'execute qu'au moment ou le caissier regle effectivement la note (voir
 * CaisseService.payerNote cote serveur) — jamais avant.
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

const api = useApi()
const auth = useAuthStore()
const saving = ref(false)
const loading = ref(false)
const loadingHistorique = ref(false)
const erreur = ref('')
const succes = ref('')
const provisions = ref<Provision[]>([])
const entrepots = ref<Entrepot[]>([])
const historique = ref<NoteFrais[]>([])
const tauxChange = ref(0)

// /notes-frais retourne TOUTES les notes de l'utilisateur (le responsable
// restaurant peut aussi demander des boissons, hors de cet écran) : on ne
// garde que celles issues de cette page, reconnaissables à l'objet généré
// automatiquement par envoyerDemande() ci-dessous, jamais saisi librement.
const historiqueProvisions = computed(() =>
  historique.value.filter(n => n.objet?.startsWith('Achat de provisions')))

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT']))

const form = reactive({
  provisionId: null as number | null,
  quantite: null as number | null,
  devisePrix: 'USD' as 'USD' | 'CDF',
  prixUnitaire: null as number | null,
  entrepotId: null as number | null,
  beneficiaire: '',
  description: '',
})

const provisionChoisie = computed(() =>
  provisions.value.find(p => p.id === form.provisionId) || null)

/** Équivalent indicatif dans l'autre devise, au taux du jour — n'influence ni la saisie ni l'enregistrement. */
const prixEquivalent = computed(() => {
  if (!form.prixUnitaire || tauxChange.value <= 0) return null
  return form.devisePrix === 'USD'
    ? `≈ ${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(form.prixUnitaire * tauxChange.value)} FC`
    : `≈ ${new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD' }).format(form.prixUnitaire / tauxChange.value)}`
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

async function envoyerDemande() {
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
  if (!form.beneficiaire.trim()) {
    erreur.value = 'Indiquez le fournisseur (bénéficiaire du paiement).'
    return
  }
  const provision = provisionChoisie.value!
  saving.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const note = await api<{ id: number; reference: string }>('/notes-frais', {
      method: 'POST',
      body: {
        objet: `Achat de provisions — ${provision.libelle} `
          + `(${form.quantite} ${provision.uniteMesure || 'unité(s)'})`,
        beneficiaire: form.beneficiaire,
        description: form.description || null,
        devise: form.devisePrix,
        sens: 'DECAISSEMENT',
        lignes: [{
          montant: form.prixUnitaire,
          description: form.description || null,
          achatMarchandise: true,
          quantiteMarchandise: form.quantite,
          articleId: form.provisionId,
          entrepotId: form.entrepotId,
          soumisTva: false,
        }],
      },
    })
    await api(`/notes-frais/${note.id}/soumettre`, { method: 'POST', body: {} })

    succes.value = `Demande ${note.reference} envoyée au DFIN pour validation `
      + `(${form.quantite} ${provision.uniteMesure || 'unité(s)'} de ${provision.libelle}).`
    form.quantite = null
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
        <h1 class="page-title">Achat de provisions</h1>
        <p class="page-sub">Demande d'achat — validée par le circuit Direction financière / Direction administrative avant paiement par la caisse</p>
      </div>
      <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-sack" to="/restaurant/provisions">
        Stock de provisions
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
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
        class="mb-4"
      />

      <div class="d-flex justify-end">
        <v-btn
          color="primary"
          variant="flat"
          rounded="lg"
          prepend-icon="mdi-send-outline"
          :loading="saving"
          :disabled="!provisions.length"
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
</style>
