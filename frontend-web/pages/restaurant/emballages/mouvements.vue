<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Journal du stock de bouteilles vides.
 *
 * Les mouvements de vente sont générés automatiquement à la validation d'une
 * vente : casse, perte et ajustements d'inventaire se saisissent ici.
 *
 * La casse d'une bouteille pleine et la péremption font aussi sortir la
 * boisson de son propre stock (avec écriture comptable) : ces deux types
 * exigent donc un entrepôt.
 */
interface Emballage {
  id: number
  code: string
  libelle: string
  articleBoissonLibelle: string
  contenanceCasier: number
  bouteillesVides: number
}
interface Entrepot { id: number; code: string; nom: string }
interface Mouvement {
  id: number
  emballageId: number
  emballageCode: string
  emballageLibelle: string
  type: string
  quantite: number
  delta: number
  dateMouvement: string
  motif?: string
  venteReference?: string
  createdByNom?: string
}

const api = useApi()
const auth = useAuthStore()
const route = useRoute()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const emballages = ref<Emballage[]>([])
const entrepots = ref<Entrepot[]>([])
const mouvements = ref<Mouvement[]>([])
const dialog = ref(false)

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT']))

const filtreEmballage = ref<number | null>(null)
const du = ref('')
const au = ref('')

/** `sens` reflète TypeMouvementEmballage côté backend : sert à calculer l'aperçu avant saisie. */
const META_MVT: Record<string, { label: string; couleur: string; auto?: boolean; sens: number; necessiteEntrepot?: boolean }> = {
  VENTE: { label: 'Vente', couleur: 'success', auto: true, sens: 1 },
  RETOUR_VENTE: { label: 'Vente annulée', couleur: 'grey', auto: true, sens: -1 },
  ACHAT: { label: 'Réception (consigne rendue)', couleur: 'orange', sens: -1 },
  CASSE: { label: 'Casse — bouteille vide', couleur: 'error', sens: -1 },
  CASSE_PLEINE: { label: 'Casse — bouteille pleine', couleur: 'error', sens: -1, necessiteEntrepot: true },
  PERIME: { label: 'Boisson périmée', couleur: 'deep-orange', sens: 0, necessiteEntrepot: true },
  CADEAU: { label: 'Boisson offerte (cadeau)', couleur: 'pink', sens: 0, necessiteEntrepot: true },
  AJUSTEMENT_PLUS: { label: 'Ajustement +', couleur: 'primary', sens: 1 },
  AJUSTEMENT_MOINS: { label: 'Ajustement −', couleur: 'deep-orange', sens: -1 },
}
/** Seuls les mouvements non automatiques sont saisissables. */
const TYPES_SAISISSABLES = ['CASSE', 'CASSE_PLEINE', 'PERIME', 'CADEAU', 'AJUSTEMENT_PLUS', 'AJUSTEMENT_MOINS']

const form = reactive({
  emballageId: null as number | null,
  type: 'CASSE',
  quantite: null as number | null,
  dateMouvement: new Date().toISOString().slice(0, 10),
  motif: '',
  entrepotId: null as number | null,
})

const necessiteEntrepot = computed(() => !!META_MVT[form.type]?.necessiteEntrepot)

const emballageChoisi = computed(() =>
  emballages.value.find(e => e.id === form.emballageId) || null)

/** Aperçu de l'état résultant, en casiers + bouteilles : évite une saisie refusée. */
const apercu = computed(() => {
  const e = emballageChoisi.value
  const q = form.quantite
  const meta = META_MVT[form.type]
  if (!e || !q || q <= 0 || !meta) return null
  const apres = e.bouteillesVides + meta.sens * q
  if (apres < 0) return { invalide: true, apres, texte: '', sansEffet: false }
  return { invalide: false, apres, texte: formatCasiers(apres, e.contenanceCasier), sansEffet: meta.sens === 0 }
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const params: Record<string, any> = {}
    if (filtreEmballage.value) params.emballageId = filtreEmballage.value
    if (du.value) params.du = du.value
    if (au.value) params.au = au.value
    const [emb, ents, mvts] = await Promise.all([
      api<Emballage[]>('/restaurant/emballages'),
      api<Entrepot[]>('/restaurant/entrepots').catch(() => []),
      api<Mouvement[]>('/restaurant/emballages/mouvements', { params }),
    ])
    emballages.value = emb
    entrepots.value = ents
    mouvements.value = mvts
    if (ents.length === 1) form.entrepotId = ents[0].id
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les mouvements.')
  } finally {
    loading.value = false
  }
}
onMounted(() => {
  const id = Number(route.query.emballage)
  if (id) filtreEmballage.value = id
  charger()
})
watch([filtreEmballage, du, au], charger)

