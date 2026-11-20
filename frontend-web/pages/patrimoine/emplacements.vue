<script setup lang="ts">
/**
 * Emplacements du patrimoine (20/11/2026) : la logistique crée les lieux d'affectation des biens — bureaux, salles,
 * magasins… et, pour l'hôtellerie, les chambres, unités de motel et appartements, liés à l'unité correspondante de
 * l'application d'hôtellerie (qui y lit ensuite les biens de chaque logement).
 */
definePageMeta({ module: 'PATRIMOINE' })
useHead({ title: 'Emplacements du patrimoine' })

interface Emplacement { id: number, nom: string, type: string, description: string | null, pmsUniteId: number | null, uniteLibelle: string | null,
  actif: boolean, nbBiens: number }
interface UniteHotellerie { pmsId: number, numero: string, typeNom: string | null, etablissementNom: string | null, etablissementType: string | null,
  ameublement: string | null, emplacementId: number | null }

const TYPES: { value: string, title: string, icone: string }[] = [
  { value: 'BUREAU', title: 'Bureau', icone: 'mdi-desk' },
  { value: 'SALLE', title: 'Salle (réunion, réception…)', icone: 'mdi-table-chair' },
  { value: 'MAGASIN', title: 'Magasin / dépôt', icone: 'mdi-warehouse' },
  { value: 'ATELIER', title: 'Atelier', icone: 'mdi-hammer-wrench' },
  { value: 'CHAMBRE', title: 'Chambre d’hôtel', icone: 'mdi-bed-outline' },
  { value: 'APPARTEMENT', title: 'Appartement', icone: 'mdi-home-city-outline' },
  { value: 'UNITE_MOTEL', title: 'Unité de motel', icone: 'mdi-car-estate' },
  { value: 'PARTIE_COMMUNE', title: 'Partie commune (hall, couloir…)', icone: 'mdi-door-open' },
  { value: 'EXTERIEUR', title: 'Extérieur (cour, parking…)', icone: 'mdi-tree-outline' },
  { value: 'AUTRE', title: 'Autre', icone: 'mdi-map-marker-outline' },
]
const HOTELLERIE = ['CHAMBRE', 'APPARTEMENT', 'UNITE_MOTEL']
const typeDe = (v: string) => TYPES.find(t => t.value === v) ?? TYPES[TYPES.length - 1]

const api = useApi()
const auth = useAuthStore()
const peutEcrire = computed(() => auth.hasAnyRole(['LOGISTIQUE', 'GEST_PATRIMOINE', 'ADMIN']))
const emplacements = ref<Emplacement[]>([])
const unites = ref<UniteHotellerie[]>([])
const recherche = ref('')
const filtreType = ref<string | null>(null)
const erreur = ref('')
const succes = ref('')
const chargement = ref(false)

async function charger() {
  chargement.value = true
  try {
    const [e, u] = await Promise.all([api<Emplacement[]>('/patrimoine/emplacements'),
      api<UniteHotellerie[]>('/patrimoine/emplacements/unites-hotellerie').catch(() => [] as UniteHotellerie[])])
    emplacements.value = e
    unites.value = u
    erreur.value = ''
  } catch (err) {
    erreur.value = messageErreurApi(err, 'Impossible de charger les emplacements.')
  } finally {
    chargement.value = false
  }
}
onMounted(charger)

const filtres = computed(() => {
  const q = recherche.value.trim().toLowerCase()
  return emplacements.value.filter(e => (!filtreType.value || e.type === filtreType.value)
    && (!q || `${e.nom} ${e.description ?? ''} ${e.uniteLibelle ?? ''}`.toLowerCase().includes(q)))
})
const nonLiees = computed(() => unites.value.filter(u => !u.emplacementId))
const compte = (type: string) => emplacements.value.filter(e => e.type === type).length

// ── Création, modification ─────────────────────────────────────────────
const form = reactive({ ouvert: false, id: null as number | null, nom: '', type: 'BUREAU', description: '', pmsUniteId: null as number | null,
  actif: true, enCours: false, erreur: '' })
function ouvrir(e?: Emplacement) {
  Object.assign(form, e ? { ouvert: true, id: e.id, nom: e.nom, type: e.type, description: e.description ?? '', pmsUniteId: e.pmsUniteId, actif: e.actif }
    : { ouvert: true, id: null, nom: '', type: 'BUREAU', description: '', pmsUniteId: null, actif: true }, { enCours: false, erreur: '' })
}
const optionsUnites = computed(() => unites.value
  .filter(u => !u.emplacementId || u.emplacementId === form.id)
  .map(u => ({ value: u.pmsId, title: `${u.etablissementNom ?? ''} · ${u.numero}`, subtitle: [u.typeNom, u.ameublement === 'MEUBLE' ? 'meublé' : u.ameublement === 'NON_MEUBLE' ? 'non meublé' : null].filter(Boolean).join(' · ') })))
