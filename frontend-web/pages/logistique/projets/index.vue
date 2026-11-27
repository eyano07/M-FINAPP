<script setup lang="ts">
/**
 * Projets (27/11/2026) : le logisticien crée un projet et sa liste de besoins, l'envoie au comptable (qui en tire le
 * budget secondaire), puis suit la fiche de stock du projet, séparée du stock global (aucune pièce comptable).
 */
definePageMeta({ module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN'] })
useHead({ title: 'Projets' })

interface Projet {
  id: number, code: string, nom: string, description: string | null, statut: string, motifRetour: string | null,
  budgetId: number | null, budgetReference: string | null, budgetStatut: string | null, nombreBesoins: number,
  prevuUsd: number, payeUsd: number
}

const api = useApi()
const auth = useAuthStore()
const peutEcrire = computed(() => auth.hasRole('LOGISTIQUE'))
const projets = ref<Projet[]>([])
const chargement = ref(false)
const erreur = ref('')
const succes = ref('')
const exportEnCours = ref(false)
const synchro = ref(false)

const nouveau = reactive({ ouvert: false, nom: '', description: '', enCours: false })

async function charger() {
  chargement.value = true
  erreur.value = ''
  try {
    projets.value = await api<Projet[]>('/logistique/projets')
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Projets indisponibles.')
  } finally {
    chargement.value = false
  }
}
onMounted(charger)

async function creer() {
  nouveau.enCours = true
  try {
    const p = await api<Projet>('/logistique/projets', { method: 'POST', body: { nom: nouveau.nom, description: nouveau.description || null } })
    nouveau.ouvert = false
    await navigateTo(`/logistique/projets/${p.id}`)
  } catch (e) {
    erreur.value = messageErreurApi(e, "Le projet n'a pas été créé.")
  } finally {
    nouveau.enCours = false
  }
}

async function synchroniser() {
  synchro.value = true
  try {
    const r = await api<{ entrees: number }>('/logistique/projets/synchroniser', { method: 'POST' })
    succes.value = r.entrees ? `${r.entrees} achat(s) payé(s) entré(s) dans les fiches de stock projet.` : 'Les fiches de stock projet sont à jour.'
    await charger()
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Synchronisation impossible.')
  } finally {
    synchro.value = false
  }
}

async function exporter() {
  exportEnCours.value = true
  try {
    await telechargerFichier(api, '/logistique/projets/export', `Projets_${new Date().toISOString().slice(0, 10)}.xlsx`)
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Export impossible.')
  } finally {
    exportEnCours.value = false
  }
}

const STATUTS: Record<string, { libelle: string, couleur: string }> = {
  BROUILLON: { libelle: 'Brouillon', couleur: 'grey' },
  ENVOYE: { libelle: 'Envoyé au comptable', couleur: 'blue' },
  RETOURNE: { libelle: 'Retourné', couleur: 'warning' },
  BUDGETE: { libelle: 'Budgété', couleur: 'success' },
  CLOTURE: { libelle: 'Clôturé', couleur: 'deep-purple' },
}
const fmt = (v: number | null | undefined) => new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(v || 0))
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Projets</h1>
        <p class="page-sub">Besoins, budget du projet et fiche de stock projet — séparée du stock global, sans écriture comptable</p>
      </div>
      <div class="d-flex flex-wrap ga-2">
        <v-btn variant="tonal" rounded="lg" prepend-icon="mdi-tray-arrow-down" to="/logistique/projets/a-affecter">À affecter</v-btn>
        <v-btn v-if="auth.hasAnyRole(['LOGISTIQUE', 'COMPTABLE', 'DFIN'])" variant="tonal" rounded="lg" prepend-icon="mdi-sync" :loading="synchro" @click="synchroniser">Synchroniser</v-btn>
        <v-btn color="success" variant="tonal" rounded="lg" prepend-icon="mdi-file-excel-outline" :loading="exportEnCours" @click="exporter">Excel</v-btn>
        <v-btn v-if="peutEcrire" color="primary" rounded="lg" prepend-icon="mdi-plus" @click="Object.assign(nouveau, { ouvert: true, nom: '', description: '' })">Nouveau projet</v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>

    <v-card class="classroom-card">
      <v-table density="comfortable">
        <thead>
          <tr><th>Projet</th><th>Statut</th><th>Budget</th><th class="text-end">Besoins</th><th class="text-end">Prévu (USD)</th><th class="text-end">Payé (USD)</th><th class="text-end">Reste (USD)</th></tr>
        </thead>
        <tbody>
          <tr v-if="!chargement && !projets.length"><td colspan="7" class="text-center text-medium-emphasis py-6">Aucun projet pour l'instant.</td></tr>
          <tr v-for="p in projets" :key="p.id" class="ligne-projet" @click="navigateTo(`/logistique/projets/${p.id}`)">
            <td><div class="font-weight-bold">{{ p.nom }}</div><div class="text-caption text-medium-emphasis">{{ p.code }}</div></td>
            <td><v-chip size="small" variant="tonal" :color="STATUTS[p.statut]?.couleur">{{ STATUTS[p.statut]?.libelle || p.statut }}</v-chip></td>
            <td class="text-caption">{{ p.budgetReference || '—' }}</td>
            <td class="text-end">{{ p.nombreBesoins }}</td>
            <td class="text-end">{{ fmt(p.prevuUsd) }}</td>
            <td class="text-end">{{ fmt(p.payeUsd) }}</td>
            <td class="text-end" :class="p.prevuUsd - p.payeUsd < 0 ? 'text-error' : ''">{{ fmt(p.prevuUsd - p.payeUsd) }}</td>
          </tr>
        </tbody>
      </v-table>
    </v-card>

    <v-dialog v-model="nouveau.ouvert" max-width="520">
      <v-card rounded="xl">
        <v-card-title class="pt-4 px-5">Nouveau projet</v-card-title>
        <v-card-text class="px-5">
          <v-text-field v-model="nouveau.nom" label="Nom du projet *" placeholder="ex. Forage du puits de Kasumbalesa" variant="outlined" density="comfortable" class="mb-3" />
          <v-textarea v-model="nouveau.description" label="Description" rows="3" variant="outlined" density="comfortable" />
          <p class="text-caption text-medium-emphasis mb-0">Ajoutez ensuite la liste des besoins, puis envoyez le projet au comptable.</p>
        </v-card-text>
        <v-card-actions class="px-5 pb-4">
          <v-spacer />
          <v-btn variant="text" @click="nouveau.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :disabled="!nouveau.nom.trim()" :loading="nouveau.enCours" @click="creer">Créer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.ligne-projet { cursor: pointer; }
</style>
