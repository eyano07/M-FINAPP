<script setup lang="ts">
// PeriodeComptableService.definirDateCloture est reserve a ADMIN : sans ce
// controle de role, DFIN et DA atteignaient la page puis recevaient un 403.
definePageMeta({ module: 'COMPTABILITE', niveau: 'ECRITURE', roles: ['ADMIN'] })

const api = useApi()
const auth = useAuthStore()

const loading = ref(false)
const envoi = ref(false)
const erreur = ref('')
const succes = ref('')
const dateCloture = ref<string | null>(null)
const nouvelleDate = ref<string>('')

const estAdmin = computed(() => auth.hasRole('ADMIN'))

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const res = await api<{ dateCloture: string | null }>('/comptabilite/cloture')
    dateCloture.value = res.dateCloture
    nouvelleDate.value = res.dateCloture ?? ''
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger la date de clôture.')
  } finally {
    loading.value = false
  }
}

async function enregistrer(supprimer = false) {
  envoi.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const res = await api<{ dateCloture: string | null }>('/comptabilite/cloture', {
      method: 'PUT',
      body: { dateCloture: supprimer ? null : nouvelleDate.value || null },
    })
    dateCloture.value = res.dateCloture
    nouvelleDate.value = res.dateCloture ?? ''
    succes.value = res.dateCloture
      ? `Période clôturée jusqu'au ${fmtDate(res.dateCloture)} inclus.`
      : 'Clôture levée : toutes les périodes sont ouvertes.'
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible d'enregistrer la clôture.")
  } finally {
    envoi.value = false
  }
}

// ── Clôture annuelle formelle ────────────────────────────────────────────
// Distincte du verrou de date ci-dessus : elle solde les comptes de gestion
// (classes 6/7) vers le résultat et réévalue les créances en devise. Elle
// génère de vraies écritures, d'où la confirmation explicite.
const dialogExercice = ref(false)
const dateExercice = ref<string>(new Date().toISOString().slice(0, 10))
const clotureEnCours = ref(false)
const resultatCloture = ref<{
  dateCloture: string
  resultatNet: number
  creancesReevaluees: number
  reevaluationsReversees: number
  pieceReevaluationReference: string | null
} | null>(null)

async function cloturerExercice() {
  clotureEnCours.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const res = await api<any>('/comptabilite/cloture/exercice', {
      method: 'POST',
      body: { dateCloture: dateExercice.value },
    })
    resultatCloture.value = res
    dialogExercice.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible de clôturer l'exercice.")
    dialogExercice.value = false
  } finally {
    clotureEnCours.value = false
  }
}

onMounted(charger)

