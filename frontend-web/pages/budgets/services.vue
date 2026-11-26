<script setup lang="ts">
/**
 * Services (départements) de l'entreprise, chacun avec le nom de son responsable. Les lignes de dépenses d'un
 * budget et les notes de frais s'y rattachent : chaque service a son enveloppe dans le budget, qui reste unique
 * (consolidé), et ses dépenses se suivent à part. Le DFIN (et l'administrateur) les gère ; un service déjà
 * utilisé ne se supprime plus, il se désactive.
 */
import { fmtMontant, statutBudget } from '~/composables/useBudget'

definePageMeta({ module: 'BUDGET', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'CAISSIER'] })

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

/** Exécution d'un service dans son budget de référence (en cours d'abord), montants de dépenses en devise de base. */
interface ExecutionService {
  serviceId: number
  budgetId: number
  budgetReference: string
  budgetIntitule?: string | null
  statutBudget: string
  duree: string
  periode: string
  dateDebut: string
  dateFin: string
  joursTotal: number
  joursEcoules: number
  prevu: number
  realise: number
  engage: number
  disponible: number
  tauxExecution: number | null
  depassement: number
  devise: string
}

useHead({ title: 'Services budgétaires' })
const api = useApi()
const auth = useAuthStore()
const rolesStore = useRolesStore()
const peutGerer = computed(() => auth.hasAnyRole(['DFIN', 'ADMIN']))

const services = ref<ServiceBudgetaire[]>([])
const executions = ref<Record<number, ExecutionService>>({})
const chargement = ref(false)
const erreur = ref('')
const succes = ref('')
const recherche = ref('')

