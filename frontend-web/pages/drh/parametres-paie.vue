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
  // CNSS patronale par branche (décret n° 18/041 du 24/11/2018) ; le total est leur somme.
  tauxCnssPensionPatronalePct: 5,
  tauxCnssPfPct: 6.5,
  tauxCnssRpPct: 1.5,
  tauxOnemPct: 0.5,
  // INPP selon l'effectif (employés actifs) : 1–50, 51–300, plus de 300.
  tauxInppPct: 3,
  tauxInpp51A300Pct: 2,
  tauxInppPlus300Pct: 1,
  conjointACharge: true,
  joursCongeParMois: 1,
  joursCongeParTranche5Ans: 1,
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
  cnssDeductibleIpr: true,
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

/** Fraction (0.065) -> pourcentage (6.5), sans les résidus de virgule flottante. */
function pct(v: unknown, defaut: number) {
  const n = Number(v)
  return Number.isFinite(n) && v !== null && v !== undefined ? Math.round(n * 1000000) / 10000 : defaut
}

const totalCnssPatronalePct = computed(() =>
  Math.round((Number(form.tauxCnssPensionPatronalePct || 0) + Number(form.tauxCnssPfPct || 0)
    + Number(form.tauxCnssRpPct || 0)) * 10000) / 10000)

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
      tauxCnssPensionPatronalePct: pct(p.tauxCnssPensionPatronale, 5),
      tauxCnssPfPct: pct(p.tauxCnssPf, 6.5),
      tauxCnssRpPct: pct(p.tauxCnssRp, 1.5),
      tauxInpp51A300Pct: pct(p.tauxInpp51A300, 2),
      tauxInppPlus300Pct: pct(p.tauxInppPlus300, 1),
      conjointACharge: p.conjointACharge !== false,
      joursCongeParMois: p.joursCongeParMois != null ? Number(p.joursCongeParMois) : 1,
      joursCongeParTranche5Ans: p.joursCongeParTranche5Ans != null ? Number(p.joursCongeParTranche5Ans) : 1,
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
        // Le serveur recalcule le total patronal comme la somme des trois branches.
        tauxCnssPatronale: totalCnssPatronalePct.value / 100,
        tauxCnssPensionPatronale: form.tauxCnssPensionPatronalePct / 100,
        tauxCnssPf: form.tauxCnssPfPct / 100,
        tauxCnssRp: form.tauxCnssRpPct / 100,
        tauxInpp51A300: form.tauxInpp51A300Pct / 100,
        tauxInppPlus300: form.tauxInppPlus300Pct / 100,
        conjointACharge: form.conjointACharge,
        joursCongeParMois: form.joursCongeParMois,
        joursCongeParTranche5Ans: form.joursCongeParTranche5Ans,
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
          label="Comptabilisation automatique de la paie"
        />
        <p v-if="form.comptabiliserPaie" class="text-caption text-medium-emphasis mb-2">
          Activé : la clôture du mois par le DFIN écrit aussitôt la pièce de paie au journal PAIE, datée du dernier
          jour du mois (charges de personnel, IPR, CNSS, INPP, ONEM et net à payer au compte de chaque agent).
        </p>
        <p v-else class="text-caption text-medium-emphasis mb-2">
          Désactivé : la clôture du mois verrouille seulement les bulletins ; le DFIN comptabilise ensuite la paie
          par le bouton « Comptabiliser la paie » de l'écran Bulletins de paie. À défaut, le paiement de la note de
          paie écrit la pièce.
        </p>
        <p class="text-body-2 mb-0">
          La note de paie règle ensuite les salaires nets (écran Bulletins de paie, « Règlement de la paie ») ;
          chaque impôt ou cotisation est versé par sa propre note.
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
        <p class="text-caption text-medium-emphasis mb-3">
          Base commune à la CNSS, à l'ONEM et à l'INPP : appointements (salaire brut − logement − transport) + congé,
          heures supplémentaires, primes et part de l'allocation familiale au-delà du minimum légal.
        </p>
        <div class="text-subtitle-2 mb-2">CNSS</div>
        <v-row>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.tauxCnssOuvrierePct" type="number" suffix="%" label="Part salariale (pensions)"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="Décret n° 18/041 du 24/11/2018 : 5 %, crédit 4313" persistent-hint />
          </v-col>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.tauxCnssPensionPatronalePct" type="number" suffix="%" label="Patronale — pensions"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="Décret n° 18/041 : 5 %, crédit 4313" persistent-hint />
          </v-col>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.tauxCnssPfPct" type="number" suffix="%" label="Patronale — prestations familiales"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="Décret n° 18/041 : 6,5 %, crédit 4311" persistent-hint />
          </v-col>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.tauxCnssRpPct" type="number" suffix="%" label="Patronale — risques professionnels"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="Décret n° 18/041 : 1,5 %, crédit 4312" persistent-hint />
          </v-col>
        </v-row>
        <p class="text-body-2 mt-2 mb-4">
          CNSS patronale totale : <strong>{{ totalCnssPatronalePct.toLocaleString('fr-FR') }} %</strong>
          (débit 6641 / 6642).
        </p>
        <div class="text-subtitle-2 mb-2">ONEM et INPP (charges de l'employeur, crédit 44281 / 44282)</div>
        <v-row>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.tauxOnemPct" type="number" suffix="%" label="ONEM"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="Arrêté d'août 2025 : 0,5 %" persistent-hint />
          </v-col>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.tauxInppPct" type="number" suffix="%" label="INPP — 1 à 50 employés"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="3 % (taux en débat : paramétrable)" persistent-hint />
          </v-col>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.tauxInpp51A300Pct" type="number" suffix="%" label="INPP — 51 à 300 employés"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="2 % (taux en débat : paramétrable)" persistent-hint />
          </v-col>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.tauxInppPlus300Pct" type="number" suffix="%" label="INPP — plus de 300"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="1 % (taux en débat : paramétrable)" persistent-hint />
          </v-col>
        </v-row>
        <p class="text-caption text-medium-emphasis mt-2 mb-0">
          La tranche INPP suit le nombre d'employés actifs au moment du calcul du bulletin.
        </p>
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
        <v-switch
          v-model="form.conjointACharge"
          color="primary"
          :disabled="!canWrite"
          hide-details
          class="mb-2"
          label="Compter le conjoint d'un agent marié comme personne à charge"
        />
        <p class="text-caption text-medium-emphasis mb-4">
          Pratique courante en RDC (réduction de 2 % par personne à charge : enfants + conjoint). Le nombre de
          personnes à charge est figé sur chaque bulletin à sa création.
        </p>
        <v-row>
          <v-col cols="6" md="3"><v-text-field v-model.number="form.reductionIprParEnfantPct" type="number" suffix="%" label="Réduction par personne à charge" variant="outlined" density="comfortable" :disabled="!canWrite" hint="2 % par personne (barème IPR)" persistent-hint /></v-col>
          <v-col cols="6" md="3"><v-text-field v-model.number="form.plafondEnfantsIpr" type="number" label="Personnes retenues (max)" variant="outlined" density="comfortable" :disabled="!canWrite" hint="9 au plus" persistent-hint /></v-col>
          <v-col cols="6" md="3"><v-text-field v-model.number="form.plancherIprFc" type="number" suffix="FC" label="Plancher IPR" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
          <v-col cols="6" md="3"><v-text-field v-model.number="form.joursOuvrablesStandard" type="number" label="Jours ouvrables standard" variant="outlined" density="comfortable" :disabled="!canWrite" /></v-col>
        </v-row>
      </v-card>

      <v-card class="classroom-card pa-5 mb-4">
        <div class="section-title">Conformité légale (RDC)</div>
        <v-alert type="info" variant="tonal" density="compact" rounded="lg" class="mb-4">
          Ces références servent surtout à <strong>signaler les écarts</strong> sur le bulletin. Deux d'entre elles
          entrent dans le calcul : la déduction de la CNSS de la base IPR, et le minimum légal de l'allocation
          familiale (SMIG ÷ diviseur), seule part exonérée — l'excédent est imposable et cotisable.
        </v-alert>

        <v-switch
          v-model="form.cnssDeductibleIpr"
          color="primary"
          :disabled="!canWrite"
          hide-details
          class="mb-2"
          label="Déduire la CNSS salariale de la base imposable IPR"
        />
        <p class="text-caption text-medium-emphasis mb-4">
          Activé par défaut : selon PwC (Tax Summaries, RDC), seule la part salariale de la CNSS est déductible de la
          base IPR. Désactivé = comportement historique du classeur DEBOURS MBSC.
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
        <div class="section-title">Congés payés</div>
        <p class="text-caption text-medium-emphasis mb-3">
          Servent au calcul de la provision pour congés payés de fin d'exercice (page « Provision pour congés payés »).
        </p>
        <v-row>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.joursCongeParMois" type="number" suffix="j" label="Jours par mois de service"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="Code du travail : au moins 1 jour ouvrable par mois entier" persistent-hint />
          </v-col>
          <v-col cols="6" md="3">
            <v-text-field v-model.number="form.joursCongeParTranche5Ans" type="number" suffix="j" label="Jours par 5 ans d'ancienneté"
              variant="outlined" density="comfortable" :disabled="!canWrite"
              hint="Code du travail : 1 jour ouvrable par période de 5 ans" persistent-hint />
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
