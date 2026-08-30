<script setup lang="ts">
// Constitution/reprise reservees au DFIN cote serveur (ProvisionService) ;
// la consultation est ouverte a DA/DG/COMPTABLE. CAISSIER a COMPTABILITE en
// LECTURE (pour Balance/Compte de resultat uniquement) mais ne voit pas les
// Provisions.
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] })

interface Reprise {
  id: number
  montant: number
  motif?: string
  dateReprise: string
  compteRepriseNumero: string
  pieceReference?: string
}

interface Provision {
  id: number
  reference: string
  libelle: string
  compteProvisionNumero: string
  compteProvisionLibelle: string
  compteDotationNumero: string
  montantConstitue: number
  montantRepris: number
  soldeRestant: number
  statut: 'CONSTITUEE' | 'REPRISE_PARTIELLE' | 'REPRISE_TOTALE'
  dateConstitution: string
  pieceConstitutionReference?: string
  createdByNom?: string
  reprises: Reprise[]
}

const api = useApi()
const auth = useAuthStore()
const canWrite = computed(() => auth.hasAnyRole(['DFIN']))

const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const provisions = ref<Provision[]>([])

const dialog = ref(false)
const form = reactive({
  libelle: '',
  compteProvisionNumero: null as string | null,
  compteDotationNumero: null as string | null,
  montant: null as number | null,
  dateConstitution: new Date().toISOString().slice(0, 10),
})

const dialogReprise = ref(false)
const provisionCourante = ref<Provision | null>(null)
const formReprise = reactive({
  montant: null as number | null,
  compteRepriseNumero: null as string | null,
  motif: '',
  dateReprise: new Date().toISOString().slice(0, 10),
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    provisions.value = await api<Provision[]>('/comptabilite/provisions')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les provisions.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function ouvrirCreation() {
  form.libelle = ''
  form.compteProvisionNumero = null
  form.compteDotationNumero = null
  form.montant = null
  form.dateConstitution = new Date().toISOString().slice(0, 10)
  erreur.value = ''
  dialog.value = true
}

async function enregistrer() {
  if (!form.libelle.trim() || !form.compteProvisionNumero || !form.compteDotationNumero || !form.montant) {
    erreur.value = 'Libellé, comptes et montant sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    await api('/comptabilite/provisions', { method: 'POST', body: { ...form } })
    succes.value = 'Provision constituée : la dotation est passée au grand livre.'
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible de constituer la provision.")
  } finally {
    saving.value = false
  }
}

function ouvrirReprise(p: Provision) {
  provisionCourante.value = p
  formReprise.montant = p.soldeRestant
  formReprise.compteRepriseNumero = null
  formReprise.motif = ''
  formReprise.dateReprise = new Date().toISOString().slice(0, 10)
  erreur.value = ''
  dialogReprise.value = true
}

async function enregistrerReprise() {
  if (!formReprise.montant || !formReprise.compteRepriseNumero) {
    erreur.value = 'Montant et compte de reprise sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    await api(`/comptabilite/provisions/${provisionCourante.value?.id}/reprendre`, {
      method: 'POST',
      body: { ...formReprise },
    })
    succes.value = 'Reprise enregistrée.'
    dialogReprise.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible d'enregistrer la reprise.")
  } finally {
    saving.value = false
  }
}

const statutMeta: Record<string, { label: string; color: string }> = {
  CONSTITUEE:        { label: 'Constituée',        color: 'primary' },
  REPRISE_PARTIELLE: { label: 'Reprise partielle', color: 'warning' },
  REPRISE_TOTALE:    { label: 'Reprise totale',    color: 'grey' },
}

const totalProvisionne = computed(() =>
  provisions.value.reduce((s, p) => s + (p.soldeRestant || 0), 0))

