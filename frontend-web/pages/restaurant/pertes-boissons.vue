<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT', roles: ['RESP_RESTAURANT', 'RESP_BAR', 'DFIN', 'DG', 'DA', 'COMPTABLE', 'ADMIN'] })

/**
 * Pertes des boissons sans consigne (whisky, vin, champagne, jus en carton… : V134) : casse, boisson périmée, boisson
 * offerte. Ces boissons n'ont pas de bouteilles vides : leurs pertes ne passent pas par les mouvements de vides (réservés
 * aux boissons consignées, dont la bouteille suit). Chaque perte sort la boisson du stock au coût moyen, au compte de la
 * même perte d'une boisson consignée (RestaurantService.compteChargePerte). Ce que des commandes en attente ont mis de côté
 * ne se perd pas. Une perte saisie par erreur s'annule d'ici : la boisson revient en stock, la pièce est extournée.
 */
interface StockNiveau {
  articleId: number
  articleLibelle: string
  uniteMesure?: string
  entrepotId: number
  quantite: number
  coutMoyen: number
  quantiteReservee?: number
  quantiteDisponible?: number
}
interface ArticleCarte { id: number; type: 'PLAT' | 'BOISSON'; sansConsigne?: boolean }
interface Entrepot { id: number; code: string; nom: string }
type Motif = 'CASSE' | 'PERIME' | 'CADEAU'
interface SortieBoisson {
  id: number
  articleId: number
  articleLibelle: string
  uniteMesure?: string
  entrepotCode: string
  quantite: number
  motif: Motif
  motifLibelle: string
  precision?: string | null
  dateSortie: string
  valeur: number
  pieceReference?: string | null
  annulee: boolean
  creePar?: string | null
}

/** Mêmes libellés que MotifSortieBoisson côté serveur. */
const MOTIFS: { value: Motif; title: string; couleur: string; compte: string }[] = [
  { value: 'CASSE', title: 'Casse', couleur: 'error', compte: 'compte de variation de la boisson (6031)' },
  { value: 'PERIME', title: 'Périmée', couleur: 'deep-orange', compte: '6031.2 Boissons périmées' },
  { value: 'CADEAU', title: 'Offerte (cadeau)', couleur: 'pink', compte: '6276.1 Boissons offertes à la clientèle' },
]
const metaMotif = (m: Motif) => MOTIFS.find(x => x.value === m)

const api = useApi()
const auth = useAuthStore()
const parametres = useRestaurantParametresStore()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const niveaux = ref<StockNiveau[]>([])
const sansConsigne = ref<Set<number>>(new Set())
const entrepots = ref<Entrepot[]>([])
const sorties = ref<SortieBoisson[]>([])

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT', 'RESP_BAR', 'ADMIN']))