const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '')
const fmtMontant = (v: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(v || 0)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Clôture de période</h1>
        <p class="page-sub">
          Aucune écriture ne peut être créée, comptabilisée ou extournée à une date
          antérieure ou égale à la date de clôture (principe « closing date »)
        </p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4">{{ succes }}</v-alert>

    <v-card class="classroom-card pa-6" max-width="640" :loading="loading">
      <div class="d-flex align-center ga-3 mb-5">
        <v-icon :icon="dateCloture ? 'mdi-lock' : 'mdi-lock-open-variant'" size="32"
                :color="dateCloture ? 'error' : 'success'" />
        <div>
          <div class="text-h6 font-weight-bold">
            {{ dateCloture ? `Comptabilité clôturée jusqu'au ${fmtDate(dateCloture)}` : 'Aucune clôture active' }}
          </div>
          <div class="text-caption text-medium-emphasis">
            {{ dateCloture
              ? 'Les écritures antérieures à cette date sont verrouillées.'
              : 'Toutes les dates sont ouvertes à la saisie.' }}
          </div>
        </div>
      </div>

      <template v-if="estAdmin">
        <v-text-field
          v-model="nouvelleDate"
          label="Clôturer jusqu'au (inclus)"
          type="date"
          variant="outlined"
          density="comfortable"
          class="mb-4"
        />
        <div class="d-flex ga-3">
          <v-btn color="primary" :loading="envoi" prepend-icon="mdi-lock" @click="enregistrer(false)">
            Enregistrer la clôture
          </v-btn>
          <v-btn v-if="dateCloture" variant="outlined" color="error" :loading="envoi"
                 prepend-icon="mdi-lock-open-variant" @click="enregistrer(true)">
            Lever la clôture
          </v-btn>
        </div>
      </template>
      <v-alert v-else type="info" variant="tonal" density="compact">
        Seul un administrateur peut modifier la date de clôture.
      </v-alert>
    </v-card>

    <!-- ── Clôture annuelle formelle ──────────────────────────────────── -->
    <v-card v-if="estAdmin" class="classroom-card pa-6 mt-4" max-width="640">
      <div class="d-flex align-center ga-3 mb-4">
        <v-icon icon="mdi-book-check-outline" size="32" color="deep-purple" />
        <div>
          <div class="text-h6 font-weight-bold">Clôture annuelle de l'exercice</div>
          <div class="text-caption text-medium-emphasis">
            Opération comptable complète, au-delà du simple verrou de date.
          </div>
        </div>
      </div>

      <v-alert type="info" variant="tonal" density="compact" class="mb-4">
        Cette clôture enchaîne quatre opérations, en une seule transaction :
        <ul class="mt-2 ml-4">
          <li>reversement des réévaluations de change de la clôture précédente ;</li>
          <li>réévaluation des créances clients en devise étrangère au taux du jour (écarts 478/479) ;</li>
          <li>soldage des comptes de charges et produits (classes 6 et 7) vers le résultat (131/139) ;</li>
          <li>verrouillage de la date.</li>
        </ul>
      </v-alert>

      <v-text-field
        v-model="dateExercice"
        label="Clôturer l'exercice au"
        type="date"
        variant="outlined"
        density="comfortable"
        class="mb-4"
      />
      <v-btn color="deep-purple" variant="flat" prepend-icon="mdi-book-check-outline"
             @click="dialogExercice = true">
        Clôturer l'exercice
      </v-btn>

      <v-alert v-if="resultatCloture" type="success" variant="tonal" class="mt-4">
        <div class="font-weight-bold mb-1">
          Exercice clôturé au {{ fmtDate(resultatCloture.dateCloture) }}
        </div>
        <div>
          Résultat net porté au bilan :
          <strong>{{ fmtMontant(resultatCloture.resultatNet) }}</strong>
          ({{ resultatCloture.resultatNet >= 0 ? 'bénéfice' : 'perte' }})
        </div>
        <div v-if="resultatCloture.creancesReevaluees > 0">
          {{ resultatCloture.creancesReevaluees }} créance(s) en devise réévaluée(s)
          (une pièce par créance, voir le grand livre du compte 4111)
        </div>
        <div v-if="resultatCloture.reevaluationsReversees > 0">
          {{ resultatCloture.reevaluationsReversees }} réévaluation(s) précédente(s) reversée(s)
        </div>
      </v-alert>
    </v-card>

    <!-- Confirmation : l'opération génère des écritures définitives -->
    <v-dialog v-model="dialogExercice" max-width="520">
      <v-card class="classroom-card pa-6">
        <div class="d-flex align-center ga-3 mb-4">
          <v-icon icon="mdi-alert-circle-outline" color="warning" size="28" />
          <span class="text-h6 font-weight-bold">Confirmer la clôture</span>
        </div>
        <p class="mb-4">
          La clôture au <strong>{{ fmtDate(dateExercice) }}</strong> va générer des écritures
          comptables définitives et verrouiller toutes les dates antérieures.
          Les écritures produites ne pourront être corrigées que par extourne.
        </p>
        <p class="text-medium-emphasis mb-4">
          Vérifiez que la balance est équilibrée et que toutes les opérations de
          l'exercice ont été saisies avant de continuer.
        </p>
        <div class="d-flex ga-3 justify-end">
          <v-btn variant="text" @click="dialogExercice = false">Annuler</v-btn>
          <v-btn color="deep-purple" variant="flat" :loading="clotureEnCours" @click="cloturerExercice">
            Clôturer définitivement
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
</style>
