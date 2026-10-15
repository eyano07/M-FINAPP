<script setup lang="ts">
/**
 * Services (départements) de l'entreprise, chacun avec le nom de son responsable. Les lignes de dépenses d'un
 * budget et les notes de frais s'y rattachent : chaque service a son enveloppe dans le budget, qui reste unique
 * (consolidé), et ses dépenses se suivent à part. Le DFIN (et l'administrateur) les gère ; un service déjà
 * utilisé ne se supprime plus, il se désactive.
 */
definePageMeta({ module: 'BUDGET', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] })

interface ServiceBudgetaire {
  id: number
  nom: string
  responsable: string
  description?: string | null
  actif: boolean
  nombreLignesBudget: number
  nombreNotes: number
  dateCreation?: string
  creeParNom?: string | null
}

useHead({ title: 'Services budgétaires' })
const api = useApi()
const auth = useAuthStore()
const rolesStore = useRolesStore()
const peutGerer = computed(() => auth.hasAnyRole(['DFIN', 'ADMIN']))

const services = ref<ServiceBudgetaire[]>([])
const chargement = ref(false)
const erreur = ref('')
const succes = ref('')
const recherche = ref('')

async function charger() {
  chargement.value = true
  erreur.value = ''
  try {
    services.value = await api<ServiceBudgetaire[]>('/services-budgetaires')
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les services.')
  } finally {
    chargement.value = false
  }
}
onMounted(charger)

const affiches = computed(() => services.value.filter(s => correspondRecherche([s.nom, s.responsable, s.description], recherche.value || '')))
const nombreActifs = computed(() => services.value.filter(s => s.actif).length)

// ── Création / modification ───────────────────────────────────────────────
const dialogue = ref(false)
const enEdition = ref<ServiceBudgetaire | null>(null)
const form = reactive({ nom: '', responsable: '', description: '' })
const enregistrement = ref(false)
const erreurDialogue = ref('')

function ouvrir(s: ServiceBudgetaire | null) {
  enEdition.value = s
  form.nom = s?.nom || ''
  form.responsable = s?.responsable || ''
  form.description = s?.description || ''
  erreurDialogue.value = ''
  dialogue.value = true
}

async function enregistrer() {
  if (!form.nom.trim() || !form.responsable.trim()) {
    erreurDialogue.value = 'Indiquez le nom du service et le nom de son responsable.'
    return
  }
  enregistrement.value = true
  erreurDialogue.value = ''
  try {
    const corps = { nom: form.nom.trim(), responsable: form.responsable.trim(), description: form.description.trim() || null }
    if (enEdition.value) {
      await api(`/services-budgetaires/${enEdition.value.id}`, { method: 'PUT', body: corps })
      succes.value = `Service « ${corps.nom} » modifié.`
    } else {
      await api('/services-budgetaires', { method: 'POST', body: corps })
      succes.value = `Service « ${corps.nom} » créé : vous pouvez lui attribuer des lignes dans un budget.`
    }
    dialogue.value = false
    await charger()
  } catch (e) {
    erreurDialogue.value = messageErreurApi(e, 'Enregistrement impossible.')
  } finally {
    enregistrement.value = false
  }
}

// ── Activation / suppression ──────────────────────────────────────────────
const action = ref<number | null>(null)
async function basculer(s: ServiceBudgetaire) {
  action.value = s.id
  erreur.value = ''
  try {
    const r = await api<ServiceBudgetaire>(`/services-budgetaires/${s.id}/actif`, { method: 'PATCH' })
    succes.value = r.actif ? `Service « ${s.nom} » réactivé.` : `Service « ${s.nom} » désactivé : il ne se choisit plus pour une nouvelle dépense, son historique reste.`
    await charger()
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Action impossible.')
  } finally {
    action.value = null
  }
}

const aSupprimer = ref<ServiceBudgetaire | null>(null)
async function supprimer() {
  const s = aSupprimer.value
  if (!s) return
  action.value = s.id
  try {
    await api(`/services-budgetaires/${s.id}`, { method: 'DELETE' })
    succes.value = `Service « ${s.nom} » supprimé.`
    aSupprimer.value = null
    await charger()
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Suppression impossible.')
    aSupprimer.value = null
  } finally {
    action.value = null
  }
}