const form = reactive({
  entrepotId: null as number | null,
  articleId: null as number | null,
  quantite: null as number | null,
  motif: null as Motif | null,
  precision: '',
  dateSortie: new Date().toISOString().slice(0, 10),
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [stock, carte, ents, hist] = await Promise.all([
      api<StockNiveau[]>('/restaurant/stock'),
      api<ArticleCarte[]>('/restaurant/carte').catch(() => []),
      api<Entrepot[]>('/restaurant/entrepots').catch(() => []),
      api<SortieBoisson[]>('/restaurant/boissons/sorties'),
      parametres.charger(),
    ])
    niveaux.value = stock
    sansConsigne.value = new Set(carte.filter(a => a.type === 'BOISSON' && a.sansConsigne).map(a => a.id))
    entrepots.value = ents
    sorties.value = hist
    if (!form.entrepotId && ents.length === 1) form.entrepotId = ents[0].id
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les pertes de boissons.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

const disponible = (n: StockNiveau) => n.quantiteDisponible ?? n.quantite

/** Boissons sans consigne en stock dans l'entrepôt choisi. */
const boissonsEnStock = computed(() =>
  niveaux.value.filter(n => sansConsigne.value.has(n.articleId) && n.quantite > 0
    && (!form.entrepotId || n.entrepotId === form.entrepotId)))
const niveauChoisi = computed(() => boissonsEnStock.value.find(n => n.articleId === form.articleId) ?? null)
watch(() => form.entrepotId, () => {
  if (form.articleId && !niveauChoisi.value) form.articleId = null
})

const libelleNiveau = (n: StockNiveau) => {
  const r = n.quantiteReservee || 0
  return `${n.articleLibelle} — ${fmtQte(disponible(n))} disponible(s)` + (r > 0 ? ` (${fmtQte(r)} mis de côté)` : '')
}

const valeurEstimee = computed(() =>
  niveauChoisi.value && form.quantite && form.quantite > 0 ? form.quantite * (niveauChoisi.value.coutMoyen || 0) : null)

async function enregistrer() {
  if (!form.entrepotId || !form.articleId || !form.quantite || form.quantite <= 0 || !form.motif) {
    erreur.value = 'Entrepôt, boisson, quantité et motif sont obligatoires.'
    return
  }
  if (niveauChoisi.value && form.quantite > disponible(niveauChoisi.value)) {
    erreur.value = `Seulement ${fmtQte(disponible(niveauChoisi.value))} disponible(s) : le reste est en stock mais mis de côté par des commandes en attente.`
    return
  }
  saving.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const s = await api<SortieBoisson>('/restaurant/boissons/sorties', {
      method: 'POST',
      body: {
        articleId: form.articleId,
        quantite: form.quantite,
        entrepotId: form.entrepotId,
        motif: form.motif,
        precision: form.precision.trim() || null,
        dateSortie: form.dateSortie,
      },
    })
    succes.value = `Perte enregistrée : ${fmtQte(s.quantite)} ${s.uniteMesure || ''} de ${s.articleLibelle} (${s.motifLibelle}), `
      + `${parametres.fmtMontant(s.valeur)}` + (s.pieceReference ? ` — pièce ${s.pieceReference}.` : ' — coût nul, aucune écriture.')
    form.quantite = null
    form.precision = ''
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement de la perte.")
  } finally {
    saving.value = false
  }
}

const aAnnuler = ref<SortieBoisson | null>(null)
const annulation = ref(false)

async function confirmerAnnulation() {
  const s = aAnnuler.value
  if (!s) return
  annulation.value = true
  erreur.value = ''
  succes.value = ''
  try {
    await api(`/restaurant/boissons/sorties/${s.id}/annuler`, { method: 'POST' })
    succes.value = `Perte annulée : ${fmtQte(s.quantite)} ${s.uniteMesure || ''} de ${s.articleLibelle} reviennent en stock, l'écriture est extournée.`
    aAnnuler.value = null
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'annulation de la perte.")
    aAnnuler.value = null
  } finally {
    annulation.value = false
  }
}

const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
const fmtQte = (q: number) => parametres.fmtQuantite(q)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Pertes de boissons sans consigne</h1>
        <p class="page-sub">Whisky, vin, jus en carton… : casse, périmée ou offerte — comptabilisées au coût moyen</p>
      </div>
      <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-bottle-wine-outline" to="/restaurant/emballages/mouvements">
        Bouteilles consignées
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>

    <v-card v-if="canWrite" class="classroom-card pa-5 mb-5">
      <p class="pb-form-title"><v-icon icon="mdi-glass-tulip" size="16" class="mr-1" />Nouvelle perte</p>
      <p class="text-caption text-medium-emphasis mb-4">
        Une boisson consignée (bière, sucrerie en casier) se déclare par les mouvements de bouteilles vides : sa bouteille
        suit. Ici, seulement les boissons sans consigne.
      </p>
      <v-alert v-if="!loading && !boissonsEnStock.length" type="info" variant="tonal" rounded="lg" density="comfortable" class="mb-4">
        Aucune boisson sans consigne en stock{{ form.entrepotId ? ' dans cet entrepôt' : '' }}.
      </v-alert>
      <div class="pb-grid">
        <v-select v-model="form.entrepotId" :items="entrepots.map(e => ({ title: `${e.code} — ${e.nom}`, value: e.id }))"
          label="Entrepôt *" variant="outlined" density="comfortable" />
        <v-select v-model="form.articleId" :items="boissonsEnStock.map(n => ({ title: libelleNiveau(n), value: n.articleId }))"
          label="Boisson *" variant="outlined" density="comfortable" no-data-text="Aucune boisson sans consigne en stock" />
        <v-text-field v-model.number="form.quantite" type="number" min="0" label="Quantité *"
          :suffix="niveauChoisi?.uniteMesure || ''" variant="outlined" density="comfortable" />
        <v-select v-model="form.motif" :items="MOTIFS" item-title="title" item-value="value"
          label="Motif *" variant="outlined" density="comfortable" />
        <v-text-field v-model="form.dateSortie" type="date" label="Date" variant="outlined" density="comfortable" />
      </div>
      <v-textarea v-model="form.precision" label="Précision (facultative)" rows="2" auto-grow counter="500" maxlength="500"
        placeholder="Ex. : bouteille tombée au service, offerte à la table 4…" variant="outlined" density="comfortable" class="mt-1" />
      <div class="d-flex flex-wrap align-center ga-3 mt-1">
        <div class="text-body-2">
          <template v-if="valeurEstimee != null">
            Valeur qui sortira du stock : <strong>{{ parametres.fmtMontant(valeurEstimee) }}</strong>
          </template>
        </div>
        <v-spacer />
        <v-btn color="error" variant="tonal" rounded="lg" prepend-icon="mdi-glass-tulip" :loading="saving"
          :disabled="!boissonsEnStock.length" @click="enregistrer">
          Enregistrer la perte
        </v-btn>
      </div>
      <p class="text-caption text-medium-emphasis mt-3 mb-0">
        Comptabilisée au coût moyen : D {{ form.motif ? metaMotif(form.motif)?.compte : 'compte de la perte' }} / C 3111 stock de
        la boisson (journal STOCK).
      </p>
    </v-card>

    <v-card class="classroom-card">
      <div class="pb-card-head"><v-icon icon="mdi-history" size="18" class="mr-2" />Historique</div>
      <v-data-table
        :headers="[
          { title: 'Date', key: 'dateSortie' },
          { title: 'Boisson', key: 'articleLibelle' },
          { title: 'Motif', key: 'motif' },
          { title: 'Quantité', key: 'quantite', align: 'end' },
          { title: 'Valeur', key: 'valeur', align: 'end' },
          { title: 'Pièce', key: 'pieceReference' },
          { title: 'Saisie par', key: 'creePar' },
          { title: '', key: 'actions', align: 'end', sortable: false },
        ]"
        :items="sorties"
        :loading="loading"
        items-per-page="15"
      >
        <template #item.dateSortie="{ item }">{{ fmtDate(item.dateSortie) }}</template>
        <template #item.articleLibelle="{ item }">
          <span :class="{ 'text-decoration-line-through text-medium-emphasis': item.annulee }">{{ item.articleLibelle }}</span>
        </template>
        <template #item.motif="{ item }">
          <v-chip :color="metaMotif(item.motif)?.couleur || 'grey'" size="small" variant="tonal">{{ item.motifLibelle }}</v-chip>
          <div v-if="item.precision" class="text-caption text-medium-emphasis mt-1">{{ item.precision }}</div>
        </template>
        <template #item.quantite="{ item }">{{ fmtQte(item.quantite) }} {{ item.uniteMesure || '' }}</template>
        <template #item.valeur="{ item }">{{ parametres.fmtMontant(item.valeur) }}</template>
        <template #item.pieceReference="{ item }">
          {{ item.pieceReference || '—' }}
          <v-chip v-if="item.annulee" size="x-small" variant="tonal" color="grey" class="ml-1">Annulée</v-chip>
        </template>
        <template #item.creePar="{ item }">{{ item.creePar || '—' }}</template>
        <template #item.actions="{ item }">
          <v-btn v-if="canWrite && !item.annulee" icon="mdi-undo-variant" size="small" variant="text" color="error"
            :title="`Annuler la perte de ${item.articleLibelle}`" :aria-label="`Annuler la perte de ${item.articleLibelle}`"
            @click="aAnnuler = item" />
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">Aucune perte de boisson sans consigne enregistrée.</div>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog :model-value="!!aAnnuler" max-width="480" @update:model-value="v => { if (!v && !annulation) aAnnuler = null }">
      <v-card v-if="aAnnuler" rounded="lg">
        <v-card-title class="text-subtitle-1 font-weight-bold">Annuler cette perte ?</v-card-title>
        <v-card-text>
          {{ fmtQte(aAnnuler.quantite) }} {{ aAnnuler.uniteMesure || '' }} de {{ aAnnuler.articleLibelle }} ({{ aAnnuler.motifLibelle }})
          reviennent en stock, et l'écriture comptable est extournée. La perte reste dans l'historique, marquée annulée.
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="annulation" @click="aAnnuler = null">Garder</v-btn>
          <v-btn color="error" variant="flat" :loading="annulation" @click="confirmerAnnulation">Annuler la perte</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.pb-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; }
@media (max-width: 1200px) { .pb-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 600px) { .pb-grid { grid-template-columns: 1fr; } }
.pb-form-title { font-size: 0.85rem; font-weight: 700; color: #374151; margin: 0 0 8px; display: flex; align-items: center; }
.pb-card-head {
  display: flex;
  align-items: center;
  font-size: 0.8rem;
  font-weight: 700;
  letter-spacing: 0.3px;
  text-transform: uppercase;
  color: #6b7280;
  padding: 16px 20px 12px;
}
</style>
