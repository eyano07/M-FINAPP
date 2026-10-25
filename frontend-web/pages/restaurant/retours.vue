<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT', roles: ['RESP_RESTAURANT', 'RESP_BAR', 'RESP_CUISINE'] })

/**
 * Retours de ventes payées à approuver (V134). Le caissier demande le retour (lignes et quantités rendues, motif) ; le
 * responsable décide : le bar pour des boissons, la cuisine pour des plats, le restaurant pour tout. Approuvé, le caissier
 * rembourse le client (part rendue extournée, bouteille intacte remise en stock, vide repris). Refusé, le motif est
 * transmis au caissier. Un responsable ne décide pas d'un retour qu'il a lui-même demandé.
 */
type Statut = 'DEMANDE' | 'APPROUVE' | 'REFUSE' | 'REMBOURSE' | 'ANNULE'
interface LigneRetour {
  ligneVenteId: number
  designation: string
  type?: string
  quantite: number
  prixUnitaire: number
  remiseEnStock: boolean
  montantTtc: number
}
interface RetourVente {
  id: number
  reference: string
  venteId: number
  venteReference: string
  client: string
  devise?: 'CDF' | 'USD'
  statut: Statut
  motif: string
  demandeur?: string | null
  dateDemande: string
  approbateur?: string | null
  dateDecision?: string | null
  commentaireDecision?: string | null
  caissier?: string | null
  dateRemboursement?: string | null
  montantTtc: number
  lignes: LigneRetour[]
}

const STATUTS: Record<Statut, { label: string; color: string }> = {
  DEMANDE: { label: 'À décider', color: 'warning' },
  APPROUVE: { label: 'Approuvé — à rembourser', color: 'primary' },
  REFUSE: { label: 'Refusé', color: 'error' },
  REMBOURSE: { label: 'Remboursé', color: 'success' },
  ANNULE: { label: 'Retiré par le caissier', color: 'grey' },
}

const api = useApi()
const loading = ref(false)
const busy = ref(false)
const erreur = ref('')
const succes = ref('')
const retours = ref<RetourVente[]>([])
const filtre = ref<'A_DECIDER' | 'TOUS'>('A_DECIDER')

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    retours.value = await api<RetourVente[]>('/restaurant/retours-ventes')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les retours de ventes.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

const aDecider = computed(() => retours.value.filter(r => r.statut === 'DEMANDE'))
const affiches = computed(() => (filtre.value === 'A_DECIDER' ? aDecider.value : retours.value))

const decision = ref<{ retour: RetourVente; approuver: boolean } | null>(null)
const commentaire = ref('')
function ouvrirDecision(retour: RetourVente, approuver: boolean) {
  decision.value = { retour, approuver }
  commentaire.value = ''
  erreur.value = ''
}
async function decider() {
  const d = decision.value
  if (!d) return
  if (!d.approuver && !commentaire.value.trim()) {
    erreur.value = 'Indiquez pourquoi le retour est refusé : le caissier en sera prévenu.'
    return
  }
  busy.value = true
  erreur.value = ''
  succes.value = ''
  try {
    await api(`/restaurant/retours-ventes/${d.retour.id}/${d.approuver ? 'approuver' : 'refuser'}`, {
      method: 'POST', body: { commentaire: commentaire.value.trim() || null },
    })
    succes.value = d.approuver
      ? `Retour ${d.retour.reference} approuvé : le caissier peut rembourser ${fmtMontant(d.retour.montantTtc, d.retour.devise)}.`
      : `Retour ${d.retour.reference} refusé : le caissier est prévenu.`
    decision.value = null
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'La décision a échoué.')
  } finally {
    busy.value = false
  }
}

const fmtMontant = (m: number, devise?: 'CDF' | 'USD') =>
  devise === 'USD'
    ? new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(m || 0)
    : `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(m || 0)} FC`
