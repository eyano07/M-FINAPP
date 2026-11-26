<script setup lang="ts">
// Arbitrage de trésorerie : réservé exclusivement au DA (l'administrateur n'y a pas accès, ni au menu ni à l'API).
// Nuxt donne la priorité à cette route statique sur /notes-frais/[id].
definePageMeta({ roles: ['DA'] })

const api = useApi()
const auth = useAuthStore()
const canWrite = computed(() => auth.hasAnyRole(['DA']))

const loading = ref(true)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const majParNom = ref<string | null>(null)
const majLe = ref<string | null>(null)

const form = reactive({
  seuilBasse: 0,
  seuilMoyenne: 0,
  seuilHaute: 0,
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const p = await api<Record<string, any>>('/notes-frais/parametres-priorite')
    form.seuilBasse = Number(p.seuilBasse) || 0
    form.seuilMoyenne = Number(p.seuilMoyenne) || 0
    form.seuilHaute = Number(p.seuilHaute) || 0
    majParNom.value = p.majParNom ?? null
    majLe.value = p.majLe ?? null
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les seuils de priorité.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

async function enregistrer() {
  if ([form.seuilBasse, form.seuilMoyenne, form.seuilHaute].some(v => v === null || v < 0)) {
    erreur.value = 'Les seuils doivent être des montants positifs (0 = aucune restriction).'
    return
  }
  saving.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const p = await api<Record<string, any>>('/notes-frais/parametres-priorite', {
      method: 'PUT',
      body: {
        seuilBasse: form.seuilBasse,
        seuilMoyenne: form.seuilMoyenne,
        seuilHaute: form.seuilHaute,
      },
    })
    majParNom.value = p.majParNom ?? null
    majLe.value = p.majLe ?? null
    succes.value = 'Seuils enregistrés — ils s’appliquent immédiatement aux prochains paiements.'
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

const fmtUsd = (v: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(v || 0)
const fmtDateHeure = (d: string | null) =>
  d ? new Date(d).toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' }) : null
</script>

<template>
  <div class="params-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Seuils de priorité</h1>
        <p class="page-sub">
          Réserve de trésorerie minimale à préserver selon la priorité de la note de frais
        </p>
      </div>
    </div>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-skeleton-loader v-if="loading" type="article" />

    <template v-else>
      <v-alert type="info" variant="tonal" density="comfortable" rounded="lg" class="mb-4">
        <div class="text-subtitle-2 mb-1">Comment la règle s'applique</div>
        Une note ne peut être payée que si le solde du canal de paiement,
        <strong>une fois le paiement effectué</strong>, reste supérieur ou égal au seuil de sa priorité.
        Une note de priorité basse ne peut donc pas entamer la réserve gardée pour les urgences.
        <div class="text-caption mt-2">
          S'applique aux trois canaux : caisse, banque et mobile money.
          Cette règle s'ajoute à l'ordre de traitement existant (HAUTE avant MOYENNE avant BASSE).
        </div>
      </v-alert>

      <v-card class="classroom-card pa-5 mb-4">
        <div class="section-title">Réserve minimale par priorité (USD)</div>
        <p class="text-caption text-medium-emphasis mb-4">
          <strong>0 = aucune restriction</strong> pour cette priorité. En principe, le seuil des notes
          basses est le plus élevé (on protège le plus de trésorerie), et celui des notes hautes le plus
          bas — souvent 0, pour ne jamais bloquer une urgence.
        </p>
        <v-row>
          <v-col cols="12" md="4">
            <v-text-field v-model.number="form.seuilBasse" type="number" min="0" prefix="$"
              label="Priorité BASSE" variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="Solde à préserver pour payer une note basse" persistent-hint />
          </v-col>
          <v-col cols="12" md="4">
            <v-text-field v-model.number="form.seuilMoyenne" type="number" min="0" prefix="$"
              label="Priorité MOYENNE" variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="Solde à préserver pour payer une note moyenne" persistent-hint />
          </v-col>
          <v-col cols="12" md="4">
            <v-text-field v-model.number="form.seuilHaute" type="number" min="0" prefix="$"
              label="Priorité HAUTE" variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="0 recommandé : ne jamais bloquer une urgence" persistent-hint />
          </v-col>
        </v-row>
      </v-card>

      <v-card class="classroom-card pa-5 mb-4">
        <div class="section-title">Exemple avec vos valeurs actuelles</div>
        <p class="text-body-2 mb-0">
          Si le solde de la caisse est de <strong>{{ fmtUsd(form.seuilBasse + 200) }}</strong>,
          une note <strong>BASSE</strong> de <strong>{{ fmtUsd(300) }}</strong> serait
          <template v-if="form.seuilBasse > 0 && (form.seuilBasse + 200 - 300) < form.seuilBasse">
            <span class="text-error font-weight-bold">refusée</span>
            (le solde tomberait à {{ fmtUsd(form.seuilBasse - 100) }}, sous la réserve de {{ fmtUsd(form.seuilBasse) }}).
          </template>
          <template v-else>
            <span class="text-success font-weight-bold">acceptée</span>
            <template v-if="form.seuilBasse === 0"> (aucune restriction : seuil à 0).</template>
            <template v-else> (le solde resterait au-dessus de la réserve).</template>
          </template>
        </p>
      </v-card>

      <div class="d-flex align-center justify-space-between flex-wrap ga-3">
        <p v-if="majParNom" class="text-caption text-medium-emphasis mb-0">
          Dernière modification par <strong>{{ majParNom }}</strong>
          <template v-if="fmtDateHeure(majLe)"> le {{ fmtDateHeure(majLe) }}</template>.
        </p>
        <v-spacer />
        <v-btn v-if="canWrite" color="success" variant="flat" rounded="lg" :loading="saving" @click="enregistrer">
          Enregistrer
        </v-btn>
      </div>
    </template>
  </div>
</template>

<style scoped>
.params-page { max-width: 900px; margin: 0 auto; padding-bottom: 48px; }
.section-title { font-size: 0.8rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.5px; color: #0d9488; margin-bottom: 12px; }
</style>