const utilise = (s: ServiceBudgetaire) => s.nombreLignesBudget > 0 || s.nombreNotes > 0
const usage = (s: ServiceBudgetaire) => {
  const parts = []
  if (s.nombreLignesBudget) parts.push(`${s.nombreLignesBudget} ligne${s.nombreLignesBudget > 1 ? 's' : ''} de budget`)
  if (s.nombreNotes) parts.push(`${s.nombreNotes} note${s.nombreNotes > 1 ? 's' : ''} de frais`)
  return parts.length ? parts.join(' · ') : 'Pas encore utilisé'
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Services budgétaires</h1>
        <p class="page-sub">
          Chaque service porte le nom de son responsable. Ses dépenses se budgètent et se suivent à part, dans un budget unique (consolidé).
        </p>
      </div>
      <div class="d-flex flex-wrap ga-2">
        <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/budgets">Budgets</v-btn>
        <v-btn v-if="peutGerer" color="primary" prepend-icon="mdi-plus" height="44" @click="ouvrir(null)">Nouveau service</v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>

    <v-alert type="info" variant="tonal" density="compact" class="mb-4" icon="mdi-information-outline">
      Comment ça marche : créez vos services (ex. Restaurant, Logistique, Direction financière), puis dans un budget, attribuez à
      chaque service ses lignes de dépenses. Le budget reste unique et consolidé ; chaque note de frais rattachée à un service est
      contrôlée sur l’enveloppe de ce service, et le {{ rolesStore.libelle('DFIN') }} voit tout de suite les dépassements.
    </v-alert>

    <div class="d-flex align-center flex-wrap ga-3 mb-3">
      <v-text-field
        v-model="recherche"
        prepend-inner-icon="mdi-magnify"
        placeholder="Rechercher un service ou un responsable"
        aria-label="Rechercher un service"
        density="compact"
        hide-details
        clearable
        style="max-width: 380px"
      />
      <span class="text-caption text-medium-emphasis">{{ nombreActifs }} service{{ nombreActifs > 1 ? 's' : '' }} actif{{ nombreActifs > 1 ? 's' : '' }} sur {{ services.length }}</span>
    </div>

    <v-skeleton-loader v-if="chargement && !services.length" type="card, card" />

    <v-row v-else>
      <v-col v-for="s in affiches" :key="s.id" cols="12" md="6" lg="4">
        <v-card class="classroom-card pa-4 svb-carte" :class="{ 'svb-carte--inactif': !s.actif }" height="100%">
          <div class="d-flex align-start ga-2">
            <div class="svb-avatar" aria-hidden="true">{{ (s.nom || '?').slice(0, 2).toUpperCase() }}</div>
            <div class="flex-grow-1" style="min-width: 0">
              <div class="text-subtitle-1 font-weight-bold svb-nom">{{ s.nom }}</div>
              <div class="text-body-2"><v-icon icon="mdi-account-tie-outline" size="16" class="mr-1" />{{ s.responsable }}</div>
            </div>
            <v-chip :color="s.actif ? 'success' : 'grey'" size="small" variant="tonal">{{ s.actif ? 'Actif' : 'Désactivé' }}</v-chip>
          </div>
          <p v-if="s.description" class="text-caption text-medium-emphasis mt-2 mb-0">{{ s.description }}</p>
          <div class="text-caption mt-3 svb-usage">
            <v-icon icon="mdi-chart-box-outline" size="14" class="mr-1" />{{ usage(s) }}
          </div>
          <div v-if="peutGerer" class="d-flex flex-wrap ga-1 mt-3">
            <v-btn size="small" variant="tonal" color="primary" prepend-icon="mdi-pencil-outline" @click="ouvrir(s)">Modifier</v-btn>
            <v-btn size="small" variant="text" :color="s.actif ? 'warning' : 'success'" :loading="action === s.id"
              :prepend-icon="s.actif ? 'mdi-pause-circle-outline' : 'mdi-play-circle-outline'" @click="basculer(s)">
              {{ s.actif ? 'Désactiver' : 'Réactiver' }}
            </v-btn>
            <v-btn v-if="!utilise(s)" size="small" variant="text" color="error" prepend-icon="mdi-delete-outline" @click="aSupprimer = s">Supprimer</v-btn>
          </div>
        </v-card>
      </v-col>
    </v-row>

    <v-empty-state
      v-if="!chargement && services.length === 0"
      icon="mdi-account-group-outline"
      title="Aucun service"
      :text="peutGerer ? 'Créez vos services pour répartir le budget et suivre les dépenses par service.' : 'Aucun service n’a encore été créé.'"
    />
    <p v-else-if="!chargement && affiches.length === 0" class="text-medium-emphasis text-center mt-6">Aucun service ne correspond à la recherche.</p>

    <!-- Création / modification -->
    <v-dialog v-model="dialogue" max-width="520">
      <v-card>
        <v-card-title>{{ enEdition ? 'Modifier le service' : 'Nouveau service' }}</v-card-title>
        <v-card-text>
          <v-alert v-if="erreurDialogue" type="error" variant="tonal" density="compact" class="mb-3">{{ erreurDialogue }}</v-alert>
          <v-text-field v-model="form.nom" label="Nom du service *" placeholder="Ex. Direction financière" autofocus class="mb-2" />
          <v-text-field v-model="form.responsable" label="Responsable du service *" placeholder="Prénom et nom, ex. Brino Kalunga"
            prepend-inner-icon="mdi-account-tie-outline" class="mb-2" />
          <v-textarea v-model="form.description" label="Description (facultatif)" rows="2" auto-grow hide-details />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="dialogue = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="enregistrement" @click="enregistrer">{{ enEdition ? 'Enregistrer' : 'Créer le service' }}</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog :model-value="!!aSupprimer" max-width="440" @update:model-value="(v: boolean) => { if (!v) aSupprimer = null }">
      <v-card v-if="aSupprimer">
        <v-card-title>Supprimer ce service ?</v-card-title>
        <v-card-text>Le service « {{ aSupprimer.nom }} » ({{ aSupprimer.responsable }}) n’a jamais été utilisé : il sera définitivement supprimé.</v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="aSupprimer = null">Annuler</v-btn>
          <v-btn color="error" variant="flat" :loading="action === aSupprimer.id" @click="supprimer">Supprimer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.svb-carte { transition: box-shadow 0.15s; }
.svb-carte--inactif { opacity: 0.7; }
.svb-avatar { width: 40px; height: 40px; border-radius: 12px; background: var(--color-primary-light); color: var(--color-primary);
  display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 0.85rem; flex-shrink: 0; }
.svb-nom { overflow-wrap: anywhere; }
.svb-usage { color: #4b5563; }
</style>
