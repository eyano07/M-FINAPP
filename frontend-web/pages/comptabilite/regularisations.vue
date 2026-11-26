<script setup lang="ts">
import { useExerciceStore } from '~/stores/exercice'
// Audit SYSCOHADA du 10/11/2026, C-11 : charges et produits constatés d'avance, factures non parvenues, produits à
// recevoir. Chaque régularisation écrit sa pièce à la date d'arrêté et son extourne au lendemain.
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] })

interface Regularisation {
  id: number
  type: 'CCA' | 'PCA' | 'FNP' | 'FAE'
  libelle: string
  compteGestion: string
  compteRegularisation: string
  montant: number
  dateRegularisation: string
  dateExtourne: string
  piece: string | null
  pieceExtourne: string | null
  statut: string
  creePar: string | null
}

const api = useApi()
const dialogues = useDialogues()
const auth = useAuthStore()
const peutSaisir = computed(() => auth.hasRole('COMPTABLE') || auth.hasRole('DFIN'))

const TYPES = [
  { value: 'CCA', title: "Charge constatée d'avance (D 476 / C charge)", prefixe: '6', defaut: '476' },
  { value: 'PCA', title: "Produit constaté d'avance (D produit / C 477)", prefixe: '7', defaut: '477' },
  { value: 'FNP', title: 'Facture non parvenue (D charge / C 4081)', prefixe: '6', defaut: '4081' },
  { value: 'FAE', title: 'Facture à établir (D 4181 / C produit)', prefixe: '7', defaut: '4181' },
]

const exerciceConsulte = useExerciceStore()
const annee = ref(exerciceConsulte.enConsultation ? exerciceConsulte.anneeChoisie : new Date().getFullYear() - (new Date().getMonth() < 3 ? 1 : 0))
const liste = ref<Regularisation[]>([])
const loading = ref(false)
const envoi = ref(false)
const erreur = ref('')
const succes = ref('')

const form = reactive({
  type: 'CCA',
  libelle: '',
  compteGestionNumero: null as string | null,
  compteRegularisationNumero: '476' as string | null,
  montant: null as number | null,
  dateRegularisation: `${annee.value}-12-31`,
})
const typeChoisi = computed(() => TYPES.find(t => t.value === form.type)!)
watch(() => form.type, () => {
  form.compteRegularisationNumero = typeChoisi.value.defaut
  form.compteGestionNumero = null
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    liste.value = await api<Regularisation[]>('/comptabilite/regularisations', { query: { annee: annee.value } })
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les régularisations.')
  } finally {
    loading.value = false
  }
}
watch(annee, charger)

async function enregistrer() {
  envoi.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const r = await api<Regularisation>('/comptabilite/regularisations', { method: 'POST', body: { ...form } })
    succes.value = `Régularisation enregistrée : pièce ${r.piece}, extourne ${r.pieceExtourne} au ${fmtDate(r.dateExtourne)}.`
    form.libelle = ''
    form.montant = null
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible d'enregistrer la régularisation.")
  } finally {
    envoi.value = false
  }
}

async function annuler(r: Regularisation) {
  const confirme = await dialogues.confirmer({
    titre: `Annuler la régularisation « ${r.libelle} » ?`,
    message: "Ses deux pièces, la régularisation et son extourne d'ouverture, seront extournées.",
    bouton: 'Annuler la régularisation',
    abandon: 'Retour',
    nature: 'danger',
    icone: 'mdi-cancel',
  })
  if (!confirme) return
  erreur.value = ''
  try {
    await api(`/comptabilite/regularisations/${r.id}/annuler`, { method: 'POST' })
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Annulation impossible.')
  }
}

onMounted(charger)

const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '')
const fmt = (v: number) => new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2 }).format(v || 0)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Régularisations de fin d'exercice</h1>
        <p class="page-sub">
          Indépendance des exercices : chaque charge et chaque produit sont rattachés à l'exercice qu'ils concernent.
          L'extourne est passée automatiquement au premier jour de l'exercice suivant.
        </p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4">{{ succes }}</v-alert>

    <v-card v-if="peutSaisir" class="classroom-card pa-6 mb-4">
      <div class="text-h6 font-weight-bold mb-3">Nouvelle régularisation</div>
      <div class="grille">
        <v-select v-model="form.type" :items="TYPES" label="Type" variant="outlined" density="comfortable" />
        <v-text-field v-model="form.dateRegularisation" type="date" label="Date d'arrêté" variant="outlined"
                      density="comfortable" />
        <v-text-field v-model="form.libelle" label="Libellé (ex. loyer de janvier payé en décembre)"
                      variant="outlined" density="comfortable" />
        <ComptabiliteSelecteurCompte v-model="form.compteGestionNumero"
                                     :label="typeChoisi.prefixe === '6' ? 'Compte de charge' : 'Compte de produit'"
                                     :prefixe="typeChoisi.prefixe" />
        <ComptabiliteSelecteurCompte v-model="form.compteRegularisationNumero" label="Compte de régularisation"
                                     exclure-prefixe="5" />
        <v-text-field v-model.number="form.montant" type="number" min="0" step="0.01" label="Montant"
                      variant="outlined" density="comfortable" />
      </div>
      <v-btn color="primary" :loading="envoi" :disabled="!form.libelle || !form.montant || !form.compteGestionNumero"
             prepend-icon="mdi-content-save" @click="enregistrer">
        Enregistrer
      </v-btn>
    </v-card>

    <v-card class="classroom-card">
      <div class="d-flex align-center ga-3 pa-4">
        <v-text-field v-model.number="annee" type="number" label="Exercice" variant="outlined" density="compact"
                      hide-details style="max-width: 140px" />
      </div>
      <v-data-table
        :headers="[
          { title: 'Type', key: 'type' },
          { title: 'Arrêté', key: 'dateRegularisation' },
          { title: 'Libellé', key: 'libelle' },
          { title: 'Comptes', key: 'comptes', sortable: false },
          { title: 'Montant', key: 'montant', align: 'end' },
          { title: 'Pièces', key: 'pieces', sortable: false },
          { title: 'Statut', key: 'statut' },
          { title: '', key: 'actions', sortable: false },
        ]"
        :items="liste"
        :loading="loading"
        no-data-text="Aucune régularisation pour cet exercice."
      >
        <template #item.dateRegularisation="{ item }">{{ fmtDate(item.dateRegularisation) }}</template>
        <template #item.comptes="{ item }">{{ item.compteGestion }} / {{ item.compteRegularisation }}</template>
        <template #item.montant="{ item }">{{ fmt(item.montant) }}</template>
        <template #item.pieces="{ item }">
          <div class="text-caption">{{ item.piece }}</div>
          <div class="text-caption text-medium-emphasis">extourne {{ item.pieceExtourne }} ({{ fmtDate(item.dateExtourne) }})</div>
        </template>
        <template #item.statut="{ item }">
          <v-chip :color="item.statut === 'ACTIVE' ? 'success' : 'grey'" size="small">{{ item.statut }}</v-chip>
        </template>
        <template #item.actions="{ item }">
          <v-btn v-if="peutSaisir && item.statut === 'ACTIVE'" size="small" variant="text" color="error"
                 @click="annuler(item)">
            Annuler
          </v-btn>
        </template>
      </v-data-table>
    </v-card>
  </div>
</template>

<style scoped>
.grille {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
}
</style>
