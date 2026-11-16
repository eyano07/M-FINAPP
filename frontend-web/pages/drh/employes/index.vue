<script setup lang="ts">
definePageMeta({ module: 'DRH_PERSONNEL' })

interface Employe {
  id: number
  matricule: string
  nomComplet: string
  categorie: string | null
  poste: string | null
  affectation: string | null
  email: string | null
  telephone: string | null
  dateEmbauche: string | null
  dateNaissance: string | null
  salaireBaseUsd: number
  situationFamiliale: string | null
  nombreEnfants: number
  diplome: string | null
  ancienneteAnnees: number
  rendementPct: number | null
  conforme: boolean
  superviseur: boolean
  expatrie: boolean
  actif: boolean
  /** Compte de rémunérations dues de l'agent (422111…), ouvert par le serveur à la création, jamais saisi. */
  compteRemuneration: string | null
  /** Compte 272… du prêt de l'agent, crédité par la retenue de prêt (à défaut 2728). */
  comptePret: string | null
  /** Compte d'avances de l'agent (42111…, sous 4211), ouvert par le serveur à la création (audit DRH, D-06). */
  compteAvance?: string | null
}

const SITUATIONS = [
  { value: 'CELIBATAIRE', title: 'Célibataire' },
  { value: 'MARIE', title: 'Marié(e)' },
  { value: 'DIVORCE', title: 'Divorcé(e)' },
  { value: 'VEUF', title: 'Veuf/Veuve' },
]

const api = useApi()
const auth = useAuthStore()
const canWrite = computed(() => auth.hasAnyRole(['RESP_DRH']))

const loading = ref(false)
const saving = ref(false)
const exportEnCours = ref(false)
const erreur = ref('')
const succes = ref('')
const recherche = ref('')
const employes = ref<Employe[]>([])
const dialog = ref(false)
const editing = ref<Employe | null>(null)

const formVide = () => ({
  nomComplet: '', categorie: '', poste: '', affectation: '', email: '', telephone: '',
  dateEmbauche: new Date().toISOString().slice(0, 10),
  dateNaissance: '' as string | null,
  salaireBaseUsd: null as number | null,
  situationFamiliale: 'CELIBATAIRE',
  nombreEnfants: 0, diplome: '', ancienneteAnnees: 0, rendementPct: null as number | null,
  conforme: true, superviseur: false, expatrie: false,
  comptePret: null as string | null,
})
const form = reactive(formVide())

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    employes.value = await api<Employe[]>('/drh/employes', { params: recherche.value ? { q: recherche.value } : {} })
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger la liste des employés.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)
let rechercheTimer: ReturnType<typeof setTimeout> | null = null
watch(recherche, () => {
  if (rechercheTimer) clearTimeout(rechercheTimer)
  rechercheTimer = setTimeout(charger, 350)
})

function ouvrirAjout() {
  editing.value = null
  Object.assign(form, formVide())
  erreur.value = ''
  dialog.value = true
}

function ouvrirEdition(e: Employe) {
  editing.value = e
  Object.assign(form, {
    nomComplet: e.nomComplet, categorie: e.categorie ?? '', poste: e.poste ?? '', affectation: e.affectation ?? '',
    email: e.email ?? '', telephone: e.telephone ?? '', dateEmbauche: e.dateEmbauche ?? '',
    dateNaissance: e.dateNaissance ?? '',
    salaireBaseUsd: e.salaireBaseUsd, situationFamiliale: e.situationFamiliale ?? 'CELIBATAIRE',
    nombreEnfants: e.nombreEnfants, diplome: e.diplome ?? '', ancienneteAnnees: e.ancienneteAnnees,
    rendementPct: e.rendementPct, conforme: e.conforme, superviseur: e.superviseur, expatrie: e.expatrie,
    comptePret: e.comptePret ?? null,
  })
  erreur.value = ''
  dialog.value = true
}

