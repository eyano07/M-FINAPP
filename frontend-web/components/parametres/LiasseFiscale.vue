<script setup lang="ts">
/**
 * Renseignements de la liasse fiscale OHADA (page de garde, fiches R1 à R3),
 * tous facultatifs. Ils remplissent les feuilles correspondantes du classeur
 * des états financiers ; la fiche R4 se coche seule d'après les notes.
 */
import type { InformationsLiasse } from '~/composables/useLiasseFiscale'

const liasse = defineModel<InformationsLiasse>({ required: true })

// Tables de codes de la note 36 de la liasse.
const FORMES_JURIDIQUES = [
  { value: '00', title: '00 – SA à participation publique' },
  { value: '01', title: '01 – Société anonyme (SA)' },
  { value: '02', title: '02 – Société à responsabilité limitée (SARL, SARLU)' },
  { value: '03', title: '03 – Société en commandite simple (SCS)' },
  { value: '04', title: '04 – Société en nom collectif (SNC)' },
  { value: '05', title: '05 – Société en participation (SP)' },
  { value: '06', title: "06 – Groupement d'intérêt économique (GIE)" },
  { value: '07', title: '07 – Association' },
  { value: '08', title: '08 – Société par actions simplifiée (SAS)' },
  { value: '09', title: '09 – Autre forme juridique' },
]
const REGIMES_FISCAUX = [
  { value: '1', title: '1 – Réel normal' },
  { value: '2', title: '2 – Réel simplifié' },
  { value: '3', title: '3 – Synthétique' },
  { value: '4', title: '4 – Forfait' },
]
const PAYS = [
  ['01', 'Bénin'], ['02', 'Burkina Faso'], ['03', "Côte d'Ivoire"], ['04', 'Guinée-Bissau'], ['05', 'Mali'],
  ['06', 'Niger'], ['07', 'Sénégal'], ['08', 'Togo'], ['09', 'Cameroun'], ['10', 'Congo'], ['11', 'Gabon'],
  ['12', 'République centrafricaine'], ['13', 'Tchad'], ['14', 'Comores'], ['15', 'Guinée'],
  ['16', 'Guinée équatoriale'], ['17', 'Congo (RDC)'], ['21', 'Autres pays africains'], ['23', 'France'],
  ['39', "Autres pays de l'Union européenne"], ['40', 'États-Unis'], ['41', 'Canada'],
  ['49', 'Autres pays américains'], ['50', 'Pays asiatiques'], ['99', 'Autres pays'],
].map(([value, nom]) => ({ value, title: `${value} – ${nom}` }))
const CONTROLES = [
  { value: 'PUBLIC', title: 'Entité sous contrôle public' },
  { value: 'PRIVE_NATIONAL', title: 'Entité sous contrôle privé national' },
  { value: 'PRIVE_ETRANGER', title: 'Entité sous contrôle privé étranger' },
]
const APPROBATION = [
  { value: true, title: 'Oui' },
  { value: false, title: 'Non' },
]

/** « Agrément prioritaire » : premier chiffre du code forme juridique à 1 (note 36). */
const agrementPrioritaire = computed({
  get: () => liasse.value.formeJuridique?.startsWith('1') ?? false,
  set: (v: boolean) => {
    const code = liasse.value.formeJuridique || '0'
    liasse.value.formeJuridique = (v ? '1' : '0') + code.slice(-1)
  },
})
const formeJuridiqueBase = computed({
  get: () => liasse.value.formeJuridique ? '0' + liasse.value.formeJuridique.slice(-1) : null,
  set: (v: string | null) => {
    liasse.value.formeJuridique = v ? (agrementPrioritaire.value ? '1' : '0') + v.slice(-1) : ''
  },
})

const totalParts = computed(() =>
  liasse.value.activites.reduce((t, a) => t + (Number(a.pourcentage) || 0), 0))

function ajouter<K extends 'domiciliations' | 'activites' | 'dirigeants' | 'administrateurs'>(liste: K, max: number) {
  const l = liasse.value[liste] as unknown[]
  if (l.length >= max) return
  const vides = {
    domiciliations: { banque: '', numeroCompte: '' },
    activites: { designation: '', codeNomenclature: '', pourcentage: null },
    dirigeants: { nom: '', prenoms: '', qualite: '', nif: '', adresse: '' },
    administrateurs: { nom: '', prenoms: '', qualite: '', adresse: '' },
  }
  l.push({ ...vides[liste] })
}
function retirer(liste: 'domiciliations' | 'activites' | 'dirigeants' | 'administrateurs', i: number) {
  (liasse.value[liste] as unknown[]).splice(i, 1)
}
</script>

