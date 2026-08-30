<script setup lang="ts">
import type { LigneEcritureForm } from '~/components/comptabilite/LignesEcriture.vue'

// ComptabiliteService.creerPiece est reserve au DFIN et au COMPTABLE (ADMIN
// ne modifie que les pages dediees a l'administration) : le DA a bien
// LECTURE sur le module mais ne peut pas creer de piece, il recevait donc un
// 403 apres avoir rempli tout le formulaire.
definePageMeta({ module: 'COMPTABILITE', niveau: 'ECRITURE', roles: ['DFIN', 'COMPTABLE'] })

const api = useApi()
const router = useRouter()
const saving = ref(false)
const erreur = ref('')

const journaux = [
  { title: 'Opérations diverses', value: 'OPERATIONS_DIVERSES' },
  { title: 'Caisse', value: 'CAISSE' },
  { title: 'Banque', value: 'BANQUE' },
  { title: 'Mobile Money', value: 'MOBILE_MONEY' },
  { title: 'Achats', value: 'ACHATS' },
  { title: 'Ventes', value: 'VENTES' },
]

const LIBELLE_OUVERTURE = "Solde d'ouverture"

const form = reactive({
  datePiece: new Date().toISOString().slice(0, 10),
  journal: 'OPERATIONS_DIVERSES',
  libelle: '',
  soldeOuverture: false,
  lignes: [
    { compteNumero: null, debit: null, credit: null, libelle: '' },
    { compteNumero: null, debit: null, credit: null, libelle: '' },
  ] as LigneEcritureForm[],
})

// Le libellé n'est plus ce qui déclenche le classement en ouverture (c'est la
// case à cocher), mais on le pré-remplit pour que la pièce reste lisible dans
// le journal et la liste. On ne touche jamais à un libellé déjà saisi.
watch(() => form.soldeOuverture, (coche) => {
  if (coche && !form.libelle.trim()) {
    form.libelle = LIBELLE_OUVERTURE
  } else if (!coche && form.libelle.trim() === LIBELLE_OUVERTURE) {
    form.libelle = ''
  }
})

const totalDebit = computed(() =>
  form.lignes.reduce((s, l) => s + (Number(l.debit) || 0), 0)
)
const totalCredit = computed(() =>
  form.lignes.reduce((s, l) => s + (Number(l.credit) || 0), 0)
)
const equilibree = computed(() =>
  Math.abs(totalDebit.value - totalCredit.value) < 0.01 && totalDebit.value > 0
)

async function enregistrer() {
  if (!form.libelle.trim()) {
    erreur.value = 'Le libellé de la pièce est obligatoire.'
    return
  }
  if (!equilibree.value) {
    erreur.value = 'La pièce doit être équilibrée (total débit = total crédit).'
    return
  }
  for (const l of form.lignes) {
    if (!l.compteNumero) {
      erreur.value = 'Chaque ligne doit avoir un compte.'
      return
    }
  }

  saving.value = true
  erreur.value = ''
  try {
    await api('/comptabilite/pieces', {
      method: 'POST',
      body: {
        datePiece: form.datePiece,
        journal: form.journal,
        libelle: form.libelle.trim(),
        soldeOuverture: form.soldeOuverture,
        lignes: form.lignes.map(l => ({
          compteNumero: l.compteNumero,
          debit: l.debit || 0,
          credit: l.credit || 0,
          libelle: l.libelle || form.libelle,
        })),
      },
    })
    router.push('/comptabilite/pieces')
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Enregistrement impossible.'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Nouvelle pièce comptable</h1>
        <p class="page-sub">Saisie manuelle en partie double (débit = crédit)</p>
      </div>
      <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/comptabilite/pieces">
        Retour
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-6 mb-4">
      <v-row>
        <v-col cols="12" md="4">
          <v-text-field
            v-model="form.datePiece"
            label="Date de la pièce"
            type="date"
            variant="outlined"
            density="comfortable"
          />
        </v-col>
        <v-col cols="12" md="4">
          <v-select
            v-model="form.journal"
            :items="journaux"
            label="Journal"
            variant="outlined"
            density="comfortable"
          />
        </v-col>
        <v-col cols="12" md="4">
          <v-text-field
            v-model="form.libelle"
            label="Libellé de la pièce"
            variant="outlined"
            density="comfortable"
          />
        </v-col>
      </v-row>

      <v-divider class="my-2" />

      <v-checkbox
        v-model="form.soldeOuverture"
        color="primary"
        density="comfortable"
        hide-details
      >
        <template #label>
          <span class="font-weight-medium">Reprise des à-nouveaux (solde d'ouverture)</span>
        </template>
      </v-checkbox>
      <p class="ouverture-aide">
        <v-icon icon="mdi-information-outline" size="14" class="mr-1" />
        <template v-if="form.soldeOuverture">
          Cette pièce alimentera les colonnes <strong>Soldes d'ouverture</strong> de la balance
          et sera exclue des mouvements de la période — même si elle est datée dans l'exercice.
        </template>
        <template v-else>
          À cocher uniquement pour reprendre les soldes de l'exercice précédent. Une pièce
          ordinaire compte dans les mouvements de la période.
        </template>
      </p>
    </v-card>

    <v-card class="classroom-card pa-6 mb-4">
      <ComptabiliteLignesEcriture v-model="form.lignes" />
    </v-card>

    <div class="d-flex justify-end ga-3">
      <v-btn variant="outlined" to="/comptabilite/pieces">Annuler</v-btn>
      <v-btn
        color="primary"
        :loading="saving"
        :disabled="!equilibree"
        prepend-icon="mdi-content-save"
        @click="enregistrer"
      >
        Enregistrer la pièce
      </v-btn>
    </div>
  </div>
</template>

<style scoped>
.ouverture-aide {
  display: flex;
  align-items: flex-start;
  margin: 4px 0 0 40px;
  font-size: 0.8rem;
  line-height: 1.4;
  color: #6b7280;
}
</style>
