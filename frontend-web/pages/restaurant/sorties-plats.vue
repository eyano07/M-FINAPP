<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Sorties de plats hors vente : périmé, moisi, renversé, brûlé, offert,
 * repas du personnel...
 *
 * Chaque sortie est comptabilisée au coût de production moyen du plat, comme
 * la part « coût » d'une vente : D 736x Variations des stocks de produits
 * finis / C 361x stock du plat (voir RestaurantService.enregistrerSortiePlat).
 * Chaque motif a son compte de débit (COMPTES_MOTIF ci-dessous, créés par V121) :
 * D compte du motif / C 361x ; seule la sortie d'un plat vendu passe par le
 * compte de variation du plat (736x).
 * Une sortie saisie par erreur s'annule d'ici : les portions reviennent en
 * stock et la pièce est extournée.
 */
interface StockNiveau {
  articleId: number
  articleCode: string
  articleLibelle: string
  uniteMesure?: string
  entrepotId: number
  entrepotCode: string
  quantite: number
  coutMoyen: number
}
interface ArticleCarte { id: number; type: 'PLAT' | 'BOISSON' }
interface Entrepot { id: number; code: string; nom: string }
type Motif = 'PERIME' | 'MOISI' | 'RENVERSE' | 'BRULE' | 'CADEAU' | 'REPAS_PERSONNEL' | 'AUTRE'
interface SortiePlat {
  id: number
  articleId: number
  articleCode: string
  articleLibelle: string
  uniteMesure?: string
  entrepotCode: string
  quantite: number
  motif: Motif
  motifLibelle: string
  precision?: string | null
  dateSortie: string
  valeur: number
  mouvementReference: string
  pieceReference?: string | null
  annulee: boolean
  creePar?: string | null
}

/** Mêmes libellés que MotifSortiePlat côté serveur. */
const MOTIFS: { value: Motif; title: string; couleur: string }[] = [
  { value: 'PERIME', title: 'Périmé', couleur: 'warning' },
  { value: 'MOISI', title: 'Moisi / avarié', couleur: 'warning' },
  { value: 'RENVERSE', title: 'Renversé / tombé', couleur: 'deep-orange' },
  { value: 'BRULE', title: 'Brûlé / raté', couleur: 'deep-orange' },
  { value: 'CADEAU', title: 'Offert (cadeau)', couleur: 'purple' },
  { value: 'REPAS_PERSONNEL', title: 'Repas du personnel', couleur: 'blue' },
  { value: 'AUTRE', title: 'Autre (à préciser)', couleur: 'grey' },
]
const couleurMotif = (m: Motif) => MOTIFS.find(x => x.value === m)?.couleur ?? 'grey'

/**
 * Compte débité par la sortie de chaque motif (RestaurantService.compteSortiePlat, créés par V121) : les pertes restent en
 * variation des stocks de produits finis, à part de la sortie des plats vendus ; un cadeau et un repas du personnel sont
 * des charges par nature (relations publiques, avantages en nature).
 */
const COMPTES_MOTIF: Record<Motif, string> = {
  PERIME: '736.1 Plats périmés',
  MOISI: '736.2 Plats moisis ou avariés',
  RENVERSE: '736.3 Plats renversés ou tombés',
  BRULE: '736.4 Plats brûlés ou ratés',
  CADEAU: '6276.2 Plats offerts à la clientèle',
  REPAS_PERSONNEL: '6617.1 Repas du personnel',
  AUTRE: '736.5 Autres sorties de plats',
}

/** Portions périmées d'un plat dans un entrepôt (PlatPerimeResponse côté serveur). */
interface PlatPerime {
  platId: number
  platCode: string
  platLibelle: string
  uniteMesure?: string
  entrepotId: number
  entrepotCode: string
  quantitePerimee: number
  valeur: number
  perimeDepuis: string
  lots: { productionReference: string; dateProduction: string; dateExpiration: string; quantite: number }[]
}