const fmtQte = (q: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 3 }).format(q || 0)
const fmtHeure = (d?: string | null) => (d ? new Date(d).toLocaleString('fr-FR') : '')
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Retours de ventes</h1>
        <p class="page-sub">Retours demandés par la caisse sur des ventes payées : à approuver avant le remboursement</p>
      </div>
      <v-btn-toggle v-model="filtre" mandatory color="primary" variant="outlined" rounded="lg" density="comfortable" class="rt-filtre">
        <v-btn value="A_DECIDER">À décider ({{ aDecider.length }})</v-btn>
        <v-btn value="TOUS">Tous</v-btn>
      </v-btn-toggle>
    </div>

    <v-alert v-if="erreur && !decision" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>

    <v-progress-linear v-if="loading" indeterminate color="primary" class="mb-3" />
    <v-alert v-if="!loading && !affiches.length" type="info" variant="tonal" rounded="lg">
      {{ filtre === 'A_DECIDER' ? 'Aucun retour n\'attend votre décision.' : 'Aucun retour de vente dans votre domaine.' }}
    </v-alert>

    <v-card v-for="r in affiches" :key="r.id" class="classroom-card pa-4 mb-3">
      <div class="rt-tete">
        <strong>{{ r.reference }}</strong>
        <v-chip :color="STATUTS[r.statut].color" size="small" variant="tonal">{{ STATUTS[r.statut].label }}</v-chip>
        <span class="rt-montant">{{ fmtMontant(r.montantTtc, r.devise) }}</span>
      </div>
      <p class="text-body-2 mb-1">
        Vente <strong>{{ r.venteReference }}</strong> · {{ r.client }}
      </p>
      <ul class="rt-lignes">
        <li v-for="l in r.lignes" :key="l.ligneVenteId">
          {{ fmtQte(l.quantite) }} × {{ l.designation }} — {{ fmtMontant(l.montantTtc, r.devise) }}
          <span class="text-caption text-medium-emphasis">{{ l.remiseEnStock ? '(remis en stock)' : '(non remis en stock)' }}</span>
        </li>
      </ul>
      <p class="text-body-2 mb-1"><span class="text-medium-emphasis">Motif :</span> {{ r.motif }}</p>
      <p class="text-caption text-medium-emphasis mb-0">
        Demandé par {{ r.demandeur || '?' }} le {{ fmtHeure(r.dateDemande) }}
        <template v-if="r.approbateur"> · décidé par {{ r.approbateur }} le {{ fmtHeure(r.dateDecision) }}<template
          v-if="r.commentaireDecision"> : « {{ r.commentaireDecision }} »</template></template>
        <template v-if="r.statut === 'REMBOURSE'"> · remboursé par {{ r.caissier }}</template>
      </p>
      <div v-if="r.statut === 'DEMANDE'" class="d-flex flex-wrap ga-2 mt-3">
        <v-btn color="success" variant="flat" rounded="lg" prepend-icon="mdi-check" :loading="busy" @click="ouvrirDecision(r, true)">
          Approuver
        </v-btn>
        <v-btn color="error" variant="tonal" rounded="lg" prepend-icon="mdi-close" :loading="busy" @click="ouvrirDecision(r, false)">
          Refuser
        </v-btn>
      </div>
    </v-card>

    <v-dialog :model-value="!!decision" max-width="480" @update:model-value="v => { if (!v && !busy) decision = null }">
      <v-card v-if="decision" rounded="lg">
        <v-card-title class="text-subtitle-1 font-weight-bold text-wrap">
          {{ decision.approuver ? 'Approuver' : 'Refuser' }} le retour {{ decision.retour.reference }} ?
        </v-card-title>
        <v-card-text>
          <v-alert v-if="erreur" type="error" variant="tonal" density="compact" rounded="lg" class="mb-3">{{ erreur }}</v-alert>
          <p class="mb-3">
            {{ decision.approuver
              ? `Le caissier pourra rembourser ${fmtMontant(decision.retour.montantTtc, decision.retour.devise)} au client.`
              : 'Le caissier sera prévenu du refus et de son motif.' }}
          </p>
          <v-textarea v-model="commentaire" :label="decision.approuver ? 'Commentaire (facultatif)' : 'Motif du refus *'"
            rows="2" auto-grow counter="500" maxlength="500" variant="outlined" density="comfortable" />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="busy" @click="decision = null">Annuler</v-btn>
          <v-btn :color="decision.approuver ? 'success' : 'error'" variant="flat" :loading="busy" @click="decider">
            {{ decision.approuver ? 'Approuver' : 'Refuser' }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.rt-tete { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 6px; }
.rt-montant { margin-left: auto; font-weight: 700; }
.rt-lignes { margin: 4px 0 8px 18px; padding: 0; font-size: 0.9rem; }
/* Téléphone : la bascule prend toute la largeur, « À décider (n) » tient sur une ligne. */
@media (max-width: 599px) {
  .rt-filtre { width: 100%; }
}
</style>
