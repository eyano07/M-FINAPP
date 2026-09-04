<script setup lang="ts">
definePageMeta({ module: 'DRH_PRESENCES' })

interface Agent { id: number; nomComplet: string; fonction: string | null; ordreAffichage: number | null }
interface Employe { id: number; matricule: string; nomComplet: string; poste: string | null }

const MOIS = ['Janvier', 'Février', 'Mars', 'Avril', 'Mai', 'Juin', 'Juillet', 'Août', 'Septembre', 'Octobre', 'Novembre', 'Décembre']

const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()
const canWrite = computed(() => auth.hasAnyRole(['RESP_DRH']))

const aujourdhui = new Date()
const mois = ref(aujourdhui.getMonth() + 1)
const annee = ref(aujourdhui.getFullYear())
const jours = computed(() => {
  const n = new Date(annee.value, mois.value, 0).getDate()
  return Array.from({ length: n }, (_, i) => i + 1)
    // Exclut samedi (6) et dimanche (0) : la fiche papier n'a pas besoin
    // d'une page d'émargement pour des jours non travaillés.
    .filter((j) => {
      const jourSemaine = new Date(annee.value, mois.value - 1, j).getDay()
      return jourSemaine !== 0 && jourSemaine !== 6
    })
})
// 2 jours par page (papier physique MBSC) : chaque page repete son propre
// en-tete et sa propre signature DRH en pied de page — voir le gabarit.
const pages = computed(() => {
  const groupes: number[][] = []
  for (let i = 0; i < jours.value.length; i += 2) {
    groupes.push(jours.value.slice(i, i + 2))
  }
  return groupes
})

const loading = ref(true)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const agents = ref<Agent[]>([])
const employes = ref<Employe[]>([])
const dialog = ref(false)
const editing = ref<Agent | null>(null)
const form = reactive({ nomComplet: '', fonction: '' })
const directeurDrh = ref('')
const fonctionDirecteur = ref('Directeur des Ressources Humaines')

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [a, p, e] = await Promise.all([
      api<Agent[]>('/drh/agents-presence-manuelle'),
      api<{ directeurDrh: string | null; fonctionDirecteur: string | null }>('/drh/parametres-paie'),
      // Module DRH_PERSONNEL, distinct de DRH_PRESENCES : un utilisateur qui
      // n'a acces qu'a cet ecran (sans RESP_DRH) recevrait un 403 sur cet
      // appel — meme garde que bulletins/index.vue, la liste sert seulement
      // au raccourci "ajouter depuis les employes", pas a l'affichage
      // principal de la page.
      canWrite.value ? api<Employe[]>('/drh/employes') : Promise.resolve([]),
    ])
    agents.value = a
    directeurDrh.value = p.directeurDrh ?? ''
    fonctionDirecteur.value = p.fonctionDirecteur || 'Directeur des Ressources Humaines'
    employes.value = e
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger la liste.')
  } finally {
    loading.value = false
  }
}
onMounted(() => { charger(); parametresStore.charger() })

// ── Ajout rapide depuis les employés déjà enregistrés ──────────────────────
const dialogEmployes = ref(false)
const employesChoisis = ref<number[]>([])
const employesDisponibles = computed(() => {
  const nomsDejaListes = new Set(agents.value.map(a => a.nomComplet.trim().toLowerCase()))
  return employes.value.filter(e => !nomsDejaListes.has(e.nomComplet.trim().toLowerCase()))
})

function ouvrirAjoutDepuisEmployes() {
  employesChoisis.value = []
  erreur.value = ''
  dialogEmployes.value = true
}

async function ajouterDepuisEmployes() {
  if (!employesChoisis.value.length) return
  saving.value = true
  erreur.value = ''
  try {
    await Promise.all(employesChoisis.value.map((id) => {
      const emp = employes.value.find(e => e.id === id)!
      return api('/drh/agents-presence-manuelle', {
        method: 'POST',
        body: { nomComplet: emp.nomComplet, fonction: emp.poste || null },
      })
    }))
    dialogEmployes.value = false
    succes.value = `${employesChoisis.value.length} employé(s) ajouté(s) à la liste.`
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'ajout.")
  } finally {
    saving.value = false
  }
}

function ouvrirAjout() {
  editing.value = null
  Object.assign(form, { nomComplet: '', fonction: '' })
  erreur.value = ''
  dialog.value = true
}
function ouvrirEdition(a: Agent) {
  editing.value = a
  Object.assign(form, { nomComplet: a.nomComplet, fonction: a.fonction ?? '' })
  erreur.value = ''
  dialog.value = true
}