async function charger() {
  chargement.value = true
  erreur.value = ''
  try {
    const [liste, execs] = await Promise.all([
      api<ServiceBudgetaire[]>('/services-budgetaires'),
      // Les montants ne doivent jamais empêcher d'afficher la liste : sans eux, les cartes restent sans chiffres.
      api<ExecutionService[]>('/services-budgetaires/execution').catch(() => [] as ExecutionService[]),
    ])
    services.value = liste
    executions.value = Object.fromEntries(execs.map(e => [e.serviceId, e]))
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

// ── Exécution budgétaire ──────────────────────────────────────────────────
const nombre = (v: unknown) => Number(v) || 0
const exec = (s: ServiceBudgetaire) => executions.value[s.id]
const dateCourte = (d: string) => new Date(`${d}T00:00:00`).toLocaleDateString('fr-FR', { day: '2-digit', month: 'short', year: 'numeric' })
const tauxAffiche = (e: ExecutionService) => (e.tauxExecution === null || e.tauxExecution === undefined ? null : Math.round(nombre(e.tauxExecution)))
/** Couleur du taux : vert jusqu'à 75 %, ambre jusqu'à 100 %, rouge au-delà (ou dès qu'un dépassement existe). */
function niveau(e: ExecutionService): 'ok' | 'haut' | 'depasse' {
  const t = nombre(e.tauxExecution)
  if (nombre(e.depassement) > 0 || t > 100) return 'depasse'
  return t >= 75 ? 'haut' : 'ok'
}
/** Parts de la barre empilée (réalisé, engagé), en % du prévu, bornées à 100. */
function parts(e: ExecutionService) {
  const prevu = nombre(e.prevu)
  if (prevu <= 0) return { realise: 0, engage: 0 }
  const realise = Math.min(100, (nombre(e.realise) / prevu) * 100)
  const engage = Math.min(100 - realise, (nombre(e.engage) / prevu) * 100)
  return { realise, engage }
}
const tempsEcoule = (e: ExecutionService) => (e.joursTotal > 0 ? Math.round((e.joursEcoules / e.joursTotal) * 100) : 0)
function etatPeriode(e: ExecutionService): string {
  if (e.joursEcoules <= 0) return 'Pas encore commencée'
  if (e.joursEcoules >= e.joursTotal) return 'Période terminée'
  const reste = e.joursTotal - e.joursEcoules
  return `${reste} jour${reste > 1 ? 's' : ''} restant${reste > 1 ? 's' : ''}`
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

          <!-- Exécution dans le budget de référence -->
          <div v-if="exec(s)" class="svb-exec mt-3" :class="`svb-exec--${niveau(exec(s)!)}`">
            <div class="svb-exec__haut">
              <div style="min-width: 0">
                <div class="svb-label">Montant prévu</div>
                <div class="svb-montant">
                  {{ fmtMontant(exec(s)!.prevu) }} <span class="svb-devise">{{ exec(s)!.devise }}</span>
                </div>
                <NuxtLink :to="`/budgets/${exec(s)!.budgetId}`" class="svb-budget">
                  <v-icon icon="mdi-file-chart-outline" size="13" />
                  {{ exec(s)!.budgetReference }}
                  <span class="svb-statut" :class="`text-${statutBudget(exec(s)!.statutBudget).color}`">· {{ statutBudget(exec(s)!.statutBudget).label }}</span>
                </NuxtLink>
              </div>
              <div class="svb-anneau" :aria-label="`Taux d'exécution ${tauxAffiche(exec(s)!) ?? '—'} %`">
                <v-progress-circular :model-value="Math.min(100, nombre(exec(s)!.tauxExecution))" :size="68" :width="7"
                  class="svb-anneau__cercle" bg-color="transparent">
                  <span class="svb-anneau__valeur">{{ tauxAffiche(exec(s)!) ?? '—' }}<small v-if="tauxAffiche(exec(s)!) !== null">%</small></span>
                </v-progress-circular>
                <span class="svb-anneau__texte">exécuté</span>
              </div>
            </div>

            <div class="svb-barre" role="img"
              :aria-label="`Réalisé ${fmtMontant(exec(s)!.realise)}, engagé ${fmtMontant(exec(s)!.engage)} sur ${fmtMontant(exec(s)!.prevu)} ${exec(s)!.devise}`">
              <span class="svb-barre__realise" :style="{ width: `${parts(exec(s)!).realise}%` }" />
              <span class="svb-barre__engage" :style="{ width: `${parts(exec(s)!).engage}%` }" />
            </div>
            <div class="svb-legende">
              <span><i class="svb-pastille svb-pastille--realise" />Réalisé <b>{{ fmtMontant(exec(s)!.realise) }}</b></span>
              <span v-if="nombre(exec(s)!.engage) > 0"><i class="svb-pastille svb-pastille--engage" />Engagé <b>{{ fmtMontant(exec(s)!.engage) }}</b></span>
              <span><i class="svb-pastille svb-pastille--dispo" />Disponible <b :class="{ 'text-error': nombre(exec(s)!.disponible) < 0 }">{{ fmtMontant(exec(s)!.disponible) }}</b></span>
            </div>

            <div class="svb-periode">
              <div class="svb-periode__ligne">
                <span class="svb-periode__titre"><v-icon icon="mdi-calendar-range" size="15" />{{ exec(s)!.duree }} · {{ exec(s)!.periode }}</span>
                <span class="svb-periode__reste">{{ etatPeriode(exec(s)!) }}</span>
              </div>
              <div class="svb-temps"><span :style="{ width: `${tempsEcoule(exec(s)!)}%` }" /></div>
              <div class="svb-periode__dates">
                <span>{{ dateCourte(exec(s)!.dateDebut) }}</span>
                <span>{{ tempsEcoule(exec(s)!) }} % du temps écoulé</span>
                <span>{{ dateCourte(exec(s)!.dateFin) }}</span>
              </div>
            </div>

            <div v-if="nombre(exec(s)!.depassement) > 0" class="svb-alerte">
              <v-icon icon="mdi-alert-circle-outline" size="15" />
              Dépassement de {{ fmtMontant(exec(s)!.depassement) }} {{ exec(s)!.devise }}
            </div>
          </div>
          <div v-else class="svb-exec svb-exec--vide mt-3">
            <v-icon icon="mdi-chart-donut" size="18" />
            <span>Aucun montant prévu : ce service n’a encore de ligne dans aucun budget.</span>
          </div>

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
.svb-usage { color: var(--ink-soft); }

/* Bloc d'exécution : la couleur d'accent suit le niveau (vert, ambre, rouge) et le thème (clair / nuit). */
.svb-exec { --accent: var(--color-primary); --accent-doux: color-mix(in srgb, var(--accent) 14%, transparent);
  border: 1px solid var(--hairline); border-radius: 14px; padding: 14px;
  background: linear-gradient(135deg, color-mix(in srgb, var(--accent) 7%, var(--surface)) 0%, var(--surface) 65%); }
.svb-exec--haut { --accent: #d97706; }
.svb-exec--depasse { --accent: #dc2626; }
:global(html[data-theme='dark'] .svb-exec--haut) { --accent: #f5a524; }
:global(html[data-theme='dark'] .svb-exec--depasse) { --accent: #f87171; }
.svb-exec--vide { display: flex; align-items: center; gap: 8px; color: var(--ink-soft); font-size: 0.8rem;
  border-style: dashed; background: transparent; }
.svb-exec__haut { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.svb-label { font-size: 0.7rem; letter-spacing: 0.06em; text-transform: uppercase; color: var(--ink-soft); font-weight: 600; }
.svb-montant { font-size: 1.45rem; font-weight: 800; line-height: 1.2; letter-spacing: -0.02em; font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere; }
.svb-devise { font-size: 0.8rem; font-weight: 600; color: var(--ink-soft); }
.svb-budget { display: inline-flex; align-items: center; gap: 4px; margin-top: 2px; font-size: 0.75rem; color: var(--ink-soft);
  text-decoration: none; }
.svb-budget:hover { color: var(--color-primary); text-decoration: underline; }
.svb-statut { font-weight: 600; }
.svb-anneau { display: flex; flex-direction: column; align-items: center; flex-shrink: 0; }
.svb-anneau__cercle { color: var(--accent); }
.svb-anneau__cercle :deep(.v-progress-circular__underlay) { stroke: var(--accent-doux); opacity: 1; }
.svb-anneau__valeur { font-size: 1.05rem; font-weight: 800; color: var(--accent); font-variant-numeric: tabular-nums; }
.svb-anneau__valeur small { font-size: 0.65rem; font-weight: 700; margin-left: 1px; }
.svb-anneau__texte { font-size: 0.65rem; text-transform: uppercase; letter-spacing: 0.06em; color: var(--ink-soft); margin-top: 2px; }

.svb-barre { display: flex; height: 8px; margin-top: 12px; border-radius: 999px; overflow: hidden; background: var(--accent-doux); }
.svb-barre span { height: 100%; transition: width 0.6s cubic-bezier(.2,.8,.2,1); }
.svb-barre__realise { background: var(--accent); }
.svb-barre__engage { background: repeating-linear-gradient(45deg, color-mix(in srgb, var(--accent) 55%, transparent) 0 4px,
  color-mix(in srgb, var(--accent) 30%, transparent) 4px 8px); }
.svb-legende { display: flex; flex-wrap: wrap; gap: 4px 14px; margin-top: 8px; font-size: 0.75rem; color: var(--ink-soft); }
.svb-legende b { color: var(--ink, inherit); font-weight: 700; font-variant-numeric: tabular-nums; }
.svb-pastille { display: inline-block; width: 8px; height: 8px; border-radius: 3px; margin-right: 5px; vertical-align: 0; }
.svb-pastille--realise { background: var(--accent); }
.svb-pastille--engage { background: color-mix(in srgb, var(--accent) 45%, transparent); }
.svb-pastille--dispo { background: var(--accent-doux); outline: 1px solid color-mix(in srgb, var(--accent) 30%, transparent); }

.svb-periode { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--hairline); }
.svb-periode__ligne { display: flex; justify-content: space-between; align-items: center; gap: 8px; font-size: 0.78rem; }
.svb-periode__titre { display: inline-flex; align-items: center; gap: 5px; font-weight: 600; }
.svb-periode__reste { color: var(--ink-soft); white-space: nowrap; }
.svb-temps { height: 4px; margin-top: 6px; border-radius: 999px; background: var(--hairline); overflow: hidden; }
.svb-temps span { display: block; height: 100%; border-radius: 999px; background: color-mix(in srgb, var(--ink-soft) 60%, transparent); }
.svb-periode__dates { display: flex; justify-content: space-between; gap: 6px; margin-top: 4px; font-size: 0.68rem; color: var(--ink-soft); }
.svb-alerte { display: flex; align-items: center; gap: 6px; margin-top: 10px; padding: 6px 10px; border-radius: 10px; font-size: 0.75rem;
  font-weight: 600; color: var(--accent); background: color-mix(in srgb, var(--accent) 12%, transparent); }
</style>
