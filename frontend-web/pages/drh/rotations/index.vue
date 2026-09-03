<script setup lang="ts">
definePageMeta({ module: 'DRH_MISSIONS' })

interface Employe { id: number; matricule: string; nomComplet: string; superviseur: boolean }
interface Site { id: number; nom: string }
interface Rotation {
  id: number; employeId: number; employeNomComplet: string; siteId: number; siteNom: string
  annee: number; mois: number; numeroCycle: number
  prestationDebut: string; prestationFin: string; joursPrestation: number
  reposDebut: string; reposFin: string; joursRepos: number; notes: string | null
}

const MOIS = ['Janvier', 'Février', 'Mars', 'Avril', 'Mai', 'Juin', 'Juillet', 'Août', 'Septembre', 'Octobre', 'Novembre', 'Décembre']

const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()
const canWrite = computed(() => auth.hasAnyRole(['RESP_DRH']))

const aujourdhui = new Date()
const mois = ref(aujourdhui.getMonth() + 1)
const annee = ref(aujourdhui.getFullYear())

const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const rotations = ref<Rotation[]>([])
const employes = ref<Employe[]>([])
const sites = ref<Site[]>([])
const dialog = ref(false)
const editing = ref<Rotation | null>(null)
const directeurDrh = ref('')
const fonctionDirecteur = ref('Directeur des Ressources Humaines')

const formVide = () => ({
  employeId: null as number | null, siteId: null as number | null,
  annee: annee.value, mois: mois.value,
  prestationDebut: new Date().toISOString().slice(0, 10),
  prestationFin: '', reposDebut: '', reposFin: '', notes: '',
})
const form = reactive(formVide())

const superviseurs = computed(() => employes.value.filter(e => e.superviseur))

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [r, e, s, p] = await Promise.all([
      api<Rotation[]>('/drh/rotations', { params: { mois: mois.value, annee: annee.value } }),
      employes.value.length ? Promise.resolve(employes.value) : api<Employe[]>('/drh/employes'),
      sites.value.length ? Promise.resolve(sites.value) : api<Site[]>('/drh/sites'),
      // Meme source que la signature de fiche-manuelle.vue et des ordres de mission : un seul "signataire
      // des documents DRH" configure dans Parametres de paie, pas un champ propre a chaque ecran.
      api<{ directeurDrh: string | null; fonctionDirecteur: string | null }>('/drh/parametres-paie'),
    ])
    rotations.value = r
    employes.value = e
    sites.value = s
    directeurDrh.value = p.directeurDrh ?? ''
    fonctionDirecteur.value = p.fonctionDirecteur || 'Directeur des Ressources Humaines'
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les rotations.')
  } finally {
    loading.value = false
  }
}
onMounted(() => { charger(); parametresStore.charger() })
watch([mois, annee], charger)

const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  nextTick(() => window.print())
}

function ouvrirAjout() {
  editing.value = null
  Object.assign(form, formVide())
  erreur.value = ''
  dialog.value = true
}
function ouvrirEdition(r: Rotation) {
  editing.value = r
  Object.assign(form, {
    employeId: r.employeId, siteId: r.siteId, annee: r.annee, mois: r.mois,
    prestationDebut: r.prestationDebut, prestationFin: r.prestationFin,
    reposDebut: r.reposDebut, reposFin: r.reposFin, notes: r.notes ?? '',
  })
  erreur.value = ''
  dialog.value = true
}

async function enregistrer() {
  if (!form.employeId || !form.siteId || !form.prestationDebut) {
    erreur.value = 'Superviseur, site et début de prestation sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    const body = {
      ...form,
      prestationFin: form.prestationFin || null,
      reposDebut: form.reposDebut || null,
      reposFin: form.reposFin || null,
    }
    if (editing.value) {
      await api(`/drh/rotations/${editing.value.id}`, { method: 'PUT', body })
    } else {
      await api('/drh/rotations', { method: 'POST', body })
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement (chevauchement possible sur le même site).")
  } finally {
    saving.value = false
  }
}

async function supprimer(r: Rotation) {
  saving.value = true
  erreur.value = ''
  try {
    await api(`/drh/rotations/${r.id}`, { method: 'DELETE' })
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la suppression.')
  } finally {
    saving.value = false
  }
}