watch(() => form.pmsUniteId, (id) => {
  // Unité choisie : nom et type proposés d'après l'hôtellerie (modifiables).
  const u = unites.value.find(x => x.pmsId === id)
  if (!u || form.id) return
  if (!form.nom) form.nom = `${u.etablissementNom ?? ''} · ${u.numero}`.replace(/^ · /, '')
  form.type = u.etablissementType === 'MOTEL' ? 'UNITE_MOTEL' : u.etablissementType === 'APPARTEMENTS' ? 'APPARTEMENT' : 'CHAMBRE'
})
async function enregistrer() {
  form.erreur = ''
  form.enCours = true
  try {
    await api(form.id ? `/patrimoine/emplacements/${form.id}` : '/patrimoine/emplacements', { method: form.id ? 'PUT' : 'POST',
      body: { nom: form.nom, type: form.type, description: form.description || null, pmsUniteId: HOTELLERIE.includes(form.type) ? form.pmsUniteId : null, actif: form.actif } })
    succes.value = `Emplacement « ${form.nom} » enregistré.`
    form.ouvert = false
    await charger()
  } catch (e) {
    form.erreur = messageErreurApi(e)
  } finally {
    form.enCours = false
  }
}
async function supprimer(e: Emplacement) {
  if (!(await useDialogues().confirmer({ titre: `Supprimer l'emplacement « ${e.nom} » ?`, message: 'Il ne porte aucun bien.', bouton: 'Supprimer', nature: 'danger' }))) return
  try {
    await api(`/patrimoine/emplacements/${e.id}`, { method: 'DELETE' })
    await charger()
  } catch (err) { erreur.value = messageErreurApi(err) }
}
async function importer() {
  if (!(await useDialogues().confirmer({ titre: `Créer ${nonLiees.value.length} emplacement(s) depuis l'hôtellerie ?`,
    message: 'Un emplacement par chambre, unité de motel ou appartement qui n’en a pas encore, nommé « établissement · numéro ».', bouton: 'Créer' }))) return
  try {
    const r = await api<{ crees: number }>('/patrimoine/emplacements/import-hotellerie', { method: 'POST' })
    succes.value = `${r.crees} emplacement(s) créé(s) depuis l'hôtellerie.`
    await charger()
  } catch (err) { erreur.value = messageErreurApi(err) }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Emplacements</h1>
        <p class="page-sub">Bureaux, salles, chambres et appartements où sont affectés les biens du patrimoine</p>
      </div>
      <div class="d-flex flex-wrap ga-2">
        <v-btn variant="text" prepend-icon="mdi-format-list-checks" to="/patrimoine/inventaire">Inventaire</v-btn>
        <v-btn v-if="peutEcrire && nonLiees.length" variant="tonal" prepend-icon="mdi-bed-outline" @click="importer">
          Unités de l'hôtellerie ({{ nonLiees.length }})
        </v-btn>
        <v-btn v-if="peutEcrire" color="primary" prepend-icon="mdi-plus" @click="ouvrir()">Emplacement</v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-4" @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" closable class="mb-4" @click:close="succes = ''">{{ succes }}</v-alert>

    <div class="types mb-4">
      <button type="button" class="type" :class="{ 'type--actif': !filtreType }" @click="filtreType = null">
        <v-icon icon="mdi-map-marker-multiple-outline" size="18" />Tous<b>{{ emplacements.length }}</b>
      </button>
      <button v-for="t in TYPES.filter(x => compte(x.value))" :key="t.value" type="button" class="type" :class="{ 'type--actif': filtreType === t.value }"
        @click="filtreType = filtreType === t.value ? null : t.value">
        <v-icon :icon="t.icone" size="18" />{{ t.title.split(' (')[0] }}<b>{{ compte(t.value) }}</b>
      </button>
    </div>

    <v-text-field v-model="recherche" prepend-inner-icon="mdi-magnify" label="Rechercher un emplacement" hide-details clearable variant="outlined"
      density="comfortable" class="mb-4" style="max-width: 420px" />

    <v-progress-linear v-if="chargement" indeterminate color="primary" class="mb-2" />
    <div v-if="filtres.length" class="grille">
      <v-card v-for="e in filtres" :key="e.id" class="classroom-card pa-4 emplacement" :class="{ 'emplacement--inactif': !e.actif }">
        <div class="d-flex align-start ga-3">
          <span class="emplacement__icone"><v-icon :icon="typeDe(e.type).icone" size="22" /></span>
          <div class="flex-grow-1" style="min-width: 0">
            <div class="emplacement__nom">{{ e.nom }}</div>
            <div class="text-caption text-medium-emphasis">{{ typeDe(e.type).title }}<template v-if="!e.actif"> · désactivé</template></div>
          </div>
          <v-menu v-if="peutEcrire" location="bottom end">
            <template #activator="{ props }"><v-btn icon="mdi-dots-vertical" variant="text" size="small" v-bind="props" aria-label="Actions" /></template>
            <v-list density="compact">
              <v-list-item prepend-icon="mdi-pencil" title="Modifier" @click="ouvrir(e)" />
              <v-list-item v-if="!e.nbBiens" prepend-icon="mdi-delete-outline" title="Supprimer" @click="supprimer(e)" />
            </v-list>
          </v-menu>
        </div>
        <div v-if="e.uniteLibelle" class="emplacement__unite"><v-icon icon="mdi-link-variant" size="15" />Hôtellerie : {{ e.uniteLibelle }}</div>
        <div v-if="e.description" class="text-body-2 text-medium-emphasis mt-1">{{ e.description }}</div>
        <div class="emplacement__pied">
          <span><b>{{ e.nbBiens }}</b> bien(s)</span>
        </div>
      </v-card>
    </div>
    <v-card v-else-if="!chargement" class="classroom-card pa-8 text-center text-medium-emphasis">
      Aucun emplacement. Créez les bureaux et salles, ou reprenez d'un clic les chambres et appartements de l'hôtellerie.
    </v-card>

    <v-dialog v-model="form.ouvert" max-width="560">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">{{ form.id ? 'Modifier l’emplacement' : 'Nouvel emplacement' }}</v-card-title>
        <v-card-text>
          <v-alert v-if="form.erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ form.erreur }}</v-alert>
          <v-select v-model="form.type" :items="TYPES" item-title="title" item-value="value" label="Type" variant="outlined" density="comfortable">
            <template #item="{ props: p, item }"><v-list-item v-bind="p" :prepend-icon="item.raw.icone" /></template>
          </v-select>
          <v-autocomplete v-if="HOTELLERIE.includes(form.type)" v-model="form.pmsUniteId" :items="optionsUnites" label="Unité de l'hôtellerie liée"
            clearable variant="outlined" density="comfortable" :no-data-text="unites.length ? 'Toutes les unités sont déjà liées' : 'Aucune unité reçue de l’hôtellerie (module coupé ou non appairé)'">
            <template #item="{ props: p, item }"><v-list-item v-bind="p" :subtitle="item.raw.subtitle" /></template>
          </v-autocomplete>
          <v-text-field v-model="form.nom" label="Nom *" placeholder="Bureau du DFIN, Salle de réunion, Hôtel du Lac · 101…" variant="outlined" density="comfortable" />
          <v-text-field v-model="form.description" label="Description (étage, bâtiment…)" variant="outlined" density="comfortable" />
          <v-switch v-model="form.actif" color="primary" inset hide-details label="Emplacement actif" />
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="form.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="form.enCours" :disabled="!form.nom.trim()" @click="enregistrer">Enregistrer</v-btn></v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.types { display: flex; flex-wrap: wrap; gap: 8px; }
.type { display: inline-flex; align-items: center; gap: 8px; height: 38px; padding: 0 14px; border-radius: 999px; cursor: pointer; font: inherit; font-size: 0.86rem;
  font-weight: 600; border: 1px solid rgba(0, 0, 0, 0.1); background: rgb(var(--v-theme-surface)); }
.type b { font-variant-numeric: tabular-nums; opacity: 0.6; }
.type--actif { color: rgb(var(--v-theme-primary)); border-color: rgb(var(--v-theme-primary)); background: rgba(var(--v-theme-primary), 0.08); }
.grille { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 14px; }
.emplacement--inactif { opacity: 0.6; }
.emplacement__icone { width: 42px; height: 42px; border-radius: 13px; flex: none; display: grid; place-items: center; color: rgb(var(--v-theme-primary));
  background: rgba(var(--v-theme-primary), 0.1); }
.emplacement__nom { font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.emplacement__unite { display: inline-flex; align-items: center; gap: 6px; margin-top: 10px; padding: 3px 10px; border-radius: 999px; font-size: 0.78rem;
  font-weight: 600; color: #4338ca; background: rgba(67, 56, 202, 0.08); }
.emplacement__pied { margin-top: 12px; padding-top: 10px; border-top: 1px dashed rgba(0, 0, 0, 0.12); font-size: 0.85rem; }
</style>
