<script setup lang="ts">
definePageMeta({ module: 'DRH_PAIE' })

interface Bulletin {
  id: number
  employeMatricule: string
  employeNomComplet: string
  employePoste: string | null
  employeAffectation: string | null
  employeDateEmbauche: string | null
  employeSituationFamiliale: string | null
  /** Numéro CNSS + lettre de catégorie (voir Employe.categorie). */
  employeCategorie: string | null
  drhNom: string | null
  /** Part cotisable H = brut − indemnités logement/transport (voir gainsVisibles). */
  baseImposableInss: number
  mois: number
  annee: number
  salaireBaseUsd: number
  presencePct: number
  nombreEnfants: number
  conge: number
  heuresSupplementaires: number
  allocationFamiliale: number
  primeDiplome: number
  primeAnciennete: number
  primeRendement: number
  avanceSalaire: number
  pret: number
  salaireBrut: number
  indemniteLogement: number
  indemniteTransport: number
  cnssOuvriere: number
  cnssPatronale: number
  onem: number
  totalInss: number
  inpp: number
  ipr: number
  salaireNet: number
  tauxChangeApplique: number
  netFc: number
  datePaiement: string
  statut: string
  pieceReference: string | null
  pieceStatut: string | null
  /** Vrai dès la clôture de la période, avec ou sans pièce comptable (voir Paramètres de paie). */
  cloture: boolean
  /** Pilote le document imprimé : bulletin complet si vrai, reçu simplifié sinon. */
  employeConforme: boolean
}

const MOIS = [
  'Janvier', 'Février', 'Mars', 'Avril', 'Mai', 'Juin',
  'Juillet', 'Août', 'Septembre', 'Octobre', 'Novembre', 'Décembre',
]

const SITUATION_FAMILIALE_LABELS: Record<string, string> = {
  CELIBATAIRE: 'Célibataire',
  MARIE: 'Marié(e)',
  DIVORCE: 'Divorcé(e)',
  VEUF: 'Veuf/Veuve',
}

const route = useRoute()
const api = useApi()
const parametresStore = useParametresStore()

const loading = ref(true)
const erreur = ref('')
const bulletin = ref<Bulletin | null>(null)

/** Ne retient que les rubriques a montant non nul : une ligne a 0 n'apporte
 * rien sur le document remis a l'employe. */
function rubriquesNonNulles(paires: [string, number][]) {
  return paires.filter(([, montant]) => !!montant).map(([label, montant]) => ({ label, montant }))
}
/**
 * Rubriques de gains. La 1re ligne est la part cotisable H (= brut − logement
 * − transport), et NON le salaire brut : logement et transport sont des
 * composantes du brut, pas des suppléments qui s'y ajoutent (voir
 * PayrollCalculationService — H = R − I − J). Les lister à côté du brut faisait
 * afficher 1 092 $ de « gains » pour un brut réel de 780 $, un écart de 312 $
 * qu'aucun lecteur du bulletin — agent, inspecteur du travail, CNSS — ne
 * pouvait rapprocher du net payé. Ainsi présentée, la colonne totalise
 * exactement le salaire brut.
 */
const gainsVisibles = computed(() => {
  const b = bulletin.value
  if (!b) return []
  return rubriquesNonNulles([
    ['Salaire de base (part cotisable)', b.baseImposableInss],
    ['Indemnité de logement', b.indemniteLogement],
    ['Indemnité de transport', b.indemniteTransport],
    ['Congé', b.conge],
    ['Heures supplémentaires', b.heuresSupplementaires],
    ['Allocation familiale', b.allocationFamiliale],
    ['Prime diplôme', b.primeDiplome],
    ['Prime ancienneté', b.primeAnciennete],
    ['Prime rendement', b.primeRendement],
  ])
})
/** Doit égaler salaireBrut + gains annexes : c'est le contrôle que le lecteur refait de tête. */
const totalGains = computed(() =>
  gainsVisibles.value.reduce((s, g) => s + Number(g.montant || 0), 0))
const totalRetenues = computed(() =>
  retenuesVisibles.value.reduce((s, r) => s + Number(r.montant || 0), 0))