const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Rotations de superviseurs</h1>
        <p class="page-sub">Cycles de prestation (14 j) et repos (7 j) sur les sites opérationnels</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn color="error" variant="tonal" rounded="lg" prepend-icon="mdi-printer-outline" :disabled="!rotations.length" @click="imprimer">
          Imprimer
        </v-btn>
        <v-btn v-if="canWrite" color="success" variant="flat" rounded="lg" prepend-icon="mdi-plus" @click="ouvrirAjout">
          Nouvelle rotation
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

    <!-- Colonne flex, hauteur fixee a l'impression (rot-print-page) : pousse la signature (margin-top:auto)
         tout en bas de la page plutot que de la laisser suivre directement le tableau — meme technique que
         le bulletin de paie (bp-print-page). -->
    <div class="rot-print-page">
    <div class="etat-print-header">
      <div class="etat-print-header__brand">
        <div class="etat-print-header__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-finance" size="16" color="white" />
        </div>
        <div>
          <span class="etat-print-header__company">{{ parametresStore.parametres.nom }}</span>
          <span class="etat-print-header__service">Direction des Ressources Humaines</span>
          <span class="etat-print-header__doc">Planning des superviseurs — {{ MOIS[mois - 1] }} {{ annee }}</span>
        </div>
      </div>
      <div class="etat-print-header__meta">
        <span>Imprimé le : {{ dateImpression }}</span>
      </div>
    </div>

    <v-card class="classroom-card no-print">
      <v-data-table
        :headers="[
          { title: 'Superviseur', key: 'employeNomComplet' },
          { title: 'Site', key: 'siteNom' },
          { title: 'Cycle', key: 'numeroCycle', align: 'center' },
          { title: 'Prestation', key: 'prestationDebut' },
          { title: 'Repos', key: 'reposDebut' },
          { title: 'Actions', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="rotations"
        :loading="loading"
        items-per-page="25"
      >
        <template #item.prestationDebut="{ item }">{{ fmtDate(item.prestationDebut) }} → {{ fmtDate(item.prestationFin) }} ({{ item.joursPrestation }}j)</template>
        <template #item.reposDebut="{ item }">{{ fmtDate(item.reposDebut) }} → {{ fmtDate(item.reposFin) }} ({{ item.joursRepos }}j)</template>
        <template #item.actions="{ item }">
          <v-btn v-if="canWrite" size="small" variant="text" icon="mdi-pencil-outline" title="Modifier" @click="ouvrirEdition(item)" />
          <v-btn v-if="canWrite" size="small" variant="text" color="error" icon="mdi-delete-outline" title="Supprimer" @click="supprimer(item)" />
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">Aucune rotation pour cette période.</div>
        </template>
      </v-data-table>
    </v-card>

    <!-- Version imprimable : table simple, sans pagination ni virtualisation. -->
    <table class="etat-table print-only">
      <thead>
        <tr><th>Superviseur</th><th>Site</th><th>Cycle</th><th>Prestation</th><th>Repos</th></tr>
      </thead>
      <tbody>
        <tr v-for="r in rotations" :key="r.id">
          <td>{{ r.employeNomComplet }}</td><td>{{ r.siteNom }}</td><td>{{ r.numeroCycle }}</td>
          <td>{{ fmtDate(r.prestationDebut) }} → {{ fmtDate(r.prestationFin) }} ({{ r.joursPrestation }}j)</td>
          <td>{{ fmtDate(r.reposDebut) }} → {{ fmtDate(r.reposFin) }} ({{ r.joursRepos }}j)</td>
        </tr>
      </tbody>
    </table>

    <div v-if="directeurDrh" class="rot-signature">
      <span class="rot-signature__nom">{{ directeurDrh.toUpperCase() }}</span>
      <span class="rot-signature__fonction">{{ fonctionDirecteur }}</span>
      <div class="rot-signature__line" />
      <span class="rot-signature__hint">Signature et cachet</span>
    </div>
    </div>

    <v-dialog v-model="dialog" max-width="640">
      <v-card>
        <v-card-title>{{ editing ? 'Modifier la rotation' : 'Nouvelle rotation' }}</v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>
          <v-row>
            <v-col cols="12" md="6">
              <v-select v-model="form.employeId" :items="superviseurs.map(e => ({ title: `${e.matricule} — ${e.nomComplet}`, value: e.id }))"
                label="Superviseur *" variant="outlined" density="comfortable" />
            </v-col>
            <v-col cols="12" md="6">
              <v-select v-model="form.siteId" :items="sites.map(s => ({ title: s.nom, value: s.id }))"
                label="Site *" variant="outlined" density="comfortable" />
            </v-col>
            <v-col cols="12" md="6"><v-select v-model="form.mois" :items="MOIS.map((m, i) => ({ title: m, value: i + 1 }))" label="Mois" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model.number="form.annee" type="number" label="Année" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.prestationDebut" type="date" label="Début de prestation *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.prestationFin" type="date" label="Fin de prestation (auto : +14j si vide)" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.reposDebut" type="date" label="Début de repos (auto si vide)" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model="form.reposFin" type="date" label="Fin de repos (auto : +7j si vide)" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12"><v-textarea v-model="form.notes" label="Notes" variant="outlined" density="comfortable" rows="2" /></v-col>
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

<style scoped>
.print-only { display: none; }
.etat-table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
.etat-table th { text-align: left; padding: 7px 8px; font-size: 0.68rem; text-transform: uppercase; color: #6b7280; border-bottom: 1px solid #e5e7eb; }
.etat-table td { padding: 6px 8px; border-bottom: 1px solid #f3f4f6; color: #374151; }

/* ── Signature DRH, pied de page imprime uniquement ─────────────────────── */
.rot-print-page { display: flex; flex-direction: column; }
.rot-signature { display: none; }

@media print {
  .print-only { display: table; }

  /* Hauteur imprimable A4 (297mm) moins les marges @page (10mm haut/bas,
     voir classroom.scss) : donne au conteneur de quoi pousser .rot-signature
     jusqu'en bas via margin-top:auto, meme technique que le bulletin de
     paie (bp-print-page). */
  .rot-print-page { min-height: 260mm; }
  .rot-signature {
    display: flex; flex-direction: column; align-items: flex-end;
    margin-top: auto; padding-top: 16px; width: 220px; align-self: flex-end;
  }
  .rot-signature__nom { font-size: 0.78rem; font-weight: 700; color: #111827; }
  .rot-signature__fonction { font-size: 0.72rem; color: #374151; margin-top: 1px; }
  .rot-signature__line { width: 100%; border-top: 1px solid #111827; margin-top: 28px; }
  .rot-signature__hint { font-size: 0.62rem; color: #9ca3af; font-style: italic; margin-top: 4px; }
}
</style>