const fmt = (v: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(v || 0)
const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '')
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Provisions</h1>
        <p class="page-sub">
          Provisions pour risques et charges et dépréciations — constitution, suivi et reprise
        </p>
      </div>
      <v-btn v-if="canWrite" color="primary" variant="flat" rounded="lg"
             prepend-icon="mdi-plus" @click="ouvrirCreation">
        Constituer une provision
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">
      {{ succes }}
    </v-alert>

    <v-card class="classroom-card pa-4 mb-4">
      <div class="kpi-label">Total provisionné (solde restant)</div>
      <div class="kpi-value">{{ fmt(totalProvisionne) }}</div>
    </v-card>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Référence', key: 'reference' },
          { title: 'Libellé', key: 'libelle' },
          { title: 'Compte', key: 'compteProvisionNumero' },
          { title: 'Constitué', key: 'montantConstitue', align: 'end' },
          { title: 'Repris', key: 'montantRepris', align: 'end' },
          { title: 'Restant', key: 'soldeRestant', align: 'end' },
          { title: 'Statut', key: 'statut' },
          { title: '', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="provisions"
        :loading="loading"
        items-per-page="25"
        no-data-text="Aucune provision constituée."
      >
        <template #item.compteProvisionNumero="{ item }">
          <span class="font-weight-medium">{{ item.compteProvisionNumero }}</span>
          <span class="text-medium-emphasis"> — {{ item.compteProvisionLibelle }}</span>
        </template>
        <template #item.montantConstitue="{ item }">{{ fmt(item.montantConstitue) }}</template>
        <template #item.montantRepris="{ item }">
          {{ item.montantRepris ? fmt(item.montantRepris) : '—' }}
        </template>
        <template #item.soldeRestant="{ item }">
          <span class="font-weight-bold">{{ fmt(item.soldeRestant) }}</span>
        </template>
        <template #item.statut="{ item }">
          <v-chip :color="statutMeta[item.statut]?.color" size="small" variant="tonal">
            {{ statutMeta[item.statut]?.label }}
          </v-chip>
        </template>
        <template #item.actions="{ item }">
          <v-btn v-if="canWrite && item.soldeRestant > 0" size="small" variant="text" color="warning"
                 prepend-icon="mdi-undo-variant" @click="ouvrirReprise(item)">
            Reprendre
          </v-btn>
        </template>
      </v-data-table>
    </v-card>

    <!-- ── Constitution ────────────────────────────────────── -->
    <v-dialog v-model="dialog" max-width="560">
      <v-card class="classroom-card pa-6">
        <div class="text-h6 font-weight-bold mb-4">Constituer une provision</div>

        <v-text-field v-model="form.libelle" label="Libellé (nature du risque) *"
                      variant="outlined" density="comfortable" class="mb-3" />

        <ComptabiliteSelecteurCompte v-model="form.compteProvisionNumero"
                                     label="Compte de provision (15x / 19x / 29x…) *" class="mb-3" />
        <ComptabiliteSelecteurCompte v-model="form.compteDotationNumero"
                                     label="Compte de dotation (68x / 69x) *" class="mb-3" />

        <v-text-field v-model.number="form.montant" type="number" label="Montant (USD) *"
                      variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.dateConstitution" type="date" label="Date de constitution"
                      variant="outlined" density="comfortable" class="mb-4" />

        <div class="d-flex ga-3 justify-end">
          <v-btn variant="text" @click="dialog = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="saving" @click="enregistrer">
            Constituer
          </v-btn>
        </div>
      </v-card>
    </v-dialog>

    <!-- ── Reprise ─────────────────────────────────────────── -->
    <v-dialog v-model="dialogReprise" max-width="560">
      <v-card class="classroom-card pa-6">
        <div class="text-h6 font-weight-bold mb-1">Reprendre la provision</div>
        <p class="text-medium-emphasis mb-4">
          {{ provisionCourante?.reference }} — solde restant
          {{ fmt(provisionCourante?.soldeRestant || 0) }}
        </p>

        <v-text-field v-model.number="formReprise.montant" type="number" label="Montant à reprendre (USD) *"
                      variant="outlined" density="comfortable" class="mb-3" />
        <ComptabiliteSelecteurCompte v-model="formReprise.compteRepriseNumero"
                                     label="Compte de reprise (79x / 77x) *" class="mb-3" />
        <v-text-field v-model="formReprise.motif" label="Motif (optionnel)"
                      variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="formReprise.dateReprise" type="date" label="Date de reprise"
                      variant="outlined" density="comfortable" class="mb-4" />

        <div class="d-flex ga-3 justify-end">
          <v-btn variant="text" @click="dialogReprise = false">Annuler</v-btn>
          <v-btn color="warning" variant="flat" :loading="saving" @click="enregistrerReprise">
            Enregistrer la reprise
          </v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.kpi-label { font-size: 0.72rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.5rem; font-weight: 700; color: #111827; margin-top: 4px; }
</style>