async function enregistrer() {
  if (!form.nomComplet || form.salaireBaseUsd === null) {
    erreur.value = 'Le nom complet et le salaire de base sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    if (editing.value) {
      await api(`/drh/employes/${editing.value.id}`, { method: 'PUT', body: form })
      succes.value = 'Employé modifié.'
    } else {
      await api('/drh/employes', { method: 'POST', body: form })
      succes.value = 'Employé créé.'
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

async function basculerActif(e: Employe) {
  saving.value = true
  erreur.value = ''
  try {
    await api(`/drh/employes/${e.id}/${e.actif ? 'desactiver' : 'reactiver'}`, { method: 'PATCH' })
    succes.value = e.actif ? 'Employé désactivé.' : 'Employé réactivé.'
    await charger()
  } catch (err: any) {
    erreur.value = messageErreurApi(err, 'Échec de la mise à jour.')
  } finally {
    saving.value = false
  }
}

async function exporterExcel() {
  exportEnCours.value = true
  erreur.value = ''
  try {
    await telechargerFichier(api, '/drh/employes/export', 'Employes_MBSC.xlsx')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de générer le classeur.')
  } finally {
    exportEnCours.value = false
  }
}

const fmtUsd = (v: number | null) =>
  v === null || v === undefined ? '—' : new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(v)
const fmtDate = (d: string | null) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
const situationLabel = (v: string | null) => SITUATIONS.find(s => s.value === v)?.title ?? (v ?? '—')
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Employés</h1>
        <p class="page-sub">Fiches du personnel MBSC — matricule, salaire de base, situation familiale</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn color="success" variant="tonal" rounded="lg" prepend-icon="mdi-file-excel-outline"
          :loading="exportEnCours" @click="exporterExcel">
          Classeur Excel
        </v-btn>
        <v-btn v-if="canWrite" color="success" variant="flat" rounded="lg" prepend-icon="mdi-plus" @click="ouvrirAjout">
          Nouvel employé
        </v-btn>
      </div>
    </div>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-text-field v-model="recherche" prepend-inner-icon="mdi-magnify" placeholder="Rechercher un employé (nom, matricule...)"
      variant="outlined" density="comfortable" rounded="lg" hide-details class="mb-4" style="max-width: 420px" clearable />

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Matricule', key: 'matricule' },
          { title: 'Nom complet', key: 'nomComplet' },
          { title: 'Catégorie', key: 'categorie' },
          { title: 'Poste', key: 'poste' },
          { title: 'Affectation', key: 'affectation' },
          { title: 'Salaire de base', key: 'salaireBaseUsd', align: 'end' },
          { title: 'Situation', key: 'situationFamiliale' },
          { title: 'Enfants', key: 'nombreEnfants', align: 'center' },
          { title: 'Compte', key: 'compteRemuneration' },
          { title: 'Statut', key: 'actif' },
          { title: 'Actions', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="employes"
        :loading="loading"
        items-per-page="25"
      >
        <template #item.salaireBaseUsd="{ item }">{{ fmtUsd(item.salaireBaseUsd) }}</template>
        <template #item.situationFamiliale="{ item }">{{ situationLabel(item.situationFamiliale) }}</template>
        <template #item.compteRemuneration="{ item }">{{ item.compteRemuneration ?? '—' }}</template>
        <template #item.actif="{ item }">
          <span class="chip-soft" :style="item.actif ? { background: 'var(--color-primary-light)', color: 'var(--color-primary-dark)' } : { background: '#f3f4f6', color: '#6b7280' }">
            {{ item.actif ? 'Actif' : 'Désactivé' }}
          </span>
        </template>
        <template #item.actions="{ item }">
          <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil-outline" title="Modifier" @click="ouvrirEdition(item)" />
          <v-btn v-if="canWrite" size="small" variant="text" :color="item.actif ? 'error' : 'success'"
            :icon="item.actif ? 'mdi-account-off-outline' : 'mdi-account-check-outline'"
            :title="item.actif ? 'Désactiver' : 'Réactiver'" @click="basculerActif(item)" />
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">Aucun employé enregistré.</div>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="760" scrollable>
      <v-card>
        <v-card-title>{{ editing ? 'Modifier l’employé' : 'Nouvel employé' }}</v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>
          <v-row>
            <v-col cols="12" md="6"><v-text-field v-model="form.nomComplet" label="Nom complet *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="3"><v-text-field v-model="form.poste" label="Poste" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="3"><v-text-field v-model="form.categorie" label="Catégorie (n° CNSS + lettre)" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.affectation" label="Affectation" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="3"><v-text-field v-model="form.dateEmbauche" type="date" label="Date d'embauche" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="3"><v-text-field v-model="form.dateNaissance" type="date" label="Date de naissance" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.email" type="email" label="Email" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.telephone" label="Téléphone" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model.number="form.salaireBaseUsd" type="number" label="Salaire de base (USD) *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-select v-model="form.situationFamiliale" :items="SITUATIONS" label="Situation familiale" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model.number="form.nombreEnfants" type="number" label="Nombre d'enfants" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.diplome" label="Diplôme" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="3"><v-text-field v-model.number="form.ancienneteAnnees" type="number" label="Ancienneté (années)" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="3"><v-text-field v-model.number="form.rendementPct" type="number" label="Rendement (%)" variant="outlined" density="comfortable" /></v-col>
            <!-- Audit DRH du 15/11/2026 (D-02, D-03) : comptes de paie de l'agent. -->
            <v-col cols="12" md="6">
              <v-text-field :model-value="editing?.compteRemuneration ?? 'Ouvert à l’enregistrement (422111…)'"
                label="Compte de rémunérations dues" variant="outlined" density="comfortable" readonly
                hint="Ouvert automatiquement sous 42211 ; crédité du net à payer, débité par la note de paie" persistent-hint />
            </v-col>
            <v-col cols="12" md="6">
              <ComptabiliteSelecteurCompte v-model="form.comptePret" label="Compte de prêt (272…)" prefixe="272" />
              <div class="text-caption text-medium-emphasis">Crédité par la retenue de prêt des bulletins ; à défaut 2728.</div>
            </v-col>
            <!-- Audit DRH du 15/11/2026 (D-06) : avances sur salaire reliées au bulletin. -->
            <v-col cols="12" md="6">
              <v-text-field :model-value="editing?.compteAvance ?? 'Ouvert à l’enregistrement (42111…)'"
                label="Compte d'avances" variant="outlined" density="comfortable" readonly
                hint="Ouvert automatiquement sous 4211 « Personnel, avances »" persistent-hint />
            </v-col>
            <v-col cols="12" md="6">
              <div class="text-caption text-medium-emphasis">
                Pour verser une avance à l'agent, créez une note de frais dont la ligne est imputée à ce compte : son
                paiement le débite. Le bulletin du mois retient ensuite l'avance (au plus l'avance ouverte, proposée
                automatiquement) et la constatation de la paie crédite le même compte.
              </div>
            </v-col>
            <v-col cols="12">
              <v-checkbox v-model="form.conforme" hide-details label="Dossier conforme (bulletin complet, sinon reçu simplifié)" />
              <v-checkbox v-model="form.superviseur" hide-details label="Superviseur (éligible aux rotations de sites)" />
              <v-checkbox v-model="form.expatrie" hide-details label="Expatrié (personnel non national — écritures comptables 6621/6642)" />
            </v-col>
          </v-row>
        </v-card-text>
        <v-divider />
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="saving" @click="dialog = false">Annuler</v-btn>
          <v-btn color="success" variant="flat" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>
