<script setup lang="ts">
definePageMeta({ module: 'DRH_PAIE', niveau: 'ECRITURE' })

const api = useApi()
const auth = useAuthStore()
const canWrite = computed(() => auth.hasAnyRole(['RESP_DRH']))

const loading = ref(true)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')

// Les taux sont stockes en base sous forme de fraction (0.30) ; l'ecran les
// affiche/saisit en pourcentage (30) pour la lisibilite, conversion faite
// uniquement au chargement/a l'enregistrement.
const form = reactive({
  tauxLogementPct: 30,
  tauxTransportPct: 10,
  tauxCnssOuvrierePct: 5,
  tauxCnssPatronalePct: 13,
  tauxOnemPct: 0.5,
  tauxInppPct: 3,
  reductionIprParEnfantPct: 2,
  plafondEnfantsIpr: 9,
  calculEnfantsActif: true,
  plancherIprFc: 2000,
  joursOuvrablesStandard: 26,
  // Comptabilisation optionnelle : desactivee, la cloture de periode
  // verrouille les bulletins sans poster aucune ecriture comptable.
  comptabiliserPaie: true,
  // Conformité RDC — références légales, purement consultatives (aucun
  // montant du bulletin n'est modifié automatiquement).
  cnssDeductibleIpr: false,
  smigJournalierFc: 21500,
  diviseurAllocationFamiliale: 27,
  plafondRetenuePct: 10,
  plafondTransportExonereFcJour: 0,
  heuresLegalesHebdo: 45,
  tauxMajorationHs1Pct: 30,
  tauxMajorationHs2Pct: 60,
  tauxMajorationHsFeriePct: 100,
  directeurDrh: '',
  fonctionDirecteur: 'Directeur des Ressources Humaines',
  villeSignature: 'Lubumbashi',
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const p = await api<Record<string, any>>('/drh/parametres-paie')
    Object.assign(form, {
      tauxLogementPct: Number(p.tauxLogement) * 100,
      tauxTransportPct: Number(p.tauxTransport) * 100,
      tauxCnssOuvrierePct: Number(p.tauxCnssOuvriere) * 100,
      tauxCnssPatronalePct: Number(p.tauxCnssPatronale) * 100,
      tauxOnemPct: Number(p.tauxOnem) * 100,
      tauxInppPct: Number(p.tauxInpp) * 100,
      reductionIprParEnfantPct: Number(p.reductionIprParEnfant) * 100,
      plafondEnfantsIpr: p.plafondEnfantsIpr,
      calculEnfantsActif: p.calculEnfantsActif !== false,
      plancherIprFc: Number(p.plancherIprFc),
      joursOuvrablesStandard: p.joursOuvrablesStandard,
      comptabiliserPaie: p.comptabiliserPaie !== false,
      cnssDeductibleIpr: !!p.cnssDeductibleIpr,
      smigJournalierFc: Number(p.smigJournalierFc),
      diviseurAllocationFamiliale: p.diviseurAllocationFamiliale,
      plafondRetenuePct: Number(p.plafondRetenuePct) * 100,
      plafondTransportExonereFcJour: Number(p.plafondTransportExonereFcJour),
      heuresLegalesHebdo: p.heuresLegalesHebdo,
      tauxMajorationHs1Pct: Number(p.tauxMajorationHs1) * 100,
      tauxMajorationHs2Pct: Number(p.tauxMajorationHs2) * 100,
      tauxMajorationHsFeriePct: Number(p.tauxMajorationHsFerie) * 100,
      directeurDrh: p.directeurDrh ?? '',
      fonctionDirecteur: p.fonctionDirecteur ?? 'Directeur des Ressources Humaines',
      villeSignature: p.villeSignature ?? 'Lubumbashi',
    })
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les paramètres de paie.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

async function enregistrer() {
  saving.value = true
  erreur.value = ''
  succes.value = ''
  try {
    await api('/drh/parametres-paie', {
      method: 'PUT',
      body: {
        tauxLogement: form.tauxLogementPct / 100,
        tauxTransport: form.tauxTransportPct / 100,
        tauxCnssOuvriere: form.tauxCnssOuvrierePct / 100,
        tauxCnssPatronale: form.tauxCnssPatronalePct / 100,
        tauxOnem: form.tauxOnemPct / 100,
        tauxInpp: form.tauxInppPct / 100,
        reductionIprParEnfant: form.reductionIprParEnfantPct / 100,
        plafondEnfantsIpr: form.plafondEnfantsIpr,
        calculEnfantsActif: form.calculEnfantsActif,
        plancherIprFc: form.plancherIprFc,
        joursOuvrablesStandard: form.joursOuvrablesStandard,
        comptabiliserPaie: form.comptabiliserPaie,
        cnssDeductibleIpr: form.cnssDeductibleIpr,
        smigJournalierFc: form.smigJournalierFc,
        diviseurAllocationFamiliale: form.diviseurAllocationFamiliale,
        plafondRetenuePct: form.plafondRetenuePct / 100,
        plafondTransportExonereFcJour: form.plafondTransportExonereFcJour,
        heuresLegalesHebdo: form.heuresLegalesHebdo,
        tauxMajorationHs1: form.tauxMajorationHs1Pct / 100,
        tauxMajorationHs2: form.tauxMajorationHs2Pct / 100,
        tauxMajorationHsFerie: form.tauxMajorationHsFeriePct / 100,
        directeurDrh: form.directeurDrh || null,
        fonctionDirecteur: form.fonctionDirecteur || null,
        villeSignature: form.villeSignature || null,
      },
    })
    succes.value = 'Paramètres de paie enregistrés.'
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="params-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Paramètres de paie</h1>
        <p class="page-sub">Taux appliqués au calcul des bulletins — logement, transport, cotisations, barème IPR</p>
      </div>
    </div>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-skeleton-loader v-if="loading" type="article" />

    <template v-else>
      <v-card class="classroom-card pa-5 mb-4">
        <div class="section-title">Comptabilisation de la paie</div>
        <v-switch
          v-model="form.comptabiliserPaie"
          color="primary"
          :disabled="!canWrite"
          hide-details
          class="mb-2"
          label="Comptabiliser la paie à la clôture de période"
        />
        <p class="text-caption text-medium-emphasis mb-0">
          Activé : clôturer une période poste automatiquement une pièce comptable BROUILLON par employé (salaire net
          à payer et charges patronales — CNSS, ONEM, INPP) dans Pièces comptables. Désactivé : la clôture verrouille
          simplement les bulletins de la période, sans générer aucune écriture comptable.
        </p>
      </v-card>

      <v-card class="classroom-card pa-5 mb-4">
        <div class="section-title">Indemnités (fraction du salaire brut prorata présence)</div>
        <v-row>
          <v-col cols="6" md="3"><v-text-field v-model.number="form.tauxLogementPct" type="number" suffix="%" label="Taux logement" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
          <v-col cols="6" md="3"><v-text-field v-model.number="form.tauxTransportPct" type="number" suffix="%" label="Taux transport" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
        </v-row>
      </v-card>

      <v-card class="classroom-card pa-5 mb-4">
        <div class="section-title">Cotisations sociales</div>
        <v-row>
          <v-col cols="6" md="3"><v-text-field v-model.number="form.tauxCnssOuvrierePct" type="number" suffix="%" label="CNSS ouvrière" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
          <v-col cols="6" md="3"><v-text-field v-model.number="form.tauxCnssPatronalePct" type="number" suffix="%" label="CNSS patronale" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
          <v-col cols="6" md="3"><v-text-field v-model.number="form.tauxOnemPct" type="number" suffix="%" label="ONEM" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
          <v-col cols="6" md="3"><v-text-field v-model.number="form.tauxInppPct" type="number" suffix="%" label="INPP" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
        </v-row>
      </v-card>

      <v-card class="classroom-card pa-5 mb-4">
        <div class="section-title">Barème IPR</div>
        <v-switch
          v-model="form.calculEnfantsActif"
          color="primary"
          :disabled="!canWrite"
          hide-details
          class="mb-2"
          label="Appliquer la réduction IPR par enfant à charge"
        />
        <p class="text-caption text-medium-emphasis mb-4">
          Désactivé : l'IPR de tous les bulletins est calculé comme si aucun employé n'avait d'enfant, quel que soit
          le nombre d'enfants renseigné sur sa fiche. Le taux et le plafond ci-dessous restent enregistrés pour une
          réactivation ultérieure.
        </p>
        <v-row>
          <v-col cols="6" md="3"><v-text-field v-model.number="form.reductionIprParEnfantPct" type="number" suffix="%" label="Réduction par enfant" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
          <v-col cols="6" md="3"><v-text-field v-model.number="form.plafondEnfantsIpr" type="number" label="Enfants retenus (max)" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
          <v-col cols="6" md="3"><v-text-field v-model.number="form.plancherIprFc" type="number" suffix="FC" label="Plancher IPR" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
          <v-col cols="6" md="3"><v-text-field v-model.number="form.joursOuvrablesStandard" type="number" label="Jours ouvrables standard" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
        </v-row>
      </v-card>

      <v-card class="classroom-card pa-5 mb-4">
        <div class="section-title">Conformité légale (RDC)</div>
        <v-alert type="info" variant="tonal" density="compact" rounded="lg" class="mb-4">
          Ces références servent uniquement à <strong>signaler les écarts</strong> sur le bulletin.
          Aucun montant n'est calculé ni corrigé automatiquement : la saisie du responsable paie reste souveraine.
        </v-alert>

        <v-switch
          v-model="form.cnssDeductibleIpr"
          color="primary"
          :disabled="!canWrite"
          hide-details
          class="mb-2"
          label="Déduire la CNSS ouvrière de la base imposable IPR"
        />
        <p class="text-caption text-medium-emphasis mb-4">
          Désactivé = comportement historique (base = salaire après indemnités). Les sources fiscales consultées
          se contredisent sur ce point : à confirmer avec votre comptable avant de l'activer, car cela modifie
          l'impôt de tous les agents.
        </p>

        <v-row>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.smigJournalierFc" type="number" suffix="FC" label="SMIG journalier"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="Décret n° 25/22 — manœuvre ordinaire" persistent-hint />
          </v-col>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.diviseurAllocationFamiliale" type="number" label="Diviseur alloc. familiale"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="1/27ᵉ du SMIG par enfant" persistent-hint />
          </v-col>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.plafondRetenuePct" type="number" suffix="%" label="Plafond des retenues"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="1/10ᵉ du salaire" persistent-hint />
          </v-col>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.plafondTransportExonereFcJour" type="number" suffix="FC"
              label="Transport exonéré / jour" variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="0 = contrôle désactivé" persistent-hint />
          </v-col>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.heuresLegalesHebdo" type="number" suffix="h" label="Durée légale hebdo"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="Art. 119" persistent-hint />
          </v-col>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.tauxMajorationHs1Pct" type="number" suffix="%" label="Heures sup (6 premières)"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="Art. 120" persistent-hint />
          </v-col>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.tauxMajorationHs2Pct" type="number" suffix="%" label="Heures sup (suivantes)"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="Art. 120" persistent-hint />
          </v-col>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.tauxMajorationHsFeriePct" type="number" suffix="%" label="Repos / jour férié"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="Art. 120" persistent-hint />
          </v-col>
        </v-row>
      </v-card>

      <v-card class="classroom-card pa-5 mb-4">
        <div class="section-title">Signature des documents</div>
        <v-row>
          <v-col cols="12" md="4"><v-text-field v-model="form.directeurDrh" label="Nom du directeur DRH" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
          <v-col cols="12" md="4"><v-text-field v-model="form.fonctionDirecteur" label="Fonction" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
          <v-col cols="12" md="4"><v-text-field v-model="form.villeSignature" label="Ville de signature" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
        </v-row>
      </v-card>

      <div v-if="canWrite" class="d-flex justify-end">
        <v-btn color="success" variant="flat" rounded="lg" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
      </div>
    </template>
  </div>
</template>

<style scoped>
.params-page { max-width: 900px; margin: 0 auto; padding-bottom: 48px; }
.section-title { font-size: 0.8rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.5px; color: #0d9488; margin-bottom: 12px; }
</style>