<template>
  <div class="liasse">
    <!-- ── Page de garde ─────────────────────────────────────── -->
    <p class="liasse__section">Page de garde</p>
    <div class="liasse__grille">
      <div class="param-field">
        <label class="param-label">Pays</label>
        <v-text-field v-model="liasse.pays" placeholder="ex: République démocratique du Congo" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">Direction des impôts</label>
        <v-text-field v-model="liasse.directionImpots" placeholder="ex: Direction générale des impôts" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">Centre de dépôt</label>
        <v-text-field v-model="liasse.centreDepot" placeholder="ex: Centre des impôts de Kinshasa" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">Sigle usuel</label>
        <v-text-field v-model="liasse.sigleUsuel" hide-details="auto" />
      </div>
    </div>

    <!-- ── Fiche R1 ──────────────────────────────────────────── -->
    <p class="liasse__section">Fiche R1 – Identification</p>
    <div class="liasse__grille">
      <div class="param-field">
        <label class="param-label">ZB – Date d'arrêté effectif des comptes</label>
        <v-text-field v-model="liasse.dateArreteComptes" type="date" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">ZC – Exercice précédent clos le</label>
        <v-text-field
          v-model="liasse.exercicePrecedentClos"
          type="date"
          hide-details="auto"
          hint="Vide : veille du début de l'exercice exporté"
          persistent-hint
        />
      </div>
      <div class="param-field">
        <label class="param-label">ZC – Durée de l'exercice précédent (mois)</label>
        <v-text-field v-model.number="liasse.dureeExercicePrecedentMois" type="number" min="1" max="24" placeholder="12" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">ZD – Greffe</label>
        <v-text-field v-model="liasse.greffe" placeholder="ex: Tribunal de commerce de Kinshasa/Gombe" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">ZD – N° répertoire des entités</label>
        <v-text-field v-model="liasse.numeroRepertoireEntites" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">ZE – N° de caisse sociale</label>
        <v-text-field v-model="liasse.numeroCaisseSociale" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">ZE – N° code importateur</label>
        <v-text-field v-model="liasse.codeImportateur" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">ZE – Code activité principale</label>
        <v-text-field v-model="liasse.codeActivitePrincipale" placeholder="ex: 033002" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">ZG – Code postal</label>
        <v-text-field v-model="liasse.codePostal" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">ZG – Boîte postale</label>
        <v-text-field v-model="liasse.boitePostale" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">ZG – Ville</label>
        <v-text-field v-model="liasse.ville" hide-details="auto" />
      </div>
      <div class="param-field liasse__large">
        <label class="param-label">ZI – Activité principale exercée</label>
        <v-text-field v-model="liasse.activitePrincipale" placeholder="ex: Bar et restauration" hide-details="auto" />
      </div>
      <div class="param-field liasse__large">
        <label class="param-label">Personne à contacter (nom, adresse, qualité)</label>
        <v-text-field v-model="liasse.personneContact" hide-details="auto" />
      </div>
      <div class="param-field liasse__large">
        <label class="param-label">Professionnel ou cabinet comptable ayant établi les états financiers</label>
        <v-text-field v-model="liasse.professionnelComptable" placeholder="Nom, adresse et téléphone" hide-details="auto" />
      </div>
      <div class="param-field liasse__large">
        <label class="param-label">Visa de l'expert-comptable agréé</label>
        <v-text-field v-model="liasse.visaExpertComptable" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">États financiers approuvés par l'AG</label>
        <v-select v-model="liasse.etatsApprouvesAg" :items="APPROBATION" clearable placeholder="Non renseigné" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">Signataire des états financiers</label>
        <v-text-field v-model="liasse.nomSignataire" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">Qualité du signataire</label>
        <v-text-field v-model="liasse.qualiteSignataire" placeholder="ex: Directeur général" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">Date de signature</label>
        <v-text-field v-model="liasse.dateSignature" type="date" hide-details="auto" />
      </div>
    </div>

    <p class="liasse__sous-section">Domiciliations bancaires</p>
    <div v-for="(d, i) in liasse.domiciliations" :key="'b' + i" class="liasse__ligne">
      <v-text-field v-model="d.banque" placeholder="Banque" hide-details="auto" />
      <v-text-field v-model="d.numeroCompte" placeholder="Numéro de compte" hide-details="auto" />
      <v-btn icon="mdi-close" variant="text" size="small" aria-label="Retirer cette banque" @click="retirer('domiciliations', i)" />
    </div>
    <v-btn
      v-if="liasse.domiciliations.length < 10"
      class="liasse__ajouter"
      variant="text"
      color="primary"
      size="small"
      prepend-icon="mdi-plus"
      @click="ajouter('domiciliations', 10)"
    >
      Ajouter une banque
    </v-btn>

    <!-- ── Fiche R2 ──────────────────────────────────────────── -->
    <p class="liasse__section">Fiche R2 – Caractéristiques</p>
    <div class="liasse__grille">
      <div class="param-field">
        <label class="param-label">ZK – Forme juridique</label>
        <v-select v-model="formeJuridiqueBase" :items="FORMES_JURIDIQUES" clearable placeholder="Non renseignée" hide-details="auto" />
        <v-checkbox
          v-model="agrementPrioritaire"
          :disabled="!liasse.formeJuridique"
          label="Agrément prioritaire"
          density="compact"
          hide-details
        />
      </div>
      <div class="param-field">
        <label class="param-label">ZL – Régime fiscal</label>
        <v-select v-model="liasse.regimeFiscal" :items="REGIMES_FISCAUX" clearable placeholder="Non renseigné" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">ZM – Pays du siège social</label>
        <v-select v-model="liasse.paysSiege" :items="PAYS" clearable placeholder="Non renseigné" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">Contrôle de l'entité</label>
        <v-select v-model="liasse.controle" :items="CONTROLES" clearable placeholder="Non renseigné" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">ZN – Établissements dans le pays</label>
        <v-text-field v-model.number="liasse.nbEtablissementsPays" type="number" min="0" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">ZO – Établissements hors du pays (comptabilité distincte)</label>
        <v-text-field v-model.number="liasse.nbEtablissementsHorsPays" type="number" min="0" hide-details="auto" />
      </div>
      <div class="param-field">
        <label class="param-label">ZP – Première année d'exercice dans le pays</label>
        <v-text-field v-model.number="liasse.premiereAnneeExercice" type="number" min="1900" max="2100" hide-details="auto" />
      </div>
    </div>

    <p class="liasse__sous-section">
      Activités (par ordre décroissant du chiffre d'affaires)
    </p>
    <p class="liasse__aide">
      Le chiffre d'affaires de l'exercice est réparti selon la part saisie ; le reste figure en « Divers ».
      Une activité seule sans part reçoit 100 %.
    </p>
    <div v-for="(a, i) in liasse.activites" :key="'a' + i" class="liasse__ligne liasse__ligne--activite">
      <v-text-field v-model="a.designation" placeholder="Désignation de l'activité" hide-details="auto" />
      <v-text-field v-model="a.codeNomenclature" placeholder="Code" hide-details="auto" />
      <v-text-field v-model.number="a.pourcentage" type="number" min="0" max="100" placeholder="% du CA" suffix="%" hide-details="auto" />
      <v-btn icon="mdi-close" variant="text" size="small" aria-label="Retirer cette activité" @click="retirer('activites', i)" />
    </div>
    <p v-if="totalParts > 100" class="liasse__erreur">
      Les parts dépassent 100 % ({{ totalParts }} %).
    </p>
    <v-btn
      v-if="liasse.activites.length < 3"
      class="liasse__ajouter"
      variant="text"
      color="primary"
      size="small"
      prepend-icon="mdi-plus"
      @click="ajouter('activites', 3)"
    >
      Ajouter une activité
    </v-btn>

    <!-- ── Fiche R3 ──────────────────────────────────────────── -->
    <p class="liasse__section">Fiche R3 – Dirigeants</p>
    <p class="liasse__aide">Président directeur général, directeur général, administrateur général, gérant… (6 au plus).</p>
    <div v-for="(d, i) in liasse.dirigeants" :key="'d' + i" class="liasse__personne">
      <v-text-field v-model="d.nom" placeholder="Nom" hide-details="auto" />
      <v-text-field v-model="d.prenoms" placeholder="Prénoms" hide-details="auto" />
      <v-text-field v-model="d.qualite" placeholder="Qualité" hide-details="auto" />
      <v-text-field v-model="d.nif" placeholder="N° identification fiscale" hide-details="auto" />
      <v-text-field v-model="d.adresse" class="liasse__personne-adresse" placeholder="Adresse (BP, ville, pays)" hide-details="auto" />
      <v-btn icon="mdi-close" variant="text" size="small" aria-label="Retirer ce dirigeant" @click="retirer('dirigeants', i)" />
    </div>
    <v-btn
      v-if="liasse.dirigeants.length < 6"
      class="liasse__ajouter"
      variant="text"
      color="primary"
      size="small"
      prepend-icon="mdi-plus"
      @click="ajouter('dirigeants', 6)"
    >
      Ajouter un dirigeant
    </v-btn>

    <p class="liasse__sous-section">Membres du conseil d'administration (7 au plus)</p>
    <div v-for="(m, i) in liasse.administrateurs" :key="'m' + i" class="liasse__personne">
      <v-text-field v-model="m.nom" placeholder="Nom" hide-details="auto" />
      <v-text-field v-model="m.prenoms" placeholder="Prénoms" hide-details="auto" />
      <v-text-field v-model="m.qualite" placeholder="Qualité" hide-details="auto" />
      <v-text-field v-model="m.adresse" class="liasse__personne-adresse" placeholder="Adresse (BP, ville, pays)" hide-details="auto" />
      <v-btn icon="mdi-close" variant="text" size="small" aria-label="Retirer ce membre" @click="retirer('administrateurs', i)" />
    </div>
    <v-btn
      v-if="liasse.administrateurs.length < 7"
      class="liasse__ajouter"
      variant="text"
      color="primary"
      size="small"
      prepend-icon="mdi-plus"
      @click="ajouter('administrateurs', 7)"
    >
      Ajouter un membre
    </v-btn>
  </div>
</template>

<style scoped>
.liasse { display: flex; flex-direction: column; gap: 10px; }
.param-field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.param-label { font-size: 0.8125rem; font-weight: 600; color: #374151; }
.liasse__section {
  font-size: 0.875rem; font-weight: 700; color: #111827;
  margin: 18px 0 4px; padding-top: 14px; border-top: 1px solid #f3f4f6;
}
.liasse__section:first-child { margin-top: 0; padding-top: 0; border-top: none; }
.liasse__sous-section { font-size: 0.8125rem; font-weight: 600; color: #374151; margin: 12px 0 0; }
.liasse__aide { font-size: 0.75rem; color: #6b7280; margin: 0; }
.liasse__erreur { font-size: 0.75rem; color: #b91c1c; margin: 0; }
.liasse__grille { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; align-items: start; }
.liasse__large { grid-column: 1 / -1; }
.liasse__ligne { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto; gap: 10px; align-items: center; }
.liasse__ligne--activite { grid-template-columns: minmax(0, 2fr) minmax(0, 1fr) minmax(0, 1fr) auto; }
.liasse__personne {
  display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)) auto; gap: 10px; align-items: center;
  padding-bottom: 10px; border-bottom: 1px dashed #e5e7eb;
}
.liasse__personne-adresse { grid-column: 1 / 5; }
.liasse__personne > .v-btn { grid-column: 5; grid-row: 1; }
.liasse__ajouter { align-self: flex-start; }
@media (max-width: 760px) {
  .liasse__grille { grid-template-columns: minmax(0, 1fr); }
  .liasse__ligne, .liasse__ligne--activite { grid-template-columns: minmax(0, 1fr) auto; }
  .liasse__ligne > :not(.v-btn), .liasse__ligne--activite > :not(.v-btn) { grid-column: 1; }
  .liasse__ligne > .v-btn { grid-column: 2; grid-row: 1; }
  .liasse__personne { grid-template-columns: minmax(0, 1fr) auto; }
  .liasse__personne > :not(.v-btn) { grid-column: 1; }
  .liasse__personne > .v-btn { grid-column: 2; grid-row: 1; }
  .liasse__personne-adresse { grid-column: 1; }
}
</style>