const retenuesVisibles = computed(() => {
  const b = bulletin.value
  if (!b) return []
  return rubriquesNonNulles([
    ['CNSS ouvrière', b.cnssOuvriere],
    ['IPR', b.ipr],
    ['Avance sur salaire', b.avanceSalaire],
    ['Prêt', b.pret],
  ])
})
const chargesPatronalesVisibles = computed(() => {
  const b = bulletin.value
  if (!b) return []
  return rubriquesNonNulles([
    ['CNSS patronale', b.cnssPatronale],
    ['ONEM', b.onem],
    ['INPP', b.inpp],
  ])
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    bulletin.value = await api<Bulletin>(`/drh/bulletins/${route.params.id}`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le bulletin.')
  } finally {
    loading.value = false
  }
}
onMounted(() => { charger(); parametresStore.charger() })

const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  nextTick(() => window.print())
}

const fmtUsd = (v: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(v || 0)
const fmtFc = (v: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(v || 0) + ' FC'
const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
const situationFamilialeLabel = (s: string) => SITUATION_FAMILIALE_LABELS[s] || s
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">{{ bulletin?.employeConforme === false ? 'Reçu de paiement' : 'Bulletin de paie' }}</h1>
        <p class="page-sub" v-if="bulletin">{{ bulletin.employeNomComplet }} — {{ MOIS[bulletin.mois - 1] }} {{ bulletin.annee }}</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/drh/bulletins">Retour</v-btn>
        <v-btn color="error" variant="tonal" rounded="lg" prepend-icon="mdi-printer-outline" :disabled="!bulletin" @click="imprimer">
          Imprimer
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print">{{ erreur }}</v-alert>
    <v-skeleton-loader v-if="loading" type="article" />

    <template v-else-if="bulletin">
      <!-- Conteneur en colonne flex, hauteur fixee a l'impression (bp-print-page)
           pour que les signatures (margin-top:auto) se retrouvent poussees
           tout en bas de la page plutot que de suivre directement le
           contenu, qui laisse souvent un grand vide en dessous. -->
      <div class="bp-print-page">
      <div class="etat-print-header">
        <div class="etat-print-header__brand">
          <div class="etat-print-header__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
            <v-icon v-else icon="mdi-finance" size="16" color="white" />
          </div>
          <div>
            <span class="etat-print-header__company">{{ parametresStore.parametres.nom }}</span>
            <span class="etat-print-header__doc">{{ bulletin.employeConforme ? 'Bulletin de paie' : 'Reçu de paiement' }}</span>
          </div>
        </div>
        <div class="etat-print-header__meta">
          <span>Période : <strong>{{ MOIS[bulletin.mois - 1] }} {{ bulletin.annee }}</strong></span>
          <span>Imprimé le : {{ dateImpression }}</span>
        </div>
      </div>

      <h1 class="bp-title">{{ bulletin.employeConforme ? 'Bulletin de paie' : 'Reçu de paiement' }}</h1>

      <div class="bp-info-card">
        <div class="bp-info-grid">
          <div class="bp-info-item"><span class="bp-info-label">Matricule</span><strong>{{ bulletin.employeMatricule }}</strong></div>
          <div class="bp-info-item"><span class="bp-info-label">Employé</span><strong>{{ bulletin.employeNomComplet }}</strong></div>
          <div v-if="bulletin.employeDateEmbauche" class="bp-info-item"><span class="bp-info-label">Date d'embauche</span><strong>{{ fmtDate(bulletin.employeDateEmbauche) }}</strong></div>
          <div v-if="bulletin.employeSituationFamiliale" class="bp-info-item"><span class="bp-info-label">État civil</span><strong>{{ situationFamilialeLabel(bulletin.employeSituationFamiliale) }}</strong></div>
          <div v-if="bulletin.employeCategorie" class="bp-info-item"><span class="bp-info-label">N° CNSS</span><strong>{{ bulletin.employeCategorie }}</strong></div>
          <div v-if="bulletin.employePoste" class="bp-info-item"><span class="bp-info-label">Poste</span><strong>{{ bulletin.employePoste }}</strong></div>
          <div v-if="bulletin.employeAffectation" class="bp-info-item"><span class="bp-info-label">Affectation</span><strong>{{ bulletin.employeAffectation }}</strong></div>
          <div class="bp-info-item"><span class="bp-info-label">Date de paiement</span><strong>{{ fmtDate(bulletin.datePaiement) }}</strong></div>
          <div class="bp-info-item"><span class="bp-info-label">Présence</span><strong>{{ bulletin.presencePct }}%</strong></div>
          <div class="bp-info-item"><span class="bp-info-label">Enfants à charge</span><strong>{{ bulletin.nombreEnfants }}</strong></div>
        </div>
      </div>

      <template v-if="bulletin.employeConforme">
        <!-- Grille CSS fixe (pas de breakpoint Vuetify md/lg) : a l'impression,
             la largeur de mise en page ne correspond pas forcement a un
             viewport ecran, et les colonnes md= de Vuetify peuvent alors ne
             jamais s'activer et tout empiler en une seule colonne. -->
        <div class="bp-cols">
          <section v-if="gainsVisibles.length" class="etat-bloc">
            <h2 class="etat-titre">Gains</h2>
            <table class="etat-table">
              <tbody>
                <tr v-for="g in gainsVisibles" :key="g.label"><td>{{ g.label }}</td><td class="num">{{ fmtUsd(g.montant) }}</td></tr>
                <tr class="bp-total"><td>Total des gains (brut)</td><td class="num">{{ fmtUsd(totalGains) }}</td></tr>
              </tbody>
            </table>
          </section>

          <div class="bp-cols__right">
            <section v-if="retenuesVisibles.length" class="etat-bloc">
              <h2 class="etat-titre">Retenues</h2>
              <table class="etat-table">
                <tbody>
                  <tr v-for="r in retenuesVisibles" :key="r.label"><td>{{ r.label }}</td><td class="num">{{ fmtUsd(r.montant) }}</td></tr>
                  <tr class="bp-total"><td>Total des retenues</td><td class="num">{{ fmtUsd(totalRetenues) }}</td></tr>
                </tbody>
              </table>
            </section>

            <section v-if="chargesPatronalesVisibles.length" class="etat-bloc">
              <h2 class="etat-titre">Charges patronales <span class="bp-titre-note">(à titre indicatif)</span></h2>
              <table class="etat-table">
                <tbody>
                  <tr v-for="c in chargesPatronalesVisibles" :key="c.label"><td>{{ c.label }}</td><td class="num">{{ fmtUsd(c.montant) }}</td></tr>
                </tbody>
              </table>
            </section>
          </div>
        </div>
      </template>

      <!-- Reçu simplifié : employé non conforme (dossier incomplet), montant
           net uniquement — pas de détail de calcul sur le document remis. -->
      <section v-else class="etat-bloc">
        <p class="text-body-2 mb-3">
          Reçu de la somme de <strong>{{ fmtUsd(bulletin.salaireNet) }}</strong>
          ({{ fmtFc(bulletin.netFc) }}) au titre du salaire de
          {{ MOIS[bulletin.mois - 1] }} {{ bulletin.annee }}.
        </p>
        <table class="etat-table recu-signature">
          <tbody>
            <tr><td>Signature de l'employé</td><td class="signature-cell" /></tr>
          </tbody>
        </table>
      </section>

      <div class="bp-net">
        <div class="bp-net__item">
          <span class="bp-net__label">Salaire net à payer (USD)</span>
          <strong class="bp-net__value">{{ fmtUsd(bulletin.salaireNet) }}</strong>
        </div>
        <div class="bp-net__sep" />
        <div class="bp-net__item">
          <span class="bp-net__label">Salaire net à payer (FC) — taux {{ bulletin.tauxChangeApplique }}</span>
          <strong class="bp-net__value">{{ fmtFc(bulletin.netFc) }}</strong>
        </div>
      </div>

      <!-- Signatures : uniquement sur le bulletin complet — le reçu simplifié
           ci-dessus porte deja sa propre ligne de signature employe. -->
      <div v-if="bulletin.employeConforme" class="bp-signatures">
        <div class="bp-sign">
          <span class="bp-sign__label">Agent</span>
          <span class="bp-sign__name">{{ bulletin.employeNomComplet }}</span>
          <div class="bp-sign__line" />
          <span class="bp-sign__hint">Signature</span>
        </div>
        <div class="bp-sign">
          <span class="bp-sign__label">DRH</span>
          <span v-if="bulletin.drhNom" class="bp-sign__name">{{ bulletin.drhNom }}</span>
          <div class="bp-sign__line" />
          <span class="bp-sign__hint">Signature et cachet</span>
        </div>
      </div>

      <p v-if="bulletin.pieceReference" class="text-caption text-medium-emphasis mt-4 no-print">
        Pièce comptable : <strong>{{ bulletin.pieceReference }}</strong> ({{ bulletin.pieceStatut }})
      </p>
      <p v-else-if="bulletin.cloture" class="text-caption text-medium-emphasis mt-4 no-print">
        Période clôturée — non comptabilisé (comptabilisation désactivée dans Paramètres de paie).
      </p>

      <div v-if="parametresStore.parametres.adresse || parametresStore.parametres.telephone" class="bp-footer">
        <span v-if="parametresStore.parametres.adresse">{{ parametresStore.parametres.adresse }}</span>
        <span v-if="parametresStore.parametres.adresse && parametresStore.parametres.telephone" class="bp-footer__sep">·</span>
        <span v-if="parametresStore.parametres.telephone">Tél. {{ parametresStore.parametres.telephone }}</span>
      </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.calc-label { font-size: 0.7rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.4px; }

.etat-bloc { margin-bottom: 20px; }
.etat-titre { font-size: 1.05rem; font-weight: 700; color: #111827; margin: 0 0 10px; padding-bottom: 6px; border-bottom: 2px solid #16a34a; }
.bp-titre-note { font-size: 0.72rem; font-weight: 500; color: #9ca3af; text-transform: none; letter-spacing: 0; }
.etat-table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
.etat-table td { padding: 6px 8px; border-bottom: 1px solid #f3f4f6; color: #374151; }
.etat-table .num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
/* Ligne de total : le lecteur du bulletin doit pouvoir refaire l'addition. */
.bp-total td { font-weight: 700; color: #111827; border-top: 1.5px solid #d1d5db; border-bottom: none !important; }
.recu-signature { max-width: 360px; }
.signature-cell { min-width: 160px; border-bottom: 1px solid #111827 !important; }

/* ── Titre du document : grand et centre, distinct du petit repere
   ".etat-print-header__doc" (classe partagee avec d'autres impressions —
   etats financiers, bilan, notes de frais — qu'on ne veut pas affecter). */
.bp-title {
  text-align: center;
  font-size: 1.9rem;
  font-weight: 800;
  letter-spacing: 1.5px;
  text-transform: uppercase;
  color: #111827;
  margin: 4px 0 18px;
}

/* ── Informations employé : grille CSS fixe, pas de v-row/v-col Vuetify
   (leurs breakpoints md/lg dependent de la largeur de viewport, qui a
   l'impression ne correspond pas forcement a l'ecran — tout s'empilait
   alors sur une seule colonne, page apres page). ────────────────────── */
.bp-info-card { background: #fff; border: 1px solid #e5e7eb; border-radius: 14px; padding: 18px 22px; margin-bottom: 16px; }
.bp-info-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px 24px; }
.bp-info-item { display: flex; flex-direction: column; gap: 3px; }
.bp-info-label { font-size: 0.7rem; color: #9ca3af; text-transform: uppercase; letter-spacing: 0.4px; font-weight: 600; }
.bp-info-item strong { font-size: 0.95rem; color: #111827; }

/* Gains a gauche, Retenues + Charges patronales empilees a droite : deux
   sections courtes cote a cote plutot que trois blocs pleine largeur. */
.bp-cols { display: grid; grid-template-columns: 1fr 1fr; gap: 0 24px; align-items: start; }
.bp-cols__right { display: flex; flex-direction: column; }

/* ── Net a payer : bandeau de synthese, plus sobre que la carte Vuetify
   teal d'origine mais toujours mis en avant. ────────────────────────── */
.bp-net {
  display: flex;
  align-items: stretch;
  gap: 20px;
  background: linear-gradient(135deg, #ecfdf5 0%, #d1fae5 100%);
  border: 1px solid #a7f3d0;
  border-radius: 14px;
  padding: 18px 22px;
  margin-top: 4px;
}
.bp-net__item { flex: 1; display: flex; flex-direction: column; gap: 4px; }
.bp-net__sep { width: 1px; background: #a7f3d0; }
.bp-net__label { font-size: 0.72rem; color: #047857; text-transform: uppercase; letter-spacing: 0.4px; font-weight: 700; }
.bp-net__value { font-size: 1.4rem; color: #065f46; font-weight: 800; letter-spacing: -0.3px; }

/* ── Signatures (agent, DRH) ──────────────────────────────────────────── */
/* Colonne flex sur toute la hauteur imprimable : pousse .bp-signatures
   (margin-top:auto ci-dessous) au pied de la page plutot que de le laisser
   juste apres le dernier bloc de contenu, ou il flottait au milieu d'un
   grand vide quand le bulletin est court (rubriques a 0 masquees). */
.bp-print-page { display: flex; flex-direction: column; }
.bp-signatures { display: flex; justify-content: space-between; gap: 40px; margin-top: auto; padding: 0 8px; }
/* Colonne en flex, trait pousse en bas (margin-top:auto) : seul l'agent
   porte un nom pre-rempli, sans quoi son trait de signature se retrouvait
   plus bas que celui du DRH. */
.bp-sign { flex: 1; max-width: 220px; text-align: center; display: flex; flex-direction: column; min-height: 50px; }
.bp-sign__label { display: block; font-size: 0.72rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.4px; color: #374151; }
.bp-sign__name { display: block; font-size: 0.8rem; color: #111827; margin: auto 0 3px; }
.bp-sign__line { border-top: 1px solid #111827; margin-top: auto; }
.bp-sign__name + .bp-sign__line { margin-top: 0; }
.bp-sign__hint { display: block; font-size: 0.66rem; color: #9ca3af; font-style: italic; margin-top: 4px; }

/* ── Pied de page : coordonnees de l'entreprise, configurees par l'ADMIN
   (ecran Parametres). Suit .bp-signatures dans le flux flex de
   .bp-print-page : la margin-top:auto du bloc signatures pousse deja les
   deux ensemble tout en bas de la page imprimee. ────────────────────── */
.bp-footer {
  margin-top: 16px;
  padding-top: 10px;
  border-top: 1px solid #e5e7eb;
  text-align: center;
  font-size: 0.72rem;
  color: #9ca3af;
}
.bp-footer__sep { margin: 0 6px; }

@media print {
  /* Hauteur imprimable A4 (297mm) moins les marges @page (10mm haut/bas,
     voir classroom.scss) : donne au conteneur de quoi pousser .bp-signatures
     jusqu'en bas via margin-top:auto. Une marge de securite est retranchee
     pour ne jamais provoquer une 2e page si le rendu ajoute quelques
     pixels. */
  .bp-print-page { min-height: 260mm; }
  /* margin-top reste "auto" (regle de base ci-dessus) : c'est justement ce
     qui pousse le bloc en bas de .bp-print-page a l'impression. */
  .bp-signatures { gap: 30px; }
  .bp-sign { min-height: 36px; }
  .bp-sign__label { font-size: 0.62rem; }
  .bp-sign__name { font-size: 0.72rem; }
  .bp-sign__hint { font-size: 0.58rem; }

  .bp-title { font-size: 1.5rem; margin: 2px 0 10px; }
  .bp-footer { margin-top: 8px; padding-top: 6px; font-size: 0.6rem; }

  .bp-info-card { padding: 10px 16px; margin-bottom: 8px; border-radius: 10px; }
  .bp-info-grid { gap: 6px 18px; }
  .bp-info-label { font-size: 0.6rem; }
  .bp-info-item strong { font-size: 0.8rem; }

  .bp-cols { gap: 0 18px; }
  .etat-bloc { margin-bottom: 8px; break-inside: avoid; }
  .etat-titre { font-size: 0.82rem; margin: 0 0 5px; padding-bottom: 3px; border-bottom-width: 1.5px; }
  .etat-table td { padding: 3px 6px; font-size: 0.72rem; }

  .bp-net { padding: 10px 16px; margin-top: 4px; border-radius: 10px; }
  .bp-net__label { font-size: 0.62rem; }
  .bp-net__value { font-size: 1.05rem; }

  .recu-signature { margin-top: 8px; }
}
</style>