async function enregistrer() {
  if (!form.nomComplet) {
    erreur.value = 'Le nom complet est obligatoire.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    if (editing.value) {
      await api(`/drh/agents-presence-manuelle/${editing.value.id}`, { method: 'PUT', body: form })
    } else {
      await api('/drh/agents-presence-manuelle', { method: 'POST', body: form })
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

async function retirer(a: Agent) {
  saving.value = true
  erreur.value = ''
  try {
    await api(`/drh/agents-presence-manuelle/${a.id}`, { method: 'DELETE' })
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la suppression.')
  } finally {
    saving.value = false
  }
}

const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  nextTick(() => window.print())
}

function libelleJour(j: number): string {
  return new Date(annee.value, mois.value - 1, j).toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })
}
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Fiche de présence manuelle</h1>
        <p class="page-sub">Liste d'agents à faire émarger sur papier, sans compte système</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn color="error" variant="tonal" rounded="lg" prepend-icon="mdi-printer-outline" :disabled="!agents.length" @click="imprimer">
          Imprimer
        </v-btn>
        <v-btn v-if="canWrite" color="primary" variant="tonal" rounded="lg" prepend-icon="mdi-account-multiple-plus-outline" @click="ouvrirAjoutDepuisEmployes">
          Depuis les employés
        </v-btn>
        <v-btn v-if="canWrite" color="success" variant="flat" rounded="lg" prepend-icon="mdi-plus" @click="ouvrirAjout">
          Ajouter un agent
        </v-btn>
      </div>
    </div>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4 no-print" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" class="mb-4 no-print" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <div class="d-flex ga-3 mb-4 no-print">
      <v-select v-model="mois" :items="MOIS.map((m, i) => ({ title: m, value: i + 1 }))" label="Mois" variant="outlined"
        density="comfortable" rounded="lg" hide-details style="max-width: 200px" />
      <v-text-field v-model.number="annee" type="number" label="Année" variant="outlined" density="comfortable"
        rounded="lg" hide-details style="max-width: 140px" />
    </div>

    <!-- Gestion de la liste (ecran uniquement) : les colonnes jour par jour
         n'ont de sens qu'a l'impression, voir la grille mensuelle plus bas. -->
    <v-card class="classroom-card no-print">
      <v-skeleton-loader v-if="loading" type="table" />
      <table v-else class="fiche-table">
        <thead>
          <tr>
            <th>#</th><th>Nom complet</th><th>Fonction</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(a, i) in agents" :key="a.id">
            <td>{{ i + 1 }}</td>
            <td>{{ a.nomComplet }}</td>
            <td>{{ a.fonction ?? '—' }}</td>
            <td>
              <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil-outline" title="Modifier" @click="ouvrirEdition(a)" />
              <v-btn v-if="canWrite" size="small" variant="text" color="error" icon="mdi-delete-outline" title="Retirer" @click="retirer(a)" />
            </td>
          </tr>
          <tr v-if="!agents.length"><td colspan="4" class="text-center text-medium-emphasis pa-4">Aucun agent dans la liste.</td></tr>
        </tbody>
      </table>
    </v-card>

    <!-- Fiches journalières imprimables, reproduisant le modèle papier MBSC :
         2 jours par page, chaque page reprenant son propre en-tête et sa
         propre signature DRH en pied de page (pas une seule fois pour tout
         le document — voir la maquette fournie). -->
    <div class="print-only fiche-jours">
      <template v-for="(groupe, pIdx) in pages" :key="'p' + pIdx">
        <div class="page-bloc" :class="{ 'page-bloc--saut-page': pIdx !== pages.length - 1 }">
          <div class="etat-print-header">
            <div class="etat-print-header__brand">
              <div class="etat-print-header__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
                <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
                <v-icon v-else icon="mdi-finance" size="16" color="white" />
              </div>
              <div>
                <span class="etat-print-header__company">{{ parametresStore.parametres.nom }}</span>
                <span class="etat-print-header__service">Direction des Ressources Humaines</span>
                <span class="etat-print-header__doc">Fiche de présence manuelle — {{ MOIS[mois - 1] }} {{ annee }}</span>
              </div>
            </div>
            <div class="etat-print-header__meta">
              <span>Imprimé le : {{ dateImpression }}</span>
            </div>
          </div>

          <div v-for="j in groupe" :key="j" class="jour-bloc">
            <h3 class="jour-titre">{{ libelleJour(j) }}</h3>
            <table class="fiche-jour-table">
              <thead>
                <tr>
                  <th>N°</th>
                  <th>Nom et Prénom</th>
                  <th>Fonction / Service</th>
                  <th>Heure d'entrée</th>
                  <th>Signature</th>
                  <th>Heure de sortie</th>
                  <th>Signature</th>
                  <th>Observation</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(a, i) in agents" :key="a.id">
                  <td>{{ i + 1 }}</td>
                  <td class="col-nom">{{ a.nomComplet }}</td>
                  <td class="col-fonction">{{ a.fonction ?? '—' }}</td>
                  <td /><td /><td /><td /><td />
                </tr>
              </tbody>
            </table>
          </div>

          <div class="fiche-signature-directeur">
            <span v-if="directeurDrh" class="fiche-signature-directeur__nom">{{ directeurDrh.toUpperCase() }}</span>
            <span class="fiche-signature-directeur__fonction">{{ fonctionDirecteur }}</span>
          </div>
        </div>
      </template>
    </div>

    <v-dialog v-model="dialog" max-width="480">
      <v-card>
        <v-card-title>{{ editing ? 'Modifier l’agent' : 'Ajouter un agent' }}</v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>
          <v-text-field v-model="form.nomComplet" label="Nom complet *" variant="outlined" density="comfortable" class="mb-2" />
          <v-text-field v-model="form.fonction" label="Fonction" variant="outlined" density="comfortable" />
        </v-card-text>
        <v-divider />
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="saving" @click="dialog = false">Annuler</v-btn>
          <v-btn color="success" variant="flat" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="dialogEmployes" max-width="520">
      <v-card>
        <v-card-title>Ajouter depuis les employés enregistrés</v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>
          <v-autocomplete
            v-model="employesChoisis"
            :items="employesDisponibles.map(e => ({ title: `${e.matricule} — ${e.nomComplet}`, value: e.id }))"
            label="Employés à ajouter"
            variant="outlined"
            density="comfortable"
            multiple
            chips
            closable-chips
            clearable
          />
          <p v-if="!employesDisponibles.length" class="text-caption text-medium-emphasis mb-0">
            Tous les employés enregistrés figurent déjà dans la liste.
          </p>
          <p v-else class="text-caption text-medium-emphasis mb-0">
            Le nom et le poste de chaque employé sélectionné sont repris tels quels — modifiables ensuite via « Modifier ».
          </p>
        </v-card-text>
        <v-divider />
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="saving" @click="dialogEmployes = false">Annuler</v-btn>
          <v-btn color="success" variant="flat" :loading="saving" :disabled="!employesChoisis.length" @click="ajouterDepuisEmployes">
            Ajouter ({{ employesChoisis.length }})
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.fiche-table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
.fiche-table th { text-align: left; padding: 8px; font-size: 0.7rem; text-transform: uppercase; color: #6b7280; border-bottom: 1px solid #e5e7eb; }
.fiche-table td { padding: 10px 8px; border-bottom: 1px solid #f3f4f6; }

.print-only { display: none; }

.jour-titre { font-size: 11pt; font-weight: 700; color: #111827; margin: 0 0 6pt; }
.jour-bloc { margin-bottom: 14pt; }
.fiche-jour-table { width: 100%; border-collapse: collapse; font-size: 8pt; }
.fiche-jour-table th, .fiche-jour-table td { border: 0.75pt solid #9ca3af; padding: 4pt 5pt; text-align: center; }
.fiche-jour-table thead th { font-weight: 700; background: #f3f4f6; font-size: 7.5pt; }
.fiche-jour-table .col-nom { text-align: left; font-weight: 600; white-space: nowrap; }
.fiche-jour-table .col-fonction { text-align: left; white-space: nowrap; }
.fiche-jour-table tbody td:not(.col-nom):not(.col-fonction):not(:first-child) { height: 16pt; }

.fiche-signature-directeur { margin-top: 20pt; text-align: right; }
.fiche-signature-directeur__nom { display: block; font-weight: 700; font-size: 10pt; color: #111827; }
.fiche-signature-directeur__fonction { display: block; font-size: 8.5pt; color: #374151; }

@media print {
  /* Pas de @page ici : cette regle n'est pas isolee par page dans une SPA —
     une fois chargee en visitant cet ecran, elle reste active et s'applique
     aussi aux prochains documents imprimes ailleurs dans l'application
     durant la meme session (bulletins de paie, etats financiers...), qui se
     retrouvaient alors en paysage sans raison apparente. Le format A4
     portrait global (classroom.scss) s'applique donc aussi ici desormais ;
     si ce tableau (large, plusieurs colonnes par jour) a besoin du paysage,
     le choisir manuellement dans la boite de dialogue d'impression reste
     possible au cas par cas, sans effet de bord sur les autres documents. */
  .print-only { display: block; }
  .page-bloc--saut-page { break-after: page; }
}
</style>