function ouvrirSaisie() {
  Object.assign(form, {
    emballageId: filtreEmballage.value,
    type: 'CASSE',
    quantite: null,
    dateMouvement: new Date().toISOString().slice(0, 10),
    motif: '',
    entrepotId: entrepots.value.length === 1 ? entrepots.value[0].id : null,
  })
  erreur.value = ''
  dialog.value = true
}

async function enregistrer() {
  if (!form.emballageId || !form.quantite || form.quantite <= 0) {
    erreur.value = 'Choisissez une boisson et une quantité strictement positive.'
    return
  }
  if (necessiteEntrepot.value && !form.entrepotId) {
    erreur.value = "Choisissez l'entrepôt : cette perte fait aussi sortir la boisson de son stock."
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    await api('/restaurant/emballages/mouvements', {
      method: 'POST',
      body: {
        emballageId: form.emballageId,
        type: form.type,
        quantite: form.quantite,
        dateMouvement: form.dateMouvement,
        motif: form.motif || null,
        entrepotId: necessiteEntrepot.value ? form.entrepotId : null,
      },
    })
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement du mouvement.")
  } finally {
    saving.value = false
  }
}

const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Mouvements des bouteilles vides</h1>
        <p class="page-sub">Ventes, réceptions, casse, péremption et ajustements d'inventaire</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-bottle-wine-outline" to="/restaurant/emballages">
          Le stock
        </v-btn>
        <v-btn v-if="canWrite" color="success" variant="flat" rounded="lg" prepend-icon="mdi-plus" @click="ouvrirSaisie">
          Casse / perte / ajustement
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <div class="d-flex ga-3 mb-4 flex-wrap">
      <v-select
        v-model="filtreEmballage"
        :items="emballages.map(e => ({ title: e.articleBoissonLibelle, value: e.id }))"
        label="Boisson"
        variant="outlined"
        density="comfortable"
        rounded="lg"
        hide-details
        clearable
        style="max-width: 320px"
      />
      <v-text-field v-model="du" type="date" label="Du" variant="outlined" density="comfortable" rounded="lg" hide-details style="max-width: 180px" />
      <v-text-field v-model="au" type="date" label="Au" variant="outlined" density="comfortable" rounded="lg" hide-details style="max-width: 180px" />
    </div>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Date', key: 'dateMouvement' },
          { title: 'Boisson', key: 'emballageLibelle' },
          { title: 'Mouvement', key: 'type' },
          { title: 'Effet sur les vides', key: 'delta', align: 'end' },
          { title: 'Motif', key: 'motif' },
          { title: 'Vente', key: 'venteReference' },
          { title: 'Saisi par', key: 'createdByNom' },
        ]"
        :items="mouvements"
        :loading="loading"
        items-per-page="25"
      >
        <template #item.dateMouvement="{ item }">{{ fmtDate(item.dateMouvement) }}</template>
        <template #item.emballageLibelle="{ item }">{{ item.emballageLibelle }}</template>
        <template #item.type="{ item }">
          <v-chip :color="META_MVT[item.type]?.couleur" size="small" variant="tonal">
            {{ META_MVT[item.type]?.label ?? item.type }}
          </v-chip>
          <v-icon v-if="META_MVT[item.type]?.auto" icon="mdi-flash-outline" size="14" class="ml-1 text-medium-emphasis" title="Automatique" />
        </template>
        <template #item.delta="{ item }">
          <span v-if="item.delta === 0" class="text-medium-emphasis">— (boisson seule)</span>
          <span v-else :class="item.delta > 0 ? 'text-success font-weight-bold' : 'text-error font-weight-bold'">
            {{ item.delta > 0 ? '+' : '' }}{{ item.delta }}
          </span>
        </template>
        <template #item.motif="{ item }">{{ item.motif || '—' }}</template>
        <template #item.venteReference="{ item }">{{ item.venteReference || '—' }}</template>
        <template #item.createdByNom="{ item }">{{ item.createdByNom || '—' }}</template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">Aucun mouvement sur cette période.</div>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="560" scrollable>
      <v-card class="pa-6">
        <h2 class="text-h6 mb-4">Casse, perte ou ajustement</h2>

        <v-alert v-if="erreur" type="error" variant="tonal" density="compact" rounded="lg" class="mb-4">{{ erreur }}</v-alert>

        <v-alert type="info" variant="tonal" density="compact" rounded="lg" class="mb-4">
          Les mouvements de vente sont générés automatiquement à la validation d'une vente : ils ne se saisissent pas ici.
        </v-alert>

        <v-select
          v-model="form.emballageId"
          :items="emballages.map(e => ({ title: e.articleBoissonLibelle, value: e.id }))"
          label="Boisson *"
          variant="outlined"
          density="comfortable"
          class="mb-3"
        />
        <v-select
          v-model="form.type"
          :items="TYPES_SAISISSABLES.map(t => ({ title: META_MVT[t].label, value: t }))"
          label="Type *"
          variant="outlined"
          density="comfortable"
          class="mb-1"
        />
        <p v-if="form.type === 'CASSE_PLEINE'" class="text-caption text-medium-emphasis mb-3">
          La bouteille n'a jamais été servie : le contenant est perdu (vides − N) ET la boisson qu'elle contenait aussi (stock − N).
        </p>
        <p v-else-if="form.type === 'PERIME'" class="text-caption text-medium-emphasis mb-3">
          Le contenant reste intact et pourra être rendu normalement : seul le stock de boisson diminue, les vides ne changent pas.
        </p>
        <p v-else-if="form.type === 'CADEAU'" class="text-caption text-medium-emphasis mb-3">
          Bouteille offerte (promotion, geste commercial) : seul le stock de boisson diminue, les vides ne changent pas.
        </p>

        <v-text-field v-model.number="form.quantite" type="number" label="Nombre de bouteilles *" variant="outlined" density="comfortable" class="mb-3" />

        <v-select
          v-if="necessiteEntrepot"
          v-model="form.entrepotId"
          :items="entrepots.map(e => ({ title: `${e.code} — ${e.nom}`, value: e.id }))"
          label="Entrepôt (sortie de la boisson) *"
          variant="outlined"
          density="comfortable"
          class="mb-3"
        />

        <v-text-field v-model="form.dateMouvement" type="date" label="Date" variant="outlined" density="comfortable" class="mb-3" />
        <v-textarea v-model="form.motif" label="Motif" rows="2" placeholder="Casse au bar, écart d'inventaire du..." variant="outlined" density="comfortable" class="mb-3" />

        <v-alert v-if="apercu" :type="apercu.invalide ? 'error' : 'info'" variant="tonal" density="compact" rounded="lg" class="mb-4">
          <template v-if="apercu.invalide">
            Impossible : le stock de vides passerait sous zéro.
            Il reste {{ emballageChoisi!.bouteillesVides }} bouteille(s) vide(s).
          </template>
          <template v-else-if="apercu.sansEffet">
            Le stock de vides ne change pas ({{ emballageChoisi!.bouteillesVides }} bouteille(s)).
            Seul le stock de la boisson diminuera de {{ form.quantite }}.
          </template>
          <template v-else>
            Vides après ce mouvement : <strong>{{ apercu.texte }}</strong>
            ({{ apercu.apres }} bouteille{{ apercu.apres > 1 ? 's' : '' }} au total).
          </template>
        </v-alert>

        <div class="d-flex justify-end ga-2">
          <v-btn variant="text" :disabled="saving" @click="dialog = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="saving" :disabled="apercu?.invalide" @click="enregistrer">
            Enregistrer
          </v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>