const api = useApi()
const auth = useAuthStore()
const parametres = useRestaurantParametresStore()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const niveaux = ref<StockNiveau[]>([])
const typesParArticle = ref<Record<number, string>>({})
const entrepots = ref<Entrepot[]>([])
const sorties = ref<SortiePlat[]>([])
const perimes = ref<PlatPerime[]>([])
const retraitEnCours = ref(false)

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT', 'ADMIN']))

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
    const [stock, carte, ents, hist, per] = await Promise.all([
      api<StockNiveau[]>('/restaurant/stock'),
      api<ArticleCarte[]>('/restaurant/carte').catch(() => []),
      api<Entrepot[]>('/restaurant/entrepots').catch(() => []),
      api<SortiePlat[]>('/restaurant/plats/sorties'),
      api<PlatPerime[]>('/restaurant/plats/perimes').catch(() => []),
      parametres.charger(),
    ])
    niveaux.value = stock
    typesParArticle.value = Object.fromEntries(carte.map(a => [a.id, a.type]))
    entrepots.value = ents
    sorties.value = hist
    perimes.value = per
    if (!form.entrepotId && ents.length === 1) {
      form.entrepotId = ents[0].id
    }
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les sorties de plats.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

/** Plats en stock dans l'entrepôt choisi : on ne peut sortir que ce qui s'y trouve. */
const platsEnStock = computed(() =>
  niveaux.value.filter(n => typesParArticle.value[n.articleId] === 'PLAT'
    && n.quantite > 0 && (!form.entrepotId || n.entrepotId === form.entrepotId)))
const niveauChoisi = computed(() =>
  platsEnStock.value.find(n => n.articleId === form.articleId) ?? null)
watch(() => form.entrepotId, () => {
  if (form.articleId && !niveauChoisi.value) form.articleId = null
})

/** Coût qui sortira du stock (et passera en écriture) : quantité × coût de production moyen. */
const valeurEstimee = computed(() =>
  niveauChoisi.value && form.quantite && form.quantite > 0 ? form.quantite * (niveauChoisi.value.coutMoyen || 0) : null)

async function enregistrer() {
  if (!form.entrepotId || !form.articleId || !form.quantite || form.quantite <= 0 || !form.motif) {
    erreur.value = 'Entrepôt, plat, quantité et motif sont obligatoires.'
    return
  }
  if (niveauChoisi.value && form.quantite > niveauChoisi.value.quantite) {
    erreur.value = `Il n'y a que ${fmtQte(niveauChoisi.value.quantite)} ${niveauChoisi.value.uniteMesure || ''} de ce plat en stock.`
    return
  }
  if (form.motif === 'AUTRE' && !form.precision.trim()) {
    erreur.value = 'Précisez la raison de la sortie : le motif « Autre » ne l\'explique pas.'
    return
  }
  saving.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const s = await api<SortiePlat>('/restaurant/plats/sorties', {
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
    succes.value = `Sortie enregistrée : ${fmtQte(s.quantite)} ${s.uniteMesure || ''} de ${s.articleLibelle} (${s.motifLibelle}), `
      + `${parametres.fmtMontant(s.valeur)}`
      + (s.pieceReference ? ` — pièce ${s.pieceReference}.` : ' — coût nul, aucune écriture.')
    form.quantite = null
    form.precision = ''
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement de la sortie.")
  } finally {
    saving.value = false
  }
}

// ── Plats périmés : la date d'expiration de leur production est passée ──────────────────────────────────
// Durée de conservation de la fiche technique + date de production = date d'expiration. Les portions encore en stock
// ensuite sont périmées : elles ne se vendent plus, et se retirent ici en perte « Périmé » (avec l'écriture comptable).
const valeurPerimes = computed(() => perimes.value.reduce((t, p) => t + (p.valeur || 0), 0))
const portionsPerimes = computed(() => perimes.value.reduce((t, p) => t + (p.quantitePerimee || 0), 0))

async function retirerPerimes(p: PlatPerime | null) {
  const cible = p ? [p] : perimes.value
  const valeur = cible.reduce((t, x) => t + (x.valeur || 0), 0)
  const portions = cible.reduce((t, x) => t + (x.quantitePerimee || 0), 0)
  const quoi = p ? `${fmtQte(portions)} portion(s) de ${p.platLibelle}` : `${fmtQte(portions)} portion(s) périmée(s)`
  if (!confirm(`Retirer ${quoi} du stock ? Une sortie « Périmé » de ${parametres.fmtMontant(valeur)} et son écriture comptable seront enregistrées.`)) {
    return
  }
  retraitEnCours.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const faites = await api<SortiePlat[]>('/restaurant/plats/perimes/retirer', {
      method: 'POST',
      body: p ? { platId: p.platId, entrepotId: p.entrepotId } : {},
    })
    succes.value = `${faites.length} sortie${faites.length > 1 ? 's' : ''} « Périmé » enregistrée${faites.length > 1 ? 's' : ''} : `
      + faites.map(s => `${fmtQte(s.quantite)} ${s.uniteMesure || ''} de ${s.articleLibelle}`).join(', ') + '.'
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec du retrait des plats périmés.')
  } finally {
    retraitEnCours.value = false
  }
}

// ── Récapitulatif (sorties non annulées) ───────────────────────────────────
const actives = computed(() => sorties.value.filter(s => !s.annulee))
const valeurTotale = computed(() => actives.value.reduce((t, s) => t + (s.valeur || 0), 0))
const parMotif = computed(() => MOTIFS
  .map(m => {
    const liste = actives.value.filter(s => s.motif === m.value)
    return { ...m, nombre: liste.length, valeur: liste.reduce((t, s) => t + (s.valeur || 0), 0) }
  })
  .filter(m => m.nombre > 0))

// ── Annulation d'une sortie saisie par erreur ──────────────────────────────
const aAnnuler = ref<SortiePlat | null>(null)
const annulation = ref(false)

async function confirmerAnnulation() {
  const s = aAnnuler.value
  if (!s) return
  annulation.value = true
  erreur.value = ''
  succes.value = ''
  try {
    await api(`/restaurant/plats/sorties/${s.id}/annuler`, { method: 'POST' })
    succes.value = `Sortie annulée : ${fmtQte(s.quantite)} ${s.uniteMesure || ''} de ${s.articleLibelle} reviennent en stock, `
      + `et l'écriture est extournée.`
    aAnnuler.value = null
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'annulation de la sortie.")
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
        <h1 class="page-title">Sorties de plats</h1>
        <p class="page-sub">Périmé, moisi, renversé, offert… — comptabilisées au coût de production</p>
      </div>
      <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-view-list-outline" to="/restaurant/stock/consolide">
        Stock consolidé
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" class="mb-4" closable @click:close="succes = ''">
      {{ succes }}
    </v-alert>

    <v-card v-if="perimes.length" class="classroom-card pa-5 mb-5 rst-perimes">
      <div class="d-flex align-center flex-wrap ga-3 mb-2">
        <p class="rst-form-title ma-0">
          <v-icon icon="mdi-timer-sand-complete" size="18" color="warning" class="mr-1" />
          Plats périmés — {{ fmtQte(portionsPerimes) }} portion{{ portionsPerimes > 1 ? 's' : '' }} à retirer du stock
        </p>
        <v-spacer />
        <span class="text-body-2">Valeur : <strong>{{ parametres.fmtMontant(valeurPerimes) }}</strong></span>
        <v-btn v-if="canWrite" color="warning" variant="flat" rounded="lg" prepend-icon="mdi-delete-sweep-outline"
          :loading="retraitEnCours" @click="retirerPerimes(null)">
          Tout retirer
        </v-btn>
      </div>
      <p class="text-caption text-medium-emphasis mb-1">
        Ces portions ont dépassé la date d'expiration de leur production (durée de conservation de la fiche technique) sans
        être vendues : elles ne se vendent plus. Les retirer les enregistre en perte « Périmé », avec son écriture.
      </p>
      <p v-if="parametres.retraitPerimes === 'AUTOMATIQUE'" class="text-caption text-medium-emphasis mb-3 rst-perimes__mode">
        <v-icon icon="mdi-autorenew" size="14" class="mr-1" />Retrait automatique activé : ces portions seront retirées à
        la prochaine vérification (toutes les 30 minutes). Si elles sont encore là plus tard, le retrait automatique est
        suspendu ou en échec — une notification vous prévient — et vous pouvez les retirer ici.
      </p>
      <p v-else class="text-caption text-medium-emphasis mb-3 rst-perimes__mode">
        <v-icon icon="mdi-bell-outline" size="14" class="mr-1" />Retrait manuel : une notification vous prévient chaque jour
        tant qu'il reste des plats périmés. L'administrateur peut choisir le retrait automatique dans les paramètres.
      </p>
      <div v-for="p in perimes" :key="`${p.platId}-${p.entrepotId}`" class="rst-perime">
        <div class="rst-perime__plat">
          <span class="font-weight-medium">{{ p.platLibelle }}</span>
          <span class="text-medium-emphasis text-caption"> · {{ p.entrepotCode }}</span>
          <div class="text-caption text-medium-emphasis">
            <span v-for="(l, i) in p.lots" :key="l.productionReference">
              {{ i ? ' · ' : '' }}{{ l.productionReference }} : {{ fmtQte(l.quantite) }}, expirée le {{ fmtDate(l.dateExpiration) }}
            </span>
          </div>
        </div>
        <span class="rst-perime__qte">{{ fmtQte(p.quantitePerimee) }} {{ p.uniteMesure || '' }}</span>
        <span class="rst-perime__valeur">{{ parametres.fmtMontant(p.valeur) }}</span>
        <v-btn v-if="canWrite" size="small" variant="tonal" color="warning" :loading="retraitEnCours" @click="retirerPerimes(p)">
          Retirer
        </v-btn>
      </div>
    </v-card>

    <v-card v-if="canWrite" class="classroom-card pa-5 mb-5">
      <p class="rst-form-title"><v-icon icon="mdi-food-off-outline" size="16" class="mr-1" />Nouvelle sortie</p>
      <v-alert v-if="!loading && !platsEnStock.length" type="info" variant="tonal" rounded="lg" density="comfortable" class="mb-4">
        Aucun plat en stock{{ form.entrepotId ? ' dans cet entrepôt' : '' }} : les plats entrent en stock par la production.
      </v-alert>
      <div class="rst-grid">
        <v-select v-model="form.entrepotId" :items="entrepots.map(e => ({ title: `${e.code} — ${e.nom}`, value: e.id }))"
          label="Entrepôt *" variant="outlined" density="comfortable" />
        <v-select
          v-model="form.articleId"
          :items="platsEnStock.map(n => ({ title: `${n.articleLibelle} — ${fmtQte(n.quantite)} ${n.uniteMesure || ''} en stock`, value: n.articleId }))"
          label="Plat *" variant="outlined" density="comfortable" no-data-text="Aucun plat en stock"
        />
        <v-text-field v-model.number="form.quantite" type="number" min="0" label="Quantité *"
          :suffix="niveauChoisi?.uniteMesure || ''" variant="outlined" density="comfortable" />
        <v-select v-model="form.motif" :items="MOTIFS" item-title="title" item-value="value"
          label="Motif *" variant="outlined" density="comfortable" />
        <v-text-field v-model="form.dateSortie" type="date" label="Date" variant="outlined" density="comfortable" />
      </div>
      <v-textarea
        v-model="form.precision"
        :label="form.motif === 'AUTRE' ? 'Précision *' : 'Précision (facultative)'"
        rows="2" auto-grow counter="500" maxlength="500"
        placeholder="Ex. : sauce tournée, plateau renversé en salle, offert à la table 4..."
        variant="outlined" density="comfortable" class="mt-1"
      />
      <div class="d-flex flex-wrap align-center ga-3 mt-1">
        <div class="text-body-2">
          <template v-if="valeurEstimee != null">
            Valeur qui sortira du stock : <strong>{{ parametres.fmtMontant(valeurEstimee) }}</strong>
            <span v-if="valeurEstimee === 0" class="text-warning"> — coût de production nul, aucune écriture</span>
          </template>
        </div>
        <v-spacer />
        <v-btn color="error" variant="tonal" rounded="lg" prepend-icon="mdi-food-off-outline" :loading="saving"
          :disabled="!platsEnStock.length" @click="enregistrer">
          Enregistrer la sortie
        </v-btn>
      </div>
      <p v-if="form.motif" class="text-caption text-medium-emphasis mt-3 mb-0 rst-compta-motif">
        Comptabilisée au coût de production moyen, au compte du motif :
        D {{ COMPTES_MOTIF[form.motif] }} / C 361 stock du plat (journal STOCK).
      </p>
      <p v-else class="text-caption text-medium-emphasis mt-3 mb-0 rst-compta-motif">
        Comptabilisée au coût de production moyen, au compte du motif choisi (perte de plat : 736.1 à 736.5 ; plat offert :
        6276.2 ; repas du personnel : 6617.1) / C 361 stock du plat (journal STOCK).
      </p>
    </v-card>

    <div class="rst-stats mb-4">
      <div class="rst-stat rst-stat--valeur">
        <v-icon icon="mdi-cash-minus" size="18" />
        <span class="rst-stat__val">{{ parametres.fmtMontant(valeurTotale) }}</span>
        <span class="rst-stat__lbl">Valeur sortie</span>
      </div>
      <div class="rst-stat rst-stat--nombre">
        <v-icon icon="mdi-food-off-outline" size="18" />
        <span class="rst-stat__val">{{ actives.length }}</span>
        <span class="rst-stat__lbl">Sorties</span>
      </div>
      <v-chip v-for="m in parMotif" :key="m.value" :color="m.couleur" variant="tonal" size="large" class="rst-motif">
        {{ m.title.replace(' (à préciser)', '') }} : {{ m.nombre }} — {{ parametres.fmtMontant(m.valeur) }}
      </v-chip>
    </div>

    <v-card class="classroom-card">
      <div class="rst-card-head">
        <v-icon icon="mdi-history" size="18" class="mr-2" />
        Historique
      </div>
      <v-data-table
        :headers="[
          { title: 'Date', key: 'dateSortie' },
          { title: 'Plat', key: 'articleLibelle' },
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
          <v-chip :color="couleurMotif(item.motif)" size="small" variant="tonal">{{ item.motifLibelle }}</v-chip>
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
          <v-btn
            v-if="canWrite && !item.annulee"
            icon="mdi-undo-variant"
            size="small"
            variant="text"
            color="error"
            :title="`Annuler la sortie de ${item.articleLibelle}`"
            :aria-label="`Annuler la sortie de ${item.articleLibelle}`"
            @click="aAnnuler = item"
          />
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">Aucune sortie de plat enregistrée.</div>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog :model-value="!!aAnnuler" max-width="480" @update:model-value="v => { if (!v && !annulation) aAnnuler = null }">
      <v-card v-if="aAnnuler" rounded="lg">
        <v-card-title class="text-subtitle-1 font-weight-bold">Annuler cette sortie ?</v-card-title>
        <v-card-text>
          <p class="mb-2">
            {{ fmtQte(aAnnuler.quantite) }} {{ aAnnuler.uniteMesure || '' }} de {{ aAnnuler.articleLibelle }}
            ({{ aAnnuler.motifLibelle }}) reviennent en stock, et l'écriture comptable est extournée.
          </p>
          <p class="text-medium-emphasis mb-0">La sortie reste dans l'historique, marquée annulée.</p>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="annulation" @click="aAnnuler = null">Garder</v-btn>
          <v-btn color="error" variant="flat" :loading="annulation" @click="confirmerAnnulation">Annuler la sortie</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.rst-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; }
@media (max-width: 1200px) { .rst-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 600px) { .rst-grid { grid-template-columns: 1fr; } }
.rst-form-title { font-size: 0.85rem; font-weight: 700; color: #374151; margin: 0 0 16px; display: flex; align-items: center; }
.rst-card-head {
  display: flex;
  align-items: center;
  font-size: 0.8rem;
  font-weight: 700;
  letter-spacing: 0.3px;
  text-transform: uppercase;
  color: #6b7280;
  padding: 16px 20px 12px;
}
.rst-stats { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
.rst-stat {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 18px;
  border-radius: 12px;
  font-size: 0.875rem;
}
.rst-stat--valeur { background: linear-gradient(135deg,#fef2f2,#fee2e2); color: #b91c1c; }
.rst-stat--nombre { background: linear-gradient(135deg,var(--color-primary-lighter),var(--color-primary-light)); color: var(--color-primary-dark); }
.rst-stat__val { font-size: 1.1rem; font-weight: 800; letter-spacing: -0.5px; }
.rst-stat__lbl { font-weight: 500; opacity: 0.75; }
.rst-motif { font-weight: 600; }
.rst-perimes { border-left: 4px solid rgb(var(--v-theme-warning)); }
.rst-perime { display: flex; align-items: center; gap: 14px; padding: 8px 0; border-top: 1px solid #f0f0f0; }
.rst-perime__plat { flex: 1 1 auto; min-width: 0; }
.rst-perime__qte { flex: 0 0 auto; font-weight: 700; color: #b45309; white-space: nowrap; }
.rst-perime__valeur { flex: 0 0 110px; text-align: right; color: #6b7280; font-size: 0.875rem; white-space: nowrap; }
</style>
